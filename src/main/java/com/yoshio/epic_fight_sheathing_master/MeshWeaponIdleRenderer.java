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
import com.mojang.math.Axis;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import yesman.epicfight.api.animation.AnimationClip;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.MathUtils;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.model.armature.HumanoidArmature;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the 3D body of an idle (not held) weapon whose Epic Fight item
 * renderer is Epic Fight Avalon's {@code epic_fight_avalon:mesh_item}
 * (EpicFight Nightfall's weapons), at the waist or back.
 *
 * <h3>How it works</h3>
 * Such a weapon is a {@link SkinnedMesh} rigidly bound to the single joint
 * {@code Tool_R}. {@code SkinnedMesh.draw} computes each vertex as
 * {@code poses[joint] × joint.getToOrigin() × v} (the inverse bind matrix is
 * applied <em>inside</em> draw, the {@code poses} we pass are plain joint
 * world transforms), so replacing {@code poses[Tool_R]} for one draw call moves
 * the whole weapon. The replacement is built as
 * <pre>
 *   S_base · T(tx,ty,tz) · bindWorld(Tool_R) · R(rotX,rotY,rotZ) · scale
 * </pre>
 * where {@code S_base = poses[base] · toOrigin(base)} is the skinning matrix
 * of the waist (Torso) or back (Chest) joint and {@code bindWorld(Tool_R)} is
 * the inverse of Tool_R's {@code toOrigin}. A fixed default placement (left
 * hip / behind the back, see the WAIST_/BACK_ constants) is inserted between
 * the tuning translation and {@code bindWorld(Tool_R)}; without it the weapon
 * would sit at its bind-pose hand position, i.e. in the right hand.
 * Tuning translation is in the bone's frame, rotation pivots at the grip.
 *
 * <h3>No compile-time dependency on Avalon</h3>
 * The renderer is recognised by class name and its {@code mesh_main},
 * {@code texture} and {@code texture_l} fields are read by reflection (cached
 * per renderer instance). Any failure → nothing is drawn for that item and
 * one log line is written (once per item), the failure is remembered per
 * renderer and later frames skip the item without touching the mesh again.
 *
 * <p>Only item ids listed in {@link #TUNING_KEYS} are handled; every other
 * item falls through to the caller's normal behavior.
 */
public final class MeshWeaponIdleRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String AVALON_MESH_RENDERER =
            "com.merlin204.avalon.entity.client.renderer.patch.item.RenderMeshItem";
    // EFN's own mesh renderer for its falchion-type weapons (crescent_moon): same idea as Avalon's
    // (a SkinnedMesh on Tool_R + a texture + a glow texture), only the field names differ.
    private static final String EFN_FALCHION_RENDERER = "com.hm.efn.client.renderer.RenderFalchion";
    // EFN's Yamato (Yamato DMC) renderer: same fields as the falchion's (mesh1_main = the Yamato IN ITS SHEATH, skinned to
    // Tool_L: the left hand holds the sheath), and Murasama's: mesh1 = the drawn blade (Tool_R) + a sheath
    // (Tool_L), mesh2 = the sheathed sword (Tool_L only). The idle display uses the sheathed mesh of each.
    private static final String EFN_YAMATO_RENDERER = "com.hm.efn.client.renderer.RenderYamato_DMC";
    private static final String EFN_MURASAMA_RENDERER = "com.hm.efn.client.renderer.RenderMurasama";
    // EFN's renderer of the Kusabimaru (楔丸): an Avalon animation item (own armature with the extra bones Sheath1 [child
    // of the Torso, at the left hip], Sheath2 + Mortal_Blade [Chest]); mesh1_sheath / mesh2_sheath = the sword IN its
    // sheath (every vertex on Sheath1), form 1 = Kusabimaru, form 2 = with the Mortal Blade (不死斬り, used when the
    // player has the MORTAL_BLADE skill: renderer.shouldUseMesh2).
    private static final String EFN_MORTALBLADE_RENDERER = "com.hm.efn.client.renderer.RenderMortalBlade";
    // Avalon's animation_item renderer (EFN thornwheel): the mesh is bound to the ITEM'S OWN armature
    // (Tool_R + a "wheel" joint...), Avalon only draws it while the item is held, from the animator.
    // For the idle display it is drawn here rigidly with that armature.
    private static final String AVALON_ANIMATION_RENDERER =
            "com.merlin204.avalon.entity.client.renderer.patch.item.RenderAnimationItem";

    /**
     * Supported item id → TuneableOffset key prefix. The WAIST (Torso) position
     * is {@code <prefix>}, the BACK (Chest) position is {@code <prefix>_back}
     * (both must exist in {@link TuneableOffset#REGISTRY}). First target:
     * air_tachi only; add further weapons here after checking them in game.
     */
    private static final Map<String, String> TUNING_KEYS = Map.ofEntries(
            Map.entry("efn:air_tachi", "efn.air_tachi"),
            Map.entry("efn:co_tachi", "efn.co_tachi"),
            Map.entry("efn:sword_of_pioneer", "efn.sword_of_pioneer"),
            Map.entry("efn:ruinsgreatsword", "efn.ruinsgreatsword"),
            Map.entry("efn:nf_shortsword", "efn.nf_shortsword"),
            Map.entry("efn:nf_shortsword_2", "efn.nf_shortsword_2"),
            Map.entry("efn:meen_spear", "efn.meen_spear"),
            Map.entry("efn:crescent_moon", "efn.crescent_moon"),
            Map.entry("efn:thornwheel", "efn.thornwheel"),
            Map.entry("efn:crimson_moon", "efn.crimson_moon"),
            // Exile Blade pair (R = exsiliumgladius, L = fire_exsiliumgladius): category SWORD in Epic Fight, so
            // like a vanilla sword the SIDE depends on the role (first idle weapon = right, off-hand / second
            // idle = left), not on the item. Both share one prefix (see SIDE_BY_ROLE).
            Map.entry("efn:exsiliumgladius", "efn.exsiliumgladius"),
            Map.entry("efn:fire_exsiliumgladius", "efn.exsiliumgladius"),
            // Ancient Sculk Twinblades: ONE item whose mesh holds two blades (right on Tool_R, left on Tool_L)
            Map.entry("efn:nf_dual_sword", "efn.nf_dual_sword"),
            // Warriors-BroadBlade: a 2.7-long blade drawn by Avalon's animation_item renderer with the item's own
            // armature (the swaying cord on the grip is bones a..h); drawn rigid, like the thornwheel
            Map.entry("efn:broadblade", "efn.broadblade"),
            // Unique weapons whose sheathed mesh is held in the LEFT hand (see LEFT_SHEATHED)
            Map.entry("efn:hf_murasama", "efn.hf_murasama"),
            Map.entry("efn:hf_blade", "efn.hf_blade"),
            Map.entry("efn:kusabimaru", "efn.kusabimaru"),
            Map.entry("efn:nf_claw", "efn.nf_claw"),
            Map.entry("efn:yamato_dmc_in_sheath", "efn.yamato_dmc_in_sheath"),
            Map.entry("efn:yamato_dmc4_in_sheath", "efn.yamato_dmc4_in_sheath")
    );

    /**
     * Weapons whose mesh has its own scabbard bound to the Torso joint (blade
     * and handle on Tool_R, scabbard on Torso, both in one mesh). Waist display =
     * the blade inserted into that scabbard (the scabbard stays where it is).
     * Back display ({@link Config#isMeshWeaponAtWaist} false) = the blade placed
     * like any Tool_R weapon, with the scabbard moved rigidly along so that the
     * tuned waist blade-in-scabbard relation is kept. Fit values are bind-pose
     * model space of the biped (+x right, +y up, -z forward), derived from the mesh.
     *
     * @param jx,jy,jz   point on the blade just in front of the guard that must
     *                   end up at the scabbard mouth
     * @param tx,ty,tz   the scabbard mouth
     * @param pitchDeg   tip-down pitch after turning the blade around to point backwards
     */
    private record ScabbardFit(float jx, float jy, float jz, float tx, float ty, float tz, float pitchDeg) {}

    private static final Map<String, ScabbardFit> SCABBARD_FIT = Map.of(
            // co_tachi: scabbard at x=-0.25 (left hip), mouth y=0.56 (raw, forward)
            // z=1.0, tip y=-1.19 z=0.5; blade axis x=0.39, rising 11.5 degrees,
            // scabbard falling 17 degrees → pitch 28.7. Raw (x,y,z) → model (x,z,-y).
            "efn.co_tachi", new ScabbardFit(0.39f, 0.92f, -0.60f, -0.25f, 0.97f, -0.58f, 28.7f),
            // sword_of_pioneer (a curved saber): same layout. Scabbard x=-0.29, mouth raw
            // y=0.45 z≈1.02, falling 25 degrees to the tip (y=-0.97, z=0.40); blade axis
            // x=0.38 rising ~7.5 degrees; the blade point at the guard (raw y=0.2, z=0.88)
            // goes to the mouth → pitch 32.5. Computed from the mesh, not yet tuned in game.
            "efn.sword_of_pioneer", new ScabbardFit(0.38f, 0.88f, -0.20f, -0.29f, 1.02f, -0.45f, 32.5f)
    );

    // Default placement targets (bind-pose model space of Epic Fight's biped:
    // +x = character's right, +y = up, -z = forward; Torso joint at y=0.814,
    // Chest joint at y=1.114, body half-width 0.25, back surface at z=+0.125).
    // Shared by every Tool_R-bound mesh weapon; per-weapon tuning
    // (TuneableOffset) is applied on top. Derived from biped.json, not yet
    // verified in game — adjust here if all weapons are off the same way.
    private static final float WAIST_TARGET_X = -0.34f;
    private static final float WAIST_TARGET_Y = 0.86f;
    private static final float WAIST_TARGET_Z = -0.04f;
    private static final float WAIST_PITCH_DEG = 25.0f;   // blade tip lowered
    private static final float BACK_TARGET_X = 0.15f;
    private static final float BACK_TARGET_Y = 1.65f;
    private static final float BACK_TARGET_Z = 0.22f;
    private static final float BACK_TILT_DEG = 70.0f;     // blade angle from horizontal

    // Weapons carried at the RIGHT hip instead of the left one (short blades, dagger-like).
    private static final Set<String> RIGHT_HIP = Set.of("efn.nf_shortsword", "efn.nf_shortsword_2");
    // Weapons that go to the right (first) or the LEFT side like a vanilla sword, by role: the tryRender caller says
    // which. Left uses the "<key>_off" tuning keys (waist) / "<key>_back_off" (back) and the "<key>_off" hitbox tag.
    private static final Set<String> SIDE_BY_ROLE = Set.of("efn.exsiliumgladius");
    // Weapons carried exactly like Epic Fight carries a plain item on the Chest (its spear):
    // see tryRender. Back only, no config toggle.
    private static final Set<String> EF_STYLE = Set.of("efn.meen_spear", "efn.crescent_moon", "efn.thornwheel", "efn.crimson_moon");
    private static final Set<String> BACK_ONLY = Set.of("efn.meen_spear", "efn.crescent_moon", "efn.thornwheel", "efn.crimson_moon");
    // Weapons whose own animations carry them at a fixed spot on the Chest (EFN's scythe: its
    // idle/sheathed animations keep Tool_R rigidly at this offset). Row-major 4x4 K = Chest⁻¹·Tool_R
    // of the animation (rotation + translation in the Chest frame), read from the animation files;
    // used instead of Epic Fight's plain-item carry, same tuning on top. See tryRender.
    private static final Map<String, float[]> ANIM_CARRY = Map.of(
            // scythe_idle_normal / scythe_sheathed (last frame): the same K at every frame
            "efn.crimson_moon", new float[]{
                    0.00843f, 0.92119f, -0.38904f, 0.14448f,
                    0.06831f, 0.38761f, 0.91929f, -0.09397f,
                    0.99763f, -0.03432f, -0.05965f, 0.38729f,
                    0f, 0f, 0f, 1f});

    private static OpenMatrix4f fromRowMajor(float[] k) {
        OpenMatrix4f r = new OpenMatrix4f().setIdentity();
        // OpenMatrix4f fields are named m<column><row> (translation = m30, m31, m32)
        r.m00 = k[0];  r.m10 = k[1];  r.m20 = k[2];  r.m30 = k[3];
        r.m01 = k[4];  r.m11 = k[5];  r.m21 = k[6];  r.m31 = k[7];
        r.m02 = k[8];  r.m12 = k[9];  r.m22 = k[10]; r.m32 = k[11];
        r.m03 = k[12]; r.m13 = k[13]; r.m23 = k[14]; r.m33 = k[15];
        return r;
    }

    // Weapons whose collision shape is two straight chains of boxes (a bent shape such as a scythe):
    // the second chain is the profile registered under this extra tag, same frame as the first.
    private static final Map<String, String> EXTRA_HITBOX = Map.of("efn.crimson_moon", "efn.crimson_moon_blade");
    // Weapons with no back variant at all (always waist, no config toggle).
    private static final Set<String> WAIST_ONLY = Set.of("efn.nf_shortsword", "efn.nf_shortsword_2",
            "efn.hf_murasama", "efn.hf_blade", "efn.yamato_dmc_in_sheath", "efn.yamato_dmc4_in_sheath",
            "efn.kusabimaru", "efn.nf_claw");
    // Fist weapons that are WORN: drawn on both hands all the time (while in an idle slot and not held), not carried
    // anywhere. Their mesh is skinned to Hand_R / Hand_L and finger-claw bones under them; every joint of a side is
    // moved with the live skinning matrix of that hand, the finger bones posed by the given idle animation relative
    // to the hand. Value = that animation's key (null-safe: rigid when it can't be read). Tuning keys: "<key>" =
    // right hand, "<key>_l" = left hand (translation / rotation in the hand's frame).
    private static final Map<String, String> WORN_ITEMS = Map.of("efn.nf_claw", "efn:biped/nf_claw/nf_claw_idle");
    // Items drawn with their own armature whose idle animation fixes where the sheathed sword hangs (the Kusabimaru's
    // Sheath1 bone, a child of the Torso): the whole item is carried as the animation does, relative to the Torso:
    // final = S_torso(live) * T(tune) * R(tune) * A_ref^-1 * A_j (the sampled clip). Value = the clip's key. Waist only.
    private static final Map<String, String> ARMATURE_CARRY =
            Map.of("efn.kusabimaru", "efn:biped/sekiro/kusabimaru/kusabimaru_idle");
    // Sheathed swords whose mesh is skinned to Tool_L (the left hand holds the sheath) and whose own idle animation
    // carries them at the left hip. Row-major K = Torso^-1 * Tool_L of that animation (read from the animation files:
    // hf_murasama_idle_sheath, dmcyamato_idle; the same at every frame). Placement: poses[Torso] * T(tune) * K * R(tune),
    // drawn by replacing poses[Tool_L]; waist only. The tuning key is the item's own prefix.
    private static final float[] K_MURASAMA = {
            0.95895f, 0.14941f, 0.24104f, -0.32322f,
            -0.12884f, 0.98670f, -0.09907f, -0.03517f,
            -0.25264f, 0.06395f, 0.96544f, -0.02198f,
            0f, 0f, 0f, 1f};
    private static final float[] K_YAMATO = {
            0.89805f, -0.03411f, -0.43856f, -0.42880f,
            -0.19900f, -0.92063f, -0.33591f, -0.01076f,
            -0.39230f, 0.38894f, -0.83356f, 0.12053f,
            0f, 0f, 0f, 1f};
    private static final Map<String, float[]> LEFT_SHEATHED = Map.of(
            "efn.hf_murasama", K_MURASAMA,
            "efn.hf_blade", K_MURASAMA,   // hf_blade_idle_sheath: the identical matrix
            "efn.yamato_dmc_in_sheath", K_YAMATO,
            "efn.yamato_dmc4_in_sheath", K_YAMATO);
    // One item = two bare blades, the left one skinned to Tool_L, each placed on its own. Waist (config):
    // the usual hip placement, right blade at the right hip, left blade at the left hip. Back: the two cross
    // on the back (rearCrossPlacement). Tuning keys: "<key>" / "<key>_back" = right blade, "<key>_l" /
    // "<key>_back_l" = left blade; hitbox tags "<key>" (right) and "<key>_l" (left).
    private static final Set<String> DUAL_BLADE = Set.of("efn.nf_dual_sword");
    // Items drawn with their own armature whose extra bones (the broadblade's cord, bones a..h) only move because
    // the player's held-weapon animations carry keyframes for them. Stowed, no such animation plays, so the cord
    // would hang rigid; here one of those animations (an idle) is sampled on a loop and only the motion of the
    // extra bones relative to Tool_R is applied. Value = the animation's key.
    private static final Set<String> SWAY_FAILED = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> SWAY_CLIP = Map.of("efn.broadblade", "efn:biped/broadblade/broadblade_idle");
    // Back cross: the grip of each blade at the upper back on one side (model space), the blade running
    // down and across to the other side. The "left" slot is the mirror image, 0.06 further back; the
    // right-hand blade takes the left slot and vice versa (cross-draw).
    private static final float REAR_X = 0.14f;
    private static final float REAR_Y = 1.5f;
    private static final float REAR_Z = 0.2f;
    private static final float REAR_TILT_DEG = 60.0f;   // blade angle below horizontal

    /**
     * Tapered sheath drawn around a bare blade: an OBJ model of a display-only item
     * (generated by tools/make_nf_sheath_obj.js, one per blade length). Everything is
     * in the weapon's own Tool_R joint space (the frame of the collision boxes): the
     * sheath centre and the tilt about X (the blade rises towards its tip). Measured
     * from the meshes.
     *
     * @param cx,cy,cz  centre of the sheath
     * @param tiltDeg   rotation about X, positive = the tip end rises
     * @param item      the sheath's display item
     */
    private record SheathFit(float cx, float cy, float cz, float tiltDeg,
                             java.util.function.Supplier<Item> item) {}

    private static final Map<String, SheathFit> SHEATH_FIT = Map.of(
            // mouth at the guard (z -0.34), 0.08 past the tip (z -1.36)
            "efn.nf_shortsword", new SheathFit(0.0065f, 0.164f, -0.85f, 10.8f,
                    Epic_fight_sheathing_master.NF_SHORTSWORD_SHEATH::get),
            // same mouth/tip span as above; the long collar covers the thick ricasso (z -0.34..-0.46)
            "efn.nf_shortsword_2", new SheathFit(0.0045f, 0.170f, -0.85f, 10.3f,
                    Epic_fight_sheathing_master.NF_SHORTSWORD_2_SHEATH::get)
    );

    // mesh2 / texture2 / glow2: an optional second form of the same item (the Kusabimaru's 不死斬り), else null.
    private record MeshData(AssetAccessor<?> mesh, ResourceLocation texture, ResourceLocation glow,
                            AssetAccessor<?> mesh2, ResourceLocation texture2, ResourceLocation glow2) {}

    private static final Object FAILED = new Object();

    // Per renderer instance (item_skin reloads create new renderers → weak keys).
    // Value: MeshData or FAILED.
    private static final Map<RenderItemBase, Object> DATA =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Item, String> ID_PREFIX = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

    private MeshWeaponIdleRenderer() {}

    /**
     * True when {@code renderer} draws the weapon as a real 3D mesh this class can reuse: Avalon's
     * {@code mesh_item} renderer or EFN's falchion renderer (matched by class name).
     */
    public static boolean isAvalonMeshRenderer(RenderItemBase renderer) {
        if (renderer == null) return false;
        String name = renderer.getClass().getName();
        return name.equals(AVALON_MESH_RENDERER) || name.equals(EFN_FALCHION_RENDERER)
                || name.equals(AVALON_ANIMATION_RENDERER)
                || name.equals(EFN_YAMATO_RENDERER) || name.equals(EFN_MURASAMA_RENDERER)
                || name.equals(EFN_MORTALBLADE_RENDERER);
    }

    /**
     * True while the MAIN-hand weapon of {@code entitypatch} is a supported mesh weapon that this class draws
     * stowed for a ladder climb (the same condition renderBypassed uses): Epic Fight has routed the tool bone
     * to the Chest and the item is allowlisted. Used to hide Avalon's own draw of a held animation_item weapon.
     */
    public static boolean drawsStowedForClimb(yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch<?> entitypatch) {
        try {
            net.minecraft.world.entity.LivingEntity living = entitypatch.getOriginal();
            if (living == null) return false;
            ItemStack stack = living.getMainHandItem();
            if (stack.isEmpty() || tuningPrefix(stack.getItem()) == null) return false;
            if (WORN_ITEMS.containsKey(tuningPrefix(stack.getItem()))) return false;
            if (!SheathAllowlist.isAllowed(stack)) return false;
            boolean toolsOnBack = entitypatch.getArmature() instanceof HumanoidArmature humanoid
                    && entitypatch.getParentJointOfHand(net.minecraft.world.InteractionHand.MAIN_HAND) == humanoid.chest;
            boolean result = climbingStowed(entitypatch, stack, toolsOnBack);
            logLadderState("avalon-draw", entitypatch, stack, result);
            return result;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether a held supported weapon must be shown stowed for a ladder climb: Epic Fight has attached the hands'
     * tool bones to the Chest (toolsOnBack), OR the weapon is one Avalon draws itself from the animation
     * (animation_item, e.g. the broadblade) and the player's current living motion is Epic Fight's CLIMB (the
     * ladder-climbing motion, not merely touching a ladder). For those Epic Fight's tool-bone switch does not
     * happen (measured in the log: the hands stay on Tool_R / Tool_L while climbing).
     */
    public static boolean climbingStowed(yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch<?> patch,
                                         ItemStack stack, boolean toolsOnBack) {
        String wp = tuningPrefix(stack.getItem());
        if (wp != null && WORN_ITEMS.containsKey(wp)) return false;   // worn items stay on the hands
        if (toolsOnBack) return true;
        try {
            if (patch.getCurrentLivingMotion() != yesman.epicfight.api.animation.LivingMotions.CLIMB) return false;
            RenderItemBase r = ClientEngine.getInstance().renderEngine.getItemRenderer(stack);
            return r != null && usesItemArmature(r);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final Map<String, String> LAST_LADDER_STATE = new ConcurrentHashMap<>();

    /**
     * Pose matrices for a WORN item (see WORN_ITEMS): for every joint of the item's armature, by NAME so that the
     * live armature (which Avalon swaps for another item's while that is held) never matters: a joint ending in _R
     * (or _R.nnn) follows the live Hand_R, one ending in _L the live Hand_L, with final = S_hand * T * R * rel_j,
     * S_hand = poses[hand] * toOrigin(hand) and rel_j the joint's motion in the sampled clip relative to the hand
     * (identity when there is no clip). Joints with no side get an identity skin. Null when the hands are missing.
     */
    private static OpenMatrix4f[] wornPoses(Armature item, Armature live, OpenMatrix4f[] poses, String prefix, String clipKey) {
        Joint handR = live.searchJointByName("Hand_R");
        Joint handL = live.searchJointByName("Hand_L");
        if (handR == null || handL == null || handR.getId() >= poses.length || handL.getId() >= poses.length) return null;
        OpenMatrix4f sR = handSkin(poses, handR, TuneableOffset.REGISTRY.get(prefix));
        OpenMatrix4f sL = handSkin(poses, handL, TuneableOffset.REGISTRY.get(prefix + "_l"));
        OpenMatrix4f[] relR = clipKey == null ? null : swayRelative(item, clipKey, "Hand_R");
        OpenMatrix4f[] relL = clipKey == null ? null : swayRelative(item, clipKey, "Hand_L");
        int n = item.getJointNumber();
        OpenMatrix4f[] out = new OpenMatrix4f[n];
        for (int j = 0; j < n; j++) {
            Joint jt = item.searchJointById(j);
            if (jt == null) {
                out[j] = new OpenMatrix4f().setIdentity();
                continue;
            }
            String name = jt.getName();
            OpenMatrix4f bw = new OpenMatrix4f(jt.getToOrigin());
            bw.invert();
            boolean right = name.matches(".*_R(\\..*)?");
            boolean left = name.matches(".*_L(\\..*)?");
            if (!right && !left) {
                out[j] = bw;   // identity skin
                continue;
            }
            OpenMatrix4f pj = new OpenMatrix4f(right ? sR : sL);
            OpenMatrix4f[] rel = right ? relR : relL;
            if (rel != null && j < rel.length && rel[j] != null) pj.mulBack(rel[j]);
            pj.mulBack(bw);
            out[j] = pj;
        }
        return out;
    }

    // S_hand = poses[hand] * toOrigin(hand), then the tuning (translation, rotation, scale) in the hand's frame.
    private static OpenMatrix4f handSkin(OpenMatrix4f[] poses, Joint hand, TuneableOffset off) {
        OpenMatrix4f s = new OpenMatrix4f(poses[hand.getId()]);
        s.mulBack(hand.getToOrigin());
        if (off != null) {
            s.translate(off.tx, off.ty, off.tz);
            s.rotateDeg(off.rotX, Vec3f.X_AXIS);
            s.rotateDeg(off.rotY, Vec3f.Y_AXIS);
            s.rotateDeg(off.rotZ, Vec3f.Z_AXIS);
            if (off.scale != 1.0f) s.scale(off.scale, off.scale, off.scale);
        }
        return s;
    }

    /** Renderers whose item draws with its own armature and is drawn by Avalon itself while held. */
    private static boolean usesItemArmature(RenderItemBase renderer) {
        String name = renderer.getClass().getName();
        return name.equals(AVALON_ANIMATION_RENDERER) || name.equals(EFN_MORTALBLADE_RENDERER);
    }

    /** The Kusabimaru's second form (Mortal Blade) when the player has that skill: EFN's own renderer decides. */
    private static boolean usesSecondForm(RenderItemBase renderer, Entity entity) {
        try {
            if (!renderer.getClass().getName().equals(EFN_MORTALBLADE_RENDERER)) return false;
            yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch<?> patch =
                    yesman.epicfight.world.capabilities.EpicFightCapabilities.getEntityPatch(entity,
                            yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch.class);
            if (patch == null) return false;
            Object r = renderer.getClass().getMethod("shouldUseMesh2",
                    yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch.class).invoke(renderer, patch);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Diagnostic for the ladder-climb handling: logs (INFO, only when the state changes) the joints Epic Fight
     * has the two hands attached to while a supported mesh weapon is in the main hand. Cheap; remove once the
     * ladder display of every weapon is confirmed.
     */
    public static void logLadderState(String where, yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch<?> patch,
                                      ItemStack stack, boolean result) {
        try {
            if (!(patch.getOriginal() instanceof net.minecraft.client.player.LocalPlayer)) return;
            Joint main = patch.getParentJointOfHand(net.minecraft.world.InteractionHand.MAIN_HAND);
            Joint off = patch.getParentJointOfHand(net.minecraft.world.InteractionHand.OFF_HAND);
            String state = where + " item=" + ForgeRegistries.ITEMS.getKey(stack.getItem())
                    + " mainJoint=" + (main == null ? "null" : main.getName())
                    + " offJoint=" + (off == null ? "null" : off.getName())
                    + " climbable=" + patch.getOriginal().onClimbable() + " motion=" + patch.getCurrentLivingMotion()
                    + " result=" + result;
            if (!state.equals(LAST_LADDER_STATE.put(where, state))) {
                LOGGER.debug("[efsm][ladder] {}", state);
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /** TuneableOffset key prefix for this item, or null when it isn't a supported mesh weapon. */
    private static String tuningPrefix(Item item) {
        String cached = ID_PREFIX.get(item);
        if (cached == null) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            String prefix = id == null ? null : TUNING_KEYS.get(id.toString());
            cached = prefix == null ? "" : prefix;
            ID_PREFIX.put(item, cached);
        }
        return cached.isEmpty() ? null : cached;
    }

    private static void logOnce(String key, String message, Throwable t) {
        if (LOGGED.add(key)) {
            if (t != null) {
                LOGGER.warn("[efsm] mesh weapon idle display: {} ({})", message, t.toString());
            } else {
                LOGGER.warn("[efsm] mesh weapon idle display: {}", message);
            }
        }
    }

    /**
     * Draws the idle body of {@code stack} if it is a supported Avalon mesh
     * weapon.
     *
     * @return true when the item is handled by this class (whether or not
     *         anything could actually be drawn — on failure nothing is drawn
     *         and the caller must NOT fall back to the flat item icon); false
     *         when the item is not ours and the caller should proceed normally.
     */
    public static boolean tryRender(
            ItemStack stack, Entity entity, PoseStack postStack, MultiBufferSource buffer,
            int packedLight, OpenMatrix4f[] poses, HumanoidArmature armature,
            OpenMatrix4f chestPose, java.util.function.Supplier<OpenMatrix4f> efCarry, boolean leftSide,
            boolean climbing) {

        String prefix = tuningPrefix(stack.getItem());
        if (prefix == null) return false;
        // Role-based side (see SIDE_BY_ROLE): left = off-hand / second idle weapon.
        boolean byRole = SIDE_BY_ROLE.contains(prefix);
        boolean left = byRole && leftSide;
        String role = left ? "_off" : "";
        // Waist (Torso) or back (Chest): per-weapon setting, see Config.isMeshWeaponAtWaist.
        ScabbardFit scabbard = SCABBARD_FIT.get(prefix);
        boolean waist = !BACK_ONLY.contains(prefix) && (WAIST_ONLY.contains(prefix) || Config.isMeshWeaponAtWaist(prefix));

        // Cheap, exception-free checks first.
        RenderItemBase renderer;
        try {
            renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(stack);
        } catch (Throwable t) {
            logOnce("renderer-lookup", "renderer lookup failed; nothing drawn", t);
            return true;
        }
        if (!isAvalonMeshRenderer(renderer)) {
            logOnce(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())),
                    "item is not rendered by Avalon's mesh_item (renderer="
                            + (renderer == null ? "null" : renderer.getClass().getName()) + "); nothing drawn", null);
            return true;
        }

        // A failure is remembered per renderer instance (FAILED): from then on
        // this returns immediately — no reflection, no mesh access, no exception —
        // until a resource reload creates a fresh renderer and thereby a retry.
        Object data = DATA.get(renderer);
        if (data == FAILED) return true;

        String itemKey = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        try {
            if (data == null) {
                data = readRenderer(renderer, itemKey);
                DATA.put(renderer, data);
                if (data == FAILED) return true;
            }
            MeshData md = (MeshData) data;

            boolean altForm = md.mesh2() != null && md.texture2() != null && usesSecondForm(renderer, entity);
            Object meshObj = (altForm ? md.mesh2() : md.mesh()).get();
            ResourceLocation drawTexture = altForm ? md.texture2() : md.texture();
            ResourceLocation drawGlow = altForm ? md.glow2() : md.glow();
            if (!(meshObj instanceof SkinnedMesh mesh)) {
                logOnce(itemKey, itemKey + ": mesh_main did not resolve to a SkinnedMesh; nothing drawn", null);
                DATA.put(renderer, FAILED);
                return true;
            }

            // Waist mode while the weapon is stowed for a ladder climb: a weapon may have its own "<key>_ladder"
            // position for that (like Epic Fight's tachi, see the "tachi_ladder" key); else the usual waist one.
            String offKey = waist ? prefix + role : prefix + "_back" + role;
            if (waist && climbing && TuneableOffset.REGISTRY.containsKey(prefix + "_ladder")) offKey = prefix + "_ladder";
            TuneableOffset off = TuneableOffset.REGISTRY.get(offKey);
            Joint base = waist ? armature.torso : armature.chest;
            OpenMatrix4f basePose = waist ? poses[base.getId()] : chestPose;

            // bindWorld(Tool_R) = inverse(toOrigin(Tool_R))
            OpenMatrix4f bind = new OpenMatrix4f(armature.toolR.getToOrigin());
            bind.invert();
            Vec3f grip = bind.toTranslationVector();

            // m = S_base · P, S_base = poses[base] · toOrigin(base)
            OpenMatrix4f m = new OpenMatrix4f(basePose);
            m.mulBack(base.getToOrigin());
            // Waist + own scabbard: an extra offset "<prefix>_all" moves blade AND
            // scabbard together (Torso frame), applied before the blade placement.
            OpenMatrix4f all = null;
            if (scabbard != null && waist) {
                TuneableOffset allOff = TuneableOffset.REGISTRY.get(prefix + "_all");
                if (allOff != null) {
                    all = new OpenMatrix4f().setIdentity();
                    all.translate(allOff.tx, allOff.ty, allOff.tz);
                    all.rotateDeg(allOff.rotX, Vec3f.X_AXIS);
                    all.rotateDeg(allOff.rotY, Vec3f.Y_AXIS);
                    all.rotateDeg(allOff.rotZ, Vec3f.Z_AXIS);
                    m.mulBack(all);
                }
            }
            OpenMatrix4f mLeft = null;   // dual blades: pose of the second (Tool_L) blade
            float[] kLeft = LEFT_SHEATHED.get(prefix);   // sheathed sword on Tool_L (Yamato, Murasama)
            String wornClip = WORN_ITEMS.get(prefix);
            boolean worn = wornClip != null;
            if (worn && !Config.isMeshWeaponAtWaist(prefix)) return false;   // worn display switched off in the config
            String carryClip = ARMATURE_CARRY.get(prefix);
            boolean carryDirect = carryClip != null;   // F = m itself (no Tool_R substitution)
            OpenMatrix4f efStyle = null;
            if (EF_STYLE.contains(prefix)) {
                float[] animCarry = ANIM_CARRY.get(prefix);
                efStyle = animCarry != null ? fromRowMajor(animCarry) : efCarry != null ? efCarry.get() : null;
            }
            if (carryDirect) {
                // Whole item carried relative to the Torso (see ARMATURE_CARRY): m = S_torso * T(tune) * R(tune), the
                // tuning key is the item's own prefix (tx/ty/tz in the Torso frame, rotations around the Torso origin).
                m = new OpenMatrix4f(poses[armature.torso.getId()]);
                m.mulBack(armature.torso.getToOrigin());
                if (off != null) {
                    m.translate(off.tx, off.ty, off.tz);
                    m.rotateDeg(off.rotX, Vec3f.X_AXIS);
                    m.rotateDeg(off.rotY, Vec3f.Y_AXIS);
                    m.rotateDeg(off.rotZ, Vec3f.Z_AXIS);
                    if (off.scale != 1.0f) m.scale(off.scale, off.scale, off.scale);
                }
            } else if (efStyle != null) {
                // Carried the way Epic Fight carries a plain item on the Chest (its spear):
                // J = poses[Chest] · C_chest · C_tool⁻¹. Tuning: tx/ty/tz in the Chest
                // frame, rotX/Y/Z and scale around the grip.
                m = new OpenMatrix4f(chestPose);
                if (off != null) m.translate(off.tx, off.ty, off.tz);
                m.mulBack(efStyle);
                if (off != null) {
                    m.rotateDeg(off.rotX, Vec3f.X_AXIS);
                    m.rotateDeg(off.rotY, Vec3f.Y_AXIS);
                    m.rotateDeg(off.rotZ, Vec3f.Z_AXIS);
                    if (off.scale != 1.0f) m.scale(off.scale, off.scale, off.scale);
                }
            } else if (kLeft != null) {
                // The pose of the idle animation: the sheathed sword where the left hand holds it, relative to
                // the Torso. m replaces poses[Tool_L] (not Tool_R). Tuning: tx/ty/tz in the Torso frame,
                // rotX/Y/Z and scale around the grip.
                m = new OpenMatrix4f(poses[armature.torso.getId()]);
                if (off != null) m.translate(off.tx, off.ty, off.tz);
                m.mulBack(fromRowMajor(kLeft));
                if (off != null) {
                    m.rotateDeg(off.rotX, Vec3f.X_AXIS);
                    m.rotateDeg(off.rotY, Vec3f.Y_AXIS);
                    m.rotateDeg(off.rotZ, Vec3f.Z_AXIS);
                    if (off.scale != 1.0f) m.scale(off.scale, off.scale, off.scale);
                }
            } else if (DUAL_BLADE.contains(prefix)) {
                mLeft = new OpenMatrix4f(m);
                OpenMatrix4f bindL = new OpenMatrix4f(armature.toolL.getToOrigin());
                bindL.invert();
                Vec3f gripL = bindL.toTranslationVector();
                if (waist) {
                    // usual hip placement, one blade per hip (off = "<key>", the left one "<key>_l")
                    m.mulBack(placement(null, true, true, false, off, bind, grip));
                    mLeft.mulBack(placement(null, true, false, false, TuneableOffset.REGISTRY.get(prefix + "_l"), bindL, gripL));
                } else {
                    // crossed on the back, cross-draw style: each blade sits on the OPPOSITE side from its own
                    // hand, i.e. the right-hand blade (Tool_R, off = "<key>_back") takes the left slot and the
                    // left-hand blade (Tool_L, "<key>_back_l") the right slot.
                    m.mulBack(rearCrossPlacement(true, off, bind, grip));
                    mLeft.mulBack(rearCrossPlacement(false, TuneableOffset.REGISTRY.get(prefix + "_back_l"), bindL, gripL));
                }
            } else {
                m.mulBack(placement(waist ? scabbard : null, waist, byRole ? !left : RIGHT_HIP.contains(prefix), left, off, bind, grip));
            }

            // Items bound to their own armature (thornwheel): draw with that armature, every joint
            // moved by the same rigid transform F = m · bindWorld(Tool_R)⁻¹ (final = F for all joints).
            Armature drawArmature = armature;
            if (usesItemArmature(renderer)) {
                drawArmature = itemArmature(stack.getItem());
                if (drawArmature == null) {
                    logOnce(itemKey, itemKey + ": the item's own armature could not be read; nothing drawn", null);
                    DATA.put(renderer, FAILED);
                    return true;
                }
            }

            // Never write into the shared array — the layer's `poses` IS the
            // armature's own pose array, also used by the body draw.
            OpenMatrix4f[] drawPoses = poses.clone();
            drawPoses[(kLeft != null ? armature.toolL : armature.toolR).getId()] = m;
            if (mLeft != null) drawPoses[armature.toolL.getId()] = mLeft;
            OpenMatrix4f[] meshPoses = drawPoses;
            if (drawArmature != armature && !worn) {
                OpenMatrix4f f = new OpenMatrix4f(m);
                OpenMatrix4f bindInv = new OpenMatrix4f(bind);
                bindInv.invert();
                if (!carryDirect) f.mulBack(bindInv);
                int n = drawArmature.getJointNumber();
                meshPoses = new OpenMatrix4f[n];
                String swayKey = carryDirect ? carryClip : SWAY_CLIP.get(prefix);
                OpenMatrix4f[] sway = swayKey == null ? null
                        : swayRelative(drawArmature, swayKey, carryDirect ? "Torso" : "Tool_R");
                for (int j = 0; j < n; j++) {
                    Joint jt = drawArmature.searchJointById(j);
                    if (jt == null) {
                        meshPoses[j] = new OpenMatrix4f(f);
                        continue;
                    }
                    OpenMatrix4f bw = new OpenMatrix4f(jt.getToOrigin());
                    bw.invert();
                    OpenMatrix4f pj = new OpenMatrix4f(f);
                    // final = F · S_j · v (S_j = the joint's animated motion relative to Tool_R; identity when rigid)
                    if (sway != null && sway[j] != null) pj.mulBack(sway[j]);
                    pj.mulBack(bw);
                    meshPoses[j] = pj;
                }
            }
            if (worn && drawArmature != armature) {
                OpenMatrix4f[] wp = wornPoses(drawArmature, armature, poses, prefix, wornClip);
                if (wp == null) return true;   // nothing sensible to draw this frame
                meshPoses = wp;
            }

            if (all != null) {
                // Scabbard (Torso-bound) follows: S_t·All·bindWorld(Torso).
                OpenMatrix4f torsoBind = new OpenMatrix4f(armature.torso.getToOrigin());
                torsoBind.invert();
                OpenMatrix4f scabbardPose = new OpenMatrix4f(basePose);
                scabbardPose.mulBack(base.getToOrigin());
                scabbardPose.mulBack(all);
                scabbardPose.mulBack(torsoBind);
                drawPoses[armature.torso.getId()] = scabbardPose;
            }

            if (scabbard != null && !waist) {
                // Back carry of a blade + own scabbard: the scabbard (Torso-bound)
                // must travel with the blade. Waist state: blade = S_t·Pw·bind⁻¹,
                // scabbard = S_t. Back state: blade = S_c·Pb·bind⁻¹ (= m·bind⁻¹), so
                // the scabbard that keeps the tuned waist blade-in-scabbard relation
                // is S_c·Pb·Pw⁻¹ = m·Pw⁻¹ (model space); as a Torso pose matrix that
                // is m·Pw⁻¹·bindWorld(Torso).
                OpenMatrix4f pwInv = placement(scabbard, true, false, false, TuneableOffset.REGISTRY.get(prefix), bind, grip);
                pwInv.invert();
                OpenMatrix4f torsoBind = new OpenMatrix4f(armature.torso.getToOrigin());
                torsoBind.invert();
                OpenMatrix4f scabbardPose = new OpenMatrix4f(m);
                scabbardPose.mulBack(pwInv);
                scabbardPose.mulBack(torsoBind);
                drawPoses[armature.torso.getId()] = scabbardPose;
            }

            postStack.pushPose();
            try {
                // NoCull: thin one-sided parts (the broadblade's cord) must show from both sides, like in the
                // held state (Avalon draws with entityTranslucent, which does not cull either).
                mesh.draw(postStack, buffer, RenderType.entityCutoutNoCull(drawTexture), packedLight,
                        1.0f, 1.0f, 1.0f, 1.0f, OverlayTexture.NO_OVERLAY, drawArmature, meshPoses);
                if (drawGlow != null) {
                    mesh.draw(postStack, buffer, RenderType.entityTranslucentEmissive(drawGlow), packedLight,
                            1.0f, 1.0f, 1.0f, 0.9f, OverlayTexture.NO_OVERLAY, drawArmature, meshPoses);
                }
            } finally {
                postStack.popPose();
            }

            // Collision clank: boxes chained along the blade in the weapon's own
            // frame (Tool_R joint space after the substitution above: the blade
            // runs along -z from the grip). Tune with /efsm hitbox <prefix> ...
            // Never allowed to mark the mesh as failed.
            if (entity != null && !worn) {
                try {
                    postStack.pushPose();
                    try {
                        // Blade weapons: Tool_R frame. Weapons with their own scabbard:
                        // the scabbard's (Torso) frame, so the boxes sit on the scabbard.
                        // Blades with a separate box sheath: the sheath's own frame.
                        SheathFit boxSheath = SHEATH_FIT.get(prefix);
                        if (boxSheath != null) applySheathFrame(postStack, boxSheath, prefix, m);
                        else if (carryDirect) {
                            // the hitbox frame of the sheathed sword = its Sheath1 bone
                            Joint sheathJoint = drawArmature.searchJointByName("Sheath1");
                            MathUtils.mulStack(postStack, sheathJoint != null && meshPoses[sheathJoint.getId()] != null
                                    ? meshPoses[sheathJoint.getId()] : m);
                        } else MathUtils.mulStack(postStack, scabbard != null ? drawPoses[armature.torso.getId()] : m);
                        SheathCollisionSystem.checkAndPlaySound(entity, prefix + role, postStack);
                        String extra = EXTRA_HITBOX.get(prefix);
                        if (extra != null) SheathCollisionSystem.checkAndPlaySound(entity, extra, postStack);
                        if (mLeft != null) {
                            // second blade: its own frame and its own collision tag
                            postStack.popPose();
                            postStack.pushPose();
                            MathUtils.mulStack(postStack, mLeft);
                            SheathCollisionSystem.checkAndPlaySound(entity, prefix + "_l", postStack);
                        }
                    } finally {
                        postStack.popPose();
                    }
                } catch (Throwable t) {
                    logOnce(itemKey + "#collision", itemKey + ": collision check failed", t);
                }
            }

            // Optional box sheath around the blade. Never allowed to mark the mesh as failed.
            SheathFit sheathFit = SHEATH_FIT.get(prefix);
            if (sheathFit != null && SheathAllowlist.isOwnSheathAllowed(stack)) {
                try {
                    drawBoxSheath(sheathFit, prefix, m, postStack, buffer, packedLight);
                } catch (Throwable t) {
                    logOnce(itemKey + "#sheath", itemKey + ": drawing the sheath failed", t);
                }
            }
        } catch (Throwable t) {
            // Remember the failure so the next frames skip this item entirely.
            DATA.put(renderer, FAILED);
            logOnce(itemKey, itemKey + ": drawing failed; nothing drawn from now on", t);
        }
        return true;
    }

    /**
     * Moves {@code postStack} into the sheath's own frame (centre of the sheath, z along
     * its axis, +z = mouth): the weapon's joint space {@code m}, then the measured fit
     * plus the {@code <prefix>_sheath} tuning. Also the frame of the collision boxes.
     */
    private static void applySheathFrame(PoseStack postStack, SheathFit fit, String prefix, OpenMatrix4f m) {
        TuneableOffset t = TuneableOffset.REGISTRY.get(prefix + "_sheath");
        MathUtils.mulStack(postStack, m); // Tool_R joint space
        postStack.translate(fit.cx() + (t != null ? t.tx : 0f), fit.cy() + (t != null ? t.ty : 0f),
                fit.cz() + (t != null ? t.tz : 0f));
        postStack.mulPose(Axis.XP.rotationDegrees(fit.tiltDeg() + (t != null ? t.rotX : 0f)));
        if (t != null) {
            postStack.mulPose(Axis.YP.rotationDegrees(t.rotY));
            postStack.mulPose(Axis.ZP.rotationDegrees(t.rotZ));
            if (t.scale != 1.0f) postStack.scale(1.0f, 1.0f, t.scale);
        }
    }

    /**
     * The weapon is HELD (Avalon draws the blade in the hand): keep the EMPTY sheath at the
     * place where the weapon would be carried (waist/back as configured) and let it clank
     * against blocks. For weapons with a box sheath ({@link #SHEATH_FIT}) only.
     */
    public static void drawHeldSheath(ItemStack stack, Entity entity, PoseStack postStack,
                                      MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                                      HumanoidArmature armature) {
        String prefix = tuningPrefix(stack.getItem());
        SheathFit fit = prefix == null ? null : SHEATH_FIT.get(prefix);
        if (fit == null || !SheathAllowlist.isOwnSheathAllowed(stack)) return;
        try {
            boolean waist = WAIST_ONLY.contains(prefix) || Config.isMeshWeaponAtWaist(prefix);
            TuneableOffset off = TuneableOffset.REGISTRY.get(waist ? prefix : prefix + "_back");
            Joint base = waist ? armature.torso : armature.chest;
            OpenMatrix4f bind = new OpenMatrix4f(armature.toolR.getToOrigin());
            bind.invert();
            Vec3f grip = bind.toTranslationVector();
            OpenMatrix4f m = new OpenMatrix4f(poses[base.getId()]);
            m.mulBack(base.getToOrigin());
            m.mulBack(placement(null, waist, RIGHT_HIP.contains(prefix), false, off, bind, grip));
            drawBoxSheath(fit, prefix, m, postStack, buffer, packedLight);
            if (entity != null) {
                postStack.pushPose();
                try {
                    applySheathFrame(postStack, fit, prefix, m);
                    SheathCollisionSystem.checkAndPlaySound(entity, prefix, postStack);
                } finally {
                    postStack.popPose();
                }
            }
        } catch (Throwable t) {
            logOnce(prefix + "#held-sheath", prefix + ": drawing the held-weapon sheath failed", t);
        }
    }

    /**
     * Draws the sheath in the weapon's joint space. {@code <prefix>_sheath} in
     * {@link TuneableOffset} adjusts it on top of the measured fit: tx/ty/tz move it
     * (joint space), rotX/Y/Z turn it around its centre (rotX = tilt change), scale
     * stretches it along the blade (1 = as modelled).
     */
    private static void drawBoxSheath(SheathFit fit, String prefix, OpenMatrix4f m, PoseStack postStack,
                                     MultiBufferSource buffer, int packedLight) {
        ItemStack sheath = new ItemStack(fit.item().get());
        postStack.pushPose();
        try {
            applySheathFrame(postStack, fit, prefix, m);
            Minecraft.getInstance().getItemRenderer().renderStatic(sheath, ItemDisplayContext.NONE,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        } finally {
            postStack.popPose();
        }
    }

    /**
     * Collision check for the scabbard of a weapon with its own scabbard
     * ({@link #SCABBARD_FIT}) while the weapon is HELD: Avalon's renderer draws
     * the whole mesh itself and its Torso-bound scabbard sits at {@code poses[Torso]},
     * the same frame the idle display's boxes are defined in (the scabbard's own
     * pose), so the same profile applies. Nothing is drawn here.
     */
    public static void checkHeldScabbardCollision(
            ItemStack stack, Entity entity, PoseStack postStack,
            OpenMatrix4f[] poses, HumanoidArmature armature) {
        if (entity == null) return;
        String prefix = tuningPrefix(stack.getItem());
        if (prefix == null || !SCABBARD_FIT.containsKey(prefix)) return;
        try {
            postStack.pushPose();
            try {
                MathUtils.mulStack(postStack, poses[armature.torso.getId()]);
                SheathCollisionSystem.checkAndPlaySound(entity, prefix, postStack);
            } finally {
                postStack.popPose();
            }
        } catch (Throwable t) {
            logOnce(prefix + "#held-collision", prefix + ": held scabbard collision check failed", t);
        }
    }

    /**
     * P = T(tune) · D · bindWorld(Tool_R) · R(tune) · scale — everything between
     * the base joint's skinning matrix and the weapon. D = T(target) · Rot ·
     * T(-grip) in bind-pose model space (x: +right, y: up, -z: forward);
     * without it the mesh would sit exactly where the right hand is in the bind
     * pose (side of the body, blade pointing forward), i.e. "in the right hand".
     *
     * @param scabbard non-null → the waist fit of a blade inserted into the
     *                 mesh's own scabbard (waist only, {@code waist} must be true)
     */
    private static OpenMatrix4f placement(ScabbardFit scabbard, boolean waist, boolean rightHip, boolean leftBack,
                                          TuneableOffset off, OpenMatrix4f bind, Vec3f grip) {
        OpenMatrix4f p = new OpenMatrix4f().setIdentity();
        if (off != null) p.translate(off.tx, off.ty, off.tz);
        if (scabbard != null) {
            // Blade turned around and inserted into the mesh's own Torso scabbard.
            p.translate(scabbard.tx(), scabbard.ty(), scabbard.tz());
            p.rotateDeg(scabbard.pitchDeg(), Vec3f.X_AXIS);
            p.rotateDeg(180.0f, Vec3f.Y_AXIS);
            p.translate(-scabbard.jx(), -scabbard.jy(), -scabbard.jz());
        } else {
            if (waist) {
                // Left hip (right hip for RIGHT_HIP weapons), grip forward, blade pointing back and slightly down.
                p.translate(rightHip ? -WAIST_TARGET_X : WAIST_TARGET_X, WAIST_TARGET_Y, WAIST_TARGET_Z);
                p.rotateDeg(WAIST_PITCH_DEG, Vec3f.X_AXIS);
                p.rotateDeg(180.0f, Vec3f.Y_AXIS);
            } else {
                // Behind the back, grip at the upper right, blade running down to the left.
                // leftBack = the mirror image (grip at the upper left, blade down to the right):
                // x negated, rotations about Y and Z negated.
                p.translate(leftBack ? -BACK_TARGET_X : BACK_TARGET_X, BACK_TARGET_Y, BACK_TARGET_Z);
                p.rotateDeg(leftBack ? -BACK_TILT_DEG : BACK_TILT_DEG, Vec3f.Z_AXIS);
                p.rotateDeg(leftBack ? -90.0f : 90.0f, Vec3f.Y_AXIS);
            }
            p.translate(-grip.x, -grip.y, -grip.z);
        }
        p.mulBack(bind);
        if (off != null) {
            p.rotateDeg(off.rotX, Vec3f.X_AXIS);
            p.rotateDeg(off.rotY, Vec3f.Y_AXIS);
            p.rotateDeg(off.rotZ, Vec3f.Z_AXIS);
            if (off.scale != 1.0f) p.scale(off.scale, off.scale, off.scale);
        }
        return p;
    }

    /**
     * BACK placement of one blade of a dual-blade item (see DUAL_BLADE): grip at the upper back on its own
     * side, the blade running down and across to the other side (the left blade is the mirror image: x, and
     * the rotations about Y and Z, negated). Same structure as {@link #placement}: tune translation in the
     * Chest frame first, rotations around the grip last.
     *
     * @param bind  bindWorld of the blade's own tool joint (Tool_R for the right, Tool_L for the left)
     * @param grip  that joint's bind-pose position
     */
    private static OpenMatrix4f rearCrossPlacement(boolean leftBlade, TuneableOffset off,
                                                   OpenMatrix4f bind, Vec3f grip) {
        float s = leftBlade ? -1.0f : 1.0f;
        OpenMatrix4f p = new OpenMatrix4f().setIdentity();
        if (off != null) p.translate(off.tx, off.ty, off.tz);
        p.translate(s * REAR_X, REAR_Y, REAR_Z + (leftBlade ? 0.06f : 0.0f));
        p.rotateDeg(s * REAR_TILT_DEG, Vec3f.Z_AXIS);
        p.rotateDeg(s * 90.0f, Vec3f.Y_AXIS);
        p.translate(-grip.x, -grip.y, -grip.z);
        p.mulBack(bind);
        if (off != null) {
            p.rotateDeg(off.rotX, Vec3f.X_AXIS);
            p.rotateDeg(off.rotY, Vec3f.Y_AXIS);
            p.rotateDeg(off.rotZ, Vec3f.Z_AXIS);
            if (off.scale != 1.0f) p.scale(off.scale, off.scale, off.scale);
        }
        return p;
    }

    // Reads mesh_main / texture / texture_l from Avalon's RenderMeshItem by reflection.
    private static Object readRenderer(RenderItemBase renderer, String itemKey) {
        try {
            String cn = renderer.getClass().getName();
            if (cn.equals(EFN_MORTALBLADE_RENDERER)) {
                // the sword in its sheath, both forms (the sheath textures fall back to the main ones in EFN's constructor)
                Object m1 = readField(renderer, "mesh1_sheath");
                Object t1 = readField(renderer, "mesh1SheathTexture");
                Object g1 = readField(renderer, "mesh1SheathTextureL");
                Object m2 = readField(renderer, "mesh2_sheath");
                Object t2 = readField(renderer, "mesh2SheathTexture");
                Object g2 = readField(renderer, "mesh2SheathTextureL");
                if (!(m1 instanceof AssetAccessor<?> acc1) || !(t1 instanceof ResourceLocation tex1)) {
                    logOnce(itemKey, itemKey + ": the sheathed mesh or its texture is missing; nothing drawn", null);
                    return FAILED;
                }
                return new MeshData(acc1, tex1, g1 instanceof ResourceLocation r1 ? r1 : null,
                        m2 instanceof AssetAccessor<?> acc2 ? acc2 : null,
                        t2 instanceof ResourceLocation r2 ? r2 : null,
                        g2 instanceof ResourceLocation r3 ? r3 : null);
            }
            // falchion / Yamato: mesh1_*; Murasama: mesh2_* (its sheathed mesh); Avalon: mesh_main / mesh
            boolean numbered = cn.equals(EFN_FALCHION_RENDERER) || cn.equals(EFN_YAMATO_RENDERER)
                    || cn.equals(EFN_MURASAMA_RENDERER);
            String n = cn.equals(EFN_MURASAMA_RENDERER) ? "2" : "1";
            boolean anim = cn.equals(AVALON_ANIMATION_RENDERER);
            Object mesh = readField(renderer, numbered ? "mesh" + n + "_main" : anim ? "mesh" : "mesh_main");
            Object texture = readField(renderer, numbered ? "mesh" + n + "Texture" : "texture");
            Object glow = readField(renderer, numbered ? "mesh" + n + "TextureL" : "texture_l");
            if (!(mesh instanceof AssetAccessor<?> accessor)) {
                logOnce(itemKey, itemKey + ": Avalon's mesh_main is missing or has an unexpected type; nothing drawn", null);
                return FAILED;
            }
            if (!(texture instanceof ResourceLocation tex)) {
                logOnce(itemKey, itemKey + ": Avalon's texture is missing; nothing drawn", null);
                return FAILED;
            }
            return new MeshData(accessor, tex, glow instanceof ResourceLocation g ? g : null, null, null, null);
        } catch (Throwable t) {
            logOnce(itemKey, itemKey + ": could not read Avalon's renderer fields by reflection; nothing drawn", t);
            return FAILED;
        }
    }

    /**
     * Per joint of the item's own armature: S_j = A_R⁻¹ · A_j, where A_j = pose_j · toOrigin_j is the skinning
     * matrix of an animation sampled on a loop (SWAY_CLIP) and R = Tool_R. It is the motion of a joint (the cord)
     * seen from the tool, independent of where the player's body is in the clip. Null when the clip is not
     * available (the caller then draws rigid).
     */
    private static OpenMatrix4f[] swayRelative(Armature arm, String clipKey, String refJoint) {
        if (SWAY_FAILED.contains(clipKey)) return null;
        try {
            AssetAccessor<? extends StaticAnimation> accessor = AnimationManager.byKey(new ResourceLocation(clipKey));
            StaticAnimation anim = accessor == null ? null : accessor.get();
            if (anim == null) return null;
            AnimationClip clip = anim.getAnimationClip();
            if (clip == null || clip.getClipTime() <= 0.0f) return null;
            float t = (float) ((System.nanoTime() / 1.0e9) % clip.getClipTime());
            Pose pose = clip.getPoseInTime(t);
            arm.setPose(pose);
            OpenMatrix4f[] pm = arm.getPoseMatrices();
            Joint tool = arm.searchJointByName(refJoint);
            if (pm == null || tool == null) return null;
            OpenMatrix4f aRInv = new OpenMatrix4f(pm[tool.getId()]);
            aRInv.mulBack(tool.getToOrigin());
            aRInv.invert();
            int n = arm.getJointNumber();
            OpenMatrix4f[] out = new OpenMatrix4f[n];
            for (int j = 0; j < n; j++) {
                Joint jt = arm.searchJointById(j);
                if (jt == null || j >= pm.length || pm[j] == null) continue;
                OpenMatrix4f aj = new OpenMatrix4f(pm[j]);
                aj.mulBack(jt.getToOrigin());
                OpenMatrix4f s = new OpenMatrix4f(aRInv);
                s.mulBack(aj);
                out[j] = s;
            }
            return out;
        } catch (Throwable t) {
            SWAY_FAILED.add(clipKey);   // never retried: no exception per frame
            logOnce("sway-" + clipKey, "sampling the sway animation " + clipKey + " failed; drawn rigid", t);
            return null;
        }
    }

    // The armature an item draws its mesh with (IAvalonAnimationItem#getArmature, by reflection), or null.
    private static Armature itemArmature(Item item) {
        try {
            Object accessor = item.getClass().getMethod("getArmature").invoke(item);
            if (accessor instanceof AssetAccessor<?> a && a.get() instanceof Armature arm) return arm;
        } catch (Throwable t) {
            logOnce("item-armature", "reading the item's own armature failed", t);
        }
        return null;
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
