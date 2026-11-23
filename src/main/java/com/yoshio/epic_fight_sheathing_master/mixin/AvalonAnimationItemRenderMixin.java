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

import com.mojang.blaze3d.vertex.PoseStack;
import com.yoshio.epic_fight_sheathing_master.MeshWeaponIdleRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Optional (@Pseudo: silently skipped when Epic Fight Avalon is not installed). Avalon draws a HELD
 * "animation_item" weapon (e.g. EFN's Warriors-BroadBlade) itself, from the player's animation, so Epic
 * Fight's ladder-climb handling (which moves the tool bones to the back) does not affect it. While this
 * mod stows such a weapon for a ladder climb (MeshWeaponIdleRenderer#drawsStowedForClimb) Avalon's own
 * draw of it is skipped, so only the stowed copy is shown. Contains no Avalon code.
 */
@Pseudo
@Mixin(targets = {"com.merlin204.avalon.entity.client.renderer.patch.item.RenderAnimationItem",
        "com.hm.efn.client.renderer.RenderMortalBlade"}, remap = false)
public abstract class AvalonAnimationItemRenderMixin {

    @Inject(method = "renderAnimationItem", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void efsm$skipWhileStowed(LivingEntityPatch<?> entitypatch, OpenMatrix4f[] poses,
                                      MultiBufferSource buffer, PoseStack poseStack, int packedLight,
                                      float partialTicks, CallbackInfo ci) {
        if (MeshWeaponIdleRenderer.drawsStowedForClimb(entitypatch)) {
            ci.cancel();
        }
    }
}
