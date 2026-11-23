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
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

// Draws the sheath collision boxes queued by SheathCollisionSystem this
// frame, using the exact same LevelRenderer.renderLineBox utility vanilla's
// own F3+B entity-hitbox overlay uses — same look, toggled independently via
// /efsm hitbox. Runs on a separate render pass (AFTER_ENTITIES) since the
// boxes were queued from deep inside the per-bone postStack transforms of
// the entity render pass itself.
@Mod.EventBusSubscriber(modid = Epic_fight_sheathing_master.MODID, value = Dist.CLIENT)
public class SheathCollisionDebugRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Earliest available stage — fires before any entity renders this
        // frame, so the level PoseStack here still holds only the camera's
        // rotation (nothing else pushed onto it yet). Captured unconditionally
        // (not gated by debugEnabled) since SheathCollisionSystem needs it for
        // collision/sound detection even when the debug overlay is off.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            SheathCollisionSystem.captureCameraRotation(event.getPoseStack());
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (!SheathCollisionSystem.isDebugEnabled()) return;

        List<AABB> boxes = SheathCollisionSystem.drainDebugBoxes();
        if (boxes.isEmpty()) return;

        // event.getPoseStack() already has the camera's ROTATION baked in
        // (GameRenderer seeds it that way before level rendering starts) but
        // NOT its position — translate by -camPos ourselves, then feed the
        // boxes' actual world coordinates directly (not pre-subtracted; that
        // would double up with the rotation already on the stack and swing
        // the box around whenever the view turns).
        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        for (AABB box : boxes) {
            LevelRenderer.renderLineBox(poseStack, lines, box, 1.0f, 1.0f, 0.0f, 1.0f);
        }
        poseStack.popPose();
        buffer.endBatch(RenderType.lines());
    }
}
