/*
 * Copyright (C) 2026 Yoshio255
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.yoshio.epic_fight_sheathing_master;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillDataKey;
import yesman.epicfight.skill.SkillDataManager;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side game logic (no client classes; safe on a dedicated server). EFN's Murasama and HF-BLADE start
 * in the DRAWN state when taken into the hand (their weapon passive sets the state to "not sheathed" in
 * onInitiate). With {@link Config#efnMurasamaSheathOnEquip} the state is switched to "sheathed" right after
 * that, using EFN's own state: its skill data key (synced to the client by Epic Fight), its item tag
 * (through the passive's own setMurasamaSheathMesh) and Epic Fight's living-motion refresh, exactly the
 * three things EFN's own sheathing event does. Everything about EFN is reached by name (reflection), so
 * nothing here depends on EFN being installed or on its exact version: on any failure it does nothing and
 * the weapon keeps EFN's default behaviour.
 *
 * <p>Only the side that runs the game logic acts (the dedicated server, or the single-player world); on a
 * server without this mod nothing happens, and the client just shows whatever state the server syncs.
 */
@Mod.EventBusSubscriber(modid = Epic_fight_sheathing_master.MODID)
public final class EfnSheathOnEquip {

    private static final String TAG = "murasama_sheath";
    // After equipping, EFN's own living animations (MurasamaLivingAnimation / MurasamaMovementAnimation) set
    // the item tag back to "drawn" when they begin, a few ticks after our switch. For this many ticks the tag
    // is therefore re-asserted whenever the sheathed state is still on but the tag was reset.
    private static final int SETTLE_TICKS = 20;
    private static final Map<UUID, Integer> SETTLING = new ConcurrentHashMap<>();

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean failureLogged = false;

    private EfnSheathOnEquip() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SETTLING.isEmpty() || !(event.player instanceof ServerPlayer player)) return;
        Integer left = SETTLING.get(player.getUUID());
        if (left == null) return;
        if (left <= 1) {
            SETTLING.remove(player.getUUID());
            return;
        }
        SETTLING.put(player.getUUID(), left - 1);
        try {
            ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty() || stack.getTag() == null) return;
            ServerPlayerPatch patch = EpicFightCapabilities.getEntityPatch(player, ServerPlayerPatch.class);
            if (patch == null) return;
            SkillContainer container = patch.getSkill(SkillSlots.WEAPON_PASSIVE);
            if (container == null || container.isEmpty()) return;
            Object registryObject = Class.forName("com.hm.efn.gameasset.EFNSKillDataKeys")
                    .getField("MURASAMA_SHEATH").get(null);
            SkillDataKey key = (SkillDataKey) ((RegistryObject<?>) registryObject).get();
            SkillDataManager data = container.getDataManager();
            // Once the skill container is fully set up, refresh the living motions again (the call made inside
            // onInitiate can run before the state is visible to the style provider): sheathed idle / walk / run.
            if (left == SETTLE_TICKS - 1 || left == SETTLE_TICKS - 4) {
                patch.modifyLivingMotionByCurrentItem(false);
            }
            // The player drew the sword (or put it away): the state is no longer ours to hold.
            if (!data.hasData(key) || !Boolean.TRUE.equals(data.getDataValue(key))) {
                SETTLING.remove(player.getUUID());
                return;
            }
            // The client's copy of the sheath state can be lost: the skill container on the client is set up by a
            // packet that may arrive AFTER the state was sent in onInitiate, which resets it to "drawn" there
            // (EFN's client-side sprint logic then sheathes the sword again while running). Send it again
            // a few times while the client catches up.
            if (left == SETTLE_TICKS - 2 || left == SETTLE_TICKS - 5 || left == SETTLE_TICKS - 9 || left == SETTLE_TICKS - 14) {
                data.setDataSync(key, Boolean.TRUE, player);
            }
            if (stack.getTag().getInt(TAG) != 1) stack.getTag().putInt(TAG, 1);
        } catch (Throwable t) {
            SETTLING.remove(player.getUUID());
        }
    }

    /** Called at the end of the passive's onInitiate. {@code passive} is the EFN passive skill instance. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void apply(Object passive, SkillContainer container) {
        try {
            if (!Config.efnMurasamaSheathOnEquip) return;
            if (container == null || container.getExecutor() == null || container.getExecutor().isLogicalClient()) return;
            ServerPlayerPatch patch = container.getServerExecutor();
            if (patch == null) return;
            ServerPlayer player = patch.getOriginal();

            Object registryObject = Class.forName("com.hm.efn.gameasset.EFNSKillDataKeys")
                    .getField("MURASAMA_SHEATH").get(null);
            SkillDataKey key = (SkillDataKey) ((RegistryObject<?>) registryObject).get();
            SkillDataManager data = container.getDataManager();
            if (!data.hasData(key)) return;

            data.setDataSync(key, Boolean.TRUE, player);
            passive.getClass().getMethod("setMurasamaSheathMesh", SkillContainer.class, boolean.class)
                    .invoke(passive, container, true);
            patch.modifyLivingMotionByCurrentItem(false);
            SETTLING.put(player.getUUID(), SETTLE_TICKS);
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[efsm] could not start the Murasama / HF-BLADE in the sheathed state ({}); EFN default is kept",
                        t.toString());
            }
        }
    }
}
