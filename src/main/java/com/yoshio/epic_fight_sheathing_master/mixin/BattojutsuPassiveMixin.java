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

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.skill.BattojutsuPassive;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.item.UchigatanaItem;

// ── Auto-sheath animation/sound: only play while actually holding the katana ─
//
// BattojutsuPassive's "5 seconds without attacking" timer (see
// getCooldownRegenPerSecond / setConsumption) keeps running as long as the
// skill remains attached to the WEAPON_PASSIVE container — which, since
// CapabilityItemMixin now keeps it attached whenever a katana still exists
// anywhere in the hotbar (not just while actually held), means the timer
// (and the SkillDataKeys.SHEATH flag flip itself) correctly keeps
// progressing even while holding something else — that part is intentional
// and untouched here.
//
// What's NOT wanted is the visible/audible side effect: setConsumption
// calls ServerPlayerPatch#playAnimationInClientSide(BIPED_UCHIGATANA_SCRAP,
// ...) unconditionally, which plays a "sheathing" body animation (with its
// sound baked into the animation itself) on the player regardless of what's
// actually in their hand — visibly wrong when there's no blade in view to
// sheath. This redirects that one call so it's skipped whenever the katana
// isn't the current main-hand item; the SHEATH data flag (set just before
// this call, unaffected) and the timer both continue exactly as before.
// BattojutsuSkill's own draw-skill (battojutsu) animation/sound is a
// completely separate class and is untouched.
@Mixin(BattojutsuPassive.class)
public abstract class BattojutsuPassiveMixin {

    @Redirect(
        method = "setConsumption",
        at = @At(
            value = "INVOKE",
            target = "Lyesman/epicfight/world/capabilities/entitypatch/player/ServerPlayerPatch;" +
                     "playAnimationInClientSide(Lyesman/epicfight/api/asset/AssetAccessor;F)V"
        ),
        remap = false
    )
    private void redirectSheathAnimation(ServerPlayerPatch executor, AssetAccessor<? extends StaticAnimation> animation, float transitionTimeModifier) {
        if (executor.getOriginal().getMainHandItem().getItem() instanceof UchigatanaItem) {
            executor.playAnimationInClientSide(animation, transitionTimeModifier);
        }
    }
}
