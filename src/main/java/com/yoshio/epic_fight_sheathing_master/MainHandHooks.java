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

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

// What PatchedItemInHandLayerMixin offers to the two mixins that hook the main-hand renderItemInHand call (MainHandRedirectMixin and
// MainHandModifyArgMixin; only one of them is applied, see EfsmMixinPlugin). The target class implements this interface through
// PatchedItemInHandLayerMixin, so they can call it by casting (a mixin class can not be referenced directly).
public interface MainHandHooks {

    /** Draws the held main-hand item the way this mod wants it (stowed when climbing, ...); a pass-through is drawn right away. */
    @SuppressWarnings("rawtypes")
    void efsm$mainHand(RenderItemBase renderer, ItemStack stack, LivingEntityPatch entitypatch, InteractionHand hand,
                       OpenMatrix4f[] poses, MultiBufferSource buffer, PoseStack postStack, int packedLight, float partialTicks);

    /**
     * Same, but a pass-through is not drawn here, only reported: returns true when the original call must draw the item itself
     * (with the real buffer), false when this mod has drawn it and the original call must draw nothing.
     */
    @SuppressWarnings("rawtypes")
    boolean efsm$mainHandDeferred(ItemStack stack, LivingEntityPatch entitypatch, InteractionHand hand, OpenMatrix4f[] poses,
                                  MultiBufferSource buffer, PoseStack postStack, int packedLight, float partialTicks);
}
