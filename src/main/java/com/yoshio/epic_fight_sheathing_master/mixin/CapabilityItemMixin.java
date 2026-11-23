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

package com.yoshio.epic_fight_sheathing_master.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.skill.BattojutsuPassive;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.item.UchigatanaItem;

// ── Katana sheath-state persistence across the hotbar ───────────────────────
//
// Epic Fight's own CapabilityItem#changeWeaponInnateSkill (called from
// ServerPlayerPatch#updateHeldItem whenever the main-hand item changes)
// resets the WEAPON_PASSIVE skill container to null whenever the newly held
// item has no passive skill of its own. For katana this wipes
// BattojutsuPassive's data — including SkillDataKeys.SHEATH — the instant
// you switch main-hand away from that specific katana instance: "letting go
// of the weapon" is what resets the sheathed state today.
//
// Confirmed via decompilation that changeWeaponInnateSkill contains exactly
// 3 SkillContainer.setSkill(Skill) call sites in bytecode order: WEAPON_INNATE
// (ordinal 0), WEAPON_PASSIVE's non-null branch (ordinal 1), and this
// null-reset branch (ordinal 2) — targeting ordinal 2 uniquely identifies the
// reset we want to intercept.
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(CapabilityItem.class)
public abstract class CapabilityItemMixin {

    @Redirect(
        method = "changeWeaponInnateSkill",
        at = @At(
            value = "INVOKE",
            target = "Lyesman/epicfight/skill/SkillContainer;setSkill(Lyesman/epicfight/skill/Skill;)Z",
            ordinal = 2
        ),
        remap = false
    )
    private boolean redirectWeaponPassiveReset(
            SkillContainer passiveSkillContainer, Skill skill,
            PlayerPatch playerpatch, ItemStack itemstack) {
        // skill is always null here (this call site only ever passes null —
        // see CapabilityItem#changeWeaponInnateSkill's else branch), checked
        // anyway as a defensive assertion.
        if (skill == null
                && passiveSkillContainer.getSkill() instanceof BattojutsuPassive
                && playerpatch.getOriginal() instanceof Player player
                && hasUchigatanaInHotbar(player)) {
            // Skip the reset entirely: leave BattojutsuPassive (and its
            // SHEATH data) attached and untouched, since another katana
            // still exists in the hotbar. Only the *specific instance*
            // reset was suppressed here — behavior when switching back to
            // ANY katana in this hotbar is unaffected either way, since
            // getSkill() != passiveSkill is already false in that case.
            return true;
        }

        return passiveSkillContainer.setSkill(skill);
    }

    private static boolean hasUchigatanaInHotbar(Player player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof UchigatanaItem) {
                return true;
            }
        }
        return false;
    }
}
