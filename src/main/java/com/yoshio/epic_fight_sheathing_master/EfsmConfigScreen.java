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

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

// Opened from the mod list's "Config" button (see ConfigScreenHandler.ConfigScreenFactory
// registration in Epic_fight_sheathing_master.ClientModEvents.onClientSetup).
//
// Rows live in a scrollable ContainerObjectSelectionList (the same vanilla
// widget the Controls/Video screens use) rather than being placed at fixed Y
// coordinates directly on the Screen, so the list keeps working as more
// per-weapon toggles are added later — it grows downward and scrolls instead
// of pushing "Done" off-screen or overlapping the title. Only "Done" stays
// outside the list, pinned to the bottom, so it's always reachable.
@OnlyIn(Dist.CLIENT)
public class EfsmConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 32;
    private static final int BOTTOM_BAR_HEIGHT = 30;

    // Prefix of this screen's translation keys (lang/en_us.json, lang/ja_jp.json).
    private static final String LANG = "gui." + Epic_fight_sheathing_master.MODID + ".";

    private static final int TAB_TOP = 28;
    private static final int TAB_HEIGHT = 20;

    // One tab per supported mod. A tab is only offered when its mod is loaded
    // (modId == null: always). With a single visible tab the tab bar is hidden.
    // To add a mod (e.g. EpicFight-Resurrection): add an entry here and a case
    // in buildTabRows.
    // The tab label is the mods' own display names (as shown in the mod list),
    // never an internal id.
    private record Tab(String id, List<String> labelModIds, String modId) {
        boolean available() {
            return modId == null || net.minecraftforge.fml.ModList.get().isLoaded(modId);
        }

        String label() {
            return String.join(" + ", labelModIds.stream().map(EfsmConfigScreen::modName).toList());
        }
    }

    private static final List<Tab> ALL_TABS = List.of(
            new Tab("vanilla", List.of("minecraft", "epicfight"), null),
            new Tab("efn", List.of("efn"), "efn"),
            new Tab("wom", List.of("wom"), "wom"));

    private static String modName(String modId) {
        return net.minecraftforge.fml.ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(modId);
    }

    // The item's name exactly as the player sees it in the current language
    // (formatting codes such as the red/bold/italic of EFN names stripped so
    // the button text stays plain). Falls back to the id if it isn't registered.
    private static Component itemName(String itemId) {
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(new net.minecraft.resources.ResourceLocation(itemId));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return Component.literal(itemId);
        return Component.literal(net.minecraft.ChatFormatting.stripFormatting(
                new net.minecraft.world.item.ItemStack(item).getHoverName().getString()));
    }

    // Epic Fight's own weapon category name (translated by Epic Fight's lang files).
    private static Component categoryName(String category) {
        return Component.translatable("epicfight.weapon_category." + category);
    }

    // Remembered while the game runs so reopening the screen returns to the same tab.
    private static String selectedTabId = "vanilla";

    private final Screen parent;
    private RowList rowList;

    public EfsmConfigScreen(Screen parent) {
        super(Component.literal("Epic Fight Sheathing Master"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<Tab> tabs = ALL_TABS.stream().filter(Tab::available).toList();
        if (tabs.stream().noneMatch(t -> t.id().equals(selectedTabId))) {
            selectedTabId = tabs.get(0).id();
        }
        boolean showTabs = tabs.size() > 1;
        int listTop = showTabs ? TAB_TOP + TAB_HEIGHT + 6 : LIST_TOP;

        if (showTabs) {
            int tabWidth = Math.min(160, (this.width - 20) / tabs.size());
            int x = this.width / 2 - tabWidth * tabs.size() / 2;
            for (Tab tab : tabs) {
                Button tabButton = Button.builder(Component.literal(tab.label()), b -> {
                            selectedTabId = tab.id();
                            this.rebuildWidgets();
                        })
                        .pos(x, TAB_TOP)
                        .size(tabWidth, TAB_HEIGHT)
                        .build();
                tabButton.active = !tab.id().equals(selectedTabId);
                this.addRenderableWidget(tabButton);
                x += tabWidth;
            }
        }

        this.rowList = new RowList(this.minecraft, this.width, this.height,
                listTop, this.height - BOTTOM_BAR_HEIGHT, ROW_HEIGHT);
        this.addWidget(this.rowList);

        buildTabRows(selectedTabId);
        this.rowList.add(Row.spacer());

        Button soundButton = Button.builder(soundLabel(Config.collisionSoundEnabled), button -> {
                    boolean newValue = !Config.collisionSoundEnabled;
                    Config.setCollisionSoundEnabled(newValue);
                    button.setMessage(soundLabel(newValue));
                })
                .pos(this.width / 2 - 100, 0)
                .size(200, 20)
                .build();
        this.rowList.add(new Row(List.of(soundButton)));
        this.rowList.add(Row.spacer());

        // Idle-weapon slots: one small toggle per hotbar slot (1-9 in the UI,
        // 0-8 internally). Highlighted with brackets when the slot is idle.
        this.rowList.add(Row.label(Component.translatable(LANG + "idle_slots")));
        AbstractWidget[] slotButtons = new AbstractWidget[9];
        for (int slot = 0; slot < 9; slot++) {
            final int s = slot;
            slotButtons[s] = Button.builder(slotLabel(s, Config.isIdleSlot(s)), button -> {
                        boolean newValue = !Config.isIdleSlot(s);
                        Config.setIdleSlot(s, newValue);
                        button.setMessage(slotLabel(s, newValue));
                    })
                    .pos(this.width / 2 - 100 + s * 23, 0)
                    .size(22, 20)
                    .build();
        }
        this.rowList.add(new Row(List.of(slotButtons)));

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .pos(this.width / 2 - 100, this.height - BOTTOM_BAR_HEIGHT + 6)
                .size(200, 20)
                .build());
    }

    // The per-weapon rows of one tab. The global rows (collision sound, hotbar
    // slots) follow in init() and are shown on every tab.
    private void buildTabRows(String tabId) {
        switch (tabId) {
            case "efn" -> {
                // 3D body of EFN mesh weapons (MeshWeaponIdleRenderer).
                addToggleRow(itemName("efn:air_tachi"), "carry", () -> Config.efnAirTachiAtWaist,
                        v -> Config.setMeshWeaponAtWaist("efn.air_tachi", v));
                // co_tachi has its own scabbard, so it is "sheathed" (佩用) like the katanas.
                addToggleRow(itemName("efn:co_tachi"), "sheathe", () -> Config.efnCoTachiAtWaist,
                        v -> Config.setMeshWeaponAtWaist("efn.co_tachi", v));
                // Own scabbard and 刀 in its Japanese name (先鋒軍の佩刀): "sheathe" (佩用).
                addToggleRow(itemName("efn:sword_of_pioneer"), "sheathe", () -> Config.efnSwordOfPioneerAtWaist,
                        v -> Config.setMeshWeaponAtWaist("efn.sword_of_pioneer", v));
                // Huge bare blade (no scabbard, no 刀 in the name): "carry" (携行).
                addToggleRow(itemName("efn:ruinsgreatsword"), "carry", () -> Config.efnRuinsGreatswordAtWaist,
                        v -> Config.setMeshWeaponAtWaist("efn.ruinsgreatsword", v));
                // Huge bare blade: "carry" (携行).
                addToggleRow(itemName("efn:broadblade"), "carry", () -> Config.efnBroadbladeAtWaist,
                        v -> Config.setMeshWeaponAtWaist("efn.broadblade", v));
                // Twin blades: one item, both blades share this switch; bare blades, "carry" (携行).
                addToggleRow(itemName("efn:nf_dual_sword"), "carry", () -> Config.efnNfDualSwordAtWaist,
                        v -> Config.setMeshWeaponAtWaist("efn.nf_dual_sword", v));
                // Exile Blade pair (both sides share this one switch): bare blades, "carry" (携行).
                addToggleRow(Component.translatable(LANG + "exile_blade"), "carry", () -> Config.efnExsiliumgladiusAtWaist,
                        v -> Config.setMeshWeaponAtWaist("efn.exsiliumgladius", v));
                // Murasama / HF-BLADE start sheathed on equip (server-side game logic; on / off).
                addToggleRow(Component.translatable(LANG + "murasama_group"), "equip_sheathed", () -> Config.efnMurasamaSheathOnEquip,
                        Config::setEfnMurasamaSheathOnEquip);
                // Wild claws: worn on both hands, no position; just on / off (default off).
                addToggleRow(itemName("efn:nf_claw"), "worn", () -> Config.efnNfClawWorn,
                        v -> Config.setMeshWeaponAtWaist("efn.nf_claw", v));
            }
            case "wom" -> {
                // Evil Tachi is an ordinary tachi in looks, with its own scabbard: "sheathe" (佩用).
                addToggleRow(itemName("wom:evil_tachi"), "sheathe", () -> Config.womEvilTachiAtWaist,
                        Config::setWomEvilTachiAtWaist);
            }
            default -> {
                // Bow and Greatsword have no separate sheath item — they just
                // reposition the bare weapon — so they use "carry" instead of
                // "sheathe" here.
                addToggleRow(categoryName("tachi"), "sheathe", () -> Config.sheatheTachiAtWaist, Config::setSheatheTachiAtWaist);
                addToggleRow(categoryName("uchigatana"), "sheathe", () -> Config.sheatheUchigatanaAtWaist, Config::setSheatheUchigatanaAtWaist);
                addToggleRow(categoryName("sword"), "carry", () -> Config.sheatheSwordAtWaist, Config::setSheatheSwordAtWaist);
                addToggleRow(categoryName("longsword"), "carry", () -> Config.sheatheLongswordAtWaist, Config::setSheatheLongswordAtWaist);
                addToggleRow(Component.translatable("item.minecraft.bow"), "carry", () -> Config.sheatheBowAtWaist, Config::setSheatheBowAtWaist);
                addToggleRow(Component.translatable(LANG + "greatsword"), "carry", () -> Config.sheatheGreatswordAtWaist, Config::setSheatheGreatswordAtWaist);
                // Gloves / knuckles (FIST): worn on both hands, just on / off (default off).
                addToggleRow(Component.translatable(LANG + "glove"), "worn", () -> Config.fistGlovesWorn, Config::setFistGlovesWorn);
            }
        }
    }

    private void addToggleRow(Component label, String verb, BooleanSupplier getter, Consumer<Boolean> setter) {
        Button button = Button.builder(toggleLabel(label, verb, getter.getAsBoolean()), b -> {
                    boolean newValue = !getter.getAsBoolean();
                    setter.accept(newValue);
                    b.setMessage(toggleLabel(label, verb, newValue));
                })
                .pos(this.width / 2 - 100, 0)
                .size(200, 20)
                .build();
        this.rowList.add(new Row(List.of(button)));
    }

    private static Component slotLabel(int slot, boolean idle) {
        String n = String.valueOf(slot + 1);
        return Component.literal(idle ? "[" + n + "]" : n);
    }

    private static Component soundLabel(boolean enabled) {
        return Component.translatable(LANG + "collision_sound",
                Component.translatable(LANG + (enabled ? "on" : "off")));
    }

    private static Component toggleLabel(Component weaponLabel, String verb, boolean atWaist) {
        // verb is "sheathe" or "carry" → keys gui.<modid>.toggle.sheathe / .carry
        // "worn" is an on/off switch (the parameter is then "shown"), not a waist/back choice
        String choice = (verb.equals("worn") || verb.equals("equip_sheathed")) ? (atWaist ? "on" : "off") : (atWaist ? "waist" : "back");
        return Component.translatable(LANG + "toggle." + verb, weaponLabel,
                Component.translatable(LANG + choice));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        this.rowList.render(guiGraphics, mouseX, mouseY, partialTicks);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    // Plain ContainerObjectSelectionList — no selection highlight, no header;
    // just a scrollable column of Row entries.
    private static class RowList extends ContainerObjectSelectionList<Row> {
        RowList(net.minecraft.client.Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight) {
            super(minecraft, width, height, y0, y1, itemHeight);
        }

        // addEntry is `protected` on AbstractSelectionList (a different
        // package) — code in the enclosing EfsmConfigScreen isn't a subtype
        // of that class (only RowList itself is), so it can't call addEntry
        // directly. This thin wrapper, called from within RowList's own
        // class body, is what makes that legal — same reason vanilla's
        // OptionsList exposes addBig/addSmall instead of addEntry itself.
        void add(Row row) {
            this.addEntry(row);
        }
    }

    // One row: either a single wide toggle button, or several small buttons
    // side by side (the hotbar-slot row), or no widgets at all (a visual
    // spacer between groups). Every widget's Y is set to the row's current
    // top on each render call, exactly like vanilla's own OptionsList.Entry —
    // that's what lets the same widgets scroll smoothly with the list.
    private static class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final List<AbstractWidget> widgets;
        private final Component label; // non-null for a plain text row (no widgets)

        Row(List<AbstractWidget> widgets) {
            this(widgets, null);
        }

        private Row(List<AbstractWidget> widgets, Component label) {
            this.widgets = widgets;
            this.label = label;
        }

        static Row spacer() {
            return new Row(List.of());
        }

        static Row label(Component text) {
            return new Row(List.of(), text);
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                            int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (label != null) {
                guiGraphics.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                        label, left + width / 2, top + (height - 8) / 2, 0xA0A0A0);
            }
            for (AbstractWidget widget : widgets) {
                widget.setY(top);
                widget.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}
