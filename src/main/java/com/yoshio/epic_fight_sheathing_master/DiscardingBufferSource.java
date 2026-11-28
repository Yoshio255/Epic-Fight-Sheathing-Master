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

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

// A buffer source that throws every vertex away. PatchedItemInHandLayerMixin hands it to the original main-hand
// renderItemInHand call when this mod has drawn the held weapon itself (the original call can not simply be skipped: another
// addon, e.g. Epic Fight: BK's Poise & Stance, redirects that same call, and only one mixin can redirect it).
// (A BufferSource, not just a MultiBufferSource: a renderer that casts the buffer it is given must keep working.)
public final class DiscardingBufferSource extends MultiBufferSource.BufferSource {
    public static final DiscardingBufferSource INSTANCE = new DiscardingBufferSource();

    // A new consumer for every getBuffer call, never one shared instance: an enchanted item's glint is drawn through
    // VertexMultiConsumer.create(glint buffer, normal buffer), which throws "Duplicate delegates" for two identical consumers.
    private static VertexConsumer discard() {
        return new VertexConsumer() {
            @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
            @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
            @Override public VertexConsumer uv(float u, float v) { return this; }
            @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
            @Override public VertexConsumer uv2(int u, int v) { return this; }
            @Override public VertexConsumer normal(float x, float y, float z) { return this; }
            @Override public void endVertex() { }
            @Override public void defaultColor(int r, int g, int b, int a) { }
            @Override public void unsetDefaultColor() { }
        };
    }

    private DiscardingBufferSource() {
        super(new BufferBuilder(256), java.util.Map.of());
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return discard();
    }

    @Override
    public void endLastBatch() { }

    @Override
    public void endBatch() { }

    @Override
    public void endBatch(RenderType type) { }
}
