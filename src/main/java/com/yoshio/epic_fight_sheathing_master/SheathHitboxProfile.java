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

import java.util.HashMap;
import java.util.Map;

// Per-weapon-tag collision-box layout for SheathCollisionSystem. Each
// tag ("tachi", "uchigatana", "sword", "longsword" — dagger never calls in,
// see SheathCollisionSystem) gets its own independent set of values, since
// each sheath's visible size/shape along the blade is different and needs
// its own /efsm hitbox <tag> ... tuning pass. Boxes are chained along the
// sheath in LOCAL space: box i sits at (offset + i*step), see
// SheathCollisionSystem.localPointToWorldPos.
public class SheathHitboxProfile {

    public volatile float halfSizeX, halfSizeY, halfSizeZ;
    public volatile float offsetX, offsetY, offsetZ;
    public volatile float stepX, stepY, stepZ;
    public volatile int count;

    private final float defHalfSizeX, defHalfSizeY, defHalfSizeZ;
    private final float defOffsetX, defOffsetY, defOffsetZ;
    private final float defStepX, defStepY, defStepZ;
    private final int defCount;

    public SheathHitboxProfile(
            float halfSizeX, float halfSizeY, float halfSizeZ,
            float offsetX, float offsetY, float offsetZ,
            float stepX, float stepY, float stepZ,
            int count) {
        this.halfSizeX = this.defHalfSizeX = halfSizeX;
        this.halfSizeY = this.defHalfSizeY = halfSizeY;
        this.halfSizeZ = this.defHalfSizeZ = halfSizeZ;
        this.offsetX = this.defOffsetX = offsetX;
        this.offsetY = this.defOffsetY = offsetY;
        this.offsetZ = this.defOffsetZ = offsetZ;
        this.stepX = this.defStepX = stepX;
        this.stepY = this.defStepY = stepY;
        this.stepZ = this.defStepZ = stepZ;
        this.count = this.defCount = count;
    }

    public void reset() {
        halfSizeX = defHalfSizeX;
        halfSizeY = defHalfSizeY;
        halfSizeZ = defHalfSizeZ;
        offsetX = defOffsetX;
        offsetY = defOffsetY;
        offsetZ = defOffsetZ;
        stepX = defStepX;
        stepY = defStepY;
        stepZ = defStepZ;
        count = defCount;
    }

    public void setHalfSize(String axis, float value) {
        switch (axis) {
            case "x" -> halfSizeX = value;
            case "y" -> halfSizeY = value;
            case "z" -> halfSizeZ = value;
        }
    }

    public void setOffset(String axis, float value) {
        switch (axis) {
            case "x" -> offsetX = value;
            case "y" -> offsetY = value;
            case "z" -> offsetZ = value;
        }
    }

    public void setStep(String axis, float value) {
        switch (axis) {
            case "x" -> stepX = value;
            case "y" -> stepY = value;
            case "z" -> stepZ = value;
        }
    }

    public void setCount(int value) {
        count = Math.max(1, value);
    }

    @Override
    public String toString() {
        return String.format("halfSize=(%.3f, %.3f, %.3f) offset=(%.3f, %.3f, %.3f) step=(%.3f, %.3f, %.3f) count=%d",
                halfSizeX, halfSizeY, halfSizeZ, offsetX, offsetY, offsetZ, stepX, stepY, stepZ, count);
    }

    public static final Map<String, SheathHitboxProfile> REGISTRY = new HashMap<>();

    static {
        REGISTRY.put("tachi",      new SheathHitboxProfile(0.12f, 0.12f, 0.12f, 0f, 0f, 0f, -0.35f, -0.3f, 0f, 3));
        REGISTRY.put("uchigatana", new SheathHitboxProfile(0.10f, 0.12f, 0.12f, 0f, 0f, -0.1f, 0f, -0.45f, -0.01f, 3));
        REGISTRY.put("sword",      new SheathHitboxProfile(0.12f, 0.12f, 0.12f, 0f, 0f, 0f, 0f, 0.3f, -0.05f, 3));
        REGISTRY.put("longsword",  new SheathHitboxProfile(0.12f, 0.12f, 0.12f, 0f, 0f, 0f, 0f, 0.5f, 0.07f, 3));
        // Weapon BODIES (no sheath) — collision is checked at the body's own
        // render origin. Untuned single-box starting point: tune the
        // count/step per weapon with /efsm hitbox <tag> ....
        REGISTRY.put("greatsword", new SheathHitboxProfile(0.15f, 0.15f, 0.15f, 0f, -0.5f, 0f, 0f, 0.3f, -0.05f, 7));
        // EFN mesh weapon bodies, keyed like TuneableOffset (item id with ':' → '.').
        // Local frame = the weapon's Tool_R joint space: the blade runs along -z
        // from the grip, rising slightly in +y. Untuned starting point.
        REGISTRY.put("efn.air_tachi", new SheathHitboxProfile(0.10f, 0.10f, 0.10f, 0f, 0.1f, -0.35f, 0f, 0.025f, -0.25f, 5)); // tuned in game
        // co_tachi: boxes sit on the SCABBARD, in its Torso-joint frame (model space
        // minus the joint at y=0.814): from the mouth (-0.25, 0.156, -0.58) towards the
        // tip (-0.25, -0.314, 1.19), starting 0.2 in, 0.33 apart. Computed from the
        // mesh, then tuned in game (step z 0.3, count 6).
        REGISTRY.put("efn.co_tachi", new SheathHitboxProfile(0.10f, 0.10f, 0.10f, -0.25f, 0.105f, -0.387f, 0f, -0.085f, 0.30f, 6));
        // sword_of_pioneer: scabbard boxes in its Torso-joint frame, mouth (-0.29, 0.21, -0.45)
        // towards the tip (-0.29, -0.41, 0.97). Computed from the mesh, untuned.
        REGISTRY.put("efn.sword_of_pioneer", new SheathHitboxProfile(0.10f, 0.10f, 0.10f, -0.29f, 0.126f, -0.267f, 0f, -0.12f, 0.275f, 5));
        // ruinsgreatsword: bare blade in the Tool_R frame, running along -z from the
        // guard (z≈-0.8) to the tip (z≈-2.6) and dropping slightly (y -0.2 → -0.4);
        // the blade is wide (0.45) and thin (0.1). Tuned in game (8 boxes from the grip).
        REGISTRY.put("efn.ruinsgreatsword", new SheathHitboxProfile(0.07f, 0.20f, 0.12f, 0f, 0f, 0f, 0f, 0f, -0.37f, 8));
        // nf_shortsword(_2): the boxes sit on the box SHEATH, in the sheath's own frame (centre of
        // the sheath, z along its axis, +z = mouth, ~1.04 long): 4 boxes from just behind the
        // mouth to the tip. Computed from the sheath size, untuned in this frame.
        for (String key : new String[]{"efn.nf_shortsword", "efn.nf_shortsword_2"}) {
            REGISTRY.put(key, new SheathHitboxProfile(0.08f, 0.08f, 0.08f, 0f, 0f, 0.36f, 0f, 0f, -0.28f, 4));
        }
        // meen_spear: a 4.5-long spear in the Tool_R frame; the shaft rises ~10 degrees towards the
        // tip: butt at z +2.0 (y -0.35) to tip at z -2.4 (y +0.40). 16 boxes. Untuned.
        REGISTRY.put("efn.meen_spear", new SheathHitboxProfile(0.09f, 0.09f, 0.14f, 0f, -0.35f, 1.9f, 0f, 0.05f, -0.28f, 16));
        // crescent_moon: 3.8 long in the Tool_R frame, butt at z +1.14 to the tip at z -2.7, axis at
        // y ~ +0.04; handle half-width 0.05, the head (z -1.4..-1.8) 0.14 wide. 14 boxes. Untuned.
        REGISTRY.put("efn.crescent_moon", new SheathHitboxProfile(0.12f, 0.12f, 0.14f, 0f, 0.04f, 1.0f, 0f, 0f, -0.28f, 14));
        // thornwheel: Tool_R frame, pole butt at z +1.25, the wheel (1.5 across, 0.4 thick, centre
        // y ~ +0.22) from z -0.8 to the end at z -2.45. 11 boxes from the butt. Untuned.
        REGISTRY.put("efn.thornwheel", new SheathHitboxProfile(0.2f, 0.3f, 0.2f, 0.02f, 0.05f, 1.0f, 0f, 0f, -0.33f, 11));
        // crimson_moon (scythe), Tool_R frame. Shaft: x -0.05..0, y ~0, from the butt (z +2.04) to z ~0,
        // 7 boxes. Blade head ("_blade"): a crescent in the y-z plane, thin in x (0.11); one straight chain
        // from the upper tip (y +0.6, z -0.75) down to the lower tip (y -1.1, z -0.35), 7 boxes. Untuned.
        REGISTRY.put("efn.crimson_moon", new SheathHitboxProfile(0.09f, 0.09f, 0.16f, -0.025f, 0f, 1.9f, 0f, 0f, -0.3f, 7));
        REGISTRY.put("efn.crimson_moon_blade", new SheathHitboxProfile(0.10f, 0.14f, 0.12f, -0.025f, 0.6f, -0.75f, 0f, -0.26f, 0.065f, 7));
        // exsiliumgladius / fire_exsiliumgladius (Exile Blade pair): bare blades in the Tool_R frame, grip near z 0,
        // blade from z -0.3 to the tip at z -2.19, rising from y ~0.1 to ~0.5. The boxes do not rotate with the
        // weapon, so they are cubes (half size 0.09, a bit smaller than the 0.4-wide blade), chained 0.2 apart:
        // 10 boxes. Untuned. "_off" = the left-side weapon (own tag: each tag keeps its own collision state).
        REGISTRY.put("efn.exsiliumgladius", new SheathHitboxProfile(0.09f, 0.09f, 0.09f, 0f, 0.1f, -0.3f, 0f, 0.048f, -0.2f, 10));
        REGISTRY.put("efn.exsiliumgladius_off", new SheathHitboxProfile(0.09f, 0.09f, 0.09f, 0f, 0.1f, -0.3f, 0f, 0.048f, -0.2f, 10));
        // nf_dual_sword (two blades, own tag each: "_l" = left blade): both are 1.6 long in their own Tool_R / Tool_L
        // frame (same orientation), blade from z -0.3 to the tip at z -1.44, curving up from y ~0.1 to ~0.65.
        // Cubes (the boxes do not rotate with the weapon), half size 0.08, 0.2 apart, 5 boxes. Untuned.
        for (String tag : new String[]{"efn.nf_dual_sword", "efn.nf_dual_sword_l"}) {
            REGISTRY.put(tag, new SheathHitboxProfile(0.08f, 0.08f, 0.08f, 0f, 0.1f, -0.3f, 0f, 0.09f, -0.2f, 5));
        }
        // broadblade: Tool_R frame; the blade from z -0.4 to the tip at z -1.85, the grip behind it up to z ~+0.2
        // (the swaying cord beyond that, to z +0.89, is not covered), axis at y ~0.15. Cubes (the boxes do not
        // rotate with the weapon), half size 0.1, 0.3 apart, 6 boxes from the grip. Untuned.
        REGISTRY.put("efn.broadblade", new SheathHitboxProfile(0.1f, 0.1f, 0.1f, -0.02f, 0.15f, 0.2f, 0f, 0f, -0.3f, 6));
        // hf_murasama (sheathed, Tool_L frame): hilt at z -0.8, guard ~-0.2, the sheath running to the tail at z +1.83,
        // axis at y ~-0.09. Cubes (half 0.1) 0.2 apart from z -0.4: 11 boxes. Untuned.
        REGISTRY.put("efn.hf_murasama", new SheathHitboxProfile(0.1f, 0.1f, 0.1f, 0f, -0.09f, -0.4f, 0f, 0f, 0.2f, 11));
        // hf_blade (the blue one): the sheathed mesh is the same size (z -0.95..1.71), same boxes. Untuned.
        REGISTRY.put("efn.hf_blade", new SheathHitboxProfile(0.1f, 0.1f, 0.1f, 0f, -0.09f, -0.4f, 0f, 0f, 0.2f, 11));
        // yamato_dmc(4)_in_sheath (Tool_L frame): the long part from z -1.4 (y ~+0.35, curving up) to the hilt end at
        // z +0.85 (y ~-0.1). Cubes (half 0.1) 0.25 apart from z -1.3: 9 boxes. Untuned.
        REGISTRY.put("efn.yamato_dmc_in_sheath", new SheathHitboxProfile(0.1f, 0.1f, 0.1f, 0f, 0.3f, -1.3f, 0f, -0.047f, 0.25f, 9));
        REGISTRY.put("efn.yamato_dmc4_in_sheath", new SheathHitboxProfile(0.1f, 0.1f, 0.1f, 0f, 0.35f, -1.4f, 0f, -0.05f, 0.25f, 9));
        // kusabimaru (sheathed, Sheath1 frame): the sheath runs along local +y from -0.44 (hilt) to +1.3 (tip), only
        // +-0.07 / +-0.09 thick. Cubes (half 0.08) 0.2 apart from y -0.2: 8 boxes. Untuned.
        REGISTRY.put("efn.kusabimaru", new SheathHitboxProfile(0.08f, 0.08f, 0.08f, 0f, -0.2f, 0f, 0f, 0.2f, 0f, 8));
        REGISTRY.put("spear", new SheathHitboxProfile(0.12f, 0.12f, 0.12f, 0f, -1.4f, -0.2f, 0f, 0.19f, 0.04f, 17));
        // Weapons of Miracles, keyed like TuneableOffset ("wom.<item id>"). Agony is a SPEAR-category item: tuned in game, drawn at its own back position (see TuneableOffset "wom.agony_back"). Tune: /efsm hitbox wom.agony ...
        REGISTRY.put("wom.agony", new SheathHitboxProfile(0.12f, 0.12f, 0.12f, 0f, -1.7f, 0.1f, 0f, 0.2f, -0.01f, 17)); // tuned in game
        // nova: computed from its OBJ model (item-local frame = model * thirdperson_righthand display transform):
        // the blade runs along +y from y -0.98 to the tip at 2.76, its axis at x -0.03, z 0.09 (grip) .. 0.12 (tip).
        // Cubes (half 0.12) 0.205 apart from y -0.85: 17 boxes (count set in game). Right-hand display.
        REGISTRY.put("wom.nova", new SheathHitboxProfile(0.12f, 0.12f, 0.12f, -0.03f, -0.85f, 0.09f, 0f, 0.205f, 0.002f, 17));
        // tormented_mind (own WeaponCategory, drawn by renderCustomCategoryBody): computed from its OBJ model like nova.
        // Along +y from y -1.58 (pommel) to the tip at 1.81, axis at x -0.03, z ~0; thin in x (+-0.03..0.09), the blade
        // head wide in z (+-0.25..0.43 from y 0.4). Boxes 0.12 x 0.13 x 0.2 half size, 0.25 apart from y -1.45:
        // 13 boxes (count set in game). Untuned otherwise.
        REGISTRY.put("wom.tormented_mind", new SheathHitboxProfile(0.12f, 0.13f, 0.2f, -0.03f, -1.45f, 0.03f, 0f, 0.25f, 0f, 13));
        // blackstar (SPEAR category), computed from its OBJ model like nova: a double-ended spear along y from -2.43 to
        // 2.12 (4.55 long), axis at x -0.03, z 0.09, a thin shaft in the middle and a wide head (1.2 across) at each
        // end. Cubes of half size 0.2, 0.35 apart from y -2.25: 13 boxes to y 1.95. Untuned in game.
        REGISTRY.put("wom.blackstar", new SheathHitboxProfile(0.2f, 0.2f, 0.2f, -0.03f, -2.25f, 0.09f, 0f, 0.35f, 0f, 13));
        // napoleon (own WeaponCategory, drawn by renderCustomCategoryBody), computed from its OBJ model like nova: a
        // polearm along y from -1.88 to 2.73 (4.6 long), axis at x 0.01, z ~0, the head wide in z (+-0.35 around
        // y 0.4..1.2). Boxes 0.12 x 0.1 x 0.2 half size (y set in game), 0.3 apart from y -1.75: 15 boxes to y 2.45.
        REGISTRY.put("wom.napoleon", new SheathHitboxProfile(0.12f, 0.1f, 0.2f, 0.01f, -1.75f, 0f, 0f, 0.3f, 0f, 15));
        // evil_tachi's own 3D sheath (tag "<item>_sheath"), in the blade's item-local frame: the sheath runs along +y from the
        // mouth at y 0.43 to the tip at 2.81, axis at x -0.03, z -0.01. Cubes (half 0.1, y 0.12) 0.25 apart from y 0.55:
        // 10 boxes to y 2.8. Untuned in game.
        REGISTRY.put("wom.evil_tachi_sheath", new SheathHitboxProfile(0.1f, 0.12f, 0.1f, -0.03f, 0.55f, -0.01f, 0f, 0.25f, 0f, 10));
        // ruine (LONGSWORD category, body drawn by renderSwordBody; the longsword body itself has no collision, an item with its
        // own profile does), computed from its OBJ model like nova: along +y from the pommel at -1.03 to the tip at 2.22,
        // axis at x 0, z 0; the cross guard is wide in z (+-0.36 around y 0). Cubes (half 0.08 x 0.13 x 0.12) 0.26 apart
        // from y -0.9: 12 boxes (one fewer than the 13 that reach the tip, tuned in game).
        REGISTRY.put("wom.ruine", new SheathHitboxProfile(0.08f, 0.13f, 0.12f, 0f, -0.9f, 0f, 0f, 0.26f, 0f, 12));
        // solar (own WeaponCategory, drawn by renderCustomCategoryBody), computed from its OBJ model like nova: a staff
        // along +y from -0.91 to 2.70 (3.6 long), axis at x -0.03, z 0.05, thin in the middle and wide in z (+-0.43)
        // around y 0..0.9 and at the tip (+-0.37). A few big boxes (half 0.15 x 0.38 x 0.15, z set in game), 0.74 apart
        // from y -0.55: 5 boxes to y 2.79.
        REGISTRY.put("wom.solar", new SheathHitboxProfile(0.15f, 0.38f, 0.15f, -0.03f, -0.55f, 0.05f, 0f, 0.74f, 0f, 5));
        // nova in the OFF hand (drawn with the THIRD_PERSON_LEFT_HAND display: its translation x -8.5 vs +7.5, and
        // the left-hand flag flips it, so the model sits 1/16 further to +x than in the right hand): same boxes,
        // offset x +0.0625. Own tag, so a main-hand and an off-hand Nova keep separate collision state.
        REGISTRY.put("wom.nova_off", new SheathHitboxProfile(0.12f, 0.12f, 0.12f, 0.03f, -0.85f, 0.09f, 0f, 0.205f, 0.002f, 17));
    }
}
