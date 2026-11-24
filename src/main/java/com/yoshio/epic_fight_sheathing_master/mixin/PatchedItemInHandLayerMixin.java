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
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.yoshio.epic_fight_sheathing_master.Config;
import com.yoshio.epic_fight_sheathing_master.Epic_fight_sheathing_master;
import com.yoshio.epic_fight_sheathing_master.MeshWeaponIdleRenderer;
import com.yoshio.epic_fight_sheathing_master.SheathAllowlist;
import com.yoshio.epic_fight_sheathing_master.SheathCollisionSystem;
import com.yoshio.epic_fight_sheathing_master.SheathHitboxProfile;
import com.yoshio.epic_fight_sheathing_master.TuneableOffset;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.utils.math.MathUtils;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.model.armature.HumanoidArmature;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.WeaponCategory;
import yesman.epicfight.world.item.EpicFightItems;
import yesman.epicfight.world.item.UchigatanaItem;

@Mixin(PatchedItemInHandLayer.class)
public abstract class PatchedItemInHandLayerMixin {

    // ── Category filter ────────────────────────────────────────────────────────

    // Categories excluded from back-sheathing. CapabilityItem.EMPTY defaults to
    // FIST (see Builder.<init>), so unregistered items are naturally excluded.
    private static final Set<CapabilityItem.WeaponCategories> SKIP_CATEGORIES = Set.of(
            CapabilityItem.WeaponCategories.NOT_WEAPON,
            CapabilityItem.WeaponCategories.FIST,
            CapabilityItem.WeaponCategories.SHIELD,
            CapabilityItem.WeaponCategories.BOW,
            CapabilityItem.WeaponCategories.CROSSBOW
    );

    // Categories that force back-sheathing even when the renderer has alwaysInHand=true
    // (i.e. the item has no dedicated item_skin JSON and falls back to the air renderer).
    // Extend this set to add more categories in the future.
    private static final Set<CapabilityItem.WeaponCategories> FORCE_SHEATH_CATEGORIES = Set.of(
            CapabilityItem.WeaponCategories.PICKAXE,
            CapabilityItem.WeaponCategories.HOE,
            CapabilityItem.WeaponCategories.SHOVEL
    );

    // Sheathed-weapon categories whose sheath is tied to Epic Fight's own
    // native held-in-hand system (i.e. the sheath is only shown while the
    // blade itself is not physically held — same as the pre-existing katana
    // behavior). Currently only Uchigatana.
    //
    // Any other sheathed category (currently just Tachi, handled by
    // renderDetachedSheath) is treated as "detached": its sheath is
    // always shown regardless of whether the blade is held. Add future
    // native-sheath weapons here manually — this is not auto-detected.
    private static final Set<CapabilityItem.WeaponCategories> NATIVE_SHEATH_CATEGORIES = Set.of(
            CapabilityItem.WeaponCategories.UCHIGATANA
    );

    // ── Detached sheath (per-category) ──────────────────────────────────────────

    // Per-category configuration for the "detached sheath" system: the sheath
    // for these categories is rendered independently of whether the blade is
    // held (see renderDetachedSheath), and its position is fully parameterized
    // by this spec rather than hardcoded. To add a new weapon to this system
    // (e.g. a dagger), register a new entry here plus its sheath item/model —
    // no other code changes should be needed.
    private record DetachedSheathSpec(
            String waistOffsetKey,       // TuneableOffset key for the blade's WAIST-mode position
            String sheathOffsetKey,      // TuneableOffset key for the sheath's BACK-mode tuning
            String sheathWaistOffsetKey, // TuneableOffset key for the sheath's WAIST-mode tuning
            RegistryObject<Item> backSheathItem,
            RegistryObject<Item> waistSheathItem
    ) {}

    private static final Map<CapabilityItem.WeaponCategories, DetachedSheathSpec> DETACHED_SHEATH_SPECS = Map.of(
            CapabilityItem.WeaponCategories.TACHI, new DetachedSheathSpec(
                    "tachi", "tachi_sheath", "tachi_sheath_waist",
                    Epic_fight_sheathing_master.TACHI_SHEATH,
                    Epic_fight_sheathing_master.TACHI_SHEATH_WITH_STRING
            )
    );

    // Lazily-computed relative sheath offset per detached-sheath category, used
    // in WAIST mode. See computeSheathRelativeOffset.
    private static final Map<CapabilityItem.WeaponCategories, OpenMatrix4f> detachedSheathRelativeOffsets =
            new HashMap<>();

    // ── Fishing rod ───────────────────────────────────────────────────────────

    // X-axis rotation applied after the Chest correction to orient the rod with
    // its handle pointing downward. TODO: tune in-game.
    private static final float ROD_ROT_X = 130.0f;

    // Position offset applied in chest-bone-local space (before the rod's own
    // rotation), so axes behave predictably regardless of rod orientation.
    // TODO: tune in-game.
    private static final float ROD_OFFSET_X = -0.1f;
    private static final float ROD_OFFSET_Y =  0.6f;
    private static final float ROD_OFFSET_Z = -0.2f;

    // ── Chest-overlap offset ───────────────────────────────────────────────────

    // Translation in chest-bone-local space applied when the Chest bone is already
    // occupied by Epic Fight's moveToolBonesToBack. Shifts the whole weapon group
    // as a unit. TODO: tune in-game.
    private static final float OVERLAP_TX = -0.2f;
    private static final float OVERLAP_TY =  0.0f;
    private static final float OVERLAP_TZ =  0.1f;

    // ── Cached relative sheath offset ─────────────────────────────────────────

    // inverse(correction_main) × correction_off, derived once from the Chest-bone
    // matrices. The bone pose cancels out mathematically, so this value is constant
    // regardless of animation frame. null = not yet computed. Uchigatana (native
    // sheath) keeps its own dedicated field; detached-sheath categories share
    // detachedSheathRelativeOffsets above, keyed by category.
    private static OpenMatrix4f katanaSheathRelativeOffset = null;

    // Items that are ALWAYS shown on the back and are deliberately kept out of their category's waist/back setting,
    // sheath and same-category-collision handling (Nova is a TACHI-category item, but not a tachi in looks).
    // Their position is their own "<namespace>.<item id>_back" TuneableOffset.
    // Moonless is a TACHI-category item too (blade only: its handle is a separate item), and is drawn by moonlessParts.
    private static final Set<String> BACK_ONLY_ITEMS = Set.of("wom:nova", "wom:moonless");

    private static boolean isBackOnlyItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && BACK_ONLY_ITEMS.contains(id.toString());
    }

    // Items drawn with a 3D sheath of their own instead of the category's flat tachi sheath. The sheath model is
    // authored in the blade's own frame (same display transform), so it is drawn exactly where the blade is drawn
    // when idle (waist or back, per the item's own setting) and stays there while the blade is in the hand.
    private static Item ownSheathItem(ItemStack stack, boolean waist) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && "wom:evil_tachi".equals(id.toString())) {
            // WAIST: the sheath with a cord round it (as if tied to the belt); BACK: the plain one.
            return waist ? Epic_fight_sheathing_master.EVIL_TACHI_SHEATH_WITH_BAND.get()
                         : Epic_fight_sheathing_master.EVIL_TACHI_SHEATH.get();
        }
        if (id != null && "wom:hollow_longsword".equals(id.toString())) {
            // Longsword category, drawn along the sword/longsword path (renderLongswordSheaths). The sheath with the
            // cord in both modes (a plain one looked bare).
            return Epic_fight_sheathing_master.HOLLOW_LONGSWORD_SHEATH_WITH_BAND.get();
        }
        return null;
    }

    // Evil Tachi smokes while it is held (Weapons of Miracles spawns vanilla SMOKE along the blade); sheathed, a little of
    // it leaks out of the mouth. The mouth of the sheath in the blade's item-local frame (x, y, z) = (-0.031, 0.40, -0.0125).
    private static final java.util.Map<java.util.UUID, Long> SMOKE_LAST = new java.util.HashMap<>();

    private static void leakSmokeFromMouth(Player owner, PoseStack postStack) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        Long last = SMOKE_LAST.get(owner.getUUID());
        if (last != null && now - last < 4) return; // render runs many times per tick: at most one check per 4 ticks
        SMOKE_LAST.put(owner.getUUID(), now);
        if (level.random.nextFloat() > 0.6f) return;
        net.minecraft.world.phys.Vec3 p = SheathCollisionSystem.worldPosOf(postStack, -0.031f, 0.40f, -0.0125f);
        level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                p.x + (level.random.nextFloat() - 0.5f) * 0.03, p.y + (level.random.nextFloat() - 0.5f) * 0.03,
                p.z + (level.random.nextFloat() - 0.5f) * 0.03, 0.0, -0.02 * level.random.nextFloat(), 0.0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderOwnSheath(
            Item sheathItem, ItemStack bladeStack, CapabilityItem.WeaponCategories category, DetachedSheathSpec spec,
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            OpenMatrix4f[] poses, HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer, boolean useWaistMode) {

        ItemStack sheathStack = new ItemStack(sheathItem);
        postStack.pushPose();
        if (useWaistMode) {
            // Same placement as the idle blade at the waist: Torso bone + the item's waist key.
            TuneableOffset off = TuneableOffset.REGISTRY.get(
                    resolveWaistOffsetKey(bladeStack, category, spec, entitypatch, humanoidArmature));
            OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            if (off != null) torsoMatrix.translate(off.tx, off.ty, off.tz);
            MathUtils.mulStack(postStack, torsoMatrix);
            if (off != null) {
                postStack.mulPose(Axis.XP.rotationDegrees(off.rotX));
                postStack.mulPose(Axis.YP.rotationDegrees(off.rotY));
                postStack.mulPose(Axis.ZP.rotationDegrees(off.rotZ));
            }
        } else {
            // Same placement as the idle blade on the back: Chest-bone main-hand correction, then tachi_back and
            // the item's own back nudge.
            Joint savedMainJoint = entitypatch.getParentJointOfHand(InteractionHand.MAIN_HAND);
            OpenMatrix4f matrixMain;
            try {
                entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, humanoidArmature.chest);
                matrixMain = new OpenMatrix4f(renderer.getCorrectionMatrix(entitypatch, InteractionHand.MAIN_HAND, poses));
            } finally {
                entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, savedMainJoint);
            }
            MathUtils.mulStack(postStack, matrixMain);
            applyOffset(postStack, TuneableOffset.REGISTRY.get("tachi_back"), false);
            applyItemBackNudge(postStack, bladeStack);
        }
        // A little smoke from the mouth while the blade is sheathed (not in the hand: held, the weapon makes its own).
        if (entitypatch.getOriginal() instanceof Player owner && owner.getMainHandItem() != bladeStack) {
            leakSmokeFromMouth(owner, postStack);
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(bladeStack.getItem());
        if (id != null) {
            String tag = id.getNamespace() + "." + id.getPath() + "_sheath";
            if (SheathHitboxProfile.REGISTRY.containsKey(tag)) {
                SheathCollisionSystem.checkAndPlaySound((Entity) entitypatch.getOriginal(), tag, postStack);
            }
        }
        itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    /**
     * Returns true when Epic Fight has already routed the main-hand weapon to the
     * Chest bone (e.g. via moveToolBonesToBack during ladder climbing). Kept as a
     * separate method so the detection predicate can be replaced without touching
     * the rendering code.
     */
    private static boolean isChestAlreadyOccupied(
            LivingEntityPatch<?> entitypatch, HumanoidArmature humanoidArmature) {
        return entitypatch.getParentJointOfHand(InteractionHand.MAIN_HAND) == humanoidArmature.chest;
    }

    // True for any category registered in DETACHED_SHEATH_SPECS (currently:
    // Tachi). Used to generalize the "held item shifts instead of the idle
    // one" ladder-climb overlap handling in redirectMainHandRenderItemInHand.
    private static boolean isDetachedSheathCategory(ItemStack stack) {
        if (isBackOnlyItem(stack)) return false;
        WeaponCategory rawCategory = EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory();
        return rawCategory instanceof CapabilityItem.WeaponCategories category
                && DETACHED_SHEATH_SPECS.containsKey(category);
    }

    // WAIST-mode Torso-bone offset key for a detached-sheath category's blade
    // (and, since the sheath shares this same torso basis, its sheath too —
    // see renderDetachedSheathAtMode's WAIST branch). During ladder-climbing
    // (isChestAlreadyOccupied), Tachi uses a dedicated "tachi_ladder" position
    // instead of its normal standing "tachi" position. Shared by
    // redirectMainHandRenderItemInHand, renderSlot0Weapon, and
    // renderDetachedSheathAtMode so blade and sheath always agree.
    private static String resolveWaistOffsetKey(
            ItemStack stack, CapabilityItem.WeaponCategories category, DetachedSheathSpec spec,
            LivingEntityPatch<?> entitypatch, HumanoidArmature humanoidArmature) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (category == CapabilityItem.WeaponCategories.TACHI
                && isChestAlreadyOccupied(entitypatch, humanoidArmature)) {
            // An item with its own ladder position ("<namespace>.<item id>_ladder", e.g. wom.evil_tachi_ladder).
            if (id != null) {
                String ownLadder = id.getNamespace() + "." + id.getPath() + "_ladder";
                if (TuneableOffset.REGISTRY.containsKey(ownLadder)) return ownLadder;
            }
            return "tachi_ladder";
        }
        // An item with its own WAIST position ("<namespace>.<item id>_waist", e.g. wom.evil_tachi_waist) uses it
        // for the blade and, since the sheath is placed relative to the blade, for the sheath too.
        if (id != null) {
            String own = id.getNamespace() + "." + id.getPath() + "_waist";
            if (TuneableOffset.REGISTRY.containsKey(own)) return own;
        }
        return spec.waistOffsetKey();
    }

    // Per-category replacement for the old single global Config.sheatheAtWaist
    // flag — only categories with an actual BACK-mode implementation have an
    // entry. Used by the DETACHED_SHEATH_SPECS-driven (currently: Tachi)
    // functions, which operate on a generic category variable rather than a
    // hardcoded one. Katana/sword/longsword-specific code instead references
    // their own Config field directly (clearer in already-category-dedicated
    // code), but must stay in sync with this mapping if it's ever extended.
    // Per-item waist/back for a tachi-category item that has its own switch (Weapons of Miracles' Evil Tachi);
    // every other item follows its category.
    private static boolean useWaistConfig(ItemStack stack, CapabilityItem.WeaponCategories category) {
        if (category == CapabilityItem.WeaponCategories.TACHI) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && "wom:evil_tachi".equals(id.toString())) return Config.womEvilTachiAtWaist;
        }
        if (category == CapabilityItem.WeaponCategories.LONGSWORD) return longswordAtWaist(stack);
        if (category == CapabilityItem.WeaponCategories.SWORD) return swordAtWaist(stack);
        return useWaistConfig(category);
    }

    // Waist (true) / back (false) of a sword-category item: Weapons of Miracles' Herrscher has its own switch, every other
    // sword follows Config.sheatheSwordAtWaist.
    private static boolean swordAtWaist(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && "wom:herrscher".equals(id.toString())) return Config.womHerrscherAtWaist;
        return Config.sheatheSwordAtWaist;
    }

    // Waist (true) / back (false) of a longsword-category item: Weapons of Miracles' Hollow Longsword has its own
    // switch, every other longsword follows Config.sheatheLongswordAtWaist.
    private static boolean longswordAtWaist(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && "wom:hollow_longsword".equals(id.toString())) return Config.womHollowLongswordAtWaist;
        return Config.sheatheLongswordAtWaist;
    }

    private static boolean useWaistConfig(CapabilityItem.WeaponCategories category) {
        return switch (category) {
            case TACHI -> Config.sheatheTachiAtWaist;
            case UCHIGATANA -> Config.sheatheUchigatanaAtWaist;
            case SWORD -> Config.sheatheSwordAtWaist;
            case LONGSWORD -> Config.sheatheLongswordAtWaist;
            default -> true;
        };
    }

    // True when some OTHER idle-weapon slot (Config.idleSlots, excluding the
    // currently selected/held slot) holds `heldCategory` — a genuine "two
    // instances of the same weapon" collision that ladder-climbing display
    // needs to resolve (mode-switch the held one to the opposite mode).
    // With the default idleSlots=[0] this is exactly the old "held item is
    // not slot 0, and slot 0 holds the same category" check.
    // General building block for hasCompetingHeldDetachedSheathWeapon (tachi)
    // and the katana branch in redirectMainHandRenderItemInHand.
    private static boolean hasCompetingIdleWeapon(Player player, CapabilityItem.WeaponCategories heldCategory) {
        int selected = player.getInventory().selected;
        for (int slot : Config.getIdleSlots()) {
            if (slot == selected) continue;
            ItemStack stack = player.getInventory().getItem(slot);
            // A non-allowlisted idle weapon isn't displayed, so it can't collide.
            if (!SheathAllowlist.isAllowed(stack)) continue;
            if (isBackOnlyItem(stack)) continue;
            if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory() == heldCategory) {
                return true;
            }
        }
        return false;
    }

    // Categories that already have an off-hand ("left") display position in
    // this mod (dagger_offhand/thighL, sword_offhand, longsword_offhand).
    // Only these can show a second idle weapon; any other category has no
    // left position to put it at.
    private static boolean hasOffhandDisplayPosition(CapabilityItem.WeaponCategories category) {
        return category == CapabilityItem.WeaponCategories.DAGGER
                || category == CapabilityItem.WeaponCategories.SWORD
                || category == CapabilityItem.WeaponCategories.LONGSWORD;
    }

    // Whether a second idle weapon of this stack's category may be shown at the
    // off-hand position: the category must have such a position (above) AND
    // Epic Fight itself must allow the item in the off-hand. The latter is
    // CapabilityItem#canBePlacedOffhand() — the item's own, held-state-
    // independent flag (default true; the Tachi/Uchigatana/Greatsword/Spear/
    // Longsword presets set it false). Style#canUseOffhand() is deliberately
    // NOT used: it describes the CURRENT main-hand style (e.g. TWO_HAND while
    // something else is wielded), not whether this weapon type can be dual-wielded.
    private static boolean canShowSecondIdle(CapabilityItem.WeaponCategories category, ItemStack stack) {
        return hasOffhandDisplayPosition(category)
                && EpicFightCapabilities.getItemStackCapability(stack).canBePlacedOffhand();
    }

    // First idle-slot stack (ascending slot order) whose weapon is `category`,
    // or ItemStack.EMPTY. Includes the currently held slot on purpose: sheaths
    // are shown regardless of held state. Used for the generic category
    // sheaths (one drawing per side is enough, they are identical for every
    // weapon of the category); the idle weapon BODIES are all drawn (see the
    // idle-slot loop), and the per-item detached sheaths too.
    private static ItemStack findIdleStack(Player player, CapabilityItem.WeaponCategories category) {
        for (int slot : Config.getIdleSlots()) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!SheathAllowlist.isSheathAllowed(stack)) continue;
            if (isBackOnlyItem(stack)) continue;
            if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory() == category) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    // Second idle-slot stack of `category` (same scan order/inclusion rules as
    // findIdleStack), or EMPTY when there is none or the category can't be
    // dual-wielded (see canShowSecondIdle). Only the left-side sheath needs it; a
    // third and later weapon's generic sheath would coincide with the first two.
    private static ItemStack findSecondIdleStack(Player player, CapabilityItem.WeaponCategories category) {
        boolean seenFirst = false;
        for (int slot : Config.getIdleSlots()) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!SheathAllowlist.isSheathAllowed(stack)) continue;
            if (isBackOnlyItem(stack)) continue;
            if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory() != category) continue;
            if (!seenFirst) {
                seenFirst = true;
                continue;
            }
            return canShowSecondIdle(category, stack) ? stack : ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    // True when `owner` (the stack findIdleStack picked as the owner of the main-hand side sheath of `category`) is the
    // weapon currently HELD, and the main-hand side position is taken by another idle weapon of the category that is
    // drawn there but gets no sheath (not sheath-allowed, e.g. a Weapons of Miracles longsword), while no sheathed
    // one shares the spot. Showing the held weapon's empty sheath there would make that other weapon look as if it
    // were sheathed in it, so the sheath is skipped (the held weapon's own side/mode takes over, see
    // hasCompetingIdleWeapon). Side rule as in the idle-slot loop: the rank counts every allowed idle weapon of
    // the category; with a second (off-hand) position, odd ranks are on the left.
    private static boolean sheathSpotTakenByUnsheathed(
            Player player, CapabilityItem.WeaponCategories category, ItemStack owner) {
        if (owner.isEmpty() || owner != player.getMainHandItem()) return false;
        int selected = player.getInventory().selected;
        int rank = 0;
        boolean takenByUnsheathed = false;
        for (int slot : Config.getIdleSlots()) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!SheathAllowlist.isAllowed(stack)) continue;
            if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory() != category) continue;
            int r = rank++;
            if (slot == selected) continue;
            boolean left = canShowSecondIdle(category, stack) && r % 2 == 1;
            if (left) continue;
            if (SheathAllowlist.isSheathAllowed(stack)) return false;
            takenByUnsheathed = true;
        }
        return takenByUnsheathed;
    }

    // Detached-sheath-category (currently: tachi) specialization of
    // hasCompetingIdleWeapon: also resolves the held item's own category and
    // gates on it being a detached-sheath one. False whenever there's no such
    // competing pair, including when the held item is the only one of its
    // category among the idle slots, or doesn't have a detached-sheath category.
    // Shared by renderSlot0Weapon,
    // redirectMainHandRenderItemInHand, and renderHeldDetachedSheath so the
    // collision definition stays in one place.
    private static boolean hasCompetingHeldDetachedSheathWeapon(Player player) {
        ItemStack heldStack = player.getMainHandItem();
        if (isBackOnlyItem(heldStack)) return false;
        WeaponCategory rawHeldCategory = EpicFightCapabilities.getItemStackCapability(heldStack).getWeaponCategory();
        if (!(rawHeldCategory instanceof CapabilityItem.WeaponCategories heldCategory)) return false;
        if (!DETACHED_SHEATH_SPECS.containsKey(heldCategory)) return false;

        return hasCompetingIdleWeapon(player, heldCategory);
    }

    // Computes inverse(correction_main) × correction_off from the Chest-bone matrices.
    // getCorrectionMatrix returns (poses[joint] × correction), so when we take
    //   inverse(matMain) × matOff = inverse(poses[chest] × corr_main) × (poses[chest] × corr_off)
    //                             = inverse(corr_main) × corr_off
    // the bone pose cancels, yielding a pose-independent constant.
    //
    // sheathOffsetKey, when non-null, applies an extra correction (TuneableOffset
    // lookup by that key) onto matOff before the relative offset is derived, so
    // the resulting cached constant lines up with wherever the sheath needs to
    // sit for the mode this call is computing the cache for. Katana (native
    // sheath) passes null — no such correction exists for it. Tachi passes
    // spec.sheathWaistOffsetKey() ("tachi_sheath_waist") here — a value
    // independent of spec.sheathOffsetKey() ("tachi_sheath", BACK-mode only),
    // since the two modes were found to need different tuning.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpenMatrix4f computeSheathRelativeOffset(
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            HumanoidArmature humanoidArmature, OpenMatrix4f[] poses, String sheathOffsetKey) {

        Joint savedMain = entitypatch.getParentJointOfHand(InteractionHand.MAIN_HAND);
        Joint savedOff  = entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);

        entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, humanoidArmature.chest);
        OpenMatrix4f matMain = new OpenMatrix4f(
                renderer.getCorrectionMatrix(entitypatch, InteractionHand.MAIN_HAND, poses));

        entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, humanoidArmature.chest);
        OpenMatrix4f matOff = new OpenMatrix4f(
                renderer.getCorrectionMatrix(entitypatch, InteractionHand.OFF_HAND, poses));

        entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, savedMain);
        entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, savedOff);

        if (sheathOffsetKey != null) {
            TuneableOffset sheathOff = TuneableOffset.REGISTRY.get(sheathOffsetKey);
            float sTx   = sheathOff != null ? sheathOff.tx   : 0.0f;
            float sTy   = sheathOff != null ? sheathOff.ty   : 0.0f;
            float sTz   = sheathOff != null ? sheathOff.tz   : 0.0f;
            float sRotX = sheathOff != null ? sheathOff.rotX : 0.0f;
            float sRotY = sheathOff != null ? sheathOff.rotY : 0.0f;
            float sRotZ = sheathOff != null ? sheathOff.rotZ : 0.0f;
            matOff.translate(sTx, sTy, sTz);
            matOff.rotateDeg(sRotX, Vec3f.X_AXIS);
            matOff.rotateDeg(sRotY, Vec3f.Y_AXIS);
            matOff.rotateDeg(sRotZ, Vec3f.Z_AXIS);
        }

        // relative = inverse(matMain) × matOff
        OpenMatrix4f relative = new OpenMatrix4f(matMain);
        relative.invert();
        relative.mulBack(matOff);
        return relative;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void ensureKatanaSheathOffsetCached(
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            HumanoidArmature humanoidArmature, OpenMatrix4f[] poses) {
        if (katanaSheathRelativeOffset == null) {
            katanaSheathRelativeOffset = computeSheathRelativeOffset(renderer, entitypatch, humanoidArmature, poses, null);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpenMatrix4f ensureDetachedSheathOffsetCached(
            CapabilityItem.WeaponCategories category, DetachedSheathSpec spec,
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            HumanoidArmature humanoidArmature, OpenMatrix4f[] poses) {
        OpenMatrix4f cached = detachedSheathRelativeOffsets.get(category);
        if (cached == null) {
            cached = computeSheathRelativeOffset(renderer, entitypatch, humanoidArmature, poses, spec.sheathWaistOffsetKey());
            detachedSheathRelativeOffsets.put(category, cached);
        }
        return cached;
    }

    // ── Injection ─────────────────────────────────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "renderLayer", at = @At("TAIL"), remap = false)
    private void onRenderLayerTail(
            LivingEntityPatch entitypatch,
            LivingEntity entityliving,
            RenderLayer vanillaLayer,
            PoseStack postStack,
            MultiBufferSource buffer,
            int packedLight,
            OpenMatrix4f[] poses,
            float bob, float yRot, float xRot, float partialTicks,
            CallbackInfo ci) {

        // Prerequisites shared by both rendering blocks.
        if (!(entityliving instanceof Player player)) return;
        if (!(entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature)) return;

        // Pre-warm the sheath offset caches on any frame a katana/tachi is in the
        // hotbar, before the first ladder-climbing redirect ever needs them.
        // FMLClientSetupEvent is too early (no player/poses exist yet), so the
        // earliest practical moment is the first TAIL render frame with context.
        if (katanaSheathRelativeOffset == null) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (!(s.getItem() instanceof UchigatanaItem)) continue;
                RenderItemBase kr = ClientEngine.getInstance().renderEngine.getItemRenderer(s);
                if (kr != null) ensureKatanaSheathOffsetCached(kr, entitypatch, humanoidArmature, poses);
                break;
            }
        }
        for (Map.Entry<CapabilityItem.WeaponCategories, DetachedSheathSpec> entry : DETACHED_SHEATH_SPECS.entrySet()) {
            CapabilityItem.WeaponCategories detachedCategory = entry.getKey();
            if (detachedSheathRelativeOffsets.containsKey(detachedCategory)) continue;
            for (int i = 0; i < 9; i++) {
                ItemStack s = player.getInventory().getItem(i);
                WeaponCategory rawSlotCategory = EpicFightCapabilities.getItemStackCapability(s).getWeaponCategory();
                if (rawSlotCategory != detachedCategory) continue;
                RenderItemBase dr = ClientEngine.getInstance().renderEngine.getItemRenderer(s);
                if (dr != null) {
                    ensureDetachedSheathOffsetCached(detachedCategory, entry.getValue(), dr, entitypatch, humanoidArmature, poses);
                }
                break;
            }
        }

        net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                Minecraft.getInstance().getItemRenderer();
        int chestId = humanoidArmature.chest.getId();
        OpenMatrix4f originalChestPose = poses[chestId];

        // Each block below is independent: early returns inside renderSlot0Weapon
        // do not prevent the fishing rod block from running.
        renderSlot0Weapon(entitypatch, player, postStack, buffer, packedLight, poses,
                humanoidArmature, chestId, originalChestPose, itemRenderer);

        // Gloves / knuckles (FIST) worn on both hands (config fistGlovesWorn, default off).
        if (Config.fistGlovesWorn) {
            renderWornGloves(entitypatch, player, postStack, buffer, packedLight, poses, partialTicks);
        }

        // Independent sheath for whatever detached-sheath-category weapon is
        // actually held (if any) — separate from slot0's own display above.
        // Handles both normal holding and ladder-climbing (stowed position).
        // No-ops when the held item is slot0 itself or isn't a
        // detached-sheath category.
        renderHeldDetachedSheath(entitypatch, player, postStack, buffer, packedLight,
                poses, humanoidArmature, itemRenderer);

        // Katana dual-wield sheath compensation — not currently reachable
        // (Epic Fight doesn't support katana dual-wielding yet), added
        // preemptively in case it is added later. See
        // renderHeldKatanaDualWieldSheath for why this is needed.
        renderHeldKatanaDualWieldSheath(entitypatch, player, postStack, buffer, packedLight,
                poses, humanoidArmature, itemRenderer);

        // ── Fishing rod on back ────────────────────────────────────────────────
        // Scans hotbar 0-8 for the first rod. Skipped when a rod is in main hand.
        if (!(player.getMainHandItem().getItem() instanceof FishingRodItem)) {
            ItemStack rodStack = null;
            for (int i = 0; i < 9; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.getItem() instanceof FishingRodItem && SheathAllowlist.isAllowed(s)) {
                    rodStack = s;
                    break;
                }
            }

            if (rodStack != null) {
                // Reuse the forceOverride matrix path: look up the Chest correction
                // from the renderer's mainhand maps and mulFront with the original
                // (unshifted) chest bone pose.
                RenderItemBase rodRenderer =
                        ClientEngine.getInstance().renderEngine.getItemRenderer(rodStack);
                if (rodRenderer != null) {
                    RenderItemBaseAccessor rodAcc = (RenderItemBaseAccessor) rodRenderer;
                    String chestName = humanoidArmature.chest.getName();
                    Map<String, OpenMatrix4f> perItemMap = rodAcc.getMainhandCorrectionTransforms();
                    Map<String, OpenMatrix4f> globalMap =
                            RenderItemBaseAccessor.getGlobalMainhandItemTransforms();
                    OpenMatrix4f correction =
                            perItemMap.getOrDefault(chestName, globalMap.get(chestName));
                    OpenMatrix4f rodMatrix = new OpenMatrix4f(correction);
                    rodMatrix.mulFront(originalChestPose);

                    postStack.pushPose();
                    MathUtils.mulStack(postStack, rodMatrix);
                    postStack.translate(ROD_OFFSET_X, ROD_OFFSET_Y, ROD_OFFSET_Z);
                    postStack.mulPose(Axis.XP.rotationDegrees(ROD_ROT_X));
                    RenderItemBaseAccessor.getItemInHandRenderer().renderItem(
                            player, rodStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                            false, postStack, buffer, packedLight);
                    postStack.popPose();
                }
            }
        }

        // ── Bow / Crossbow on back (or bow at waist) ────────────────────────────
        // Scans hotbar 0-8. Each bow/crossbow NOT in the main hand is rendered.
        // Both can appear simultaneously (dual-carry is valid).
        // bow.json / crossbow.json configure offhand.Chest, so the OFF_HAND Chest
        // matrix already carries the correct back-sheathing position and rotation —
        // no extra offsets needed for the (default) BACK mode. BOW alone can
        // instead be carried at the waist (Torso bone + TuneableOffset "bow",
        // same pattern as sword/longsword) when Config.sheatheBowAtWaist is
        // true — CROSSBOW always stays BACK-mode regardless of this flag.
        int selectedSlot = player.getInventory().selected;
        for (int i = 0; i < 9; i++) {
            if (i == selectedSlot) continue;
            ItemStack rangedStack = player.getInventory().getItem(i);
            boolean isBow = rangedStack.getItem() instanceof BowItem;
            if (!isBow && !(rangedStack.getItem() instanceof CrossbowItem)) continue;
            if (!SheathAllowlist.isAllowed(rangedStack)) continue;

            if (isBow && Config.sheatheBowAtWaist) {
                TuneableOffset off = TuneableOffset.REGISTRY.get("bow");
                float tx   = off != null ? off.tx   : 0.0f;
                float ty   = off != null ? off.ty   : 0.0f;
                float tz   = off != null ? off.tz   : 0.0f;
                float rotX = off != null ? off.rotX : 0.0f;
                float rotY = off != null ? off.rotY : 0.0f;
                float rotZ = off != null ? off.rotZ : 0.0f;

                OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
                torsoMatrix.translate(tx, ty, tz);

                postStack.pushPose();
                MathUtils.mulStack(postStack, torsoMatrix);
                postStack.mulPose(Axis.XP.rotationDegrees(rotX));
                postStack.mulPose(Axis.YP.rotationDegrees(rotY));
                postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));
                // Same render call as the BACK branch below — only the base
                // transform (Torso vs Chest-correction) differs.
                RenderItemBaseAccessor.getItemInHandRenderer().renderItem(
                        player, rangedStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        false, postStack, buffer, packedLight);
                postStack.popPose();
                continue;
            }

            RenderItemBase rangedRenderer =
                    ClientEngine.getInstance().renderEngine.getItemRenderer(rangedStack);
            if (rangedRenderer == null) continue;

            Joint savedOffJoint = entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, humanoidArmature.chest);
            OpenMatrix4f rangedMatrix = new OpenMatrix4f(
                    rangedRenderer.getCorrectionMatrix(entitypatch, InteractionHand.OFF_HAND, poses));
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, savedOffJoint);

            postStack.pushPose();
            MathUtils.mulStack(postStack, rangedMatrix);
            // RenderTwoHandedRangedWeapon uses RIGHT_HAND + leftHand=false even for OFF_HAND.
            RenderItemBaseAccessor.getItemInHandRenderer().renderItem(
                    player, rangedStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    false, postStack, buffer, packedLight);
            postStack.popPose();
        }

        // ── Shield on back ─────────────────────────────────────────────────────
        // Scans hotbar 0-8. Skips if:
        //   (a) the slot is the selected (main-hand) slot, or
        //   (b) a shield is already equipped in the off-hand (already visible there).
        // shield.json defines offhand.Chest, so no extra offset is needed.
        // RenderShield.alwaysInHand is false (not set in shield.json → default false),
        // so setParentJointOfHand correctly routes the correction matrix to Chest.
        boolean shieldInOffHand = player.getOffhandItem().getItem() instanceof ShieldItem;
        for (int i = 0; i < 9; i++) {
            if (i == selectedSlot) continue;
            if (shieldInOffHand) continue;
            ItemStack shieldStack = player.getInventory().getItem(i);
            if (!(shieldStack.getItem() instanceof ShieldItem)) continue;
            if (!SheathAllowlist.isAllowed(shieldStack)) continue;

            RenderItemBase shieldRenderer =
                    ClientEngine.getInstance().renderEngine.getItemRenderer(shieldStack);
            if (shieldRenderer == null) continue;

            Joint savedOffJoint = entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, humanoidArmature.chest);
            OpenMatrix4f shieldMatrix = new OpenMatrix4f(
                    shieldRenderer.getCorrectionMatrix(entitypatch, InteractionHand.OFF_HAND, poses));
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, savedOffJoint);

            postStack.pushPose();
            MathUtils.mulStack(postStack, shieldMatrix);
            // Per-item nudge ("<namespace>.<id>_back", e.g. "wom.gesetz_back"), for shields whose own display is not
            // the vanilla shield's (Epic Fight's shield-on-back correction is made for the vanilla one).
            applyItemBackNudge(postStack, shieldStack);
            BakedModel shieldModel = itemRenderer.getItemModelShaper().getItemModel(shieldStack);
            itemRenderer.render(shieldStack, ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                    true, postStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, shieldModel);
            postStack.popPose();

            renderShieldHandleAtWrist(shieldStack, shieldRenderer, poses, humanoidArmature,
                    postStack, buffer, packedLight, itemRenderer);
        }

        // ── Off-hand weapon on back (when main-hand style disables offhand) ────
        // getStyle().canUseOffhand() returns false when the main-hand weapon is in
        // a style that prevents dual-wielding (e.g. TWO_HAND greatsword). The off-
        // hand weapon is then idle, so we show it on the back at the Chest bone.
        // Shields in the off-hand are excluded (already handled by the block above).
        // alwaysInHand=true weapons (no dedicated item_skin JSON) are skipped — they
        // fall back to the air renderer whose getCorrectionMatrix ignores the joint
        // override; add them to FORCE_SHEATH_CATEGORIES if needed after in-game test.
        if (!shieldInOffHand) {
            ItemStack offhandStack = player.getOffhandItem();
            if (!offhandStack.isEmpty() && SheathAllowlist.isAllowed(offhandStack)) {
                CapabilityItem offCap = EpicFightCapabilities.getItemStackCapability(offhandStack);
                WeaponCategory offRawCat = offCap.getWeaponCategory();
                if (offRawCat instanceof CapabilityItem.WeaponCategories offCat
                        && !SKIP_CATEGORIES.contains(offCat)) {

                    if (!entitypatch.isOffhandItemValid()) {

                        if (MeshWeaponIdleRenderer.tryRender(offhandStack, player, postStack, buffer,
                                packedLight, poses, humanoidArmature, originalChestPose,
                                () -> computeEfCarryCorrection(humanoidArmature), true, false)) {
                            // EFN 3D mesh weapon (e.g. the Exile Blade) in the off-hand: drawn as a mesh at
                            // the left position, never as the flat item icon.
                        } else if (offCat == CapabilityItem.WeaponCategories.DAGGER) {
                            // DAGGER offhand uses its own key so position can differ from slot-0.
                            renderDaggerAtWaist("dagger_offhand", offhandStack, player, postStack,
                                    buffer, packedLight, poses, humanoidArmature.thighL);
                        } else if (offCat == CapabilityItem.WeaponCategories.SWORD) {
                            // Off-hand sword shown here only while dual-wield isn't
                            // actually valid (see isOffhandItemValid() check above) —
                            // always the left position, no evacuation concept needed.
                            boolean useWaist = swordAtWaist(offhandStack);
                            OpenMatrix4f baseMatrix;
                            if (useWaist) {
                                baseMatrix = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
                            } else {
                                RenderItemBase offSwordRenderer =
                                        ClientEngine.getInstance().renderEngine.getItemRenderer(offhandStack);
                                if (offSwordRenderer == null) {
                                    baseMatrix = null;
                                } else {
                                    baseMatrix = resolveChestCorrectionViaOffHand(
                                            offSwordRenderer, entitypatch, humanoidArmature, poses);
                                }
                            }
                            if (baseMatrix != null) {
                                renderSwordBody(useWaist ? "sword_offhand" : "sword_offhand_back",
                                        offhandStack, baseMatrix, postStack, buffer, packedLight, itemRenderer);
                            }
                        } else if (offCat == CapabilityItem.WeaponCategories.LONGSWORD) {
                            // Exact duplicate of the SWORD offhand branch above
                            // (unreachable in current gameplay — LONGSWORD has
                            // canBePlacedOffhand(false) — kept for consistency).
                            boolean useWaist = longswordAtWaist(offhandStack);
                            OpenMatrix4f baseMatrix;
                            if (useWaist) {
                                baseMatrix = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
                            } else {
                                RenderItemBase offLongswordRenderer =
                                        ClientEngine.getInstance().renderEngine.getItemRenderer(offhandStack);
                                if (offLongswordRenderer == null) {
                                    baseMatrix = null;
                                } else {
                                    baseMatrix = resolveChestCorrectionViaOffHand(
                                            offLongswordRenderer, entitypatch, humanoidArmature, poses);
                                }
                            }
                            if (baseMatrix != null) {
                                renderSwordBody(useWaist ? "longsword_offhand" : "longsword_offhand_back",
                                        offhandStack, baseMatrix, postStack, buffer, packedLight, itemRenderer);
                            }
                        } else {
                            RenderItemBase offRenderer =
                                    ClientEngine.getInstance().renderEngine.getItemRenderer(offhandStack);
                            if (offRenderer != null
                                    && !((RenderItemBaseAccessor) offRenderer).getAlwaysInHand()) {

                                Joint savedOffJoint =
                                        entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);
                                entitypatch.setParentJointOfHand(
                                        InteractionHand.OFF_HAND, humanoidArmature.chest);
                                OpenMatrix4f offMatrix = new OpenMatrix4f(
                                        offRenderer.getCorrectionMatrix(
                                                entitypatch, InteractionHand.OFF_HAND, poses));
                                entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, savedOffJoint);

                                postStack.pushPose();
                                MathUtils.mulStack(postStack, offMatrix);
                                // Back-only items (Nova): the left copy of the right-hand display. tachi_back mirrored,
                                // then the item's own off-hand key "<namespace>.<item id>_back_off" (tuned separately);
                                // without such a key, the main-hand key mirrored.
                                if (isBackOnlyItem(offhandStack)) {
                                    if (offCat == CapabilityItem.WeaponCategories.TACHI) {
                                        applyOffset(postStack, TuneableOffset.REGISTRY.get("tachi_back"), true);
                                    }
                                    ResourceLocation offId = ForgeRegistries.ITEMS.getKey(offhandStack.getItem());
                                    if (offId != null) {
                                        String base = offId.getNamespace() + "." + offId.getPath() + "_back";
                                        TuneableOffset own = TuneableOffset.REGISTRY.get(base + "_off");
                                        if (own != null) applyOffset(postStack, own, false);
                                        else applyOffset(postStack, TuneableOffset.REGISTRY.get(base), true);
                                    }
                                }
                                // Collision boxes of an item with its own profile; the off-hand copy uses "<tag>_off" when registered.
                                String offHitboxTag = itemHitboxTag(offhandStack, null);
                                if (offHitboxTag != null) {
                                    String offOwn = offHitboxTag + "_off";
                                    SheathCollisionSystem.checkAndPlaySound(player,
                                            SheathHitboxProfile.REGISTRY.containsKey(offOwn) ? offOwn : offHitboxTag, postStack);
                                }
                                // Match RenderItemBase.renderItemInHand: hand==OFF_HAND → LEFT_HAND, leftHand=true.
                                RenderItemBaseAccessor.getItemInHandRenderer().renderItem(
                                        player, offhandStack, ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                                        true, postStack, buffer, packedLight);
                                postStack.popPose();
                            }
                        }
                    }
                } else if (isThighItem(offhandStack) && !entitypatch.isOffhandItemValid()) {
                    // Ender Blaster (custom category) in the off hand while Epic Fight does not draw it there:
                    // the left thigh, like the off-hand dagger. Own key "wom.ender_blaster_thigh_off".
                    renderDaggerAtWaist("wom.ender_blaster_thigh_off", offhandStack, player, postStack,
                            buffer, packedLight, poses, humanoidArmature.thighL);
                }
            }
        }
    }

    // ── Worn gloves / knuckles ────────────────────────────────────────────────
    //
    // A "glove" here is a FIST item on the body list whose Epic Fight renderer is a plain vanilla-model one
    // that is always drawn in the hand (alwaysInHand, e.g. epicfight:glove: a small cube drawn at the hand
    // bone). Mesh items (EFN's claws, handled by MeshWeaponIdleRenderer) are excluded. One glove covers one
    // hand only (see renderWornGloves); drawn with Epic Fight's own renderItemInHand, so the placement is
    // exactly what Epic Fight uses for a held glove. Visual only.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RenderItemBase gloveRenderer(ItemStack stack) {
        if (stack.isEmpty() || !SheathAllowlist.isAllowed(stack)) return null;
        // Epic Fight gives every item it does not know (arrows, blocks, food, ...) the EMPTY capability, whose
        // category is FIST too: only items that really are registered as fist weapons count.
        CapabilityItem cap = EpicFightCapabilities.getItemStackCapability(stack);
        if (cap.isEmpty() || cap.getWeaponCategory() != CapabilityItem.WeaponCategories.FIST) return null;
        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(stack);
        if (renderer == null || MeshWeaponIdleRenderer.isAvalonMeshRenderer(renderer)) return null;
        if (!((RenderItemBaseAccessor) renderer).getAlwaysInHand()) return null;
        return renderer;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderWornGloves(
            LivingEntityPatch entitypatch, Player player, PoseStack postStack, MultiBufferSource buffer,
            int packedLight, OpenMatrix4f[] poses, float partialTicks) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        RenderItemBase mainR = gloveRenderer(main);
        RenderItemBase offR = gloveRenderer(off);
        // One glove = one hand. A held glove is drawn by Epic Fight on its own hand only. The gloves of the
        // idle hotbar slots fill the hands that hold no glove: the first one the right hand, the next one the
        // left hand. Both hands show a glove only when there are two gloves.
        boolean rightFree = mainR == null;
        boolean leftFree = offR == null;
        // A glove in the off hand counts as equipped on the left hand, like an off-hand sword or dagger. Epic
        // Fight draws an off-hand item only while the off hand is valid for the current main-hand weapon;
        // otherwise (and only then) it is drawn here, visually only.
        if (offR != null && !entitypatch.isOffhandItemValid()) {
            offR.renderItemInHand(off, entitypatch, InteractionHand.OFF_HAND, poses, buffer, postStack, packedLight, partialTicks);
        }
        int selected = player.getInventory().selected;
        for (int i = 0; i < 9 && (rightFree || leftFree); i++) {
            if (i == selected || !Config.isIdleSlot(i)) continue;
            ItemStack s = player.getInventory().getItem(i);
            RenderItemBase r = gloveRenderer(s);
            if (r == null) continue;
            if (rightFree) {
                r.renderItemInHand(s, entitypatch, InteractionHand.MAIN_HAND, poses, buffer, postStack, packedLight, partialTicks);
                rightFree = false;
            } else {
                r.renderItemInHand(s, entitypatch, InteractionHand.OFF_HAND, poses, buffer, postStack, packedLight, partialTicks);
                leftFree = false;
            }
        }
    }

    // ── Katana ladder-climb redirect ──────────────────────────────────────────
    //
    // When moveToolBonesToBack is active (MAIN_HAND joint == chest), Epic Fight
    // calls renderItemInHand and positions the katana at the Chest bone. We
    // intercept that call (ordinal=0 = MAIN_HAND) and replace it with the same
    // Torso-based rendering used for the sheathed back-display, so position is
    // identical whether the player is holding the katana or not.
    //
    // Condition-false path: fall through to the original renderItemInHand call.

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
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
        remap = false
    )
    private void redirectMainHandRenderItemInHand(
            RenderItemBase renderer,
            ItemStack stack,
            LivingEntityPatch entitypatch,
            InteractionHand hand,
            OpenMatrix4f[] poses,
            MultiBufferSource buffer,
            PoseStack postStack,
            int packedLight,
            float partialTicks) {

        // Moonless: glides from the back to the hand right after it was drawn (moonlessHeld).
        if (isMoonless(stack) && entitypatch.getOriginal() instanceof Player moonlessPlayer
                && moonlessHeld(stack, entitypatch, moonlessPlayer, poses, buffer, postStack, packedLight)) {
            return;
        }

        // Ladder-climb weapon handling below only applies to allowlisted
        // items; everything else keeps Epic Fight/the pre-allowlist native path.
        if (bypassClimbHandling(renderer, stack)) {
            renderBypassed(renderer, stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
            return;
        }

        boolean stackIsKatana = stack.getItem() instanceof UchigatanaItem;

        if (stackIsKatana
                && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature
                && isChestAlreadyOccupied(entitypatch, humanoidArmature)) {
            // A competing idle katana in slot0 no longer flips this held one
            // to the opposite mode — it evacuates to a fixed left-belt
            // position instead (same concept as vanilla sword's/dagger's
            // offhand position), regardless of Config.sheatheUchigatanaAtWaist. The
            // non-competing case (climbing while holding the only katana) is
            // completely unaffected — still the configured WAIST/BACK mode.
            boolean hasCompeting = entitypatch.getOriginal() instanceof Player katanaPlayer
                    && hasCompetingIdleWeapon(katanaPlayer, CapabilityItem.WeaponCategories.UCHIGATANA);

            if (hasCompeting) {
                ensureKatanaSheathOffsetCached(renderer, entitypatch, humanoidArmature, poses);
                renderKatanaLeftBeltEvacuation(stack, (Entity) entitypatch.getOriginal(), humanoidArmature, poses, postStack, buffer, packedLight);
            } else if (Config.sheatheUchigatanaAtWaist) {
                ensureKatanaSheathOffsetCached(renderer, entitypatch, humanoidArmature, poses);

                TuneableOffset off = TuneableOffset.REGISTRY.get("uchigatana");
                float tx   = off != null ? off.tx   : 0.0f;
                float ty   = off != null ? off.ty   : 0.0f;
                float tz   = off != null ? off.tz   : 0.0f;
                float rotX = off != null ? off.rotX : 0.0f;
                float rotY = off != null ? off.rotY : 0.0f;
                float rotZ = off != null ? off.rotZ : 0.0f;

                int torsoId = humanoidArmature.torso.getId();
                OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[torsoId]);
                torsoMatrix.translate(tx, ty, tz);

                net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                        Minecraft.getInstance().getItemRenderer();

                postStack.pushPose();
                MathUtils.mulStack(postStack, torsoMatrix);
                postStack.mulPose(Axis.XP.rotationDegrees(rotX));
                postStack.mulPose(Axis.YP.rotationDegrees(rotY));
                postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));

                itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);

                if (katanaSheathRelativeOffset != null) {
                    ItemStack sheathStack = new ItemStack(EpicFightItems.UCHIGATANA_SHEATH.get());
                    postStack.pushPose();
                    MathUtils.mulStack(postStack, katanaSheathRelativeOffset);
                    SheathCollisionSystem.checkAndPlaySound((Entity) entitypatch.getOriginal(), "uchigatana", postStack);
                    itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                            packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
                    postStack.popPose();
                }

                postStack.popPose();
            } else {
                // Native BACK-mode passthrough — identical to Epic Fight's own
                // default climbing render, same as the non-competing case.
                renderer.renderItemInHand(stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
            }
        } else if (isDetachedSheathCategory(stack)
                && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature
                && isChestAlreadyOccupied(entitypatch, humanoidArmature)) {
            // Detached-sheath category (currently: tachi) actually held,
            // climbing (either Config mode). Chooses WAIST (Torso) or BACK
            // (native Chest) stowed rendering based on whether hotbar slot 0
            // has a competing weapon of the same category — mode-switching
            // instead of an OVERLAP_TX/TY/TZ positional shift, symmetric with
            // renderHeldDetachedSheath's collision handling for the
            // independent sheath. Non-player holders (mobs) have no
            // hotbar/slot0 concept, so they never have a competing item and
            // always use the configured mode. Katana is unaffected (handled
            // above; its BACK-mode climbing stays native/unmodified).
            WeaponCategory rawHeldCategory = EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory();
            if (!(rawHeldCategory instanceof CapabilityItem.WeaponCategories heldCategory)) return;
            DetachedSheathSpec spec = DETACHED_SHEATH_SPECS.get(heldCategory);
            if (spec == null) return;

            boolean hasCompeting = entitypatch.getOriginal() instanceof Player player
                    && hasCompetingHeldDetachedSheathWeapon(player);
            boolean configuredWaist = useWaistConfig(stack, heldCategory);
            boolean useWaistMode = hasCompeting ? !configuredWaist : configuredWaist;

            if (useWaistMode) {
                TuneableOffset off = TuneableOffset.REGISTRY.get(
                        resolveWaistOffsetKey(stack, heldCategory, spec, entitypatch, humanoidArmature));
                float tx   = off != null ? off.tx   : 0.0f;
                float ty   = off != null ? off.ty   : 0.0f;
                float tz   = off != null ? off.tz   : 0.0f;
                float rotX = off != null ? off.rotX : 0.0f;
                float rotY = off != null ? off.rotY : 0.0f;
                float rotZ = off != null ? off.rotZ : 0.0f;

                int torsoId = humanoidArmature.torso.getId();
                OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[torsoId]);
                torsoMatrix.translate(tx, ty, tz);

                net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                        Minecraft.getInstance().getItemRenderer();

                postStack.pushPose();
                MathUtils.mulStack(postStack, torsoMatrix);
                postStack.mulPose(Axis.XP.rotationDegrees(rotX));
                postStack.mulPose(Axis.YP.rotationDegrees(rotY));
                postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));

                itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);

                postStack.popPose();
            } else {
                // Explicit Chest-bone (main-hand correction) position, same as
                // renderSlot0Weapon's idle rendering — no OVERLAP needed, since
                // any collision was already resolved by switching mode instead.
                // tachi_back is applied AFTER matrixMain (i.e. in the blade's
                // own corrected local frame), matching renderSlot0Weapon. Using
                // renderer.renderItemInHand(...) directly here would apply
                // tachi_back as an outer transform around that call's own
                // internal correction matrix instead — a different, incorrect
                // frame (previously diagnosed as the cause of a positioning bug
                // in the non-competing held-tachi BACK-mode case).
                Joint savedMainJoint = entitypatch.getParentJointOfHand(InteractionHand.MAIN_HAND);
                entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, humanoidArmature.chest);
                OpenMatrix4f matrixMain = new OpenMatrix4f(
                        renderer.getCorrectionMatrix(entitypatch, InteractionHand.MAIN_HAND, poses));
                entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, savedMainJoint);

                net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                        Minecraft.getInstance().getItemRenderer();

                postStack.pushPose();
                MathUtils.mulStack(postStack, matrixMain);
                if (heldCategory == CapabilityItem.WeaponCategories.TACHI) {
                    TuneableOffset backOff = TuneableOffset.REGISTRY.get("tachi_back");
                    if (backOff != null) {
                        postStack.translate(backOff.tx, backOff.ty, backOff.tz);
                        postStack.mulPose(Axis.XP.rotationDegrees(backOff.rotX));
                        postStack.mulPose(Axis.YP.rotationDegrees(backOff.rotY));
                        postStack.mulPose(Axis.ZP.rotationDegrees(backOff.rotZ));
                    }
                }
                applyItemBackNudge(postStack, stack);
                itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
                postStack.popPose();
            }
        } else if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory()
                == CapabilityItem.WeaponCategories.SWORD
                && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature
                && isChestAlreadyOccupied(entitypatch, humanoidArmature)
                && entitypatch.getOriginal() instanceof Player swordPlayer) {
            // A competing idle sword in slot0 would otherwise collide with
            // this held one at the same right position — evacuate the held
            // one to the left position instead (sword has no WAIST/BACK mode
            // to switch between here, unlike tachi/katana — the mode is
            // still Config-driven, only the side changes). Off-hand-is-sword
            // (genuine dual-wield) is excluded: the left position is already
            // the off-hand's own position in that case.
            boolean offhandIsSword = EpicFightCapabilities.getItemStackCapability(swordPlayer.getOffhandItem())
                    .getWeaponCategory() == CapabilityItem.WeaponCategories.SWORD;
            boolean evacuateToLeft = !offhandIsSword
                    && hasCompetingIdleWeapon(swordPlayer, CapabilityItem.WeaponCategories.SWORD);

            boolean useWaist = swordAtWaist(stack);
            OpenMatrix4f baseMatrix = resolveSwordBaseMatrix(renderer, entitypatch, humanoidArmature, poses, useWaist, evacuateToLeft);
            String offsetKey = evacuateToLeft
                    ? (useWaist ? "sword_offhand" : "sword_offhand_back")
                    : (useWaist ? "sword" : "sword_back");

            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                    Minecraft.getInstance().getItemRenderer();
            renderSwordBody(offsetKey, stack, baseMatrix, postStack, buffer, packedLight, itemRenderer);
        } else if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory()
                == CapabilityItem.WeaponCategories.LONGSWORD
                && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature
                && isChestAlreadyOccupied(entitypatch, humanoidArmature)
                && entitypatch.getOriginal() instanceof Player longswordPlayer) {
            // Exact duplicate of the SWORD evacuation branch above. Offhand
            // dual-wield is unreachable for LONGSWORD (canBePlacedOffhand(false)
            // in Epic Fight's own preset), so evacuateToLeft is effectively
            // always driven by hasCompetingIdleWeapon alone — kept in this
            // form for consistency and in case that restriction changes later.
            boolean offhandIsLongsword = EpicFightCapabilities.getItemStackCapability(longswordPlayer.getOffhandItem())
                    .getWeaponCategory() == CapabilityItem.WeaponCategories.LONGSWORD;
            boolean evacuateToLeft = !offhandIsLongsword
                    && hasCompetingIdleWeapon(longswordPlayer, CapabilityItem.WeaponCategories.LONGSWORD);

            boolean useWaist = longswordAtWaist(stack);
            OpenMatrix4f baseMatrix = resolveSwordBaseMatrix(renderer, entitypatch, humanoidArmature, poses, useWaist, evacuateToLeft);
            String offsetKey = evacuateToLeft
                    ? (useWaist ? "longsword_offhand" : "longsword_offhand_back")
                    : (useWaist ? "longsword" : "longsword_back");

            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                    Minecraft.getInstance().getItemRenderer();
            renderSwordBody(offsetKey, stack, baseMatrix, postStack, buffer, packedLight, itemRenderer);
        } else if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory()
                == CapabilityItem.WeaponCategories.DAGGER
                && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature
                && isChestAlreadyOccupied(entitypatch, humanoidArmature)
                && entitypatch.getOriginal() instanceof Player daggerPlayer) {
            // A competing idle dagger in slot0 would otherwise collide with
            // this held one at the same thighR position — evacuate the held
            // one to thighL instead (dagger has no WAIST/BACK mode to switch
            // between, unlike tachi/katana). Off-hand-is-dagger (genuine
            // dual-wield) is excluded: thighL is already the off-hand's own
            // position in that case, and must not be confused with this.
            boolean offhandIsDagger = EpicFightCapabilities.getItemStackCapability(daggerPlayer.getOffhandItem())
                    .getWeaponCategory() == CapabilityItem.WeaponCategories.DAGGER;
            boolean evacuateToLeftThigh = !offhandIsDagger
                    && hasCompetingIdleWeapon(daggerPlayer, CapabilityItem.WeaponCategories.DAGGER);
            if (evacuateToLeftThigh) {
                renderDaggerAtWaist("dagger_offhand", stack, daggerPlayer, postStack, buffer, packedLight, poses, humanoidArmature.thighL);
            } else {
                renderDaggerAtWaist("dagger", stack, daggerPlayer, postStack, buffer, packedLight, poses, humanoidArmature.thighR);
            }
        } else {
            renderNativeItemInHand(renderer, stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
        }
    }

    // True when the held-weapon ladder-climb handling in the two
    // renderItemInHand redirects must NOT run for this item:
    //  - it is not on the body allowlist, or
    //  - its renderer is Avalon's mesh_item: that renderer draws a real 3D mesh
    //    (and handles the moved-to-back Tool_R bone itself), whereas every
    //    branch below can only draw the flat item icon via renderStatic.
    private static boolean bypassClimbHandling(RenderItemBase renderer, ItemStack stack) {
        return !SheathAllowlist.isAllowed(stack)
                || MeshWeaponIdleRenderer.isAvalonMeshRenderer(renderer);
    }

    // The bypassed render: not-allowlisted items go through renderNativeItemInHand
    // (unchanged dagger scale tweak etc.), Avalon mesh items through Epic
    // Fight's own renderItemInHand untouched.
    @SuppressWarnings({"rawtypes", "unchecked"})
    // C_chest · C_tool⁻¹ of Epic Fight's default item renderer: what turns a weapon held at
    // Tool_R into the same weapon carried the way Epic Fight carries a plain item on the Chest
    // (e.g. its spear) — see MeshWeaponIdleRenderer's EF_STYLE. Null when unavailable.
    private static OpenMatrix4f computeEfCarryCorrection(HumanoidArmature armature) {
        try {
            RenderItemBase def = ClientEngine.getInstance().renderEngine.getItemRenderer(new ItemStack(net.minecraft.world.item.Items.AIR));
            Map<String, OpenMatrix4f> perItem = ((RenderItemBaseAccessor) def).getMainhandCorrectionTransforms();
            Map<String, OpenMatrix4f> global = RenderItemBaseAccessor.getGlobalMainhandItemTransforms();
            OpenMatrix4f chest = perItem.getOrDefault(armature.chest.getName(), global.get(armature.chest.getName()));
            OpenMatrix4f tool = perItem.getOrDefault(armature.toolR.getName(), global.get(armature.toolR.getName()));
            if (chest == null || tool == null) return null;
            OpenMatrix4f result = new OpenMatrix4f(chest);
            OpenMatrix4f toolInv = new OpenMatrix4f(tool);
            toolInv.invert();
            result.mulBack(toolInv);
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderBypassed(
            RenderItemBase renderer, ItemStack stack, LivingEntityPatch entitypatch,
            InteractionHand hand, OpenMatrix4f[] poses, MultiBufferSource buffer,
            PoseStack postStack, int packedLight, float partialTicks) {
        if (MeshWeaponIdleRenderer.isAvalonMeshRenderer(renderer)) {
            // Held allowlisted mesh weapon while climbing a ladder (Epic Fight has
            // routed the main-hand tool bone to the Chest): stow it like the idle
            // display — waist or back per the config — instead of Epic Fight's
            // native render, which would leave the mesh at its bind-pose hand spot.
            if (hand == InteractionHand.MAIN_HAND) {
                MeshWeaponIdleRenderer.logLadderState("bypassed", entitypatch, stack,
                        entitypatch.getArmature() instanceof HumanoidArmature h && isChestAlreadyOccupied(entitypatch, h));
            }
            // The off-hand weapon (dual wield, e.g. the Exile Blade pair) is stowed too, at the left
            // position; Epic Fight may route either hand's tool bone to the Chest.
            if (SheathAllowlist.isAllowed(stack)
                    && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature
                    && MeshWeaponIdleRenderer.climbingStowed(entitypatch, stack,
                            isChestAlreadyOccupied(entitypatch, humanoidArmature)
                            || entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND) == humanoidArmature.chest)
                    && MeshWeaponIdleRenderer.tryRender(stack, (Entity) entitypatch.getOriginal(), postStack, buffer, packedLight,
                            poses, humanoidArmature, poses[humanoidArmature.chest.getId()],
                            () -> computeEfCarryCorrection(humanoidArmature), hand == InteractionHand.OFF_HAND, true)) {
                return;
            }
            renderer.renderItemInHand(stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
            // Held: the mesh's own waist scabbard (co_tachi) still clanks against blocks.
            if (hand == InteractionHand.MAIN_HAND
                    && SheathAllowlist.isAllowed(stack)
                    && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature) {
                MeshWeaponIdleRenderer.checkHeldScabbardCollision(
                        stack, (Entity) entitypatch.getOriginal(), postStack, poses, humanoidArmature);
                // Blades with a separate box sheath: the empty sheath stays on the body.
                MeshWeaponIdleRenderer.drawHeldSheath(
                        stack, (Entity) entitypatch.getOriginal(), postStack, buffer, packedLight, poses, humanoidArmature);
            }
        } else {
            renderNativeItemInHand(renderer, stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
        }
    }

    // Held katana's ladder-climbing evacuation position when it competes with
    // a slot0 katana (see redirectMainHandRenderItemInHand) — a fixed
    // left-belt (Torso-bone) position, independent of Config.sheatheUchigatanaAtWaist,
    // same concept as vanilla sword's/dagger's offhand position. The body is
    // directly tuned via "uchigatana_offhand"; the sheath auto-follows via
    // the same katanaSheathRelativeOffset used by the normal WAIST-mode
    // katana sheath (caller must call ensureKatanaSheathOffsetCached first)
    // — that relative offset is a fixed local blade-to-sheath transform
    // (see computeSheathRelativeOffset), independent of where the blade's
    // own torsoMatrix+rotation places it in world space, so it applies
    // equally well here without needing its own separate tuning key.
    private static void renderKatanaLeftBeltEvacuation(
            ItemStack stack, Entity entity, HumanoidArmature humanoidArmature, OpenMatrix4f[] poses,
            PoseStack postStack, MultiBufferSource buffer, int packedLight) {
        net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                Minecraft.getInstance().getItemRenderer();
        int torsoId = humanoidArmature.torso.getId();

        TuneableOffset bodyOff = TuneableOffset.REGISTRY.get("uchigatana_offhand");
        float tx   = bodyOff != null ? bodyOff.tx   : 0.0f;
        float ty   = bodyOff != null ? bodyOff.ty   : 0.0f;
        float tz   = bodyOff != null ? bodyOff.tz   : 0.0f;
        float rotX = bodyOff != null ? bodyOff.rotX : 0.0f;
        float rotY = bodyOff != null ? bodyOff.rotY : 0.0f;
        float rotZ = bodyOff != null ? bodyOff.rotZ : 0.0f;

        OpenMatrix4f bodyMatrix = new OpenMatrix4f(poses[torsoId]);
        bodyMatrix.translate(tx, ty, tz);

        postStack.pushPose();
        MathUtils.mulStack(postStack, bodyMatrix);
        postStack.mulPose(Axis.XP.rotationDegrees(rotX));
        postStack.mulPose(Axis.YP.rotationDegrees(rotY));
        postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));
        itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);

        if (katanaSheathRelativeOffset != null) {
            ItemStack sheathStack = new ItemStack(EpicFightItems.UCHIGATANA_SHEATH.get());
            postStack.pushPose();
            MathUtils.mulStack(postStack, katanaSheathRelativeOffset);
            SheathCollisionSystem.checkAndPlaySound(entity, "uchigatana", postStack);
            itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
            postStack.popPose();
        }

        postStack.popPose();
    }

    // Native (actually-drawn-in-hand) rendering, scaled down for daggers only
    // via "dagger"/"dagger_offhand".scale (live-tunable — /efsm tune dagger
    // scale <value>, /efsm tune dagger_offhand scale <value>), plus a small
    // manual position/rotation nudge on top of Epic Fight's own hand
    // positioning via "dagger_held"/"dagger_offhand_held" (independent of
    // "dagger"/"dagger_offhand", which only govern the thigh-stowed position)
    // — everything else passes through completely unmodified via the
    // original renderItemInHand call.
    //
    // For daggers, renderItemInHand's own logic is replicated by hand instead
    // of calling it directly — confirmed via decompilation to be exactly
    // `getCorrectionMatrix` -> `MathUtils.mulStack` -> `ItemInHandRenderer.
    // renderItem` — so the nudge/scale can be inserted AFTER the
    // hand-positioning matrix (i.e. purely around the hand's own local
    // origin) instead of wrapping the whole call as an outer transform. The
    // outer-wrap approach was tried first and confirmed broken: it also
    // scaled the hand's translation, pulling the item toward the entity's
    // root instead of just shrinking it in place.
    private static void renderNativeItemInHand(
            RenderItemBase renderer, ItemStack stack, LivingEntityPatch entitypatch,
            InteractionHand hand, OpenMatrix4f[] poses, MultiBufferSource buffer,
            PoseStack postStack, int packedLight, float partialTicks) {
        boolean isDagger = EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory()
                == CapabilityItem.WeaponCategories.DAGGER;
        if (isDagger) {
            boolean isMainHand = hand == InteractionHand.MAIN_HAND;
            TuneableOffset scaleOff = TuneableOffset.REGISTRY.get(isMainHand ? "dagger" : "dagger_offhand");
            float scale = scaleOff != null ? scaleOff.scale : 1.0f;

            TuneableOffset heldOff = TuneableOffset.REGISTRY.get(isMainHand ? "dagger_held" : "dagger_offhand_held");
            float tx   = heldOff != null ? heldOff.tx   : 0.0f;
            float ty   = heldOff != null ? heldOff.ty   : 0.0f;
            float tz   = heldOff != null ? heldOff.tz   : 0.0f;
            float rotX = heldOff != null ? heldOff.rotX : 0.0f;
            float rotY = heldOff != null ? heldOff.rotY : 0.0f;
            float rotZ = heldOff != null ? heldOff.rotZ : 0.0f;

            OpenMatrix4f matrix = renderer.getCorrectionMatrix(entitypatch, hand, poses);
            ItemDisplayContext context = isMainHand
                    ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
            boolean leftHand = !isMainHand;

            postStack.pushPose();
            MathUtils.mulStack(postStack, matrix);
            postStack.translate(tx, ty, tz);
            postStack.mulPose(Axis.XP.rotationDegrees(rotX));
            postStack.mulPose(Axis.YP.rotationDegrees(rotY));
            postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));
            postStack.scale(scale, scale, scale);
            RenderItemBaseAccessor.getItemInHandRenderer().renderItem(
                    (LivingEntity) entitypatch.getOriginal(), stack, context, leftHand, postStack, buffer, packedLight);
            postStack.popPose();
        } else {
            renderer.renderItemInHand(stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
        }
    }

    // ── Off-hand dagger ladder-climb redirect ────────────────────────────────
    //
    // renderLayer's ordinal=1 renderItemInHand call (OFF_HAND) is itself
    // guarded by Epic Fight with `if (entitypatch.isOffhandItemValid())` —
    // it only ever executes when dual-wielding is actually allowed, which is
    // exactly the case the existing `!isOffhandItemValid()` "off-hand on
    // back" block in onRenderLayerTail does NOT cover. Without this redirect,
    // a valid off-hand dagger had no ladder-climbing handling at all (it fell
    // through to Epic Fight's unmodified native rendering), unlike the
    // main-hand dagger above. Mirrors that branch exactly, using
    // "dagger_offhand"/thighL instead of "dagger"/thighR.
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
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
            ordinal = 1
        ),
        remap = false
    )
    private void redirectOffHandRenderItemInHand(
            RenderItemBase renderer,
            ItemStack stack,
            LivingEntityPatch entitypatch,
            InteractionHand hand,
            OpenMatrix4f[] poses,
            MultiBufferSource buffer,
            PoseStack postStack,
            int packedLight,
            float partialTicks) {

        // Same bypass as the main-hand redirect (see bypassClimbHandling).
        if (bypassClimbHandling(renderer, stack)) {
            renderBypassed(renderer, stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
            return;
        }

        if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory()
                == CapabilityItem.WeaponCategories.DAGGER
                && entitypatch.getArmature() instanceof HumanoidArmature humanoidArmature
                && isChestAlreadyOccupied(entitypatch, humanoidArmature)
                && entitypatch.getOriginal() instanceof Player daggerPlayer) {
            renderDaggerAtWaist("dagger_offhand", stack, daggerPlayer, postStack, buffer, packedLight, poses, humanoidArmature.thighL);
        } else if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory()
                == CapabilityItem.WeaponCategories.SWORD
                && entitypatch.getArmature() instanceof HumanoidArmature swordHumanoidArmature
                && isChestAlreadyOccupied(entitypatch, swordHumanoidArmature)) {
            // Only reached while genuinely dual-wielding (this ordinal=1 call
            // is itself guarded by Epic Fight's own isOffhandItemValid()
            // check) — always the left position, no evacuation needed.
            boolean useWaist = swordAtWaist(stack);
            OpenMatrix4f baseMatrix = resolveSwordBaseMatrix(renderer, entitypatch, swordHumanoidArmature, poses, useWaist, true);
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                    Minecraft.getInstance().getItemRenderer();
            renderSwordBody(useWaist ? "sword_offhand" : "sword_offhand_back", stack, baseMatrix,
                    postStack, buffer, packedLight, itemRenderer);
        } else if (EpicFightCapabilities.getItemStackCapability(stack).getWeaponCategory()
                == CapabilityItem.WeaponCategories.LONGSWORD
                && entitypatch.getArmature() instanceof HumanoidArmature longswordHumanoidArmature
                && isChestAlreadyOccupied(entitypatch, longswordHumanoidArmature)) {
            // Exact duplicate of the SWORD branch above — unreachable in
            // current gameplay (canBePlacedOffhand(false)), kept for
            // consistency and in case that restriction changes later.
            boolean useWaist = longswordAtWaist(stack);
            OpenMatrix4f baseMatrix = resolveSwordBaseMatrix(renderer, entitypatch, longswordHumanoidArmature, poses, useWaist, true);
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer =
                    Minecraft.getInstance().getItemRenderer();
            renderSwordBody(useWaist ? "longsword_offhand" : "longsword_offhand_back", stack, baseMatrix,
                    postStack, buffer, packedLight, itemRenderer);
        } else {
            renderNativeItemInHand(renderer, stack, entitypatch, hand, poses, buffer, postStack, packedLight, partialTicks);
        }
    }

    // Thigh-bone (matches the dagger sheath — see renderDaggerSheathAtThigh)
    // idle/stowed dagger body render. registryKey/thighJoint pairing follows
    // the same main-hand ("dagger"/thighR) vs off-hand ("dagger_offhand"/
    // thighL) split as the sheath.
    private static void renderDaggerAtWaist(
            String registryKey, ItemStack stack, Player player,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            OpenMatrix4f[] poses, Joint thighJoint) {
        TuneableOffset off = TuneableOffset.REGISTRY.get(registryKey);
        float tx    = off != null ? off.tx    : 0.0f;
        float ty    = off != null ? off.ty    : 0.0f;
        float tz    = off != null ? off.tz    : 0.0f;
        float rotX  = off != null ? off.rotX  : 0.0f;
        float rotY  = off != null ? off.rotY  : 0.0f;
        float rotZ  = off != null ? off.rotZ  : 0.0f;
        float scale = off != null ? off.scale : 1.0f;

        int thighId = thighJoint.getId();
        OpenMatrix4f thighMatrix = new OpenMatrix4f(poses[thighId]);
        thighMatrix.translate(tx, ty, tz);

        postStack.pushPose();
        MathUtils.mulStack(postStack, thighMatrix);
        postStack.mulPose(Axis.XP.rotationDegrees(rotX));
        postStack.mulPose(Axis.YP.rotationDegrees(rotY));
        postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));
        postStack.scale(scale, scale, scale);
        RenderItemBaseAccessor.getItemInHandRenderer().renderItem(
                player, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                false, postStack, buffer, packedLight);
        postStack.popPose();
    }

    /**
     * Renders the dagger's thigh sheaths (HumanoidArmature.thighR / thighL),
     * completely independently of whether the dagger itself is physically
     * held — mirroring renderDetachedSheath's "always shown" behavior for
     * Tachi. Main-hand dagger (hotbar slot 0) uses thighR / "dagger_sheath_right"
     * (independently tuned); off-hand dagger uses thighL, following
     * "dagger_sheath_right"'s confirmed offset from "dagger" and reapplying
     * it onto "dagger_offhand" — see renderDaggerSheathLeftFollowing.
     *
     * The thighR sheath is also shown for a held (not slot0) main-hand dagger
     * that ISN'T being evacuated to thighL (see below) — e.g. holding a
     * dagger from a non-zero slot while slot0 has some other, non-dagger
     * weapon. Without this, the held dagger's sheath would vanish entirely
     * whenever slot0 doesn't itself hold a dagger.
     *
     * The thighL sheath is also shown — always, not just while ladder-climbing
     * — for a held (not slot0) main-hand dagger that competes with a slot0
     * dagger (see redirectMainHandRenderItemInHand, whose thighR/thighL body
     * switch IS climbing-only, since only then does the held body attempt to
     * render at the thigh at all). The sheath itself must stay
     * held-state-independent like every other dagger sheath here, so gating
     * it on climbing would make it pop in/out of existence for no visible
     * reason. Not shown when the off-hand IS a dagger (genuine dual-wield
     * already covers thighL; evacuation must not double up with it).
     */
    private static void renderDaggerSheaths(
            Player player, PoseStack postStack, MultiBufferSource buffer, int packedLight,
            OpenMatrix4f[] poses, HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        ItemStack idleDagger = findIdleStack(player, CapabilityItem.WeaponCategories.DAGGER);
        boolean slot0IsDagger = !idleDagger.isEmpty()
                && !sheathSpotTakenByUnsheathed(player, CapabilityItem.WeaponCategories.DAGGER, idleDagger);

        // Held / off-hand daggers only get a sheath when sheath-allowlisted.
        boolean offhandIsDagger = SheathAllowlist.isSheathAllowed(player.getOffhandItem())
                && EpicFightCapabilities.getItemStackCapability(player.getOffhandItem()).getWeaponCategory()
                == CapabilityItem.WeaponCategories.DAGGER;
        boolean mainHandIsDagger = SheathAllowlist.isSheathAllowed(player.getMainHandItem())
                && EpicFightCapabilities.getItemStackCapability(player.getMainHandItem()).getWeaponCategory()
                == CapabilityItem.WeaponCategories.DAGGER;
        boolean evacuatingHeldDagger = !offhandIsDagger
                && mainHandIsDagger
                && hasCompetingIdleWeapon(player, CapabilityItem.WeaponCategories.DAGGER);

        if (slot0IsDagger || (mainHandIsDagger && !evacuatingHeldDagger)) {
            renderDaggerSheathAtThigh("dagger_sheath_right", humanoidArmature.thighR,
                    poses, postStack, buffer, packedLight, itemRenderer);
        }

        // A second idle dagger (dual-wieldable category) also uses the left slot.
        boolean secondIdleDagger = !findSecondIdleStack(player, CapabilityItem.WeaponCategories.DAGGER).isEmpty();

        if (offhandIsDagger || evacuatingHeldDagger || secondIdleDagger) {
            renderDaggerSheathLeftFollowing(humanoidArmature.thighL,
                    poses, postStack, buffer, packedLight, itemRenderer);
        }
    }

    // Builds an Identity-based local matrix (translate then X/Y/Z rotate, same
    // order as the postStack sequence elsewhere in this class) directly from a
    // TuneableOffset entry, with no bone-pose dependency.
    private static OpenMatrix4f buildOffsetMatrix(String key) {
        TuneableOffset off = TuneableOffset.REGISTRY.get(key);
        float tx   = off != null ? off.tx   : 0.0f;
        float ty   = off != null ? off.ty   : 0.0f;
        float tz   = off != null ? off.tz   : 0.0f;
        float rotX = off != null ? off.rotX : 0.0f;
        float rotY = off != null ? off.rotY : 0.0f;
        float rotZ = off != null ? off.rotZ : 0.0f;

        OpenMatrix4f matrix = new OpenMatrix4f();
        matrix.translate(tx, ty, tz);
        matrix.rotateDeg(rotX, Vec3f.X_AXIS);
        matrix.rotateDeg(rotY, Vec3f.Y_AXIS);
        matrix.rotateDeg(rotZ, Vec3f.Z_AXIS);
        return matrix;
    }

    // inverse(dagger) × dagger_sheath_right, derived once directly from the
    // two TuneableOffset entries (no bone pose involved — the relationship is
    // fixed regardless of where the thigh bone actually is). Reapplied as-is
    // (no X mirroring) onto the off-hand body position in
    // renderDaggerSheathLeftFollowing: thighR/thighL share the same local
    // axis orientation, only their own base offset ("dagger" vs
    // "dagger_offhand") differs in sign to mirror sides.
    private static OpenMatrix4f daggerSheathRelativeOffset;

    private static void ensureDaggerSheathOffsetCached() {
        if (daggerSheathRelativeOffset == null) {
            OpenMatrix4f relative = buildOffsetMatrix("dagger");
            relative.invert();
            relative.mulBack(buildOffsetMatrix("dagger_sheath_right"));
            daggerSheathRelativeOffset = relative;
        }
    }

    // Off-hand dagger sheath, positioned by following the right-thigh
    // sheath's confirmed offset from its own body, reapplied on top of the
    // off-hand body's own ("dagger_offhand") position — mirrors how
    // WAIST-mode tachi's sheath follows the blade via a cached relative
    // offset. No separate "dagger_sheath_left" TuneableOffset needed.
    private static void renderDaggerSheathLeftFollowing(
            Joint thighJoint, OpenMatrix4f[] poses,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        ensureDaggerSheathOffsetCached();

        OpenMatrix4f localMatrix = buildOffsetMatrix("dagger_offhand");
        localMatrix.mulBack(daggerSheathRelativeOffset);

        int thighId = thighJoint.getId();
        OpenMatrix4f thighMatrix = new OpenMatrix4f(poses[thighId]);
        thighMatrix.mulBack(localMatrix);

        postStack.pushPose();
        MathUtils.mulStack(postStack, thighMatrix);

        ItemStack sheathStack = new ItemStack(Epic_fight_sheathing_master.DAGGER_SHEATH.get());
        itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    private static void renderDaggerSheathAtThigh(
            String offsetKey, Joint thighJoint, OpenMatrix4f[] poses,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        TuneableOffset off = TuneableOffset.REGISTRY.get(offsetKey);
        float tx   = off != null ? off.tx   : 0.0f;
        float ty   = off != null ? off.ty   : 0.0f;
        float tz   = off != null ? off.tz   : 0.0f;
        float rotX = off != null ? off.rotX : 0.0f;
        float rotY = off != null ? off.rotY : 0.0f;
        float rotZ = off != null ? off.rotZ : 0.0f;

        int thighId = thighJoint.getId();
        OpenMatrix4f thighMatrix = new OpenMatrix4f(poses[thighId]);
        thighMatrix.translate(tx, ty, tz);

        postStack.pushPose();
        MathUtils.mulStack(postStack, thighMatrix);
        postStack.mulPose(Axis.XP.rotationDegrees(rotX));
        postStack.mulPose(Axis.YP.rotationDegrees(rotY));
        postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));

        ItemStack sheathStack = new ItemStack(Epic_fight_sheathing_master.DAGGER_SHEATH.get());
        itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    // ── Vanilla sword (SWORD category) ────────────────────────────────────────
    //
    // Unlike dagger (two distinct thigh bones) or tachi/katana (mode-switch
    // between WAIST/BACK), sword shares ONE bone per mode (Torso for WAIST,
    // Chest for BACK) between both left/right positions — the split is purely
    // a tx sign difference between "sword"/"sword_offhand" (and their "_back"
    // counterparts). Genuine dual-wield (offhand also SWORD) uses the left
    // position permanently; a single held sword competing with a slot0 sword
    // during ladder-climbing evacuates to the left position instead (mirrors
    // dagger's thighL evacuation, but via tx sign instead of a distinct bone).

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpenMatrix4f resolveChestCorrectionViaMainHand(
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            HumanoidArmature humanoidArmature, OpenMatrix4f[] poses) {
        Joint saved = entitypatch.getParentJointOfHand(InteractionHand.MAIN_HAND);
        entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, humanoidArmature.chest);
        OpenMatrix4f matrix = new OpenMatrix4f(renderer.getCorrectionMatrix(
                entitypatch, InteractionHand.MAIN_HAND, poses));
        entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, saved);
        return matrix;
    }

    // Epic Fight's own GLOBAL_OFFHAND_ITEM_TRANSFORMS."Chest" entry is a
    // distinct, already-mirrored matrix from GLOBAL_MAINHAND_ITEM_TRANSFORMS.
    // "Chest" (confirmed via decompilation: translation.x and several
    // rotation components are sign-flipped) — Epic Fight already solves the
    // BACK-mode left/right split natively. Used for the LEFT (offhand) side's
    // BACK-mode base matrix instead of reusing the main-hand one with a
    // manual tx flip, which would double up on (and, with a nonzero manual
    // rotation, fight against) the native mirroring.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpenMatrix4f resolveChestCorrectionViaOffHand(
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            HumanoidArmature humanoidArmature, OpenMatrix4f[] poses) {
        Joint saved = entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);
        entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, humanoidArmature.chest);
        OpenMatrix4f matrix = new OpenMatrix4f(renderer.getCorrectionMatrix(
                entitypatch, InteractionHand.OFF_HAND, poses));
        entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, saved);
        return matrix;
    }

    // WAIST mode: Torso bone pose directly (no native left/right split exists
    // at this shared bone, so the caller's tx-sign TuneableOffset does that
    // job — see the "sword"/"sword_offhand" registry comment). BACK mode:
    // Epic Fight's own native mainhand/offhand Chest correction, selected by
    // isLeftSide (all vanilla sword tiers share the same item_skin-less
    // global Chest correction — see mainhandCorrectionTransforms fallback —
    // so any sword-category renderer yields the same result regardless of
    // which specific stack it came from).
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpenMatrix4f resolveSwordBaseMatrix(
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            HumanoidArmature humanoidArmature, OpenMatrix4f[] poses,
            boolean useWaist, boolean isLeftSide) {
        if (useWaist) {
            return new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
        }
        return isLeftSide
                ? resolveChestCorrectionViaOffHand(renderer, entitypatch, humanoidArmature, poses)
                : resolveChestCorrectionViaMainHand(renderer, entitypatch, humanoidArmature, poses);
    }

    private static void renderSwordBody(
            String offsetKey, ItemStack stack, OpenMatrix4f baseMatrix,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        renderSwordBody(offsetKey, stack, baseMatrix, postStack, buffer, packedLight, itemRenderer, null, null);
    }

    // collisionTag != null: also run the block-collision/sound check for this
    // body (SheathHitboxProfile tag) at its final render transform.
    private static void renderSwordBody(
            String offsetKey, ItemStack stack, OpenMatrix4f baseMatrix,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer,
            Entity collisionEntity, String collisionTag) {
        renderSwordBody(offsetKey, stack, stack, baseMatrix, postStack, buffer, packedLight, itemRenderer,
                collisionEntity, collisionTag);
    }

    // drawStack != stack: the item's own sheath, drawn exactly where the blade (stack) is drawn (same position,
    // same per-item nudge), see renderLongswordSheaths.
    private static void renderSwordBody(
            String offsetKey, ItemStack stack, ItemStack drawStack, OpenMatrix4f baseMatrix,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer,
            Entity collisionEntity, String collisionTag) {
        TuneableOffset off = TuneableOffset.REGISTRY.get(offsetKey);
        float tx   = off != null ? off.tx   : 0.0f;
        float ty   = off != null ? off.ty   : 0.0f;
        float tz   = off != null ? off.tz   : 0.0f;
        float rotX = off != null ? off.rotX : 0.0f;
        float rotY = off != null ? off.rotY : 0.0f;
        float rotZ = off != null ? off.rotZ : 0.0f;

        OpenMatrix4f matrix = new OpenMatrix4f(baseMatrix);
        matrix.translate(tx, ty, tz);

        postStack.pushPose();
        MathUtils.mulStack(postStack, matrix);
        postStack.mulPose(Axis.XP.rotationDegrees(rotX));
        postStack.mulPose(Axis.YP.rotationDegrees(rotY));
        postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));
        applyItemModeNudge(postStack, stack, offsetKey);
        if (collisionTag != null) {
            SheathCollisionSystem.checkAndPlaySound(collisionEntity, collisionTag, postStack);
        }
        itemRenderer.renderStatic(drawStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    /**
     * Per-item nudge for the sword / longsword / greatsword-waist bodies (renderSwordBody), on top of the category's
     * own offset: "<namespace>.<item id>_back" in BACK mode (the category key ends in "_back"), "_waist" otherwise;
     * the off-hand copies (category key contains "offhand") first try the same key + "_off".
     */
    private static void applyItemModeNudge(PoseStack postStack, ItemStack stack, String categoryKey) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return;
        String base = id.getNamespace() + "." + id.getPath() + (categoryKey.endsWith("_back") ? "_back" : "_waist");
        TuneableOffset off = null;
        if (categoryKey.contains("offhand")) off = TuneableOffset.REGISTRY.get(base + "_off");
        if (off == null) off = TuneableOffset.REGISTRY.get(base);
        applyOffset(postStack, off, false);
    }

    // inverse(sword_offhand) × sword_sheath — the sheath's fixed local offset
    // from the offhand blade, tuned once in WAIST mode. This is reused as-is
    // for BOTH modes and both sides: the physical sheath-to-blade
    // relationship doesn't change depending on which body-fixed bone the
    // blade happens to be mounted on (Torso for WAIST, Chest for BACK) — see
    // resolveSwordBaseMatrix — so BACK mode does not need (and no longer
    // has) its own separate "sword_sheath_back" tuning; it just reapplies
    // this same delta onto "sword_offhand_back"/"sword_back". Recomputed
    // every call rather than cached: this is cheap TuneableOffset-only math
    // with no bone pose dependency, so there's no reason to pay a staleness
    // cost (a static cache would go stale the moment /efsm tune changes
    // "sword_offhand"/"sword_sheath" afterward) for the saved computation.
    private static OpenMatrix4f computeSwordSheathRelativeOffset() {
        OpenMatrix4f relative = buildOffsetMatrix("sword_offhand");
        relative.invert();
        relative.mulBack(buildOffsetMatrix("sword_sheath"));
        return relative;
    }

    // bodyOffsetKey selects which body position the sheath follows:
    // "sword_offhand"/"sword_offhand_back" for the left sheath, "sword"/
    // "sword_back" for the right sheath (see computeSwordSheathRelativeOffset
    // for why the same relative delta applies to all four).
    private static void renderSwordSheathFollowing(
            Entity entity, OpenMatrix4f baseMatrix, String bodyOffsetKey,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        OpenMatrix4f local = buildOffsetMatrix(bodyOffsetKey);
        local.mulBack(computeSwordSheathRelativeOffset());

        OpenMatrix4f matrix = new OpenMatrix4f(baseMatrix);
        matrix.mulBack(local);

        postStack.pushPose();
        MathUtils.mulStack(postStack, matrix);
        SheathCollisionSystem.checkAndPlaySound(entity, "sword", postStack);
        ItemStack sheathStack = new ItemStack(Epic_fight_sheathing_master.SWORD_SHEATH.get());
        itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    /**
     * Renders the sword's waist/back sheath(es), completely independently of
     * whether a sword is physically held — mirroring renderDaggerSheaths.
     * Right-side sheath shown for slot0-is-sword or a held (non-slot0)
     * main-hand sword that isn't being evacuated; left-side sheath shown for
     * a genuine off-hand sword (dual-wield) or an evacuating held sword.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderSwordSheaths(
            Player player, LivingEntityPatch entitypatch, HumanoidArmature humanoidArmature,
            OpenMatrix4f[] poses, PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        ItemStack slot0 = findIdleStack(player, CapabilityItem.WeaponCategories.SWORD);
        boolean slot0IsSword = !slot0.isEmpty()
                && !sheathSpotTakenByUnsheathed(player, CapabilityItem.WeaponCategories.SWORD, slot0);
        // Held / off-hand swords only get a sheath when sheath-allowlisted.
        boolean offhandIsSword = SheathAllowlist.isSheathAllowed(player.getOffhandItem())
                && EpicFightCapabilities.getItemStackCapability(player.getOffhandItem()).getWeaponCategory()
                == CapabilityItem.WeaponCategories.SWORD;
        boolean mainHandIsSword = SheathAllowlist.isSheathAllowed(player.getMainHandItem())
                && EpicFightCapabilities.getItemStackCapability(player.getMainHandItem()).getWeaponCategory()
                == CapabilityItem.WeaponCategories.SWORD;
        boolean evacuatingHeldSword = !offhandIsSword
                && mainHandIsSword
                && hasCompetingIdleWeapon(player, CapabilityItem.WeaponCategories.SWORD);

        // A second idle sword (dual-wieldable category) also uses the left side.
        ItemStack secondIdleSword = findSecondIdleStack(player, CapabilityItem.WeaponCategories.SWORD);

        boolean showRight = slot0IsSword || (mainHandIsSword && !evacuatingHeldSword);
        boolean showLeft = offhandIsSword || evacuatingHeldSword || !secondIdleSword.isEmpty();
        if (!showRight && !showLeft) return;

        boolean useWaist = Config.sheatheSwordAtWaist;

        // BACK mode needs a different base matrix per side (Epic Fight's own
        // native mainhand/offhand Chest correction already differ — see
        // resolveSwordBaseMatrix) — WAIST mode shares the same Torso pose for
        // both, so resolving a renderer per side there would be needless work.
        if (showRight) {
            OpenMatrix4f rightBase;
            if (useWaist) {
                rightBase = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            } else {
                ItemStack rightSword = slot0IsSword ? slot0 : player.getMainHandItem();
                RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(rightSword);
                rightBase = renderer != null
                        ? resolveChestCorrectionViaMainHand(renderer, entitypatch, humanoidArmature, poses)
                        : null;
            }
            if (rightBase != null) {
                renderSwordSheathFollowing(player, rightBase, useWaist ? "sword" : "sword_back",
                        postStack, buffer, packedLight, itemRenderer);
            }
        }
        if (showLeft) {
            OpenMatrix4f leftBase;
            if (useWaist) {
                leftBase = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            } else {
                ItemStack leftSword = offhandIsSword ? player.getOffhandItem()
                        : !secondIdleSword.isEmpty() ? secondIdleSword : player.getMainHandItem();
                RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(leftSword);
                leftBase = renderer != null
                        ? resolveChestCorrectionViaOffHand(renderer, entitypatch, humanoidArmature, poses)
                        : null;
            }
            if (leftBase != null) {
                renderSwordSheathFollowing(player, leftBase, useWaist ? "sword_offhand" : "sword_offhand_back",
                        postStack, buffer, packedLight, itemRenderer);
            }
        }
    }

    // inverse(longsword_offhand) × longsword_sheath — exact same technique as
    // computeSwordSheathRelativeOffset, see that method's comment.
    private static OpenMatrix4f computeLongswordSheathRelativeOffset() {
        OpenMatrix4f relative = buildOffsetMatrix("longsword_offhand");
        relative.invert();
        relative.mulBack(buildOffsetMatrix("longsword_sheath"));
        return relative;
    }

    // Exact same technique as renderSwordSheathFollowing, see that method's
    // comment.
    private static void renderLongswordSheathFollowing(
            Entity entity, OpenMatrix4f baseMatrix, String bodyOffsetKey,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        OpenMatrix4f local = buildOffsetMatrix(bodyOffsetKey);
        local.mulBack(computeLongswordSheathRelativeOffset());

        OpenMatrix4f matrix = new OpenMatrix4f(baseMatrix);
        matrix.mulBack(local);

        postStack.pushPose();
        MathUtils.mulStack(postStack, matrix);
        SheathCollisionSystem.checkAndPlaySound(entity, "longsword", postStack);
        ItemStack sheathStack = new ItemStack(Epic_fight_sheathing_master.LONGSWORD_SHEATH.get());
        itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    /**
     * Renders the longsword's waist/back sheath(es) — exact duplicate of
     * renderSwordSheaths for CapabilityItem.WeaponCategories.LONGSWORD. The
     * "genuine off-hand dual-wield" side is currently unreachable in
     * gameplay (LONGSWORD has canBePlacedOffhand(false) in Epic Fight's own
     * preset), but the check is kept for consistency and in case that
     * restriction changes later.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderLongswordSheaths(
            Player player, LivingEntityPatch entitypatch, HumanoidArmature humanoidArmature,
            OpenMatrix4f[] poses, PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        ItemStack slot0 = findIdleStack(player, CapabilityItem.WeaponCategories.LONGSWORD);
        boolean slot0IsLongsword = !slot0.isEmpty()
                && !sheathSpotTakenByUnsheathed(player, CapabilityItem.WeaponCategories.LONGSWORD, slot0);
        // Held / off-hand longswords only get a sheath when sheath-allowlisted.
        boolean offhandIsLongsword = SheathAllowlist.isSheathAllowed(player.getOffhandItem())
                && EpicFightCapabilities.getItemStackCapability(player.getOffhandItem()).getWeaponCategory()
                == CapabilityItem.WeaponCategories.LONGSWORD;
        boolean mainHandIsLongsword = SheathAllowlist.isSheathAllowed(player.getMainHandItem())
                && EpicFightCapabilities.getItemStackCapability(player.getMainHandItem()).getWeaponCategory()
                == CapabilityItem.WeaponCategories.LONGSWORD;
        boolean evacuatingHeldLongsword = !offhandIsLongsword
                && mainHandIsLongsword
                && hasCompetingIdleWeapon(player, CapabilityItem.WeaponCategories.LONGSWORD);

        // Currently always EMPTY (Epic Fight's longsword preset has
        // canBePlacedOffhand(false)); kept so this mirrors the sword branch
        // if that restriction ever changes.
        ItemStack secondIdleLongsword = findSecondIdleStack(player, CapabilityItem.WeaponCategories.LONGSWORD);

        boolean showRight = slot0IsLongsword || (mainHandIsLongsword && !evacuatingHeldLongsword);
        boolean showLeft = offhandIsLongsword || evacuatingHeldLongsword || !secondIdleLongsword.isEmpty();
        if (!showRight && !showLeft) return;

        // (waist / back is per weapon: see longswordAtWaist, decided per side below)

        if (showRight) {
            ItemStack rightLongsword = slot0IsLongsword ? slot0 : player.getMainHandItem();
            boolean useWaist = longswordAtWaist(rightLongsword);
            OpenMatrix4f rightBase;
            if (useWaist) {
                rightBase = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            } else {
                RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(rightLongsword);
                rightBase = renderer != null
                        ? resolveChestCorrectionViaMainHand(renderer, entitypatch, humanoidArmature, poses)
                        : null;
            }
            if (rightBase != null) {
                renderLongswordSheathOf(player, rightLongsword, rightBase, useWaist ? "longsword" : "longsword_back",
                        useWaist, postStack, buffer, packedLight, itemRenderer);
            }
        }
        if (showLeft) {
            ItemStack leftLongsword = offhandIsLongsword ? player.getOffhandItem()
                    : !secondIdleLongsword.isEmpty() ? secondIdleLongsword : player.getMainHandItem();
            boolean useWaist = longswordAtWaist(leftLongsword);
            OpenMatrix4f leftBase;
            if (useWaist) {
                leftBase = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            } else {
                RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(leftLongsword);
                leftBase = renderer != null
                        ? resolveChestCorrectionViaOffHand(renderer, entitypatch, humanoidArmature, poses)
                        : null;
            }
            if (leftBase != null) {
                renderLongswordSheathOf(player, leftLongsword, leftBase,
                        useWaist ? "longsword_offhand" : "longsword_offhand_back",
                        useWaist, postStack, buffer, packedLight, itemRenderer);
            }
        }
    }

    // The sheath of one longsword at the position of its body (bodyOffsetKey): the item's own 3D sheath when it has one
    // (ownSheathItem: drawn exactly where the blade is drawn, same per-item nudge, its own collision tag
    // "<namespace>.<item id>_sheath"), else the generic longsword sheath following the body.
    private static void renderLongswordSheathOf(
            Player player, ItemStack longsword, OpenMatrix4f baseMatrix, String bodyOffsetKey, boolean useWaist,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        Item own = ownSheathItem(longsword, useWaist);
        if (own == null) {
            renderLongswordSheathFollowing(player, baseMatrix, bodyOffsetKey, postStack, buffer, packedLight, itemRenderer);
            return;
        }
        String tag = null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(longsword.getItem());
        if (id != null && SheathHitboxProfile.REGISTRY.containsKey(id.getNamespace() + "." + id.getPath() + "_sheath")) {
            tag = id.getNamespace() + "." + id.getPath() + "_sheath";
        }
        renderSwordBody(bodyOffsetKey, longsword, new ItemStack(own), baseMatrix, postStack, buffer, packedLight,
                itemRenderer, player, tag);
    }

    /**
     * Renders the sheath for any category registered in DETACHED_SHEATH_SPECS
     * (currently: Tachi) completely independently of whether the blade itself
     * is physically held. Always computed at the same position the sheath
     * would occupy if the blade were unheld, so drawing/sheathing the weapon
     * never moves or hides it.
     *
     * Resolves slot0's category itself (rather than receiving it as a
     * parameter), since it must run unconditionally from the top of
     * renderSlot0Weapon — before that method's own category resolution and
     * held-state early-return chain — so this lookup can't be shared with the
     * caller. Renders at the category's own configured mode (see
     * useWaistConfig) — this represents the
     * slot0 item's own "let go" display (case 1). See renderHeldDetachedSheath
     * for the actually-held item's independent sheath (cases 2/3), which may
     * render at the opposite mode to avoid overlapping this one.
     *
     * redirectMainHandRenderItemInHand skips its own sheath render for these
     * categories to avoid double-rendering, since this method already covers
     * the held+climbing case every frame.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderDetachedSheath(
            LivingEntityPatch entitypatch,
            Player player,
            PoseStack postStack,
            MultiBufferSource buffer,
            int packedLight,
            OpenMatrix4f[] poses,
            HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        // A sheath for every idle slot holding a weapon of a detached-sheath category (several of one
        // category are all shown). The first one (see findIdleStack) is drawn also while it is the held
        // item; a later one only while it is not held (the held one is drawn by renderHeldDetachedSheath,
        // which may switch it to the opposite mode). Weapons that would get the identical sheath at the
        // identical place (same sheath item and mode) share one drawing.
        int selectedSlot = player.getInventory().selected;
        try {
            for (Map.Entry<CapabilityItem.WeaponCategories, DetachedSheathSpec> entry : DETACHED_SHEATH_SPECS.entrySet()) {
                CapabilityItem.WeaponCategories category = entry.getKey();
                DetachedSheathSpec spec = entry.getValue();

                Set<String> drawn = new java.util.HashSet<>();
                boolean first = true;
                for (int idleSlot : Config.getIdleSlots()) {
                    ItemStack idleStack = player.getInventory().getItem(idleSlot);
                    if (!SheathAllowlist.isSheathAllowed(idleStack)) continue;
                    if (isBackOnlyItem(idleStack)) continue;
                    if (EpicFightCapabilities.getItemStackCapability(idleStack).getWeaponCategory() != category) continue;
                    boolean isFirst = first;
                    first = false;
                    if (!isFirst && idleSlot == selectedSlot) continue;
                    // The held weapon's empty sheath doesn't go where another (unsheathed) idle weapon is drawn;
                    // renderHeldDetachedSheath then draws it on its own (opposite) mode.
                    if (isFirst && sheathSpotTakenByUnsheathed(player, category, idleStack)) continue;

                    RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(idleStack);
                    if (renderer == null) continue;

                    boolean waist = useWaistConfig(idleStack, category);
                    Item own = ownSheathItem(idleStack, waist);
                    if (!drawn.add(waist + ":" + (own == null ? "" : ForgeRegistries.ITEMS.getKey(own)))) continue;

                    SheathCollisionSystem.instanceSlot = idleSlot;
                    renderDetachedSheathAtMode(idleStack, category, spec, renderer, entitypatch, postStack, buffer,
                            packedLight, poses, humanoidArmature, itemRenderer, waist);
                }
            }
        } finally {
            SheathCollisionSystem.instanceSlot = 0;
        }
    }

    /**
     * Renders a detached-sheath category's sheath (WAIST or BACK "wearable"
     * position) using an explicit mode instead of always reading the
     * category's own configured mode (useWaistConfig), so the same geometry can be reused for both the
     * slot0 "let go" display (renderDetachedSheath) and the actually-held
     * item's independent display (renderHeldDetachedSheath), which may need
     * the opposite mode to avoid visually overlapping the former.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderDetachedSheathAtMode(
            ItemStack bladeStack, CapabilityItem.WeaponCategories category, DetachedSheathSpec spec,
            RenderItemBase renderer, LivingEntityPatch entitypatch,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            OpenMatrix4f[] poses, HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer,
            boolean useWaistMode) {

        Item ownSheath = ownSheathItem(bladeStack, useWaistMode);
        if (ownSheath != null) {
            renderOwnSheath(ownSheath, bladeStack, category, spec, renderer, entitypatch, postStack, buffer,
                    packedLight, poses, humanoidArmature, itemRenderer, useWaistMode);
            return;
        }

        // WAIST mode uses the carry-string sheath texture; BACK mode keeps the
        // plain one. Only the item differs — the position math below is unchanged.
        ItemStack sheathStack = new ItemStack(useWaistMode
                ? spec.waistSheathItem().get()
                : spec.backSheathItem().get());

        if (useWaistMode) {
            OpenMatrix4f relativeOffset = ensureDetachedSheathOffsetCached(
                    category, spec, renderer, entitypatch, humanoidArmature, poses);

            TuneableOffset off = TuneableOffset.REGISTRY.get(
                    resolveWaistOffsetKey(bladeStack, category, spec, entitypatch, humanoidArmature));
            float tx   = off != null ? off.tx   : 0.0f;
            float ty   = off != null ? off.ty   : 0.0f;
            float tz   = off != null ? off.tz   : 0.0f;
            float rotX = off != null ? off.rotX : 0.0f;
            float rotY = off != null ? off.rotY : 0.0f;
            float rotZ = off != null ? off.rotZ : 0.0f;

            int torsoId = humanoidArmature.torso.getId();
            OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[torsoId]);
            torsoMatrix.translate(tx, ty, tz);

            postStack.pushPose();
            MathUtils.mulStack(postStack, torsoMatrix);
            postStack.mulPose(Axis.XP.rotationDegrees(rotX));
            postStack.mulPose(Axis.YP.rotationDegrees(rotY));
            postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));

            postStack.pushPose();
            MathUtils.mulStack(postStack, relativeOffset);
            SheathCollisionSystem.checkAndPlaySound((Entity) entitypatch.getOriginal(), "tachi", postStack);
            itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
            postStack.popPose();

            postStack.popPose();
        } else {
            // Absolute Chest-bone (off-hand correction) position + sheathOffsetKey
            // tuning, independent of the blade's own transform.
            //
            // Always the normal, unshifted Chest-bone position: any collision
            // with a competing slot0 item is resolved by the caller choosing
            // the opposite mode instead (see hasCompetingHeldDetachedSheathWeapon
            // usages), not by shifting within this mode.
            Joint savedOffJoint = entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, humanoidArmature.chest);
            OpenMatrix4f matrixOff = new OpenMatrix4f(
                    renderer.getCorrectionMatrix(entitypatch, InteractionHand.OFF_HAND, poses));
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, savedOffJoint);

            TuneableOffset sheathOff = TuneableOffset.REGISTRY.get(spec.sheathOffsetKey());
            float sTx   = sheathOff != null ? sheathOff.tx   : 0.0f;
            float sTy   = sheathOff != null ? sheathOff.ty   : 0.0f;
            float sTz   = sheathOff != null ? sheathOff.tz   : 0.0f;
            float sRotX = sheathOff != null ? sheathOff.rotX : 0.0f;
            float sRotY = sheathOff != null ? sheathOff.rotY : 0.0f;
            float sRotZ = sheathOff != null ? sheathOff.rotZ : 0.0f;

            postStack.pushPose();
            MathUtils.mulStack(postStack, matrixOff);
            postStack.translate(sTx, sTy, sTz);
            postStack.mulPose(Axis.XP.rotationDegrees(sRotX));
            postStack.mulPose(Axis.YP.rotationDegrees(sRotY));
            postStack.mulPose(Axis.ZP.rotationDegrees(sRotZ));

            SheathCollisionSystem.checkAndPlaySound((Entity) entitypatch.getOriginal(), "tachi", postStack);
            itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
            postStack.popPose();
        }
    }

    /**
     * Renders an independent sheath for the item actually held in the main
     * hand, when it's a detached-sheath category (currently: Tachi) and it's
     * NOT the same instance already handled by renderDetachedSheath (i.e. the
     * held slot isn't slot 0). The blade itself is left untouched — it's
     * always rendered by Epic Fight's own pipeline (normal holding) or by
     * redirectMainHandRenderItemInHand (ladder-climbing, using the same
     * mode-switching rule as here); only the "on the body" sheath display
     * needs adding here.
     *
     * Applies uniformly whether or not the player is climbing: competing
     * slot0 item (see hasCompetingHeldDetachedSheathWeapon) → render this
     * sheath at the OPPOSITE mode from the category's own configured mode
     * (useWaistConfig), so it doesn't
     * overlap slot0's own sheath/blade (which always use the configured
     * mode). No competing item → the configured mode is fine as-is.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderHeldDetachedSheath(
            LivingEntityPatch entitypatch,
            Player player,
            PoseStack postStack,
            MultiBufferSource buffer,
            int packedLight,
            OpenMatrix4f[] poses,
            HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        ItemStack heldStack = player.getMainHandItem();
        if (isBackOnlyItem(heldStack)) return;
        if (!SheathAllowlist.isSheathAllowed(heldStack)) return;
        CapabilityItem heldCapability = EpicFightCapabilities.getItemStackCapability(heldStack);
        WeaponCategory rawHeldCategory = heldCapability.getWeaponCategory();
        if (!(rawHeldCategory instanceof CapabilityItem.WeaponCategories heldCategory)) return;
        DetachedSheathSpec spec = DETACHED_SHEATH_SPECS.get(heldCategory);
        if (spec == null) return;

        // The held item's own sheath is already drawn by renderDetachedSheath
        // when it is the idle-slot stack that method picked for this category
        // (with the default idleSlots=[0]: "the held slot is slot 0").
        if (findIdleStack(player, heldCategory) == heldStack
                && !sheathSpotTakenByUnsheathed(player, heldCategory, heldStack)) return;

        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(heldStack);
        if (renderer == null) return;

        boolean hasCompetingSlot0Item = hasCompetingHeldDetachedSheathWeapon(player);
        boolean configuredWaist = useWaistConfig(heldStack, heldCategory);
        boolean useWaistMode = hasCompetingSlot0Item ? !configuredWaist : configuredWaist;

        renderDetachedSheathAtMode(heldStack, heldCategory, spec, renderer, entitypatch, postStack, buffer,
                packedLight, poses, humanoidArmature, itemRenderer, useWaistMode);
    }

    /**
     * Renders an independent sheath for a genuinely-held katana when the
     * off-hand ALSO holds a katana — not currently reachable in gameplay
     * (Epic Fight doesn't support katana dual-wielding yet), added
     * preemptively in case it is added later.
     *
     * While holding a katana normally (off-hand empty), Epic Fight's own
     * native RenderKatana renders a sheath in the off-hand as part of
     * holding it — that's why our own idle-style sheath rendering is
     * otherwise suppressed while the blade is genuinely held (see
     * NATIVE_SHEATH_CATEGORIES). If the off-hand is instead occupied by a
     * second katana blade, that native off-hand sheath has nowhere to
     * render and would vanish entirely. This substitutes our own
     * idle-style sheath (same WAIST/BACK positioning as the not-held
     * display) in its place. Skipped during ladder-climbing, which already
     * has its own dedicated sheath handling
     * (renderKatanaLeftBeltEvacuation / redirectMainHandRenderItemInHand's
     * WAIST branch).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderHeldKatanaDualWieldSheath(
            LivingEntityPatch entitypatch,
            Player player,
            PoseStack postStack,
            MultiBufferSource buffer,
            int packedLight,
            OpenMatrix4f[] poses,
            HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        if (!(player.getMainHandItem().getItem() instanceof UchigatanaItem)) return;
        if (!(player.getOffhandItem().getItem() instanceof UchigatanaItem)) return;
        if (!SheathAllowlist.isSheathAllowed(player.getMainHandItem())
                || !SheathAllowlist.isSheathAllowed(player.getOffhandItem())) return;
        if (isChestAlreadyOccupied(entitypatch, humanoidArmature)) return;

        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(player.getMainHandItem());
        if (renderer == null) return;

        ItemStack sheathStack = new ItemStack(EpicFightItems.UCHIGATANA_SHEATH.get());

        if (Config.sheatheUchigatanaAtWaist) {
            ensureKatanaSheathOffsetCached(renderer, entitypatch, humanoidArmature, poses);
            if (katanaSheathRelativeOffset == null) return;

            TuneableOffset off = TuneableOffset.REGISTRY.get("uchigatana");
            float tx   = off != null ? off.tx   : 0.0f;
            float ty   = off != null ? off.ty   : 0.0f;
            float tz   = off != null ? off.tz   : 0.0f;
            float rotX = off != null ? off.rotX : 0.0f;
            float rotY = off != null ? off.rotY : 0.0f;
            float rotZ = off != null ? off.rotZ : 0.0f;

            int torsoId = humanoidArmature.torso.getId();
            OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[torsoId]);
            torsoMatrix.translate(tx, ty, tz);

            postStack.pushPose();
            MathUtils.mulStack(postStack, torsoMatrix);
            postStack.mulPose(Axis.XP.rotationDegrees(rotX));
            postStack.mulPose(Axis.YP.rotationDegrees(rotY));
            postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));

            postStack.pushPose();
            MathUtils.mulStack(postStack, katanaSheathRelativeOffset);
            SheathCollisionSystem.checkAndPlaySound(player, "uchigatana", postStack);
            itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
            postStack.popPose();

            postStack.popPose();
        } else {
            Joint savedOffJoint = entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, humanoidArmature.chest);
            OpenMatrix4f matrixOff = new OpenMatrix4f(
                    renderer.getCorrectionMatrix(entitypatch, InteractionHand.OFF_HAND, poses));
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, savedOffJoint);

            postStack.pushPose();
            MathUtils.mulStack(postStack, matrixOff);
            SheathCollisionSystem.checkAndPlaySound(player, "uchigatana", postStack);
            itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
            postStack.popPose();
        }
    }

    /**
     * Renders the weapon from hotbar slot 0 at the Chest bone position.
     * Uses its own early-return logic; returning here does not affect the
     * caller's control flow (fishing rod rendering still runs afterwards).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderSlot0Weapon(
            LivingEntityPatch entitypatch,
            Player player,
            PoseStack postStack,
            MultiBufferSource buffer,
            int packedLight,
            OpenMatrix4f[] poses,
            HumanoidArmature humanoidArmature,
            int chestId,
            OpenMatrix4f originalChestPose,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        // Detached-sheath categories (currently: Tachi) always show their
        // sheath, independent of whether the blade itself is currently held.
        // Must run before the held-state early-return chain below.
        renderDetachedSheath(entitypatch, player, postStack, buffer, packedLight,
                poses, humanoidArmature, itemRenderer);

        // Dagger thigh sheaths, same "always shown" behavior as above.
        renderDaggerSheaths(player, postStack, buffer, packedLight,
                poses, humanoidArmature, itemRenderer);

        // Sword waist/back sheaths, same "always shown" behavior as above.
        renderSwordSheaths(player, entitypatch, humanoidArmature, poses,
                postStack, buffer, packedLight, itemRenderer);

        // Longsword waist/back sheaths, same "always shown" behavior as above.
        renderLongswordSheaths(player, entitypatch, humanoidArmature, poses,
                postStack, buffer, packedLight, itemRenderer);

        // Idle weapon bodies: one per idle slot (Config.idleSlots), skipping
        // the slot that is physically in the main hand. Every idle weapon is
        // drawn, also several of the same category (they may overlap at the
        // same position). For a dual-wieldable category (canShowSecondIdle:
        // dagger/sword) the first (lowest slot) uses the main-hand position,
        // the second the off-hand position, the third the main-hand position
        // again and so on (the rank counts the held slot too, so it matches
        // the sheaths — see findSecondIdleStack).
        // Each call is self-contained — its early returns only end that one
        // slot's rendering.
        int selectedSlot = player.getInventory().selected;
        Map<CapabilityItem.WeaponCategories, Integer> idleRank = new HashMap<>();
        try {
            for (int idleSlot : Config.getIdleSlots()) {
                ItemStack idleStack = player.getInventory().getItem(idleSlot);

                // Allowlist: non-listed items get no idle display and don't take
                // part in the rank bookkeeping below.
                if (!SheathAllowlist.isAllowed(idleStack)) continue;

                // Collision state is kept per slot, so several weapons of one kind don't share it.
                SheathCollisionSystem.instanceSlot = idleSlot;

                // Resolve weapon category via Epic Fight's capability system.
                // getItemStackCapability never returns null (falls back to CapabilityItem.EMPTY).
                WeaponCategory rawCategory = EpicFightCapabilities.getItemStackCapability(idleStack).getWeaponCategory();

                // A custom WeaponCategory from another mod (e.g. EFN's Yamato): only the 3D mesh weapons this mod knows
                // are drawn (MeshWeaponIdleRenderer returns false for anything else).
                if (!(rawCategory instanceof CapabilityItem.WeaponCategories category)) {
                    if (idleSlot != selectedSlot) {
                        if (!MeshWeaponIdleRenderer.tryRender(idleStack, player, postStack, buffer,
                                packedLight, poses, humanoidArmature, originalChestPose,
                                () -> computeEfCarryCorrection(humanoidArmature), false, false)) {
                            // Not a 3D mesh weapon: weapons of mods listed in CUSTOM_BACK_NAMESPACES
                            // (Weapons of Miracles) are drawn from their normal item model at the back.
                            renderCustomCategoryBody(entitypatch, idleStack, postStack, buffer, packedLight,
                                    poses, humanoidArmature, chestId, originalChestPose, itemRenderer);
                        }
                    }
                    continue;
                }

                int rank = idleRank.merge(category, 1, Integer::sum) - 1;
                if (idleSlot == selectedSlot) continue;

                boolean leftSide = canShowSecondIdle(category, idleStack) && rank % 2 == 1;

                renderIdleWeaponBody(entitypatch, player, idleStack, category, leftSide, postStack, buffer,
                        packedLight, poses, humanoidArmature, chestId, originalChestPose, itemRenderer);
            }
        } finally {
            SheathCollisionSystem.instanceSlot = 0;
        }
    }

    /**
     * Per-item nudge for the BACK display, on top of the category's own position: looks up the TuneableOffset
     * "<namespace>.<item id>_back" (e.g. "wom.agony_back", tunable with /efsm tune) and applies it if registered.
     */
    private static void applyItemBackNudge(PoseStack postStack, ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return;
        applyOffset(postStack, TuneableOffset.REGISTRY.get(id.getNamespace() + "." + id.getPath() + "_back"), false);
    }

    /** Applies a TuneableOffset; {@code mirror} = the left-hand copy of a right-hand value (x flipped, rotations about Y and Z flipped). */
    private static void applyOffset(PoseStack postStack, TuneableOffset off, boolean mirror) {
        if (off == null) return;
        float sign = mirror ? -1.0f : 1.0f;
        postStack.translate(sign * off.tx, off.ty, off.tz);
        postStack.mulPose(Axis.XP.rotationDegrees(off.rotX));
        postStack.mulPose(Axis.YP.rotationDegrees(sign * off.rotY));
        postStack.mulPose(Axis.ZP.rotationDegrees(sign * off.rotZ));
        if (off.scale != 1.0f) postStack.scale(off.scale, off.scale, off.scale);
    }

    /** The item's own collision profile tag ("<namespace>.<item id>", e.g. "wom.agony") if one is registered, else {@code fallback}. */
    private static String itemHitboxTag(ItemStack stack, String fallback) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return fallback;
        String tag = id.getNamespace() + "." + id.getPath();
        if (SheathHitboxProfile.REGISTRY.containsKey(tag)) return tag;
        // Weapons of Miracles' staffs (every tier) share one model, so one profile: "wom.staff".
        if ("wom".equals(id.getNamespace()) && id.getPath().endsWith("_staff")
                && SheathHitboxProfile.REGISTRY.containsKey("wom.staff")) return "wom.staff";
        return fallback;
    }

    // Mods whose weapons use their own WeaponCategory (not one of Epic Fight's) and are ordinary item models:
    // when idle they are drawn at the Chest bone with Epic Fight's normal "back" correction (same as a greatsword).
    private static final Set<String> CUSTOM_BACK_NAMESPACES = Set.of("wom");

    // Custom-category items drawn at the right thigh bone (thighR) instead of the back (see renderCustomCategoryBody).
    private static final Set<String> THIGH_ITEMS = Set.of("wom:ender_blaster");

    private static boolean isThighItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && THIGH_ITEMS.contains(id.toString());
    }

    /**
     * Idle display of a weapon with a custom (non-Epic-Fight) category from a mod in CUSTOM_BACK_NAMESPACES:
     * the item's own model at the back. Only the base item model is drawn (extras that the mod's own renderer
     * adds while held, such as auras or separate handles, are not).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderCustomCategoryBody(
            LivingEntityPatch entitypatch,
            ItemStack stack,
            PoseStack postStack,
            MultiBufferSource buffer,
            int packedLight,
            OpenMatrix4f[] poses,
            HumanoidArmature humanoidArmature,
            int chestId,
            OpenMatrix4f originalChestPose,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !CUSTOM_BACK_NAMESPACES.contains(id.getNamespace())) return;

        // Ender Blaster (a gun): carried at the right thigh, like the dagger, instead of at the back.
        // Key "wom.ender_blaster_thigh" (/efsm tune wom.ender_blaster_thigh ...).
        if (THIGH_ITEMS.contains(id.toString()) && entitypatch.getOriginal() instanceof Player thighOwner) {
            renderDaggerAtWaist("wom.ender_blaster_thigh", stack, thighOwner, postStack, buffer, packedLight,
                    poses, humanoidArmature.thighR);
            return;
        }

        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(stack);
        if (renderer == null || ((RenderItemBaseAccessor) renderer).getAlwaysInHand()) return;

        Joint savedMainJoint = entitypatch.getParentJointOfHand(InteractionHand.MAIN_HAND);
        OpenMatrix4f matrixMain;
        try {
            // Same ladder handling as the generic path: shift the whole thing when Epic Fight already
            // routed the held weapon to the Chest bone.
            if (isChestAlreadyOccupied(entitypatch, humanoidArmature)) {
                OpenMatrix4f shifted = new OpenMatrix4f(originalChestPose);
                shifted.translate(OVERLAP_TX, OVERLAP_TY, OVERLAP_TZ);
                poses[chestId] = shifted;
            }
            entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, humanoidArmature.chest);
            matrixMain = new OpenMatrix4f(renderer.getCorrectionMatrix(entitypatch, InteractionHand.MAIN_HAND, poses));
        } finally {
            entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, savedMainJoint);
            poses[chestId] = originalChestPose;
        }

        postStack.pushPose();
        MathUtils.mulStack(postStack, matrixMain);
        applyItemBackNudge(postStack, stack);
        String hitboxTag = itemHitboxTag(stack, null);
        if (hitboxTag != null) {
            SheathCollisionSystem.checkAndPlaySound((Entity) entitypatch.getOriginal(), hitboxTag, postStack);
        }
        itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    private static boolean isMoonless(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "wom:moonless".equals(id.toString());
    }

    // Moonless: the item is only the blade (Weapons of Miracles' RenderMoonless draws the handle separately, in the hand,
    // and lets the blade float on its own tool bone). Idle: the blade stays at the back, floats up and down and glides
    // to its place (also from the hand when it is put away, see moonlessGlide); the handle goes to the right hip. Called by
    // the generic Chest path (Moonless is a TACHI item). matrixMain = the Chest correction of the back position.
    private static void renderMoonless(
            Player player, ItemStack blade, OpenMatrix4f matrixMain, PoseStack postStack, MultiBufferSource buffer,
            int packedLight, OpenMatrix4f[] poses, HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        renderMoonlessHandle(postStack, buffer, packedLight, poses, humanoidArmature, itemRenderer);

        // The full back transform of the blade: Chest correction lifted by the float, tachi_back, the item's own nudge.
        OpenMatrix4f lift = new OpenMatrix4f();
        lift.translate(0.0f, moonlessFloat(), 0.0f);
        OpenMatrix4f target = new OpenMatrix4f(matrixMain);
        target.mulFront(lift);
        target.mulBack(buildOffsetMatrix("tachi_back"));
        target.mulBack(buildOffsetMatrix("wom.moonless_back"));
        OpenMatrix4f pose = moonlessGlide(player.getUUID(), target);

        postStack.pushPose();
        MathUtils.mulStack(postStack, pose);
        TuneableOffset own = TuneableOffset.REGISTRY.get("wom.moonless_back");
        if (own != null && own.scale != 1.0f) postStack.scale(own.scale, own.scale, own.scale);
        // The boxes follow the blade, floating and gliding with it ("wom.moonless" in SheathHitboxProfile).
        String hitboxTag = itemHitboxTag(blade, null);
        if (hitboxTag != null) SheathCollisionSystem.checkAndPlaySound(player, hitboxTag, postStack);
        itemRenderer.renderStatic(blade, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    // ── Moonless' glide ─────────────────────────────────────────────────────────────────────────────────────
    // The blade is not drawn straight at its place but eases toward it (exponential smoothing of position, rotation and
    // scale in body space, time based so the frame rate does not matter), in both directions:
    //  - idle: the blade glides to its place at the back (moonlessGlide); from the hand when it was just put away, because
    //    while it is HELD the glide pose is kept at the blade's real place in the hand;
    //  - held, right after it was drawn: Weapons of Miracles' own drawing (blade + handle) is replaced for a moment by ours
    //    (moonlessHeld, from redirectMainHandRenderItemInHand): the blade glides from the back to the hand, the handle is
    //    in the hand at once; once it has arrived, Weapons of Miracles draws the blade again.
    // Note: a mixin class must not have inner classes, so the state lives in plain maps.
    private static final java.util.Map<java.util.UUID, OpenMatrix4f> MOONLESS_POSE = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Long> MOONLESS_NANOS = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> MOONLESS_STATE = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Long> MOONLESS_DRAW_START = new java.util.HashMap<>();
    private static final int MOONLESS_IDLE = 0;
    private static final int MOONLESS_DRAWING = 1;
    private static final int MOONLESS_HELD = 2;
    // The state is stale (the weapon was away for a while): start at the target right away.
    private static final long MOONLESS_STALE_NANOS = 1_500_000_000L;
    // A draw glide that has not arrived by now is cut short.
    private static final long MOONLESS_DRAW_TIMEOUT_NANOS = 1_500_000_000L;

    private static void setMoonlessPose(java.util.UUID id, OpenMatrix4f pose, int state) {
        MOONLESS_POSE.put(id, new OpenMatrix4f(pose));
        MOONLESS_NANOS.put(id, System.nanoTime());
        MOONLESS_STATE.put(id, state);
    }

    private static float moonlessGlideSeconds() {
        TuneableOffset cfg = TuneableOffset.REGISTRY.get("wom.moonless_bob");
        return cfg != null ? cfg.tx : 0.0f;
    }

    // One smoothing step of the pose toward the target; null when they are far apart (teleport, dimension change: no
    // gliding across the world).
    private static OpenMatrix4f moonlessGlideStep(OpenMatrix4f current, OpenMatrix4f target, float dt, float tau) {
        yesman.epicfight.api.utils.math.Vec3f p0 = current.toTranslationVector();
        yesman.epicfight.api.utils.math.Vec3f p1 = target.toTranslationVector();
        float dx = p1.x - p0.x, dy = p1.y - p0.y, dz = p1.z - p0.z;
        if (dx * dx + dy * dy + dz * dz > 16.0f) return null;
        float a = 1.0f - (float) Math.exp(-Math.max(0.0f, dt) / tau);

        org.joml.Quaternionf q0 = OpenMatrix4f.toQuaternion(new OpenMatrix4f(current).removeScale());
        org.joml.Quaternionf q1 = OpenMatrix4f.toQuaternion(new OpenMatrix4f(target).removeScale());
        org.joml.Quaternionf q = q0.slerp(q1, a, new org.joml.Quaternionf());
        yesman.epicfight.api.utils.math.Vec3f s0 = current.toScaleVector();
        yesman.epicfight.api.utils.math.Vec3f s1 = target.toScaleVector();

        OpenMatrix4f rs = OpenMatrix4f.fromQuaternion(q);
        rs.scale(s0.x + (s1.x - s0.x) * a, s0.y + (s1.y - s0.y) * a, s0.z + (s1.z - s0.z) * a);
        OpenMatrix4f result = OpenMatrix4f.createTranslation(p0.x + dx * a, p0.y + dy * a, p0.z + dz * a);
        result.mulBack(rs);
        return result;
    }

    // Idle: the pose to draw the blade at (eases toward the back transform). Marks the weapon as idle, so drawing it later
    // glides from wherever it is now.
    private static OpenMatrix4f moonlessGlide(java.util.UUID id, OpenMatrix4f target) {
        float tau = moonlessGlideSeconds();
        long now = System.nanoTime();
        OpenMatrix4f current = MOONLESS_POSE.get(id);
        Long last = MOONLESS_NANOS.get(id);
        if (current == null || last == null || tau <= 0.0f || now - last > MOONLESS_STALE_NANOS) {
            setMoonlessPose(id, target, MOONLESS_IDLE);
            return target;
        }
        OpenMatrix4f next = moonlessGlideStep(current, target, (now - last) / 1.0e9f, tau);
        if (next == null) next = target;
        setMoonlessPose(id, next, MOONLESS_IDLE);
        return next;
    }

    // The held blade's place in body space (Tool bone pose x the item's Tool_R correction, as RenderMoonless does), or null.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpenMatrix4f moonlessHandBlade(LivingEntityPatch entitypatch, ItemStack blade, OpenMatrix4f[] poses) {
        if (!(entitypatch.getArmature() instanceof yesman.epicfight.model.armature.types.ToolHolderArmature holder)) return null;
        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(blade);
        if (renderer == null) return null;
        OpenMatrix4f correction = ((RenderItemBaseAccessor) renderer).getMainhandCorrectionTransforms().get("Tool_R");
        Joint tool = holder.rightToolJoint();
        if (correction == null || tool == null) return null;
        OpenMatrix4f held = new OpenMatrix4f(correction);
        held.mulFront(poses[tool.getId()]);
        return held;
    }

    // Held (called for every frame the Moonless is drawn in the main hand). Keeps the glide pose at the blade's place in
    // the hand; right after it was drawn from the back, draws blade and handle itself until the blade has arrived. Returns
    // true when it drew them (Weapons of Miracles' own drawing must then be skipped).
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean moonlessHeld(
            ItemStack stack, LivingEntityPatch entitypatch, Player player, OpenMatrix4f[] poses,
            MultiBufferSource buffer, PoseStack postStack, int packedLight) {
        OpenMatrix4f held = moonlessHandBlade(entitypatch, stack, poses);
        if (held == null) return false;

        java.util.UUID id = player.getUUID();
        float tau = moonlessGlideSeconds();
        long now = System.nanoTime();
        Integer state = MOONLESS_STATE.get(id);
        OpenMatrix4f current = MOONLESS_POSE.get(id);
        Long last = MOONLESS_NANOS.get(id);
        if (tau <= 0.0f || state == null || state == MOONLESS_HELD || current == null || last == null
                || now - last > MOONLESS_STALE_NANOS) {
            setMoonlessPose(id, held, MOONLESS_HELD);
            return false;
        }
        if (state == MOONLESS_IDLE) MOONLESS_DRAW_START.put(id, now);

        OpenMatrix4f next = moonlessGlideStep(current, held, (now - last) / 1.0e9f, tau);
        Long start = MOONLESS_DRAW_START.get(id);
        boolean arrived = next == null || (start != null && now - start > MOONLESS_DRAW_TIMEOUT_NANOS);
        if (!arrived) {
            yesman.epicfight.api.utils.math.Vec3f p0 = next.toTranslationVector();
            yesman.epicfight.api.utils.math.Vec3f p1 = held.toTranslationVector();
            float dx = p1.x - p0.x, dy = p1.y - p0.y, dz = p1.z - p0.z;
            org.joml.Quaternionf q0 = OpenMatrix4f.toQuaternion(new OpenMatrix4f(next).removeScale());
            org.joml.Quaternionf q1 = OpenMatrix4f.toQuaternion(new OpenMatrix4f(held).removeScale());
            arrived = dx * dx + dy * dy + dz * dz < 0.0004f && Math.abs(q0.dot(q1)) > 0.9998f;
        }
        if (arrived) {
            setMoonlessPose(id, held, MOONLESS_HELD);
            return false;
        }
        setMoonlessPose(id, next, MOONLESS_DRAWING);

        net.minecraft.client.renderer.entity.ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        // The blade on its way to the hand.
        postStack.pushPose();
        MathUtils.mulStack(postStack, next);
        itemRenderer.renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
        // The handle in the hand at once, where Weapons of Miracles puts it (hand bone x the Tool_R correction, nudged).
        Item handleItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("wom:moonless_handle"));
        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(stack);
        if (handleItem != null && handleItem != net.minecraft.world.item.Items.AIR && renderer != null
                && entitypatch.getArmature() instanceof HumanoidArmature humanoid) {
            OpenMatrix4f correction = ((RenderItemBaseAccessor) renderer).getMainhandCorrectionTransforms().get("Tool_R");
            if (correction != null) {
                OpenMatrix4f handle = new OpenMatrix4f(correction);
                handle.mulFront(poses[humanoid.handR.getId()]);
                handle.translate(-0.001f, -0.02f, -0.08f);
                postStack.pushPose();
                MathUtils.mulStack(postStack, handle);
                itemRenderer.renderStatic(new ItemStack(handleItem), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
                postStack.popPose();
            }
        }
        return true;
    }

    // The float of Moonless' blade, taken from Weapons of Miracles' own idle animation (moonless_idle: the Tool_R bone goes
    // y 0.270 -> 0.193 -> 0.270 over 2.5 s, smoothly: mean 0.232, amplitude 0.0385). The amplitude is "wom.moonless_bob" ty
    // (tunable, /efsm tune wom.moonless_bob ty <value>; 0 = no float). Time: game ticks + partial tick, so it runs at the
    // same pace for everybody and stands still while the game is paused.
    private static final float MOONLESS_FLOAT_PERIOD_SECONDS = 2.5f;

    private static float moonlessFloat() {
        TuneableOffset bob = TuneableOffset.REGISTRY.get("wom.moonless_bob");
        float amplitude = bob != null ? bob.ty : 0.0f;
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (amplitude == 0.0f || level == null) return 0.0f;
        double seconds = (level.getGameTime() + Minecraft.getInstance().getFrameTime()) / 20.0;
        return (float) (amplitude * Math.cos(2.0 * Math.PI * seconds / MOONLESS_FLOAT_PERIOD_SECONDS));
    }

    // Gesetz (a shield) has a separate handle item "wom:gesetz_handle" which Weapons of Miracles draws at the hand while the
    // shield is held (RenderGesetz: Tool_R correction x hand bone, nudged by (-0.001, -0.02, -0.08); the off-hand one uses the
    // left hand bone and display). Idle, the big plate is on the back (the shield loop) and the handle stays at the left
    // wrist, always shown. "wom.gesetz_handle" is a tuneable nudge on top of that. Generic: "<id>_handle" of the shield.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderShieldHandleAtWrist(
            ItemStack shieldStack, RenderItemBase shieldRenderer, OpenMatrix4f[] poses, HumanoidArmature humanoidArmature,
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(shieldStack.getItem());
        if (id == null || !"wom".equals(id.getNamespace())) return;
        Item handleItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id.getNamespace(), id.getPath() + "_handle"));
        if (handleItem == null || handleItem == net.minecraft.world.item.Items.AIR) return;

        OpenMatrix4f correction = ((RenderItemBaseAccessor) shieldRenderer).getMainhandCorrectionTransforms().get("Tool_R");
        if (correction == null) return;
        OpenMatrix4f handle = new OpenMatrix4f(correction);
        handle.mulFront(poses[humanoidArmature.handL.getId()]);
        handle.translate(-0.001f, -0.02f, -0.08f);

        postStack.pushPose();
        MathUtils.mulStack(postStack, handle);
        applyOffset(postStack, TuneableOffset.REGISTRY.get(id.getNamespace() + "." + id.getPath() + "_handle"), false);
        itemRenderer.renderStatic(new ItemStack(handleItem), ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    // Moonless' handle (the item "wom:moonless_handle", which Weapons of Miracles draws at the hand while the weapon is held)
    // at the right hip: Torso bone + the tuneable "wom.moonless_handle", the same technique as a waist-carried blade.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderMoonlessHandle(
            PoseStack postStack, MultiBufferSource buffer, int packedLight,
            OpenMatrix4f[] poses, HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        Item handleItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("wom:moonless_handle"));
        if (handleItem == null || handleItem == net.minecraft.world.item.Items.AIR) return;

        TuneableOffset off = TuneableOffset.REGISTRY.get("wom.moonless_handle");
        float tx   = off != null ? off.tx   : 0.0f;
        float ty   = off != null ? off.ty   : 0.0f;
        float tz   = off != null ? off.tz   : 0.0f;
        float rotX = off != null ? off.rotX : 0.0f;
        float rotY = off != null ? off.rotY : 0.0f;
        float rotZ = off != null ? off.rotZ : 0.0f;

        OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
        torsoMatrix.translate(tx, ty, tz);

        postStack.pushPose();
        MathUtils.mulStack(postStack, torsoMatrix);
        postStack.mulPose(Axis.XP.rotationDegrees(rotX));
        postStack.mulPose(Axis.YP.rotationDegrees(rotY));
        postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));
        itemRenderer.renderStatic(new ItemStack(handleItem), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();
    }

    private static boolean isSatsujin(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "wom:satsujin".equals(id.toString());
    }

    // Satsujin is a katana with a scabbard of its own ("wom:satsujin_sheath", which Weapons of Miracles draws in the LEFT hand
    // while the blade is held). Idle (not held; held, Weapons of Miracles draws both), the blade is drawn at the waist
    // (Torso bone, Epic Fight's "uchigatana" position + "wom.satsujin_waist") or on the back (Epic Fight's Chest correction
    // + "wom.satsujin_back"), per Config.womSatsujinAtWaist, and the scabbard goes on it: the scabbard model hangs
    // downward from its mouth at y 0.08 (to -1.70), the blade points up (-0.62 .. 1.78), so the scabbard is turned half
    // a turn about z (x also flips) and lifted so that its mouth sits at the guard: its tip then meets the blade's tip.
    // "wom.satsujin_sheath" nudges it relative to the blade; the collision boxes of "wom.satsujin_sheath" follow it.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderSatsujin(
            LivingEntityPatch entitypatch, Player player, ItemStack blade, PoseStack postStack, MultiBufferSource buffer,
            int packedLight, OpenMatrix4f[] poses, HumanoidArmature humanoidArmature,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {
        boolean waist = Config.womSatsujinAtWaist;

        postStack.pushPose();
        if (waist) {
            TuneableOffset base = TuneableOffset.REGISTRY.get("uchigatana");
            OpenMatrix4f torso = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            if (base != null) torso.translate(base.tx, base.ty, base.tz);
            MathUtils.mulStack(postStack, torso);
            if (base != null) {
                postStack.mulPose(Axis.XP.rotationDegrees(base.rotX));
                postStack.mulPose(Axis.YP.rotationDegrees(base.rotY));
                postStack.mulPose(Axis.ZP.rotationDegrees(base.rotZ));
            }
            applyOffset(postStack, TuneableOffset.REGISTRY.get("wom.satsujin_waist"), false);
        } else {
            RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(blade);
            if (renderer == null) {
                postStack.popPose();
                return;
            }
            MathUtils.mulStack(postStack, resolveChestCorrectionViaMainHand(renderer, entitypatch, humanoidArmature, poses));
            applyItemBackNudge(postStack, blade);
        }

        itemRenderer.renderStatic(blade, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);

        Item sheathItem = SheathAllowlist.isSheathAllowed(blade)
                ? ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("wom:satsujin_sheath")) : null;
        if (sheathItem != null && sheathItem != net.minecraft.world.item.Items.AIR) {
            postStack.pushPose();
            applyOffset(postStack, TuneableOffset.REGISTRY.get("wom.satsujin_sheath"), false);
            postStack.translate(-0.015f, 0.1f, 0.0f);
            postStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
            SheathCollisionSystem.checkAndPlaySound(player, "wom.satsujin_sheath", postStack);
            itemRenderer.renderStatic(new ItemStack(sheathItem), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
            postStack.popPose();
        }
        postStack.popPose();
    }

    /**
     * Renders one idle-slot weapon's body (the per-slot part of what used to
     * be renderSlot0Weapon's tail). Uses its own early-return logic; returning
     * here only ends this one slot's rendering.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderIdleWeaponBody(
            LivingEntityPatch entitypatch,
            Player player,
            ItemStack slot0,
            CapabilityItem.WeaponCategories category,
            boolean leftSide,
            PoseStack postStack,
            MultiBufferSource buffer,
            int packedLight,
            OpenMatrix4f[] poses,
            HumanoidArmature humanoidArmature,
            int chestId,
            OpenMatrix4f originalChestPose,
            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer) {

        if (SKIP_CATEGORIES.contains(category)) {
            // Fist weapons (FIST) get no body position, but EFN's claws are WORN: drawn on both hands by
            // MeshWeaponIdleRenderer (false for every other fist item, which stays undrawn as before).
            if (category == CapabilityItem.WeaponCategories.FIST) {
                MeshWeaponIdleRenderer.tryRender(slot0, player, postStack, buffer,
                        packedLight, poses, humanoidArmature, originalChestPose,
                        () -> computeEfCarryCorrection(humanoidArmature), false, false);
            }
            return;
        }

        // Avalon mesh_item weapons (EFN): the vanilla item model is only a flat
        // icon, so draw the real 3D mesh instead. true = handled (also when
        // drawing failed — never fall back to the flat icon for these).
        if (MeshWeaponIdleRenderer.tryRender(slot0, player, postStack, buffer,
                packedLight, poses, humanoidArmature, originalChestPose,
                () -> computeEfCarryCorrection(humanoidArmature), leftSide, false)) {
            return;
        }

        // DAGGER uses the thigh bone directly, same as its sheath. leftSide
        // (second idle dagger) uses the off-hand key/thighL, exactly like a
        // genuine off-hand dagger (see redirectMainHandRenderItemInHand).
        if (category == CapabilityItem.WeaponCategories.DAGGER) {
            if (leftSide) {
                renderDaggerAtWaist("dagger_offhand", slot0, player, postStack, buffer, packedLight, poses, humanoidArmature.thighL);
            } else {
                renderDaggerAtWaist("dagger", slot0, player, postStack, buffer, packedLight, poses, humanoidArmature.thighR);
            }
            return;
        }

        // Satsujin (WoM, UCHIGATANA category but not Epic Fight's UchigatanaItem): blade + its own scabbard, waist or back.
        if (isSatsujin(slot0)) {
            renderSatsujin(entitypatch, player, slot0, postStack, buffer, packedLight, poses, humanoidArmature,
                    itemRenderer);
            return;
        }

        // SWORD: idle slot0 sword (not currently held) always shows at the
        // right position — evacuation only applies to the actually-HELD
        // competing sword (see redirectMainHandRenderItemInHand), never to
        // slot0's own idle display.
        if (category == CapabilityItem.WeaponCategories.SWORD) {
            boolean useWaist = swordAtWaist(slot0);
            OpenMatrix4f baseMatrix;
            if (useWaist) {
                baseMatrix = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            } else {
                RenderItemBase swordRenderer = ClientEngine.getInstance().renderEngine.getItemRenderer(slot0);
                if (swordRenderer == null) return;
                baseMatrix = leftSide
                        ? resolveChestCorrectionViaOffHand(swordRenderer, entitypatch, humanoidArmature, poses)
                        : resolveChestCorrectionViaMainHand(swordRenderer, entitypatch, humanoidArmature, poses);
            }
            String swordKey = leftSide
                    ? (useWaist ? "sword_offhand" : "sword_offhand_back")
                    : (useWaist ? "sword" : "sword_back");
            renderSwordBody(swordKey, slot0, baseMatrix,
                    postStack, buffer, packedLight, itemRenderer, player, itemHitboxTag(slot0, null));
            return;
        }

        // LONGSWORD: exact duplicate of the SWORD idle branch above.
        if (category == CapabilityItem.WeaponCategories.LONGSWORD) {
            boolean useWaist = longswordAtWaist(slot0);
            OpenMatrix4f baseMatrix;
            if (useWaist) {
                baseMatrix = new OpenMatrix4f(poses[humanoidArmature.torso.getId()]);
            } else {
                RenderItemBase longswordRenderer = ClientEngine.getInstance().renderEngine.getItemRenderer(slot0);
                if (longswordRenderer == null) return;
                baseMatrix = leftSide
                        ? resolveChestCorrectionViaOffHand(longswordRenderer, entitypatch, humanoidArmature, poses)
                        : resolveChestCorrectionViaMainHand(longswordRenderer, entitypatch, humanoidArmature, poses);
            }
            String longswordKey = leftSide
                    ? (useWaist ? "longsword_offhand" : "longsword_offhand_back")
                    : (useWaist ? "longsword" : "longsword_back");
            renderSwordBody(longswordKey, slot0, baseMatrix,
                    postStack, buffer, packedLight, itemRenderer, player, itemHitboxTag(slot0, null));
            return;
        }

        // GREATSWORD WAIST mode: Torso bone + "greatsword_waist", same body
        // rendering as sword/longsword. BACK mode (default) falls through to
        // the generic Chest path below (with the "greatsword" nudge).
        if (category == CapabilityItem.WeaponCategories.GREATSWORD && Config.sheatheGreatswordAtWaist) {
            renderSwordBody("greatsword_waist", slot0,
                    new OpenMatrix4f(poses[humanoidArmature.torso.getId()]),
                    postStack, buffer, packedLight, itemRenderer, player, "greatsword");
            return;
        }

        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(slot0);
        if (renderer == null) return;

        // Items without a dedicated item_skin JSON fall back to the "air" renderer
        // which has alwaysInHand=true. Skip them unless the category is whitelisted.
        boolean forceOverride = FORCE_SHEATH_CATEGORIES.contains(category);
        if (((RenderItemBaseAccessor) renderer).getAlwaysInHand() && !forceOverride) return;

        // UchigatanaItem (打刀) and TACHI (太刀) both use Torso bone (waist) with manual
        // offsets when their own per-category Config flag is true. When false
        // (BACK mode), skip this block and fall through to the chest-based path.
        boolean isKatana = slot0.getItem() instanceof UchigatanaItem;
        boolean isTachi = category == CapabilityItem.WeaponCategories.TACHI;
        // Back-only items (Nova) never take the tachi WAIST branch below, whatever Config.sheatheTachiAtWaist says.
        boolean isSheathedSword = isKatana || (isTachi && !isBackOnlyItem(slot0));
        // Native categories keep the sheath tied to this early-return chain
        // (only drawn while unheld). Non-native (Tachi) sheath rendering is
        // skipped here entirely — renderDetachedSheath already handled
        // it unconditionally at the top of this method.
        // Also requires the sheath allowlist: a body-only item (on the body
        // list but not the sheath list) draws just the blade, no sheath.
        boolean isNativeSheath = NATIVE_SHEATH_CATEGORIES.contains(category)
                && SheathAllowlist.isSheathAllowed(slot0);

        if (isSheathedSword && (isKatana ? Config.sheatheUchigatanaAtWaist : useWaistConfig(slot0, category))) {
            // No special-casing needed here for a competing held detached-sheath
            // weapon (tachi) during ladder-climbing: redirectMainHandRenderItemInHand
            // already moves that held item to the opposite mode in that case,
            // so it's never at this same Torso position — this idle render is
            // always safe to draw normally.
            if (isNativeSheath) {
                ensureKatanaSheathOffsetCached(renderer, entitypatch, humanoidArmature, poses);
            }

            String waistKey = isKatana
                    ? "uchigatana"
                    : resolveWaistOffsetKey(slot0, category, DETACHED_SHEATH_SPECS.get(category), entitypatch, humanoidArmature);
            TuneableOffset off = TuneableOffset.REGISTRY.get(waistKey);
            float tx   = off != null ? off.tx   : 0.0f;
            float ty   = off != null ? off.ty   : 0.0f;
            float tz   = off != null ? off.tz   : 0.0f;
            float rotX = off != null ? off.rotX : 0.0f;
            float rotY = off != null ? off.rotY : 0.0f;
            float rotZ = off != null ? off.rotZ : 0.0f;

            int torsoId = humanoidArmature.torso.getId();
            OpenMatrix4f torsoMatrix = new OpenMatrix4f(poses[torsoId]);
            torsoMatrix.translate(tx, ty, tz);

            // Apply blade transform to postStack once; sheath reuses it via inner push.
            postStack.pushPose();
            MathUtils.mulStack(postStack, torsoMatrix);
            postStack.mulPose(Axis.XP.rotationDegrees(rotX));
            postStack.mulPose(Axis.YP.rotationDegrees(rotY));
            postStack.mulPose(Axis.ZP.rotationDegrees(rotZ));

            // Blade body at blade transform
            itemRenderer.renderStatic(slot0, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);

            // Sheath at bladeTransform × relativeSheathOffset (auto-derived from Chest
            // matrices) — native categories only; Tachi's sheath was already drawn
            // independently by renderDetachedSheath above.
            if (isNativeSheath) {
                ItemStack sheathStack = new ItemStack(EpicFightItems.UCHIGATANA_SHEATH.get());
                postStack.pushPose();
                MathUtils.mulStack(postStack, katanaSheathRelativeOffset);
                SheathCollisionSystem.checkAndPlaySound(player, "uchigatana", postStack);
                itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
                postStack.popPose();
            }

            postStack.popPose();
            return;
        }

        // When the Chest bone is already occupied, temporarily replace poses[chestId]
        // with a translated copy before computing correction matrices. Both items share
        // the same bone pose origin, so the whole group shifts as a unit.
        //
        // Detached-sheath categories (currently: Tachi) and Katana are
        // excluded: for those, the HELD item mode-switches instead (see
        // redirectMainHandRenderItemInHand), so this idle rendering always
        // stays at its normal, unshifted position.
        if (isChestAlreadyOccupied(entitypatch, humanoidArmature)
                && !DETACHED_SHEATH_SPECS.containsKey(category)
                && !isKatana) {
            OpenMatrix4f shifted = new OpenMatrix4f(originalChestPose);
            shifted.translate(OVERLAP_TX, OVERLAP_TY, OVERLAP_TZ);
            poses[chestId] = shifted;
        }

        // Compute the correction matrices for the chest bone. forceOverride
        // categories (FORCE_SHEATH_CATEGORIES: pickaxe/hoe/shovel) use the "air"
        // renderer with alwaysInHand=true, whose own getCorrectionMatrix()
        // ignores our chest-joint reroute below (alwaysInHand routes to the
        // tool/root joint instead — see RenderItemBase#getCorrectionMatrix) — so
        // for those, look up the Chest correction directly from the renderer's
        // mainhand maps and mulFront with poses[chestId] ourselves, replicating
        // what getCorrectionMatrix() would have done for a normal chest-routed
        // item. Non-forceOverride categories take the normal path: temporarily
        // route the hand joint to the Chest bone so getCorrectionMatrix picks up
        // the Chest correction transform from the renderer's maps.
        // getCorrectionMatrix() returns its internal transformHolder reference —
        // copy with new OpenMatrix4f(...) before the second call, or both
        // variables alias the same (last-written) matrix.
        OpenMatrix4f matrixMain;
        OpenMatrix4f matrixOff = null;

        if (forceOverride) {
            RenderItemBaseAccessor rendererAcc = (RenderItemBaseAccessor) renderer;
            String chestName = humanoidArmature.chest.getName();
            Map<String, OpenMatrix4f> perItemMap = rendererAcc.getMainhandCorrectionTransforms();
            Map<String, OpenMatrix4f> globalMap = RenderItemBaseAccessor.getGlobalMainhandItemTransforms();
            OpenMatrix4f correction = perItemMap.getOrDefault(chestName, globalMap.get(chestName));
            matrixMain = new OpenMatrix4f(correction);
            matrixMain.mulFront(poses[chestId]);
        } else {
            Joint savedMainJoint = entitypatch.getParentJointOfHand(InteractionHand.MAIN_HAND);
            entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, humanoidArmature.chest);
            matrixMain = new OpenMatrix4f(renderer.getCorrectionMatrix(
                    entitypatch, InteractionHand.MAIN_HAND, poses));
            entitypatch.setParentJointOfHand(InteractionHand.MAIN_HAND, savedMainJoint);
        }

        // Only native-sheath categories need matrixOff here — Tachi's sheath is
        // rendered independently by renderDetachedSheath.
        if (isNativeSheath) {
            Joint savedOffJoint = entitypatch.getParentJointOfHand(InteractionHand.OFF_HAND);
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, humanoidArmature.chest);
            matrixOff = new OpenMatrix4f(renderer.getCorrectionMatrix(
                    entitypatch, InteractionHand.OFF_HAND, poses));
            entitypatch.setParentJointOfHand(InteractionHand.OFF_HAND, savedOffJoint);
        }

        poses[chestId] = originalChestPose;

        if (isMoonless(slot0)) {
            renderMoonless(player, slot0, matrixMain, postStack, buffer, packedLight, poses, humanoidArmature, itemRenderer);
            return;
        }

        // ── Weapon body ────────────────────────────────────────────────────────
        postStack.pushPose();
        MathUtils.mulStack(postStack, matrixMain);
        if (isTachi) {
            // Confirmed BACK-mode correction, blade-only — the sheath (drawn
            // separately by renderDetachedSheath above) does not follow this.
            TuneableOffset backOff = TuneableOffset.REGISTRY.get("tachi_back");
            if (backOff != null) {
                postStack.translate(backOff.tx, backOff.ty, backOff.tz);
                postStack.mulPose(Axis.XP.rotationDegrees(backOff.rotX));
                postStack.mulPose(Axis.YP.rotationDegrees(backOff.rotY));
                postStack.mulPose(Axis.ZP.rotationDegrees(backOff.rotZ));
            }
        }
        if (category == CapabilityItem.WeaponCategories.GREATSWORD) {
            // Small manual nudge on top of Epic Fight's native idle position.
            TuneableOffset gsOff = TuneableOffset.REGISTRY.get("greatsword");
            if (gsOff != null) {
                postStack.translate(gsOff.tx, gsOff.ty, gsOff.tz);
                postStack.mulPose(Axis.XP.rotationDegrees(gsOff.rotX));
                postStack.mulPose(Axis.YP.rotationDegrees(gsOff.rotY));
                postStack.mulPose(Axis.ZP.rotationDegrees(gsOff.rotZ));
            }
        }
        // Per-item nudge first, so the collision boxes below follow the weapon where it is actually drawn.
        applyItemBackNudge(postStack, slot0);
        // Block-collision/sound for the weapon body itself (greatsword BACK
        // mode and spear; greatsword WAIST is handled in its own branch above).
        // An item with its own hitbox profile ("<namespace>.<item id>") uses that instead of the category's.
        if (category == CapabilityItem.WeaponCategories.GREATSWORD) {
            SheathCollisionSystem.checkAndPlaySound(player, itemHitboxTag(slot0, "greatsword"), postStack);
        } else if (category == CapabilityItem.WeaponCategories.SPEAR) {
            SheathCollisionSystem.checkAndPlaySound(player, itemHitboxTag(slot0, "spear"), postStack);
        } else {
            // Any other category: only an item with its own profile (e.g. Nova) has a collision check.
            String ownTag = itemHitboxTag(slot0, null);
            if (ownTag != null) SheathCollisionSystem.checkAndPlaySound(player, ownTag, postStack);
        }
        itemRenderer.renderStatic(slot0, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
        postStack.popPose();

        // ── Sheath icon (Uchigatana only) ───────────────────────────────────────
        // Tachi's sheath was already drawn independently by
        // renderDetachedSheath at the top of this method.
        if (isNativeSheath) {
            ItemStack sheathStack = new ItemStack(EpicFightItems.UCHIGATANA_SHEATH.get());
            postStack.pushPose();
            MathUtils.mulStack(postStack, matrixOff);
            SheathCollisionSystem.checkAndPlaySound(player, "uchigatana", postStack);
            itemRenderer.renderStatic(sheathStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    packedLight, OverlayTexture.NO_OVERLAY, postStack, buffer, null, 0);
            postStack.popPose();
        }
    }
}
