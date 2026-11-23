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
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.input.EpicFightKeyMappings;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.skill.weaponinnate.BattojutsuSkill;
import yesman.epicfight.world.item.UchigatanaItem;

// Manual battojutsu (iaijutsu) trigger: holding the attack key while
// switching the hotbar to a katana casts the WEAPON_INNATE skill
// (BattojutsuSkill) exactly as if the player had pressed the attack/weapon-
// innate-skill key normally — see SkillContainer#sendCastRequest, which
// Epic Fight's own ControlEngine calls from the same physical input. Reusing
// it means the WEAPON_CHARGE resource check (is the gauge full?) and network
// request are entirely Epic Fight's own logic; we only detect the trigger.
//
// IMPORTANT: use EpicFightKeyMappings.ATTACK.isDown(), NOT
// Minecraft.options.keyAttack.isDown(). Confirmed via debug logging that
// while in Epic Fight Mode, vanilla's own keyAttack.isDown() never reflects
// the physical mouse state at all (stays false even while genuinely held for
// several seconds) — Epic Fight's own separate ATTACK keymapping (same
// default physical binding, but a distinct KeyMapping instance registered by
// EpicFightKeyMappings) is the one that actually tracks it correctly.
//
// The WEAPON_INNATE container's skill is only assigned to BattojutsuSkill by
// CapabilityItem#changeWeaponInnateSkill, which runs server-side only (via
// ServerPlayerPatch#updateHeldItem) and reaches the client through a
// round-trip sync packet — the local hotbar selection changes instantly, but
// the local WEAPON_INNATE container can still briefly hold the OLD skill for
// a moment after that. Instead of a single check, this polls (still via
// level detection — isDown() every tick) for up to PENDING_TIMEOUT_TICKS
// until the sync completes, canceling early if the key is released or the
// selection changes away from a katana in the meantime.
@Mod.EventBusSubscriber(modid = Epic_fight_sheathing_master.MODID, value = Dist.CLIENT)
public class BattojutsuTrigger {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PENDING_TIMEOUT_TICKS = 20; // 1 second

    private static int previousSelectedSlot = -1;
    private static int pendingCastTicksRemaining = 0;

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft minecraft = Minecraft.getInstance();
        Player player = event.player;
        if (player != minecraft.player) return;

        int selected = player.getInventory().selected;
        int previous = previousSelectedSlot;
        previousSelectedSlot = selected;

        boolean selectedIsKatana = player.getInventory().getItem(selected).getItem() instanceof UchigatanaItem;
        boolean attackKeyDown = EpicFightKeyMappings.ATTACK.isDown();

        if (previous != selected && selectedIsKatana && attackKeyDown) {
            LOGGER.debug("[Battojutsu] pending cast started (slot {} -> {})", previous, selected);
            pendingCastTicksRemaining = PENDING_TIMEOUT_TICKS;
        }

        if (pendingCastTicksRemaining <= 0) return;

        if (!attackKeyDown || !selectedIsKatana) {
            pendingCastTicksRemaining = 0;
            return;
        }

        ClientEngine clientEngine = ClientEngine.getInstance();
        LocalPlayerPatch playerPatch = clientEngine.getPlayerPatch();
        if (playerPatch == null) return;

        SkillContainer weaponInnate = playerPatch.getSkill(SkillSlots.WEAPON_INNATE);

        if (!(weaponInnate.getSkill() instanceof BattojutsuSkill)) {
            // Still waiting for the server's sync packet to arrive.
            pendingCastTicksRemaining--;
            return;
        }

        pendingCastTicksRemaining = 0;
        weaponInnate.sendCastRequest(playerPatch, clientEngine.controlEngine);
    }
}
