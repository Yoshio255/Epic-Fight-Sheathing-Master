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

public class TuneableOffset {

    private final float defaultTx, defaultTy, defaultTz;
    private final float defaultRotX, defaultRotY, defaultRotZ;
    private final float defaultScale;

    public float tx, ty, tz, rotX, rotY, rotZ, scale;

    public TuneableOffset(float tx, float ty, float tz,
                          float rotX, float rotY, float rotZ) {
        this(tx, ty, tz, rotX, rotY, rotZ, 1.0f);
    }

    public TuneableOffset(float tx, float ty, float tz,
                          float rotX, float rotY, float rotZ, float scale) {
        this.defaultTx    = tx;
        this.defaultTy    = ty;
        this.defaultTz    = tz;
        this.defaultRotX  = rotX;
        this.defaultRotY  = rotY;
        this.defaultRotZ  = rotZ;
        this.defaultScale = scale;
        reset();
    }

    public void reset() {
        tx    = defaultTx;
        ty    = defaultTy;
        tz    = defaultTz;
        rotX  = defaultRotX;
        rotY  = defaultRotY;
        rotZ  = defaultRotZ;
        scale = defaultScale;
    }

    @Override
    public String toString() {
        return String.format("tx=%.4f  ty=%.4f  tz=%.4f  rotX=%.4f  rotY=%.4f  rotZ=%.4f  scale=%.4f",
                tx, ty, tz, rotX, rotY, rotZ, scale);
    }

    // ── Registry ──────────────────────────────────────────────────────────────

    public static final Map<String, TuneableOffset> REGISTRY = new HashMap<>();

    static {
        // scale applies to the dagger BODY only (both the thigh-stowed and
        // genuinely-in-hand renders — see renderDaggerAtWaist /
        // renderNativeItemInHand). The sheath's scale is baked into
        // dagger_sheath.json instead and isn't live-tunable.
        REGISTRY.put("dagger", new TuneableOffset(
                0.18f,   // tx
                0.05f,   // ty
                0.0f,    // tz
                0.0f,    // rotX
                0.0f,    // rotY
                0.0f,    // rotZ
                0.7f     // scale
        ));
        REGISTRY.put("dagger_offhand", new TuneableOffset(
                -0.18f,   // tx
                 0.05f,   // ty
                 0.0f,    // tz
                 0.0f,    // rotX
                 0.0f,    // rotY
                 0.0f,    // rotZ
                 0.7f     // scale
        ));
        // Small manual nudge applied on top of Epic Fight's own native hand
        // position, for the genuinely-in-hand dagger render only (does not
        // affect the thigh-stowed "dagger" render) — see
        // renderNativeItemInHand. Independent of "dagger"/"dagger_offhand"
        // (which only govern the thigh-stowed position + the shared scale).
        REGISTRY.put("dagger_held", new TuneableOffset(
                0.0f,    // tx
                0.08f,   // ty
                0.05f,   // tz
                0.0f,    // rotX
                0.0f,    // rotY
                0.0f     // rotZ
        ));
        REGISTRY.put("dagger_offhand_held", new TuneableOffset(
                0.0f,    // tx
                0.08f,   // ty
                0.05f,   // tz
                0.0f,    // rotX
                0.0f,    // rotY
                0.0f     // rotZ
        ));
        // Thigh-bone (HumanoidArmature.thighR) sheath for the main-hand dagger.
        // Always shown, independent of held state — see renderDaggerSheaths.
        REGISTRY.put("dagger_sheath_right", new TuneableOffset(
                0.18f,   // tx
                0.18f,   // ty
                0.03f,   // tz
                0.0f,    // rotX
                0.0f,    // rotY
                0.0f     // rotZ
        ));
        REGISTRY.put("uchigatana", new TuneableOffset(
                -0.3f,   // tx
                 0.1f,   // ty
                -0.1f,   // tz
               120.0f,   // rotX
               180.0f,   // rotY
                 0.0f    // rotZ
        ));
        // Ladder-climbing evacuation position (competing with a slot0 katana)
        // only — see renderKatanaLeftBeltEvacuation. Fixed left-belt (Torso)
        // position, independent of Config.sheatheUchigatanaAtWaist; does not affect the
        // idle slot0 display or the normal (non-climbing) held display. The
        // sheath has no separate key here — it auto-follows via the same
        // katanaSheathRelativeOffset used by the normal WAIST-mode sheath, a
        // fixed local blade-to-sheath transform independent of this key's
        // own position.
        REGISTRY.put("uchigatana_offhand", new TuneableOffset(
                -0.3f,    // tx
                 0.03f,   // ty
                -0.1f,    // tz
               100.0f,    // rotX
               180.0f,    // rotY
                10.0f     // rotZ
        ));
        REGISTRY.put("tachi", new TuneableOffset(
                -0.385f,   // tx
                -0.2f,     // ty
                -0.7f,     // tz
               105.0f,     // rotX
               170.0f,     // rotY
                 0.0f      // rotZ
        ));
        // WAIST mode only, while ladder-climbing (isChestAlreadyOccupied):
        // dedicated position/rotation for the tachi blade, used instead of
        // "tachi". The sheath shares this same torso basis (see
        // renderDetachedSheathAtMode / resolveWaistOffsetKey), so it follows
        // automatically.
        REGISTRY.put("tachi_ladder", new TuneableOffset(
                -0.7f,    // tx
                -0.07f,   // ty
                 0.16f,   // tz
                70.0f,    // rotX
               150.0f,    // rotY
                90.0f     // rotZ
        ));
        // BACK mode (Config.sheatheTachiAtWaist==false) only: extra correction applied
        // on top of the Chest-bone off-hand correction matrix for the tachi sheath.
        REGISTRY.put("tachi_sheath", new TuneableOffset(
                  0.0f,    // tx
                  0.35f,   // ty
                 -0.13f,   // tz
                -34.0f,    // rotX
                270.0f,    // rotY
                203.0f     // rotZ
        ));
        // WAIST mode only: extra correction folded into the cached WAIST-mode
        // relative sheath offset (see computeSheathRelativeOffset). Independent
        // of tachi_sheath (BACK mode) — the two modes need different tuning.
        // Starting value carried over from tachi_sheath's old shared value,
        // which was previously confirmed correct for WAIST mode.
        REGISTRY.put("tachi_sheath_waist", new TuneableOffset(
                  0.0f,   // tx
                  0.3f,   // ty
                 -0.35f,  // tz
                 96.0f,   // rotX
                 90.0f,   // rotY
                 40.0f    // rotZ
        ));
        // BACK mode only: confirmed extra correction on top of the tachi
        // blade's Chest-bone position. Blade-only — deliberately independent
        // of tachi_sheath (BACK-mode sheath tuning is tuned by hand to match
        // wherever this places the blade, not vice versa).
        REGISTRY.put("tachi_back", new TuneableOffset(
                  0.0f,   // tx
                  0.3f,   // ty
                  0.78f,  // tz
                -60.0f,   // rotX
                180.0f,   // rotY
                  0.0f    // rotZ
        ));
        // ── Avalon mesh_item weapons (EFN) — see MeshWeaponIdleRenderer ───────
        // One WAIST (Torso) and one BACK (Chest) entry per weapon, keyed by the
        // item id with ':' replaced by '.' (so it fits /efsm tune's word
        // argument). All-zero = the default placement from MeshWeaponIdleRenderer
        // (left hip / behind the back); these values are adjustments on top of
        // it. tx/ty/tz are in the bone's frame, rotX/Y/Z pivot at the grip.
        // Tune in game: /efsm tune efn.air_tachi tx 0.1
        REGISTRY.put("efn.air_tachi", new TuneableOffset(
                0.0f, 0.0f, -0.2f,  // tx ty tz (tuned in game)
                0.0f, 0.0f, 0.0f    // rotX rotY rotZ
        ));
        // co_tachi: blade inserted into the mesh's own Torso scabbard. The WAIST
        // entry is the blade-in-scabbard fit on top of the computed
        // MeshWeaponIdleRenderer.SCABBARD_FIT (tuned in game); the BACK entry
        // only moves the whole blade+scabbard assembly, which keeps exactly the
        // waist blade-to-scabbard relation (see MeshWeaponIdleRenderer).
        REGISTRY.put("efn.co_tachi", new TuneableOffset(
                -0.04f, -0.007f, 0.4f,  // tx ty tz (tuned in game)
                -2.0f, 0.0f, 0.0f       // rotX rotY rotZ (tuned in game)
        ));
        // WAIST only: moves blade and scabbard together (Torso frame), keeping their relation.
        REGISTRY.put("efn.co_tachi_all", new TuneableOffset(
                0.0f, -0.16f, 0.35f,  // tx ty tz (tuned in game)
                0.0f, 0.0f, 0.0f
        ));
        // ruinsgreatsword: a bare 2.6-long blade (taller than the 1.8 player), kept at
        // full size. Both entries tuned in game.
        REGISTRY.put("efn.ruinsgreatsword", new TuneableOffset(
                -0.8f, -0.1f, 0.2f,   // tx ty tz
                0.0f, 90.0f, 20.0f    // rotX rotY rotZ
        ));
        REGISTRY.put("efn.ruinsgreatsword_back", new TuneableOffset(
                -0.85f, 0.5f, 0.0f,   // tx ty tz
                -60.0f, 0.0f, 180.0f  // rotX rotY rotZ
        ));
        // nf_shortsword / nf_shortsword_2: bare blades about 1.3 long, carried at the RIGHT
        // waist only, like Epic Fight's dagger (MeshWeaponIdleRenderer.RIGHT_HIP / WAIST_ONLY).
        // Tuned in game on nf_shortsword (tz -0.3); both blades share it.
        for (String key : new String[]{"efn.nf_shortsword", "efn.nf_shortsword_2"}) {
            REGISTRY.put(key, new TuneableOffset(
                    0.0f, 0.0f, -0.3f,
                    0.0f, 0.0f, 0.0f
            ));
        }
        // Unique sheathed swords (MeshWeaponIdleRenderer.LEFT_SHEATHED): placed exactly where their own idle animation
        // holds the sheathed sword at the left hip, waist only; zero = that placement, untuned. tx/ty/tz in the Torso
        // frame, rotations around the grip.
        // Murasama needs no tuning (its sheath already sits at the hip); the blue HF-BLADE shares its animation pose.
        for (String key : new String[]{"efn.hf_murasama", "efn.hf_blade"}) {
            REGISTRY.put(key, new TuneableOffset(
                    0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f
            ));
        }
        // Yamato (DMC3), tuned in game; the DMC4 one shares the same values (to be checked in game).
        for (String key : new String[]{"efn.yamato_dmc_in_sheath", "efn.yamato_dmc4_in_sheath"}) {
            REGISTRY.put(key, new TuneableOffset(
                    0.12f, 0.0f, 0.0f,
                    25.0f, 10.0f, 0.0f
            ));
        }
        // nf_claw (EFN's wild claws, a FIST weapon): WORN on both hands (MeshWeaponIdleRenderer.WORN_ITEMS). "efn.nf_claw" =
        // right hand, "efn.nf_claw_l" = left hand; translation / rotation in the hand's frame. Zero = exactly on the hand.
        for (String key : new String[]{"efn.nf_claw", "efn.nf_claw_l"}) {
            REGISTRY.put(key, new TuneableOffset(
                    0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f
            ));
        }
        // kusabimaru (楔丸, the sword in its sheath hanging at the left hip, MeshWeaponIdleRenderer.ARMATURE_CARRY):
        // zero = the position of its own idle animation, waist only. tx/ty/tz in the Torso frame.
        REGISTRY.put("efn.kusabimaru", new TuneableOffset(
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f
        ));
        // broadblade, waist mode, stowed for a LADDER CLIMB only ("_ladder", like Epic Fight's tachi): tuned in
        // game; the standing waist position below stays the standard one.
        REGISTRY.put("efn.broadblade_ladder", new TuneableOffset(
                0.8f, -0.1f, 0.2f,
                240.0f, 270.0f, 200.0f
        ));
        // broadblade (Warriors-BroadBlade; 2.7 long only with the end of its cord, the blade itself is much
        // shorter; drawn with the item's own armature): worn at the waist by default, or on the back per the
        // config. The standing waist position, tuned in game.
        REGISTRY.put("efn.broadblade", new TuneableOffset(
                0.0f, 0.05f, -0.35f,
                0.0f, 0.0f, 0.0f
        ));
        // Back tuned in game; the waist entry above is still the ruinsgreatsword start value.
        REGISTRY.put("efn.broadblade_back", new TuneableOffset(
                -0.6f, 0.05f, 0.0f,
                320.0f, 0.0f, 180.0f
        ));
        // nf_dual_sword (Ancient Sculk Twinblades): one item, two bare blades. Waist (usual hip placement, right
        // blade at the right hip) = "efn.nf_dual_sword" / left blade "_l". Back (the two cross on the back,
        // MeshWeaponIdleRenderer.rearCrossPlacement) = "_back" / left blade "_back_l". Tuned in game.
        REGISTRY.put("efn.nf_dual_sword", new TuneableOffset(
                -0.65f, 0.0f, -0.3f,
                35.0f, 0.0f, 180.0f
        ));
        REGISTRY.put("efn.nf_dual_sword_l", new TuneableOffset(
                0.65f, 0.0f, -0.3f,
                35.0f, 0.0f, 180.0f
        ));
        // Back: the blades are cross-drawn (each on the opposite side). Tuned in game (the two blades differ:
        // the left one is flipped over, rotZ 180).
        REGISTRY.put("efn.nf_dual_sword_back", new TuneableOffset(
                -0.2f, 0.2f, 0.0f,
                -20.0f, 0.0f, 0.0f
        ));
        REGISTRY.put("efn.nf_dual_sword_back_l", new TuneableOffset(
                -0.65f, 0.1f, 0.0f,
                -50.0f, 0.0f, 180.0f
        ));
        // Exile Blade pair (exsiliumgladius + fire_exsiliumgladius, category SWORD): bare ~2.2-long blades,
        // handled like a vanilla sword. The SIDE depends on the role, not the item: the first idle weapon
        // goes to the right, a weapon in the off-hand (when it is not a valid dual-wield) or a second idle
        // one to the left ("_off" keys). Waist or back per the config toggle. Zero = default placement.
        // Waist values tuned in game (right and left identical).
        REGISTRY.put("efn.exsiliumgladius", new TuneableOffset(
                0.0f, 0.1f, -0.2f,
                0.0f, 0.0f, 0.0f
        ));
        REGISTRY.put("efn.exsiliumgladius_off", new TuneableOffset(
                0.0f, 0.1f, -0.2f,
                0.0f, 0.0f, 0.0f
        ));
        // Exile Blade pair, BACK variant: the right one starts from the ruinsgreatsword values (a similar
        // bare blade). The left one ("_back_off") is its mirror image across the body's centre plane (the
        // default back placement is mirrored in MeshWeaponIdleRenderer.placement): tx negated, rotY and
        // rotZ negated, so the two cross behind the back. Tuned in game (the mirror held: left = right mirrored).
        REGISTRY.put("efn.exsiliumgladius_back", new TuneableOffset(
                -0.7f, 0.15f, 0.0f,
                -45.0f, 0.0f, 180.0f
        ));
        REGISTRY.put("efn.exsiliumgladius_back_off", new TuneableOffset(
                0.7f, 0.15f, 0.0f,
                -45.0f, 0.0f, -180.0f
        ));
        // meen_spear: carried like Epic Fight carries its spear (MeshWeaponIdleRenderer.EF_STYLE):
        // J = poses[Chest] · C_chest · C_tool⁻¹. Back only (the _back key). Zero = exactly that
        // placement, untuned; tx/ty/tz are in the Chest frame, rot/scale around the grip.
        REGISTRY.put("efn.meen_spear_back", new TuneableOffset(
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f
        ));
        // crescent_moon (a ~3.8-long falchion-type pole weapon drawn by EFN's RenderFalchion): same
        // Epic Fight style carry as meen_spear, same meaning of the values. Tuned in game.
        REGISTRY.put("efn.crescent_moon_back", new TuneableOffset(
                0.0f, -0.65f, 0.0f,
                95.0f, 0.0f, 0.0f
        ));
        // thornwheel (Ghiza's Wheel, a 3.7-long pole with a 1.5 wheel, drawn by Avalon's animation_item
        // renderer with the item's own armature): same Epic Fight style carry. Tuned in game.
        REGISTRY.put("efn.thornwheel_back", new TuneableOffset(
                0.5f, 0.5f, 0.0f,
                0.0f, 0.0f, 0.0f
        ));
        // crimson_moon (a scythe, ~2.9 long): same Epic Fight style carry. Untuned.
        REGISTRY.put("efn.crimson_moon_back", new TuneableOffset(
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f
        ));
        // Adjusts the box/OBJ sheath of those blades on top of MeshWeaponIdleRenderer.SHEATH_FIT:
        // tx/ty/tz = move (weapon joint space), rotX/Y/Z = turn around its centre
        // (rotX = change of the tilt), scale = stretch along the blade (1 = as modelled).
        for (String key : new String[]{"efn.nf_shortsword_sheath", "efn.nf_shortsword_2_sheath"}) {
            REGISTRY.put(key, new TuneableOffset(
                    0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f
            ));
        }
        // sword_of_pioneer: same blade+scabbard scheme as co_tachi. Zero = computed fit
        // (MeshWeaponIdleRenderer.SCABBARD_FIT), untuned. _back starts from the values
        // that worked for air_tachi/co_tachi.
        REGISTRY.put("efn.sword_of_pioneer", new TuneableOffset(
                0.0f, 0.06f, 0.02f,  // tx ty tz (tuned in game)
                1.0f, 0.0f, 0.0f     // rotX rotY rotZ (tuned in game)
        ));
        REGISTRY.put("efn.sword_of_pioneer_all", new TuneableOffset(
                0.0f, -0.05f, 0.15f, // tx ty tz (tuned in game)
                0.0f, 0.0f, 0.0f
        ));
        REGISTRY.put("efn.sword_of_pioneer_back", new TuneableOffset(
                -0.5f, 0.0f, 0.0f,
                -40.0f, 0.0f, 180.0f
        ));
        REGISTRY.put("efn.co_tachi_back", new TuneableOffset(
                -0.5f, 0.0f, 0.0f,    // tx ty tz (tuned in game)
                -40.0f, 0.0f, 180.0f  // rotX rotY rotZ (tuned in game)
        ));
        REGISTRY.put("efn.air_tachi_back", new TuneableOffset(
                -0.5f, 0.0f, 0.0f,    // tx ty tz (tuned in game)
                -40.0f, 0.0f, 180.0f  // rotX rotY rotZ (tuned in game)
        ));

        // Torso-bone (waist) position shared by all FORCE_SHEATH_CATEGORIES
        // tools (pickaxe/hoe/shovel) — see renderSlot0Weapon's forceOverride
        // branch. All three categories render identically with this one key.
        REGISTRY.put("tool_waist", new TuneableOffset(
                0.0f,   // tx
                0.0f,   // ty
                0.0f,   // tz
                0.0f,   // rotX
                0.0f,   // rotY
                0.0f    // rotZ
        ));

        // ── Vanilla sword (WeaponCategories.SWORD) ──────────────────────────
        // WAIST mode: sword shares a single bone (Torso) between both sides —
        // left/right is purely a tx sign difference on top of that shared
        // bone, per explicit design request, since Torso has no native
        // left/right split of its own. "sword"/"sword_offhand" are the
        // WAIST-mode body positions (right/left) — fully independent, no
        // auto-mirroring between them (see resolveSwordBaseMatrix's WAIST
        // branch), so expect their rotations to end up genuinely different
        // after tuning, not just sign-flipped.
        //
        // BACK mode: unlike WAIST, Epic Fight's own native Chest correction
        // is already split per hand (GLOBAL_OFFHAND_ITEM_TRANSFORMS."Chest"
        // is a distinct, pre-mirrored matrix from GLOBAL_MAINHAND_ITEM_
        // TRANSFORMS."Chest" — confirmed via decompilation), and the vanilla
        // sword's own item_skin doesn't override it — so "sword_back"/
        // "sword_offhand_back" start at all-zero: the native mainhand/
        // offhand Chest matrices (see resolveSwordBaseMatrix's BACK branch)
        // already place each side correctly on their own.
        REGISTRY.put("sword", new TuneableOffset(
                -0.33f,   // tx
                 0.1f,    // ty
                -0.1f,    // tz
               120.0f,    // rotX
               180.0f,    // rotY
                 0.0f     // rotZ
        ));
        REGISTRY.put("sword_offhand", new TuneableOffset(
                 0.32f,   // tx
                -0.1f,    // ty
                -0.3f,    // tz
               100.0f,    // rotX
               180.0f,    // rotY
                 0.0f     // rotZ
        ));
        REGISTRY.put("sword_back", new TuneableOffset(
                0.0f,   // tx
                0.0f,   // ty
                0.0f,   // tz
                0.0f,   // rotX
                0.0f,   // rotY
                0.0f    // rotZ
        ));
        REGISTRY.put("sword_offhand_back", new TuneableOffset(
                0.07f,  // tx
                0.0f,   // ty
                0.0f,   // tz
                0.0f,   // rotX
                0.0f,   // rotY
                0.0f    // rotZ
        ));
        // The ONLY sheath tuning key, for both modes and both sides. Directly
        // defines the left/offhand WAIST-mode sheath position (confirmed
        // in-game against "sword_offhand"); its offset from "sword_offhand"
        // is then reapplied onto "sword"/"sword_offhand_back"/"sword_back" to
        // derive the other three (right-WAIST, left-BACK, right-BACK)
        // automatically — see computeSwordSheathRelativeOffset /
        // renderSwordSheathFollowing. There is no separate "sword_sheath_back":
        // the physical sheath-to-blade relationship doesn't depend on which
        // body-fixed bone the blade is mounted on.
        REGISTRY.put("sword_sheath", new TuneableOffset(
                 0.32f,    // tx
                -0.13f,    // ty
                -0.02f,    // tz
               100.0f,     // rotX
               180.0f,     // rotY
                 0.0f      // rotZ
        ));

        // ── Longsword (WeaponCategories.LONGSWORD) ──────────────────────────
        // Exact same pattern as vanilla sword above (shared Torso/Chest bone,
        // tx-sign left/right split, single "longsword_sheath" key reused for
        // both modes/sides) — see resolveSwordBaseMatrix / renderSwordBody /
        // computeLongswordSheathRelativeOffset / renderLongswordSheaths.
        // Longsword's own item_skin also has no "transforms" override (falls
        // back to the same GLOBAL_MAINHAND/OFFHAND_ITEM_TRANSFORMS "Chest"
        // matrices as vanilla sword), so "longsword_back"/
        // "longsword_offhand_back" start at all-zero for the same reason.
        // Note: Epic Fight's own LONGSWORD preset has canBePlacedOffhand(false)
        // — genuine offhand dual-wield is unreachable in current gameplay —
        // but the pattern is still fully duplicated for consistency and in
        // case that restriction changes later. All values below are untuned
        // starting points; expect manual /efsm tune adjustment.
        REGISTRY.put("longsword", new TuneableOffset(
                -0.38f,   // tx
                -0.1f,    // ty
                -0.2f,    // tz
               110.0f,    // rotX
               180.0f,    // rotY
                10.0f     // rotZ
        ));
        REGISTRY.put("longsword_offhand", new TuneableOffset(
                 0.38f,   // tx
                -0.1f,    // ty
                -0.2f,    // tz
               115.0f,    // rotX
               180.0f,    // rotY
               -10.0f     // rotZ
        ));
        REGISTRY.put("longsword_back", new TuneableOffset(
                 0.0f,     // tx
                -0.25f,    // ty
                 0.0f,     // tz
               -20.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        REGISTRY.put("longsword_offhand_back", new TuneableOffset(
                 0.05f,    // tx
                -0.25f,    // ty
                 0.0f,     // tz
               -20.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        REGISTRY.put("longsword_sheath", new TuneableOffset(
                 0.28f,    // tx
                -0.23f,    // ty
                 0.36f,    // tz
               115.0f,     // rotX
               180.0f,     // rotY
               -10.0f      // rotZ
        ));

        // ── Bow (WeaponCategories.BOW) WAIST mode ───────────────────────────
        // Only used when Config.sheatheBowAtWaist is true — see the "Bow /
        // Crossbow on back" block in PatchedItemInHandLayerMixin. Torso-bone
        // basis, same tx/ty/tz + rotX/rotY/rotZ pattern as sword/longsword.
        // BACK mode (default) is untouched and doesn't reference this key.
        REGISTRY.put("bow", new TuneableOffset(
                0.35f,   // tx
               -0.1f,    // ty
                0.0f,    // tz
               90.0f,    // rotX
                0.0f,    // rotY
              -13.0f     // rotZ
        ));

        // ── Greatsword (WeaponCategories.GREATSWORD) idle position ─────────
        // Small manual nudge on top of Epic Fight's own native Chest-bone
        // idle position — see renderSlot0Weapon's generic fallback branch
        // (same pattern as the "tachi_back" nudge applied there). All-zero
        // starting point: Epic Fight's own placement is used until tuned.
        // Confirmed in-game: only a ty nudge was needed.
        REGISTRY.put("greatsword", new TuneableOffset(
                0.0f,    // tx
               -0.1f,    // ty
                0.0f,    // tz
                0.0f,    // rotX
                0.0f,    // rotY
                0.0f     // rotZ
        ));
        // WAIST mode only (Config.sheatheGreatswordAtWaist == true): Torso-bone
        // position, same pattern as "sword"/"longsword". "greatsword" above is
        // the BACK-mode nudge (kept under that name so existing /efsm tune
        // usage still works). Confirmed in-game.
        REGISTRY.put("greatsword_waist", new TuneableOffset(
                 0.5f,    // tx
                -0.1f,    // ty
                 0.2f,    // tz
                83.0f,    // rotX
                10.0f,    // rotY
                90.0f     // rotZ
        ));

        // ── Weapons of Miracles (wom) weapons with their own WeaponCategory, BACK mode ───
        // Nudge applied on top of Epic Fight's normal Chest-bone position, see
        // renderCustomCategoryBody in PatchedItemInHandLayerMixin. Key: "wom.<item id>_back"
        // (e.g. /efsm tune wom.agony_back ty -0.2). All-zero until tuned in game.
        for (String id : new String[]{"agony", "tormented_mind", "ruine", "ender_blaster", "antitheus",
                "moonless", "gesetz", "napoleon", "herrscher", "solar", "nova", "orbit", "blackstar", "satsujin",
                "evil_tachi", "wooden_staff", "stone_staff", "iron_staff", "golden_staff", "diamond_staff",
                "netherite_staff"}) {
            REGISTRY.put("wom." + id + "_back", new TuneableOffset(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        }
        // Weapons of Miracles weapons that use Epic Fight's sword / longsword / greatsword paths (applied by renderSwordBody
        // on top of the category offset): a nudge per mode, "_back" / "_waist", and per off-hand copy, "_back_off" /
        // "_waist_off". Zero until tuned (existing values are kept).
        for (String id : new String[]{"ruine", "herrscher", "hollow_longsword", "iron_greataxe", "golden_greataxe", "diamond_greataxe",
                "netherite_greataxe"}) {
            for (String suffix : new String[]{"_back", "_waist", "_back_off", "_waist_off"}) {
                REGISTRY.putIfAbsent("wom." + id + suffix, new TuneableOffset(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
            }
        }
        // Ruine (LONGSWORD category, drawn by renderSwordBody): BACK nudge on top of "longsword_back". Tuned in game.
        REGISTRY.put("wom.ruine_back", new TuneableOffset(
                 0.0f,     // tx
                 0.0f,     // ty
                 1.1f,     // tz
               -50.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Ender Blaster: carried at the right thigh (thighR), not the back — same placement as the dagger
        // (renderDaggerAtWaist); starts from the dagger's values, not tuned in game yet. scale applies to the gun.
        REGISTRY.put("wom.ender_blaster_thigh", new TuneableOffset(
                 0.18f,    // tx
                -0.1f,     // ty
                 0.0f,     // tz
                90.0f,     // rotX
                 0.0f,     // rotY
                 0.0f,     // rotZ
                 1.0f      // scale
        ));
        // Ender Blaster in the OFF hand (left thigh, thighL): the dagger_offhand-style mirror of the right one.
        REGISTRY.put("wom.ender_blaster_thigh_off", new TuneableOffset(
                -0.18f,    // tx
                -0.1f,     // ty
                 0.0f,     // tz
                90.0f,     // rotX
                 0.0f,     // rotY
                 0.0f,     // rotZ
                 1.0f      // scale
        ));
        // Moonless is two parts: the item "wom:moonless" is the blade (a back-only TACHI item, on top of "tachi_back"), the
        // handle "wom:moonless_handle" is drawn separately at the right hip, Torso bone + "wom.moonless_handle". Both tuned in game.
        REGISTRY.put("wom.moonless_back", new TuneableOffset(
                 0.0f,     // tx
                 1.0f,     // ty
                 0.5f,     // tz
               100.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        REGISTRY.put("wom.moonless_handle", new TuneableOffset(
                 0.3f,     // tx
                -0.25f,    // ty
                -0.1f,     // tz
               120.0f,     // rotX
               180.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Moonless' blade at the back: ty = the up/down float's amplitude in blocks (Weapons of Miracles' own idle animation
        // uses 0.0385, period 2.5 s; 0 turns the float off), tx = the glide time in seconds (the blade eases toward its
        // position, also from the hand when it is put away: about 3x this is the time it takes; 0 = no glide, it warps).
        REGISTRY.put("wom.moonless_bob", new TuneableOffset(
                 0.12f,    // tx (glide time)
                 0.0385f,  // ty (amplitude)
                 0.0f,     // tz
                 0.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Hollow Longsword (LONGSWORD category, drawn by renderSwordBody): BACK nudge, tuned in game (ty only). The
        // off-hand / second-weapon copy (the left position, key "_back_off") takes the same values: only ty is set, so
        // there is nothing to mirror. The waist keys stay zero until tuned.
        for (String suffix : new String[]{"_back", "_back_off"}) {
            REGISTRY.put("wom.hollow_longsword" + suffix, new TuneableOffset(
                     0.0f,     // tx
                    -0.2f,     // ty
                     0.0f,     // tz
                     0.0f,     // rotX
                     0.0f,     // rotY
                     0.0f      // rotZ
            ));
        }
        // Solar (own WeaponCategory): tuned in game.
        REGISTRY.put("wom.solar_back", new TuneableOffset(
                 0.0f,     // tx
                 0.4f,     // ty
                 1.27f,    // tz
               -90.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Satsujin (UCHIGATANA category, drawn by renderSatsujin; waist by default, its own switch womSatsujinAtWaist): the waist
        // nudge sits on top of Epic Fight's "uchigatana" waist position, the sheath nudge is relative to the blade (the
        // scabbard is turned and lifted to fit the blade, see renderSatsujin); "wom.satsujin_back" is in the zero loop above.
        REGISTRY.put("wom.satsujin_back", new TuneableOffset(
                 0.0f,     // tx
                 0.25f,    // ty
                 0.8f,     // tz
               100.0f,     // rotX
                 0.0f,     // rotY
               180.0f      // rotZ
        ));
        REGISTRY.put("wom.satsujin_waist", new TuneableOffset(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        REGISTRY.put("wom.satsujin_sheath", new TuneableOffset(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        // Herrscher (SWORD category, drawn by renderSwordBody; waist by default, its own switch womHerrscherAtWaist): tuned in game.
        REGISTRY.put("wom.herrscher_waist", new TuneableOffset(
                 0.0f,     // tx
                -0.2f,     // ty
                -0.2f,     // tz
                 0.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Herrscher's BACK nudge (on top of "sword_back" / "sword_offhand_back" for the off-hand copy): tuned in game, the same
        // values for both sides.
        for (String suffix : new String[]{"_back", "_back_off"}) {
            REGISTRY.put("wom.herrscher" + suffix, new TuneableOffset(
                     0.0f,     // tx
                    -0.2f,     // ty
                    -0.1f,     // tz
                   -15.0f,     // rotX
                     0.0f,     // rotY
                     0.0f      // rotZ
            ));
        }
        // Gesetz (a ShieldItem, so it goes through the shield-on-back loop of PatchedItemInHandLayerMixin, which applies this
        // key after Epic Fight's vanilla-shield correction): tuned in game.
        REGISTRY.put("wom.gesetz_back", new TuneableOffset(
                -0.1f,     // tx
                 0.0f,     // ty
                 0.2f,     // tz
               120.0f,     // rotX
               180.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Gesetz' handle ("wom:gesetz_handle"), drawn at the left wrist while the shield is idle: a nudge on top of where
        // Weapons of Miracles puts it in the hand (zero = exactly there).
        REGISTRY.put("wom.gesetz_handle", new TuneableOffset(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        // Orbit (own WeaponCategory): tuned in game, ty only.
        REGISTRY.put("wom.orbit_back", new TuneableOffset(
                 0.0f,     // tx
                -1.2f,     // ty
                 0.0f,     // tz
                 0.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Tormented Mind (own WeaponCategory): tuned in game, ty only.
        REGISTRY.put("wom.tormented_mind_back", new TuneableOffset(
                 0.0f,     // tx
                -0.24f,    // ty
                 0.0f,     // tz
                 0.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Blackstar (SPEAR category): tuned in game.
        REGISTRY.put("wom.blackstar_back", new TuneableOffset(
                 0.0f,     // tx
                 0.2f,     // ty
                 0.0f,     // tz
                33.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Napoleon (own WeaponCategory): tuned in game.
        REGISTRY.put("wom.napoleon_back", new TuneableOffset(
                 0.0f,     // tx
                -0.2f,     // ty
                 0.0f,     // tz
                16.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Evil Tachi WAIST position (blade and its sheath). Tuned in game.
        REGISTRY.put("wom.evil_tachi_waist", new TuneableOffset(
                -0.385f,   // tx
                 0.15f,    // ty
                -0.7f,     // tz
               105.0f,     // rotX
               170.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Evil Tachi while ladder-climbing in WAIST mode (resolveWaistOffsetKey): "tachi_ladder" with ty raised by 0.2.
        REGISTRY.put("wom.evil_tachi_ladder", new TuneableOffset(
                -0.7f,     // tx
                 0.13f,    // ty
                 0.16f,    // tz
                70.0f,     // rotX
               150.0f,     // rotY
                90.0f      // rotZ
        ));
        // Evil Tachi (TACHI category, an ordinary tachi in looks): BACK mode only, applied after "tachi_back".
        // Tuned in game. Waist/back is its own switch (Config.womEvilTachiAtWaist).
        REGISTRY.put("wom.evil_tachi_back", new TuneableOffset(
                 0.05f,    // tx
                -0.18f,    // ty
                -0.07f,    // tz
                30.0f,     // rotX
                 0.0f,     // rotY
                 0.0f      // rotZ
        ));
        // Nova (TACHI category, but always shown on the back, see BACK_ONLY_ITEMS in PatchedItemInHandLayerMixin).
        // Tuned in game; applied after "tachi_back". The off-hand display has its own key, "wom.nova_back_off".
        REGISTRY.put("wom.nova_back", new TuneableOffset(
                 0.15f,    // tx
                -0.2f,     // ty
                -0.1f,     // tz
                20.0f,     // rotX
                15.0f,     // rotY
                 6.0f      // rotZ
        ));
        // Nova held in the OFF hand and shown on the back: its own value (applied after the mirrored "tachi_back").
        // Tuned separately from "wom.nova_back", in game.
        REGISTRY.put("wom.nova_back_off", new TuneableOffset(
                 0.0f,     // tx
                 0.64f,    // ty
                 1.3f,     // tz
               100.0f,     // rotX
                 0.0f,     // rotY
               180.0f      // rotZ
        ));
        // Confirmed in game (Agony is a SPEAR-category item: this nudge is applied on top of the spear back position).
        REGISTRY.put("wom.agony_back", new TuneableOffset(
                0.0f,     // tx
               -0.05f,    // ty
                0.1f,     // tz
                7.0f,     // rotX
                0.0f,     // rotY
                0.0f      // rotZ
        ));
    }
}
