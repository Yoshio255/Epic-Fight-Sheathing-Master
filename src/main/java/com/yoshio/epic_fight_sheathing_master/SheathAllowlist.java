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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Allowlists deciding for which items this mod draws things. There are two
 * independent lists, read from
 * {@code config/epic_fight_sheathing_master-allowlist.json}:
 *
 * <ul>
 *   <li><b>body list</b> ({@code add} / {@code remove}) — {@link #isAllowed}:
 *       idle (not held) weapon display, and every other feature of this mod
 *       for the item (held-weapon ladder-climb handling, collision sounds of
 *       the displayed body, ...). Anything not on it is left completely alone.</li>
 *   <li><b>sheath list</b> ({@code sheath_add} / {@code sheath_remove}) —
 *       {@link #isSheathAllowed}: whether the item additionally gets a sheath
 *       drawn (and the sheath's collision sound). An item needs to be on
 *       <em>both</em> lists to get a sheath, so an item that is only on the
 *       body list shows its body without any sheath.</li>
 * </ul>
 *
 * <p>On the client there is a third step for items matched by neither list's
 * add/defaults. BODY: any item Epic Fight treats as a weapon (it has a capability
 * of its own) is shown, unless removed. SHEATH: if {@link Config#autoAllowMode}
 * is not OFF and the item is not removed from the sheath list,
 * {@link VanillaLikeDetector} may allow the sheath because the item is a recolour
 * of a vanilla sword/pickaxe/axe/shovel/hoe; an item that fails is simply shown
 * without a sheath. Priority: remove → add (and defaults) → automatic.
 *
 * <p>Each list = (built-in defaults ∪ add) − remove; when a pattern in both
 * matches an item, remove wins. The defaults of both lists are
 * {@code minecraft:*} and {@code epicfight:*} (v1.0 behavior); the body list
 * additionally contains the EFN weapons drawn in 3D by
 * {@link MeshWeaponIdleRenderer}. Those get a body display but none of the
 * generic category sheaths (put them into {@code sheath_add} as well to get
 * those); the two EFN short blades additionally get the box sheath that class
 * draws itself, see {@link #isOwnSheathAllowed}.
 * Entries are {@code namespace:*} (whole mod) or {@code namespace:path}.
 *
 * <p>A broken file (bad JSON, wrong shape, malformed entry) never crashes the
 * game: the built-in defaults are used for both lists and the reason is
 * logged. Missing file → created from a template. {@link #reload()} re-reads
 * it ({@code /efsm reload}).
 */
public final class SheathAllowlist {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE_NAME = Epic_fight_sheathing_master.MODID + "-allowlist.json";

    // v1.0 behavior: vanilla and Epic Fight's own items (both lists).
    private static final Set<String> DEFAULT_NAMESPACES = Set.of("minecraft", "epicfight");
    // Body list only: EpicFight Nightfall weapons that MeshWeaponIdleRenderer can
    // draw in 3D. Keep in sync with MeshWeaponIdleRenderer#TUNING_KEYS (not
    // referenced from here: that class is client-only). Deliberately NOT
    // "efn:*" — other EFN mesh weapons would otherwise show as flat icons.
    private static final Set<String> DEFAULT_BODY_ITEMS = Set.of("efn:air_tachi", "efn:co_tachi", "efn:sword_of_pioneer", "efn:ruinsgreatsword",
            "efn:nf_shortsword", "efn:nf_shortsword_2", "efn:meen_spear", "efn:crescent_moon", "efn:thornwheel", "efn:crimson_moon",
            "efn:exsiliumgladius", "efn:fire_exsiliumgladius", "efn:nf_dual_sword", "efn:broadblade",
            "efn:hf_murasama", "efn:hf_blade", "efn:yamato_dmc_in_sheath", "efn:yamato_dmc4_in_sheath", "efn:kusabimaru", "efn:nf_claw");
    // Sheath list: only wom:evil_tachi, wom:hollow_longsword and wom:satsujin by default (they are drawn with their own 3D sheath,
    // see the mixin's ownSheathItem). The generic category sheaths (sword, longsword, dagger, ...) must NOT appear on
    // EFN weapons; the box sheath that MeshWeaponIdleRenderer draws itself is governed by isOwnSheathAllowed instead.
    private static final Set<String> DEFAULT_SHEATH_ITEMS = Set.of("wom:evil_tachi", "wom:hollow_longsword", "wom:satsujin");

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private static final String TEMPLATE = """
            {
              "_comment": "add/remove = items that get the idle weapon display (body). sheath_add/sheath_remove = items that ALSO get a sheath (an item needs to be on both lists). Each list = built-in defaults + add - remove (remove wins). Entries: \\"modid:*\\" for a whole mod, or \\"modid:item_id\\". Built-in defaults: minecraft:* and epicfight:* in both lists, plus the EFN weapons that can be drawn in 3D (efn:air_tachi, efn:co_tachi, efn:sword_of_pioneer, efn:ruinsgreatsword, efn:nf_shortsword, efn:nf_shortsword_2, efn:meen_spear, efn:crescent_moon, efn:thornwheel, efn:crimson_moon, efn:exsiliumgladius, efn:fire_exsiliumgladius, efn:nf_dual_sword, efn:broadblade, efn:hf_murasama, efn:hf_blade, efn:yamato_dmc_in_sheath, efn:yamato_dmc4_in_sheath, efn:kusabimaru, efn:nf_claw) in the body list, and wom:evil_tachi (Weapons of Miracles) in the sheath list. efn:nf_shortsword and efn:nf_shortsword_2 also get their own box sheath; sheath_remove turns that off. Reload in game with /efsm reload.",
              "add": [],
              "remove": [],
              "sheath_add": [],
              "sheath_remove": []
            }
            """;

    /** One list: (defaults ∪ add) − remove, as namespace / exact-id sets. */
    private record ListRules(Set<String> namespaces, Set<String> items,
                             Set<String> removeNamespaces, Set<String> removeItems) {
        static ListRules defaults(Set<String> defaultItems) {
            return new ListRules(DEFAULT_NAMESPACES, defaultItems, Set.of(), Set.of());
        }

        boolean isRemoved(ResourceLocation id) {
            return removeNamespaces.contains(id.getNamespace()) || removeItems.contains(id.toString());
        }

        boolean matches(ResourceLocation id) {
            if (isRemoved(id)) return false;
            return namespaces.contains(id.getNamespace()) || items.contains(id.toString());
        }
    }

    /** Immutable snapshot, swapped atomically on reload. */
    private record Rules(ListRules body, ListRules sheath) {
        static Rules defaults() {
            return new Rules(ListRules.defaults(DEFAULT_BODY_ITEMS), ListRules.defaults(DEFAULT_SHEATH_ITEMS));
        }
    }

    private static volatile Rules rules = Rules.defaults();
    // Per-Item result caches (registries are fixed after load); replaced on reload.
    private static volatile Map<Item, Boolean> bodyCache = new ConcurrentHashMap<>();
    private static volatile Map<Item, Boolean> sheathCache = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static volatile boolean autoUsed = false; // VanillaLikeDetector has been called at least once

    private SheathAllowlist() {}

    public static Path getFilePath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /** Body list: may the idle weapon display / other features apply to this stack? */
    public static boolean isAllowed(ItemStack stack) {
        return !stack.isEmpty() && isAllowed(stack.getItem());
    }

    public static boolean isAllowed(Item item) {
        if (!loaded) reload();
        return lookup(item, bodyCache, true);
    }

    /** Sheath list AND body list: may a sheath be drawn for this stack? */
    public static boolean isSheathAllowed(ItemStack stack) {
        return !stack.isEmpty() && isSheathAllowed(stack.getItem());
    }

    public static boolean isSheathAllowed(Item item) {
        if (!loaded) reload();
        return isAllowed(item) && lookup(item, sheathCache, false);
    }

    /**
     * A weapon that is on the body list and whose renderer draws its own sheath
     * (MeshWeaponIdleRenderer's box sheath): only {@code sheath_remove} can turn
     * the sheath off. Independent of the generic sheath list.
     */
    public static boolean isOwnSheathAllowed(ItemStack stack) {
        if (stack.isEmpty() || !isAllowed(stack.getItem())) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && !rules.sheath().isRemoved(id);
    }

    private static boolean lookup(Item item, Map<Item, Boolean> cache, boolean body) {
        Boolean hit = cache.get(item);
        if (hit != null) return hit;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        Rules r = rules;
        ListRules list = body ? r.body() : r.sheath();
        boolean result = id != null && list.matches(id);
        // Body: besides the list, any item Epic Fight treats as a weapon (it has a capability of its own, i.e.
        // not the EMPTY one every unknown item gets) is shown, unless it is removed from the list. A positive
        // answer is cached; a negative one is not (capabilities may not be loaded yet), so it is asked again.
        if (!result && body && id != null && !list.isRemoved(id) && FMLEnvironment.dist == Dist.CLIENT) {
            if (!ClientAuto.isEpicFightWeapon(item)) return false;
            cache.put(item, true);
            return true;
        }
        // The automatic detector decides the SHEATH only (body == false): an item that is shown but that the
        // sheath list and the detector both reject gets no sheath.
        if (!result && !body && id != null && !list.isRemoved(id)
                && Config.autoAllowMode != Config.AutoAllowMode.OFF
                && FMLEnvironment.dist == Dist.CLIENT) {
            // Third and last step (after remove and add/defaults): vanilla look-alikes.
            autoUsed = true;
            Boolean auto = ClientAuto.decide(item, id);
            if (auto == null) return false; // not decidable yet: do not cache, ask again later
            result = auto;
        }
        cache.put(item, result);
        return result;
    }

    // Holder so the client-only detector class is only touched on the client.
    private static final class ClientAuto {
        static Boolean decide(Item item, ResourceLocation id) {
            return VanillaLikeDetector.decide(item, id);
        }

        static boolean isEpicFightWeapon(Item item) {
            try {
                return !yesman.epicfight.world.capabilities.EpicFightCapabilities
                        .getItemStackCapability(new ItemStack(item)).isEmpty();
            } catch (Throwable t) {
                return false;
            }
        }

        static void invalidate() {
            VanillaLikeDetector.invalidate();
        }
    }

    /** Drops the per-item results (auto-allow results included); the next lookups recompute them. */
    public static void clearCaches() {
        bodyCache = new ConcurrentHashMap<>();
        sheathCache = new ConcurrentHashMap<>();
        if (autoUsed) ClientAuto.invalidate(); // the detector class is only touched once it has been used
    }

    /**
     * (Re)reads the JSON file. Never throws. Returns a one-line human-readable
     * result for {@code /efsm reload}.
     */
    public static synchronized String reload() {
        Path path = getFilePath();
        Rules newRules;
        String summary;
        try {
            if (Files.notExists(path)) {
                writeTemplate(path);
                newRules = Rules.defaults();
                summary = "no file yet — created template, using built-in defaults";
            } else {
                newRules = parse(path);
                summary = "loaded " + path.getFileName();
            }
        } catch (Exception e) {
            // Any problem → built-in defaults for both lists, with the reason in the log.
            LOGGER.warn("[efsm] allowlist file {} is invalid ({}); falling back to the built-in default lists.",
                    path, e.getMessage());
            newRules = Rules.defaults();
            summary = "FILE INVALID (" + e.getMessage() + ") — using built-in defaults, see log";
        }
        rules = newRules;
        clearCaches();
        loaded = true;
        LOGGER.info("[efsm] allowlist: {} | body: +{} +{} -{} -{} | sheath: +{} +{} -{} -{}", summary,
                diff(newRules.body().namespaces(), DEFAULT_NAMESPACES), diff(newRules.body().items(), DEFAULT_BODY_ITEMS),
                newRules.body().removeNamespaces(), newRules.body().removeItems(),
                diff(newRules.sheath().namespaces(), DEFAULT_NAMESPACES), diff(newRules.sheath().items(), DEFAULT_SHEATH_ITEMS),
                newRules.sheath().removeNamespaces(), newRules.sheath().removeItems());
        return summary;
    }

    private static Set<String> diff(Set<String> set, Set<String> base) {
        Set<String> out = new HashSet<>(set);
        out.removeAll(base);
        return out;
    }

    private static void writeTemplate(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Not being able to write the template is not fatal.
            LOGGER.warn("[efsm] could not create allowlist template {}: {}", path, e.getMessage());
        }
    }

    private static Rules parse(Path path) throws IOException {
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException e) { // JsonParseException / IllegalStateException
            throw new IOException("not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isJsonObject()) {
            throw new IOException("top level must be a JSON object with \"add\" / \"remove\" / "
                    + "\"sheath_add\" / \"sheath_remove\" arrays");
        }
        JsonObject obj = root.getAsJsonObject();
        return new Rules(
                parseList(obj, "add", "remove", DEFAULT_BODY_ITEMS),
                parseList(obj, "sheath_add", "sheath_remove", DEFAULT_SHEATH_ITEMS));
    }

    private static ListRules parseList(JsonObject obj, String addKey, String removeKey,
                                       Set<String> defaultItems) throws IOException {
        Set<String> namespaces = new HashSet<>(DEFAULT_NAMESPACES);
        Set<String> items = new HashSet<>(defaultItems);
        readList(obj, addKey, namespaces, items);

        Set<String> removeNamespaces = new HashSet<>();
        Set<String> removeItems = new HashSet<>();
        readList(obj, removeKey, removeNamespaces, removeItems);

        return new ListRules(Set.copyOf(namespaces), Set.copyOf(items),
                Set.copyOf(removeNamespaces), Set.copyOf(removeItems));
    }

    private static void readList(JsonObject obj, String key, Set<String> namespaces, Set<String> items)
            throws IOException {
        if (!obj.has(key)) return; // a missing list is the same as an empty one
        JsonElement el = obj.get(key);
        if (el.isJsonNull()) return;
        if (!el.isJsonArray()) throw new IOException("\"" + key + "\" must be an array of strings");
        JsonArray arr = el.getAsJsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IOException("\"" + key + "\"[" + i + "] must be a string");
            }
            String entry = e.getAsString().trim();
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IOException("\"" + key + "\"[" + i + "] = \"" + entry
                        + "\" is not \"modid:*\" or \"modid:item_id\"");
            }
            String ns = entry.substring(0, colon);
            String path = entry.substring(colon + 1);
            if (!NAMESPACE.matcher(ns).matches()) {
                throw new IOException("\"" + key + "\"[" + i + "] = \"" + entry + "\" has an invalid mod id");
            }
            if (path.equals("*")) {
                namespaces.add(ns);
            } else if (PATH.matcher(path).matches()) {
                items.add(ns + ":" + path);
            } else {
                throw new IOException("\"" + key + "\"[" + i + "] = \"" + entry + "\" has an invalid item id");
            }
        }
    }
}
