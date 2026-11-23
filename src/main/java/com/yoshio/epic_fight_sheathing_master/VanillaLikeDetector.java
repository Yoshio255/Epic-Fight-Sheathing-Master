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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.item.CapabilityItem.WeaponCategories;
import yesman.epicfight.world.capabilities.item.WeaponCategory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only. Decides whether an item on the body list but not on the sheath list
 * is just a "recolour" of a vanilla tool/sword, so it can safely get a sheath
 * like the vanilla one (the body display is not decided here: weapons are shown either way) ({@link Config.AutoAllowMode#EXACT}).
 *
 * <p>An item qualifies when ALL of these hold (anything unclear → no):
 * <ol>
 *   <li>its model JSON is exactly {@code parent: item/handheld} + one
 *       {@code layer0} texture (no 3D elements, overrides, custom loader,
 *       display tweaks or extra layers);</li>
 *   <li>its texture is a plain (not animated) image whose size and set of
 *       non-transparent pixels equal those of one of the vanilla items of one
 *       category (sword / pickaxe / axe / shovel / hoe, all six materials);</li>
 *   <li>it has no colour tint, no custom item renderer and no Epic Fight item
 *       skin;</li>
 *   <li>Epic Fight puts it in that same weapon category.</li>
 * </ol>
 * The reference shapes are read from the resource manager, i.e. they follow
 * resource packs. Everything is computed lazily per item and cached; the caches
 * are dropped by {@link #invalidate()} (resource reload, world join,
 * {@code /efsm reload}). Never throws.
 */
final class VanillaLikeDetector {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<String> MATERIALS =
            List.of("wooden", "stone", "iron", "golden", "diamond", "netherite");

    private static final Map<WeaponCategories, String> REFERENCE_SUFFIX = Map.of(
            WeaponCategories.SWORD, "sword",
            WeaponCategories.PICKAXE, "pickaxe",
            WeaponCategories.AXE, "axe",
            WeaponCategories.SHOVEL, "shovel",
            WeaponCategories.HOE, "hoe");

    private static final Set<String> ALLOWED_MODEL_KEYS = Set.of("parent", "textures", "credit", "comment", "_comment");

    /** Marker for "structurally not a vanilla look-alike". */
    private static final Object NONE = new Object();

    // Item → WeaponCategories (the category whose reference shape it matches) or NONE.
    private static final Map<Item, Object> STRUCTURE = new ConcurrentHashMap<>();
    // Item → final yes/no.
    private static final Map<Item, Boolean> VERDICT = new ConcurrentHashMap<>();
    // Item → last time (ms) Epic Fight's category was asked while it still said "no weapon".
    private static final Map<Item, Long> LAST_RETRY = new ConcurrentHashMap<>();
    private static final Set<Item> LOGGED = ConcurrentHashMap.newKeySet();
    // Category → set of shape keys of the vanilla items; built lazily.
    private static volatile Map<WeaponCategories, Set<String>> references;

    private VanillaLikeDetector() {}

    static void invalidate() {
        STRUCTURE.clear();
        VERDICT.clear();
        LAST_RETRY.clear();
        LOGGED.clear();
        references = null;
    }

    /**
     * @return TRUE / FALSE when decided (the caller may cache it), or null when
     *         it cannot be decided yet (Epic Fight has not told us the item's
     *         weapon category) — ask again later.
     */
    static Boolean decide(Item item, ResourceLocation id) {
        try {
            Boolean done = VERDICT.get(item);
            if (done != null) return done;

            Object structure = STRUCTURE.get(item);
            if (structure == null) {
                structure = analyseStructure(item, id);
                STRUCTURE.put(item, structure);
            }
            if (structure == NONE) return remember(item, false);
            WeaponCategories shape = (WeaponCategories) structure;

            // Cheap structural checks passed (rare) → ask Epic Fight for the category.
            // While it still says "no weapon" (its item data may not have arrived
            // yet) the answer is not final: ask again, but at most once a second.
            long now = System.currentTimeMillis();
            Long last = LAST_RETRY.get(item);
            if (last != null && now - last < 1000L) return null;
            WeaponCategory category = EpicFightCapabilities.getItemStackCapability(new ItemStack(item)).getWeaponCategory();
            if (category == shape) {
                if (LOGGED.add(item)) {
                    LOGGER.info("[efsm] auto-allowed (vanilla look-alike, {}): {}", shape.name().toLowerCase(), id);
                }
                return remember(item, true);
            }
            if (category == WeaponCategories.NOT_WEAPON) {
                LAST_RETRY.put(item, now);
                return null;
            }
            return remember(item, false); // Epic Fight sees something else (e.g. a greatsword)
        } catch (Throwable t) {
            if (LOGGED.add(item)) {
                LOGGER.warn("[efsm] auto-allow check failed for {} ({}); not allowed", id, t.toString());
            }
            return remember(item, false);
        }
    }

    private static Boolean remember(Item item, boolean value) {
        VERDICT.put(item, value);
        return value;
    }

    // ── structure: model JSON, texture shape, tint, renderer ─────────────────

    private static Object analyseStructure(Item item, ResourceLocation id) throws IOException {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();

        ResourceLocation texture = readHandheldTexture(rm, id);
        if (texture == null) return NONE;
        String shape = readShape(rm, texture, true);
        if (shape == null) return NONE;

        WeaponCategories match = null;
        for (Map.Entry<WeaponCategories, Set<String>> e : references().entrySet()) {
            if (e.getValue().contains(shape)) { match = e.getKey(); break; }
        }
        if (match == null) return NONE;

        ItemStack stack = new ItemStack(item);
        // Tinted by an item colour handler (e.g. dyed/potion-like) → not a plain recolour.
        if (Minecraft.getInstance().getItemColors().getColor(stack, 0) != -1) return NONE;
        // Epic Fight item skin / special renderer → drawn differently from a flat icon.
        RenderItemBase renderer = ClientEngine.getInstance().renderEngine.getItemRenderer(stack);
        RenderItemBase fallback = ClientEngine.getInstance().renderEngine.getItemRenderer(new ItemStack(Items.AIR));
        if (renderer != fallback) return NONE;
        return match;
    }

    /**
     * The layer0 texture of a model that is exactly {@code item/handheld} + one
     * texture and nothing else; null for any other model (or none found).
     */
    private static ResourceLocation readHandheldTexture(ResourceManager rm, ResourceLocation id) throws IOException {
        ResourceLocation modelLoc = new ResourceLocation(id.getNamespace(), "models/item/" + id.getPath() + ".json");
        Resource res = rm.getResource(modelLoc).orElse(null);
        if (res == null) return null;
        JsonElement root;
        try (Reader r = res.openAsReader()) {
            root = JsonParser.parseReader(r);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || !root.isJsonObject()) return null;
        JsonObject model = root.getAsJsonObject();
        for (String key : model.keySet()) {
            if (!ALLOWED_MODEL_KEYS.contains(key)) return null; // elements / overrides / loader / display / ...
        }
        if (!model.has("parent") || !model.get("parent").isJsonPrimitive()) return null;
        ResourceLocation parent = ResourceLocation.tryParse(model.get("parent").getAsString());
        if (parent == null || !parent.equals(new ResourceLocation("minecraft", "item/handheld"))) return null;
        if (!model.has("textures") || !model.get("textures").isJsonObject()) return null;
        JsonObject textures = model.getAsJsonObject("textures");
        if (textures.size() != 1 || !textures.has("layer0") || !textures.get("layer0").isJsonPrimitive()) return null;
        String tex = textures.get("layer0").getAsString();
        if (tex.startsWith("#")) return null;
        return ResourceLocation.tryParse(tex);
    }

    /**
     * "WIDTHxHEIGHT:" + one char per pixel ('#' = not fully transparent). Null
     * when the texture is missing, unreadable, or (if {@code rejectAnimated})
     * animated.
     */
    private static String readShape(ResourceManager rm, ResourceLocation texture, boolean rejectAnimated) {
        ResourceLocation png = new ResourceLocation(texture.getNamespace(), "textures/" + texture.getPath() + ".png");
        if (rejectAnimated) {
            ResourceLocation meta = new ResourceLocation(png.getNamespace(), png.getPath() + ".mcmeta");
            if (rm.getResource(meta).isPresent()) return null;
        }
        Resource res = rm.getResource(png).orElse(null);
        if (res == null) return null;
        try (InputStream in = res.open(); NativeImage img = NativeImage.read(in)) {
            int w = img.getWidth(), h = img.getHeight();
            StringBuilder sb = new StringBuilder(w * h + 12).append(w).append('x').append(h).append(':');
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int alpha = (img.getPixelRGBA(x, y) >>> 24) & 0xFF;
                    sb.append(alpha != 0 ? '#' : '.');
                }
            }
            return sb.toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ── references: the vanilla items of each category, as the packs show them now ──

    private static Map<WeaponCategories, Set<String>> references() {
        Map<WeaponCategories, Set<String>> refs = references;
        if (refs != null) return refs;
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        refs = new EnumMap<>(WeaponCategories.class);
        for (Map.Entry<WeaponCategories, String> e : REFERENCE_SUFFIX.entrySet()) {
            Set<String> shapes = new HashSet<>();
            for (String material : MATERIALS) {
                ResourceLocation id = new ResourceLocation("minecraft", material + "_" + e.getValue());
                if (!ForgeRegistries.ITEMS.containsKey(id)) continue;
                try {
                    ResourceLocation texture = readHandheldTexture(rm, id);
                    String shape = texture == null ? null : readShape(rm, texture, false);
                    if (shape != null) shapes.add(shape);
                } catch (IOException | RuntimeException ex) {
                    // skip this reference item
                }
            }
            refs.put(e.getKey(), shapes);
        }
        references = refs;
        return refs;
    }
}
