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

import com.yoshio.epic_fight_sheathing_master.HeldWeaponSmoke;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Drops the smoke a held weapon makes at the hand while this mod draws that weapon elsewhere (see HeldWeaponSmoke).
@Mixin(ClientLevel.class)
public abstract class ClientLevelParticleMixin {

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void efsm$dropHeldWeaponSmoke(ParticleOptions options, double x, double y, double z,
                                          double xSpeed, double ySpeed, double zSpeed, CallbackInfo ci) {
        if (options == ParticleTypes.SMOKE && HeldWeaponSmoke.suppresses((ClientLevel) (Object) this, x, y, z)) {
            ci.cancel();
        }
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void efsm$dropHeldWeaponSmokeForced(ParticleOptions options, boolean force, double x, double y, double z,
                                                double xSpeed, double ySpeed, double zSpeed, CallbackInfo ci) {
        if (options == ParticleTypes.SMOKE && HeldWeaponSmoke.suppresses((ClientLevel) (Object) this, x, y, z)) {
            ci.cancel();
        }
    }
}
