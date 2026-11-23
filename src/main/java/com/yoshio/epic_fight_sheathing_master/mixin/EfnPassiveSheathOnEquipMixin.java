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

import com.yoshio.epic_fight_sheathing_master.EfnSheathOnEquip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.skill.SkillContainer;

/**
 * Optional (@Pseudo: silently skipped when EFN is not installed or its classes differ). After EFN's weapon
 * passives for the Murasama and the HF-BLADE have initialised (which sets the item to the drawn state), the
 * state is switched to sheathed, see {@link EfnSheathOnEquip}. Contains no EFN code; works on the logical
 * server only, so it is harmless on a client connected to a server without this mod.
 */
@Pseudo
@Mixin(targets = {"com.hm.efn.skill.weapon_passive.MurasamaPassive",
        "com.hm.efn.skill.weapon_passive.HfBladePassive"}, remap = false)
public abstract class EfnPassiveSheathOnEquipMixin {

    @Inject(method = "onInitiate", at = @At("TAIL"), remap = false, require = 0)
    private void efsm$sheathOnEquip(SkillContainer container, CallbackInfo ci) {
        EfnSheathOnEquip.apply(this, container);
    }
}
