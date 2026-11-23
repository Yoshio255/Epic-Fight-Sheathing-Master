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

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillDataKey;
import yesman.epicfight.skill.SkillSlots;

/**
 * Client side of {@link EfnSheathOnEquip}: keeps the "murasama_sheath" tag of the main-hand Murasama / HF-BLADE
 * on the CLIENT's copy of the stack at 1 while the synced sheath state is on (see reassertTag).
 */
@Mod.EventBusSubscriber(modid = Epic_fight_sheathing_master.MODID, value = Dist.CLIENT)
public final class EfnSheathClientSync {

    private EfnSheathClientSync() {}

    /**
     * The Epic Fight skill logic also runs on the client for the local player and, like on the server, EFN's
     * passive resets the item tag to "drawn" there (onInitiate, and the begin of its living animations),
     * while the server's copy of the tag stays at "sheathed" and is therefore never re-sent. The renderer
     * reads the CLIENT's stack, so while the synced sheath state is on, the local tag is kept at 1 too.
     */
    @SuppressWarnings({"rawtypes", "unchecked", "deprecation"})
    private static void reassertTag(ItemStack stack, String id) {
        if (!Config.efnMurasamaSheathOnEquip) return;
        if (!(id.equals("efn:hf_murasama") || id.equals("efn:hf_blade")) || stack.getTag() == null) return;
        if (stack.getTag().getInt("murasama_sheath") == 1) return;
        try {
            var patch = ClientEngine.getInstance().getPlayerPatch();
            if (patch == null) return;
            SkillContainer container = patch.getSkill(SkillSlots.WEAPON_PASSIVE);
            if (container == null || container.isEmpty()) return;
            Object registryObject = Class.forName("com.hm.efn.gameasset.EFNSKillDataKeys")
                    .getField("MURASAMA_SHEATH").get(null);
            SkillDataKey key = (SkillDataKey) ((RegistryObject<?>) registryObject).get();
            if (container.getDataManager().hasData(key) && Boolean.TRUE.equals(container.getDataManager().getDataValue(key))) {
                stack.getTag().putInt("murasama_sheath", 1);
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack stack = mc.player.getMainHandItem();
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        reassertTag(stack, id);
    }
}
