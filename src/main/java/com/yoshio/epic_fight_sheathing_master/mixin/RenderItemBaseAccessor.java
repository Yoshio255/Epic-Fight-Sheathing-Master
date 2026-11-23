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

import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;

import java.util.Map;

@Mixin(RenderItemBase.class)
public interface RenderItemBaseAccessor {

    @Accessor("alwaysInHand")
    boolean getAlwaysInHand();

    @Accessor("mainhandCorrectionTransforms")
    Map<String, OpenMatrix4f> getMainhandCorrectionTransforms();

    // Static field accessors — throw bodies are replaced by the Mixin framework at load time.

    @Accessor("GLOBAL_MAINHAND_ITEM_TRANSFORMS")
    static Map<String, OpenMatrix4f> getGlobalMainhandItemTransforms() {
        throw new AssertionError("Mixin not applied");
    }

    @Accessor("itemInHandRenderer")
    static ItemInHandRenderer getItemInHandRenderer() {
        throw new AssertionError("Mixin not applied");
    }
}
