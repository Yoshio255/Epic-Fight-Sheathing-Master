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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

// Smoke that a held weapon makes (Weapons of Miracles' Evil Tachi puffs vanilla smoke along its blade, positions taken from the
// animation's hand bone) would stay at the hand while this mod draws that weapon somewhere else (on the waist / back while the
// player climbs a ladder). PatchedItemInHandLayerMixin marks such a player each frame it draws the weapon elsewhere, and
// ClientLevelParticleMixin drops the smoke that appears near a marked player AND is spawned by that WoM code.
public final class HeldWeaponSmoke {
    private static final Map<UUID, Long> MARKED = new HashMap<>();
    // Marks last this many game ticks after the last frame that drew the weapon elsewhere.
    private static final long MARK_TICKS = 3L;
    // Smoke closer than this (blocks) to a marked player is dropped.
    private static final double RADIUS_SQR = 3.5 * 3.5;
    // The class of Weapons of Miracles that spawns the smoke along the Evil Tachi's blade.
    private static final String WOM_PASSIVE_CLASS = "reascer.wom.skill.weaponpassive.EvilTachiPassive";

    private HeldWeaponSmoke() {}

    public static void mark(Player player) {
        MARKED.put(player.getUUID(), player.level().getGameTime());
    }

    public static boolean suppresses(Level level, double x, double y, double z) {
        if (MARKED.isEmpty()) return false;
        long now = level.getGameTime();
        MARKED.values().removeIf(t -> now - t > MARK_TICKS + 20L || t > now + 20L);
        for (Player player : level.players()) {
            Long t = MARKED.get(player.getUUID());
            if (t == null || now - t > MARK_TICKS) continue;
            if (player.distanceToSqr(x, y, z) < RADIUS_SQR && cameFromWomPassive()) return true;
        }
        return false;
    }

    // True when the particle is being spawned by Weapons of Miracles' Evil Tachi passive (found on the call stack), so that
    // smoke from anything else (torches, furnaces, ...) is never touched. Only asked for particles already near a marked player.
    private static boolean cameFromWomPassive() {
        return StackWalker.getInstance().walk(frames ->
                frames.anyMatch(frame -> frame.getClassName().equals(WOM_PASSIVE_CLASS)));
    }
}
