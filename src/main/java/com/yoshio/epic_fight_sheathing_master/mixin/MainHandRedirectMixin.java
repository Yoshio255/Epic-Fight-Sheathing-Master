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
import com.yoshio.epic_fight_sheathing_master.MainHandHooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

// The main-hand renderItemInHand call of PatchedItemInHandLayer.renderLayer, replaced (the way this mod has always done it):
// PatchedItemInHandLayerMixin draws the held weapon its own way, or draws it the normal way. Applied unless Epic Fight: BK's Poise &
// Stance, which redirects the same call, is installed (see EfsmMixinPlugin and MainHandModifyArgMixin). Priority below the
// default, so any other redirect of that call that is not known wins instead of the game crashing (require = 0).
@Mixin(value = PatchedItemInHandLayer.class, priority = 600)
public abstract class MainHandRedirectMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "renderLayer",
        at = @At(
            value = "INVOKE",
            target = "Lyesman/epicfight/client/renderer/patched/item/RenderItemBase;" +
                     "renderItemInHand(Lnet/minecraft/world/item/ItemStack;" +
                     "Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;" +
                     "Lnet/minecraft/world/InteractionHand;" +
                     "[Lyesman/epicfight/api/utils/math/OpenMatrix4f;" +
                     "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                     "Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
            ordinal = 0
        ),
        remap = false,
        require = 0
    )
    private void efsm$mainHandRedirect(
            RenderItemBase renderer, ItemStack stack, LivingEntityPatch entitypatch, InteractionHand hand, OpenMatrix4f[] poses,
            MultiBufferSource buffer, PoseStack postStack, int packedLight, float partialTicks) {
        ((MainHandHooks) (Object) this).efsm$mainHand(renderer, stack, entitypatch, hand, poses, buffer, postStack,
                packedLight, partialTicks);
    }
}
