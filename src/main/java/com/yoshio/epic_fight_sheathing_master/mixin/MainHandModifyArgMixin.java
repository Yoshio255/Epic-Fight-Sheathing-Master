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
import com.yoshio.epic_fight_sheathing_master.DiscardingBufferSource;
import com.yoshio.epic_fight_sheathing_master.MainHandHooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

// The main-hand renderItemInHand call of PatchedItemInHandLayer.renderLayer, not replaced but with its buffer argument changed:
// Epic Fight: BK's Poise & Stance redirects that very call, and only one @Redirect can take an instruction (a crash for the one
// that loses). PatchedItemInHandLayerMixin draws the held weapon its own way; then the original call (which Poise may still
// redirect) is handed a buffer that discards everything, so it draws nothing a second time. When there is nothing to draw
// differently, the call is left completely untouched. Applied only when Poise is installed (see EfsmMixinPlugin); priority below
// the default so that this runs before Poise's redirect.
@Mixin(value = PatchedItemInHandLayer.class, priority = 600)
public abstract class MainHandModifyArgMixin {

    @ModifyArg(
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
        index = 4,
        remap = false,
        require = 0
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private MultiBufferSource efsm$mainHandBuffer(
            ItemStack stack, LivingEntityPatch entitypatch, InteractionHand hand, OpenMatrix4f[] poses,
            MultiBufferSource buffer, PoseStack postStack, int packedLight, float partialTicks) {
        boolean passedThrough = ((MainHandHooks) (Object) this).efsm$mainHandDeferred(
                stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
        return passedThrough ? buffer : DiscardingBufferSource.INSTANCE;
    }
}
