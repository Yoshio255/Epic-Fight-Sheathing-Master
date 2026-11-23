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

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = Epic_fight_sheathing_master.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // Per-category WAIST/BACK toggles, replacing the single global
    // sheatheAtWaist flag — only categories with an actual BACK-mode
    // implementation get one (tachi, uchigatana/katana, sword, longsword).
    private static final ForgeConfigSpec.BooleanValue SHEATHE_TACHI_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to sheathe the tachi at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = upper-back position, Chest bone (Epic Fight default)")
                   .define("sheatheTachiAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue SHEATHE_UCHIGATANA_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to sheathe the katana (uchigatana) at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = upper-back position, Chest bone (Epic Fight default)")
                   .define("sheatheUchigatanaAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue SHEATHE_SWORD_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to sheathe vanilla swords at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = upper-back position, Chest bone (Epic Fight default)")
                   .define("sheatheSwordAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue SHEATHE_LONGSWORD_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to sheathe the longsword at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone",
                            "false = upper-back position, Chest bone (Epic Fight default) (default)")
                   .define("sheatheLongswordAtWaist", false);

    private static final ForgeConfigSpec.BooleanValue SHEATHE_BOW_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the bow at the waist (true) or on the back (false). Crossbow is unaffected (always back).",
                            "true  = waist position, Torso bone",
                            "false = upper-back position, Chest bone (Epic Fight default) (default)")
                   .define("sheatheBowAtWaist", false);

    private static final ForgeConfigSpec.BooleanValue COLLISION_SOUND_ENABLED_ENTRY =
            BUILDER.comment("Whether to play the clank sound when a sheath touches a block.",
                            "true  = sound on (default)",
                            "false = sound off")
                   .define("collisionSoundEnabled", true);

    private static final ForgeConfigSpec.BooleanValue SHEATHE_GREATSWORD_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the greatsword at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone",
                            "false = upper-back position, Chest bone (Epic Fight default) (default)")
                   .define("sheatheGreatswordAtWaist", false);

    // EpicFight Nightfall (EFN) mesh weapons, one flag per weapon (see
    // MeshWeaponIdleRenderer). Key used by isMeshWeaponAtWaist is the weapon's
    // TuneableOffset prefix.
    private static final ForgeConfigSpec.BooleanValue EFN_AIR_TACHI_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the EFN Air Tachi at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = back position, Chest bone")
                   .define("efnAirTachiAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue EFN_CO_TACHI_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the EFN Co Tachi (blade + its own scabbard) at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = back position, Chest bone")
                   .define("efnCoTachiAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue EFN_SWORD_OF_PIONEER_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the EFN Sword of Pioneer (blade + its own scabbard) at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = back position, Chest bone")
                   .define("efnSwordOfPioneerAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue EFN_RUINS_GREATSWORD_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the EFN Ruins Greatsword at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone",
                            "false = back position, Chest bone (default)")
                   .define("efnRuinsGreatswordAtWaist", false);


    private static final ForgeConfigSpec.BooleanValue EFN_BROADBLADE_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the EFN Warriors-BroadBlade at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = back position, Chest bone")
                   .define("efnBroadbladeAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue EFN_NF_DUAL_SWORD_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the EFN Ancient Sculk Twinblades (both blades) at the waist (true) or crossed on the back (false).",
                            "true  = waist position, one blade at each hip, Torso bone (default)",
                            "false = back position, the two blades crossed, Chest bone")
                   .define("efnNfDualSwordAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue EFN_EXSILIUMGLADIUS_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the EFN Exile Blade pair (Exsiliumgladius + Fire Exsiliumgladius, both sides) at the waist (true) or on the back (false).",
                            "true  = waist position, Torso bone (default)",
                            "false = back position, Chest bone")
                   .define("efnExsiliumgladiusAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue EFN_NF_CLAW_WORN_ENTRY =
            BUILDER.comment("Whether to show the EFN Wild Claws on both hands all the time while they sit in an idle hotbar slot (WORN display).",
                            "true  = shown on both hands (the hands are drawn deeper than the real ones, so held items can look off)",
                            "false = not shown (default)")
                   .define("efnNfClawWorn", false);

    private static final ForgeConfigSpec.BooleanValue FIST_GLOVES_WORN_ENTRY =
            BUILDER.comment("Whether to show fist weapons that are plain gloves / knuckles (Epic Fight's glove and other FIST items drawn the same way) on both hands all the time (WORN display).",
                            "One glove = one hand: the first glove in an idle hotbar slot is shown on the right hand, a second one on the left hand (both hands only with two gloves).",
                            "true  = shown",
                            "false = not shown (default)")
                   .define("fistGlovesWorn", false);

    private static final ForgeConfigSpec.BooleanValue WOM_EVIL_TACHI_AT_WAIST_ENTRY =
            BUILDER.comment("Whether to carry the Weapons of Miracles Evil Tachi at the waist (true) or on the back (false).",
                            "It is an ordinary tachi in looks, so it has its own switch separate from the Epic Fight tachi one.",
                            "true  = waist position, Torso bone (default)",
                            "false = back position, Chest bone")
                   .define("womEvilTachiAtWaist", true);

    private static final ForgeConfigSpec.BooleanValue EFN_MURASAMA_SHEATH_ON_EQUIP_ENTRY =
            BUILDER.comment("EFN Murasama and HF-BLADE: start in the sheathed state the moment they are taken into the hand (EFN itself starts them drawn).",
                            "This changes the game state (server side): it uses EFN own sheath state, so the sheathed idle / walk / run animations apply from the start.",
                            "Read by whichever side runs the game logic (the dedicated server, or the single-player world). Without this mod on the server the setting has no effect.",
                            "true  = sheathed on equip",
                            "false = EFN default (drawn on equip) (default)")
                   .define("efnMurasamaSheathOnEquip", false);

    /**
     * Automatic permission for the SHEATH of items that are on the body list but not on the sheath list (client only).
     * EXACT = recolours of vanilla tools: model is exactly item/handheld and the
     * texture has the same size and the same non-transparent pixels as the
     * vanilla sword/pickaxe/axe/shovel/hoe, and Epic Fight puts the item in that
     * category (see VanillaLikeDetector). More lenient modes can be added later.
     */
    public enum AutoAllowMode { OFF, EXACT }

    private static final ForgeConfigSpec.EnumValue<AutoAllowMode> AUTO_ALLOW_MODE_ENTRY =
            BUILDER.comment("Automatically give a sheath to weapons that are shown (body list) but are not on the sheath list of the allowlist file, when they are plain recolours of a vanilla sword / pickaxe / axe / shovel / hoe.",
                            "This only decides the sheath: a weapon (anything Epic Fight treats as a weapon) is shown either way, and an item that fails here is shown without a sheath.",
                            "OFF   = only the allowlist file decides the sheath",
                            "EXACT = same model parent (item/handheld), same texture size and exactly the same non-transparent pixels as the vanilla item, no tint, no custom renderer, and Epic Fight classifies it in the same category (default)",
                            "'remove' entries of the allowlist file always win. Automatically allowed items are written to the log ('auto-allowed').")
                   .defineEnum("autoAllowVanillaLike", AutoAllowMode.EXACT);

    // Hotbar slots (0-based, 0-8) whose weapons are treated as "idle" weapons
    // and displayed on the body when not held. Default: slot 0 only.
    private static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> IDLE_SLOTS_ENTRY =
            BUILDER.comment("Hotbar slots (0-8, 0 = leftmost slot) whose weapons are shown on the body while not held.",
                            "Multiple slots can be listed, e.g. [0, 1]. Default: [0]")
                   .defineList("idleSlots", List.of(0), o -> o instanceof Integer i && i >= 0 && i <= 8);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean sheatheTachiAtWaist = true;
    public static boolean sheatheUchigatanaAtWaist = true;
    public static boolean sheatheSwordAtWaist = true;
    public static boolean sheatheLongswordAtWaist = false;
    public static boolean sheatheBowAtWaist = false;
    public static boolean sheatheGreatswordAtWaist = false;
    public static boolean collisionSoundEnabled = true;
    public static boolean efnAirTachiAtWaist = true;
    public static boolean efnCoTachiAtWaist = true;
    public static boolean efnSwordOfPioneerAtWaist = true;
    public static boolean efnRuinsGreatswordAtWaist = false;
    public static boolean efnExsiliumgladiusAtWaist = true;
    public static boolean efnNfDualSwordAtWaist = true;
    public static boolean efnBroadbladeAtWaist = true;
    /** Weapons of Miracles Evil Tachi: waist (true) / back (false). */
    public static boolean womEvilTachiAtWaist = true;
    /** On/off of the worn display of the wild claws (default off). Reuses the "at waist" flag plumbing: true = shown. */
    public static boolean efnNfClawWorn = false;
    /** On/off of the worn display of gloves / knuckles (EF's glove etc.), default off. */
    public static boolean fistGlovesWorn = false;
    /** Server logic: Murasama / HF-BLADE start sheathed when equipped (default off). */
    public static volatile boolean efnMurasamaSheathOnEquip = false;
    public static volatile AutoAllowMode autoAllowMode = AutoAllowMode.EXACT;

    /** Waist (true) / back (false) for a mesh weapon, by its TuneableOffset prefix. Unknown → waist. */
    public static boolean isMeshWeaponAtWaist(String prefix) {
        return switch (prefix) {
            case "efn.air_tachi" -> efnAirTachiAtWaist;
            case "efn.co_tachi" -> efnCoTachiAtWaist;
            case "efn.sword_of_pioneer" -> efnSwordOfPioneerAtWaist;
            case "efn.ruinsgreatsword" -> efnRuinsGreatswordAtWaist;
            case "efn.exsiliumgladius" -> efnExsiliumgladiusAtWaist;
            case "efn.nf_dual_sword" -> efnNfDualSwordAtWaist;
            case "efn.broadblade" -> efnBroadbladeAtWaist;
            case "efn.nf_claw" -> efnNfClawWorn;   // worn item: true = shown
            default -> true;
        };
    }

    public static void setMeshWeaponAtWaist(String prefix, boolean value) {
        if ("efn.air_tachi".equals(prefix)) {
            EFN_AIR_TACHI_AT_WAIST_ENTRY.set(value);
            EFN_AIR_TACHI_AT_WAIST_ENTRY.save();
            efnAirTachiAtWaist = value;
        } else if ("efn.co_tachi".equals(prefix)) {
            EFN_CO_TACHI_AT_WAIST_ENTRY.set(value);
            EFN_CO_TACHI_AT_WAIST_ENTRY.save();
            efnCoTachiAtWaist = value;
        } else if ("efn.sword_of_pioneer".equals(prefix)) {
            EFN_SWORD_OF_PIONEER_AT_WAIST_ENTRY.set(value);
            EFN_SWORD_OF_PIONEER_AT_WAIST_ENTRY.save();
            efnSwordOfPioneerAtWaist = value;
        } else if ("efn.ruinsgreatsword".equals(prefix)) {
            EFN_RUINS_GREATSWORD_AT_WAIST_ENTRY.set(value);
            EFN_RUINS_GREATSWORD_AT_WAIST_ENTRY.save();
            efnRuinsGreatswordAtWaist = value;
        } else if ("efn.broadblade".equals(prefix)) {
            EFN_BROADBLADE_AT_WAIST_ENTRY.set(value);
            EFN_BROADBLADE_AT_WAIST_ENTRY.save();
            efnBroadbladeAtWaist = value;
        } else if ("efn.nf_dual_sword".equals(prefix)) {
            EFN_NF_DUAL_SWORD_AT_WAIST_ENTRY.set(value);
            EFN_NF_DUAL_SWORD_AT_WAIST_ENTRY.save();
            efnNfDualSwordAtWaist = value;
        } else if ("efn.exsiliumgladius".equals(prefix)) {
            EFN_EXSILIUMGLADIUS_AT_WAIST_ENTRY.set(value);
            EFN_EXSILIUMGLADIUS_AT_WAIST_ENTRY.save();
            efnExsiliumgladiusAtWaist = value;
        } else if ("efn.nf_claw".equals(prefix)) {
            EFN_NF_CLAW_WORN_ENTRY.set(value);
            EFN_NF_CLAW_WORN_ENTRY.save();
            efnNfClawWorn = value;
        }
    }

    public static void setWomEvilTachiAtWaist(boolean value) {
        WOM_EVIL_TACHI_AT_WAIST_ENTRY.set(value);
        WOM_EVIL_TACHI_AT_WAIST_ENTRY.save();
        womEvilTachiAtWaist = value;
    }

    // Runtime mirror of idleSlots, indexed by hotbar slot. Replaced wholesale
    // (never mutated in place) so the render thread always sees a consistent array.
    private static volatile boolean[] idleSlotFlags = new boolean[] {true, false, false, false, false, false, false, false, false};

    public static boolean isIdleSlot(int slot) {
        boolean[] flags = idleSlotFlags;
        return slot >= 0 && slot < flags.length && flags[slot];
    }

    // Idle slots in ascending order.
    public static List<Integer> getIdleSlots() {
        boolean[] flags = idleSlotFlags;
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) result.add(i);
        }
        return result;
    }

    private static boolean[] toFlags(List<? extends Integer> slots) {
        boolean[] flags = new boolean[9];
        for (Integer s : slots) {
            if (s != null && s >= 0 && s < 9) flags[s] = true;
        }
        return flags;
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        sheatheTachiAtWaist = SHEATHE_TACHI_AT_WAIST_ENTRY.get();
        sheatheUchigatanaAtWaist = SHEATHE_UCHIGATANA_AT_WAIST_ENTRY.get();
        sheatheSwordAtWaist = SHEATHE_SWORD_AT_WAIST_ENTRY.get();
        sheatheLongswordAtWaist = SHEATHE_LONGSWORD_AT_WAIST_ENTRY.get();
        sheatheBowAtWaist = SHEATHE_BOW_AT_WAIST_ENTRY.get();
        sheatheGreatswordAtWaist = SHEATHE_GREATSWORD_AT_WAIST_ENTRY.get();
        collisionSoundEnabled = COLLISION_SOUND_ENABLED_ENTRY.get();
        efnAirTachiAtWaist = EFN_AIR_TACHI_AT_WAIST_ENTRY.get();
        efnCoTachiAtWaist = EFN_CO_TACHI_AT_WAIST_ENTRY.get();
        efnSwordOfPioneerAtWaist = EFN_SWORD_OF_PIONEER_AT_WAIST_ENTRY.get();
        efnRuinsGreatswordAtWaist = EFN_RUINS_GREATSWORD_AT_WAIST_ENTRY.get();
        efnExsiliumgladiusAtWaist = EFN_EXSILIUMGLADIUS_AT_WAIST_ENTRY.get();
        efnNfDualSwordAtWaist = EFN_NF_DUAL_SWORD_AT_WAIST_ENTRY.get();
        efnBroadbladeAtWaist = EFN_BROADBLADE_AT_WAIST_ENTRY.get();
        efnNfClawWorn = EFN_NF_CLAW_WORN_ENTRY.get();
        womEvilTachiAtWaist = WOM_EVIL_TACHI_AT_WAIST_ENTRY.get();
        fistGlovesWorn = FIST_GLOVES_WORN_ENTRY.get();
        efnMurasamaSheathOnEquip = EFN_MURASAMA_SHEATH_ON_EQUIP_ENTRY.get();
        autoAllowMode = AUTO_ALLOW_MODE_ENTRY.get();
        SheathAllowlist.clearCaches(); // the auto mode may have changed
        idleSlotFlags = toFlags(IDLE_SLOTS_ENTRY.get());
    }

    // Used by the config screen's toggle buttons: persists to the .toml file
    // immediately (ConfigValue.set() alone only updates the in-memory value)
    // and updates the live runtime flag, so the change applies instantly with
    // no restart/reload needed.
    public static void setSheatheTachiAtWaist(boolean value) {
        SHEATHE_TACHI_AT_WAIST_ENTRY.set(value);
        SHEATHE_TACHI_AT_WAIST_ENTRY.save();
        sheatheTachiAtWaist = value;
    }

    public static void setSheatheUchigatanaAtWaist(boolean value) {
        SHEATHE_UCHIGATANA_AT_WAIST_ENTRY.set(value);
        SHEATHE_UCHIGATANA_AT_WAIST_ENTRY.save();
        sheatheUchigatanaAtWaist = value;
    }

    public static void setSheatheSwordAtWaist(boolean value) {
        SHEATHE_SWORD_AT_WAIST_ENTRY.set(value);
        SHEATHE_SWORD_AT_WAIST_ENTRY.save();
        sheatheSwordAtWaist = value;
    }

    public static void setSheatheLongswordAtWaist(boolean value) {
        SHEATHE_LONGSWORD_AT_WAIST_ENTRY.set(value);
        SHEATHE_LONGSWORD_AT_WAIST_ENTRY.save();
        sheatheLongswordAtWaist = value;
    }

    public static void setSheatheBowAtWaist(boolean value) {
        SHEATHE_BOW_AT_WAIST_ENTRY.set(value);
        SHEATHE_BOW_AT_WAIST_ENTRY.save();
        sheatheBowAtWaist = value;
    }

    public static void setEfnMurasamaSheathOnEquip(boolean value) {
        EFN_MURASAMA_SHEATH_ON_EQUIP_ENTRY.set(value);
        EFN_MURASAMA_SHEATH_ON_EQUIP_ENTRY.save();
        efnMurasamaSheathOnEquip = value;
    }

    public static void setFistGlovesWorn(boolean value) {
        FIST_GLOVES_WORN_ENTRY.set(value);
        FIST_GLOVES_WORN_ENTRY.save();
        fistGlovesWorn = value;
    }

    public static void setSheatheGreatswordAtWaist(boolean value) {
        SHEATHE_GREATSWORD_AT_WAIST_ENTRY.set(value);
        SHEATHE_GREATSWORD_AT_WAIST_ENTRY.save();
        sheatheGreatswordAtWaist = value;
    }

    public static void setIdleSlot(int slot, boolean idle) {
        if (slot < 0 || slot >= 9) return;
        boolean[] flags = idleSlotFlags.clone();
        flags[slot] = idle;
        idleSlotFlags = flags;

        List<Integer> slots = getIdleSlots();
        IDLE_SLOTS_ENTRY.set(slots);
        IDLE_SLOTS_ENTRY.save();
    }

    public static void setCollisionSoundEnabled(boolean value) {
        COLLISION_SOUND_ENABLED_ENTRY.set(value);
        COLLISION_SOUND_ENABLED_ENTRY.save();
        collisionSoundEnabled = value;
    }
}
