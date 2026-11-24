# Epic Fight Sheathing Master

[日本語版はこちら](README.ja.md)

An [Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod) addon that shows your weapons **sheathed on your body** (waist, back or thigh) while they are not in your hand.

- Every weapon in your idle hotbar slot(s) is drawn on your body, also while you hold something else. Katanas and tachis get their sheath, daggers sit on the thigh, greatswords and spears go on the back, and so on.
- Weapons and their sheaths have **collision boxes**: a sheath that touches a block makes a clank sound.
- Where each weapon is carried (**waist or back**) can be switched in the config screen.

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47+ |
| [Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod) | 20.14.17 |

## Optional integrations

None of these is required. Their weapons are also shown on your body when the mod is installed:

- **EpicFight Nightfall (EFN)**
- **Epic Fight: Avalon**
- **Weapons of Miracles (WoM)** — fully supported: every weapon has its own position, most of them have collision, and some have their own 3D sheath (Evil Tachi, Hollow Longsword, Satsujin). Moonless is shown with its handle at the hip and its blade floating at the back.

The mod declares them as optional dependencies without a version limit, so use whichever version you like — at your own risk.

## Installation

Put the jar in your `mods` folder (together with Epic Fight).

## Settings

Open **Mods → Epic Fight Sheathing Master → Config**. The screen has one tab per mod (Epic Fight, EFN, WoM, ...), each with switches such as *carry at the waist / on the back* per weapon type, and a switch for the collision sound.

The slots that are displayed (default: hotbar slot 1 only) are set by `idleSlots` in `config/epic_fight_sheathing_master-common.toml`.

### Which items are shown

`config/epic_fight_sheathing_master-allowlist.json` decides which items get the display:

| Key | Meaning |
|---|---|
| `add` / `remove` | items that get (or lose) the idle weapon display |
| `sheath_add` / `sheath_remove` | items that also get (or lose) a sheath |

Entries are `"modid:*"` for a whole mod or `"modid:item_id"`. Run `/efsm reload` after editing the file.

## Commands — for debugging and tuning, not for players

> **The `/efsm` commands are almost entirely a developer tool.** They exist to tune the positions and collision boxes of weapons while building the mod, and are **not** meant for normal play. You do not need them to use the mod, and nothing you change with them is saved (it is reset when the game restarts). They need operator permission (level 2).

| Command | What it does |
|---|---|
| `/efsm reload` | reloads the allowlist file (the only one that is useful outside of development) |
| `/efsm tune <key> <field> <value>`, `print`, `reset` | live-edit a weapon position (translation / rotation / scale) |
| `/efsm hitbox` | toggles the collision-box overlay |
| `/efsm hitbox <tag> size / offset / step <axis> <value>`, `count`, `print`, `reset` | live-edit a collision-box layout |

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

Author: Yoshio255
