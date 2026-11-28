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

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

// Chooses how the main-hand renderItemInHand call of Epic Fight's PatchedItemInHandLayer is hooked:
//  - by default with a plain @Redirect (MainHandRedirectMixin), the way this mod has always done it;
//  - when Epic Fight: BK's Poise & Stance is installed, with a @ModifyArg on the buffer (MainHandModifyArgMixin), because Poise
//    redirects the very same call and only one @Redirect can take an instruction.
// So the compatibility with that one addon costs nothing in a setup without it.
public class EfsmMixinPlugin implements IMixinConfigPlugin {
    private static final String REDIRECT_MIXIN = "com.yoshio.epic_fight_sheathing_master.mixin.MainHandRedirectMixin";
    private static final String MODIFY_ARG_MIXIN = "com.yoshio.epic_fight_sheathing_master.mixin.MainHandModifyArgMixin";
    private static final String POISE_MOD_ID = "epicfight_poise";

    private boolean poiseLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        boolean found = false;
        try {
            net.minecraftforge.fml.loading.LoadingModList list = net.minecraftforge.fml.loading.LoadingModList.get();
            found = list != null && list.getModFileById(POISE_MOD_ID) != null;
        } catch (Throwable ignored) {
            // can not tell: keep the default (plain redirect)
        }
        poiseLoaded = found;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (REDIRECT_MIXIN.equals(mixinClassName)) return !poiseLoaded;
        if (MODIFY_ARG_MIXIN.equals(mixinClassName)) return poiseLoaded;
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
