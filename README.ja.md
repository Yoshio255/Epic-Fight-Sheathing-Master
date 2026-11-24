# Epic Fight Sheathing Master

[English](README.md)

[Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod) の追加 MOD です。手に持っていない武器を、**体に納めた状態**(腰・背中・太もも)で表示します。

- 待機スロットの武器は、別の物を持っている間も、すべて体に表示されます。刀と太刀は鞘付き、短剣は太もも、大剣や槍は背中、のように置かれます。
- 武器と鞘には**当たり判定**があります。鞘がブロックに触れると、カチャッと音が鳴ります。
- 武器ごとの携行位置(**腰か背中か**)は、設定画面で切り替えられます。

## 動作条件

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47 以上 |
| [Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod) | 20.14.17 |

## 任意の連携

どれも必須ではありません。入れていれば、その武器も体に表示されます。

- **EpicFight Nightfall(EFN)**
- **Epic Fight: Avalon**
- **Weapons of Miracles(WoM)** — 完全対応です。武器ごとに専用の位置があり、ほとんどに当たり判定があります。専用の 3D の鞘があるものもあります(Evil Tachi、Hollow Longsword、Satsujin)。Moonless は、持ち手が右腰、刃が背中で浮かぶ形です。

任意の依存として、バージョンの制限なしで宣言しています。どのバージョンでも使えますが、自己責任です。

## 導入

jar を `mods` フォルダに入れてください(Epic Fight も一緒に)。

## 設定

**Mods → Epic Fight Sheathing Master → Config** を開きます。MOD ごと(Epic Fight、EFN、WoM、…)にタブがあり、武器の種類ごとの「腰/背中」の切り替えや、当たり判定の音の切り替えがあります。

表示するスロット(初期値はホットバーの 1 番だけ)は、`config/epic_fight_sheathing_master-common.toml` の `idleSlots` で決めます。

### 表示する武器

`config/epic_fight_sheathing_master-allowlist.json` で、表示の対象を決めます。

| キー | 意味 |
|---|---|
| `add` / `remove` | 待機表示を、付ける/外す武器 |
| `sheath_add` / `sheath_remove` | 鞘も、付ける/外す武器 |

書き方は、MOD 全体なら `"modid:*"`、1 つなら `"modid:item_id"` です。編集したら、`/efsm reload` を実行してください。

## コマンド — デバッグと調整用です。プレイヤー向けではありません

> **`/efsm` コマンドは、ほとんど開発用の道具です。** MOD を作る時に、武器の位置や当たり判定を調整するためのもので、**普段の遊びには使いません**。MOD を使うのに必要なものではなく、変更した内容は保存されません(ゲームを再起動すると元に戻ります)。実行にはオペレーター権限(レベル 2)が必要です。

| コマンド | 内容 |
|---|---|
| `/efsm reload` | allowlist ファイルを読み直す(開発以外でも使えるのは、これだけ) |
| `/efsm tune <key> <field> <value>`、`print`、`reset` | 武器の位置(移動・回転・拡大)を、その場で編集する |
| `/efsm hitbox` | 当たり判定の箱の表示を切り替える |
| `/efsm hitbox <tag> size / offset / step <axis> <value>`、`count`、`print`、`reset` | 当たり判定の箱の並びを、その場で編集する |

## ライセンス

GPL-3.0-or-later。[LICENSE](LICENSE) を見てください。

作者: Yoshio255
