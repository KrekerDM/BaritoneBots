[Русский](README.md) · **English**

# BaritoneBots

![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62B47A)
![Baritone](https://img.shields.io/badge/Baritone-1.19.0-3B82C4)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5-DBD0B4)
![Java](https://img.shields.io/badge/Java-25-E76F00?logo=openjdk&logoColor=white)
![Tests](https://img.shields.io/badge/Tests-211-2EA043)
![Status](https://img.shields.io/badge/Status-beta-D9A400)
![License](https://img.shields.io/badge/License-MIT-555555)

Bots for Minecraft Java 26.2 that work out what a job needs. Before a task the
bot checks its inventory and walks to the chests for the right pickaxe, food and
materials, instead of standing there with empty hands and a "nothing to mine
with" error.

Each bot is a real Minecraft client without a window that moves and digs
through Baritone. You control them from a web panel on the PC that runs the
bots. Pick what should happen — "build this schematic", "keep 128 torches in
storage", "progress to iron" — and the manager splits the work between bots.

## What it does

- **27 bot tasks**: walking, following, mining, farming, clearing, filling
  areas, schematic building, chests, crafting, smelting, animals, combat,
  guarding, going back for items after death, any Baritone command.
- **Finds chests by itself** around the bot and home, looks inside and remembers
  what is where. No manual tagging of storage.
- **Auto-supply**: before a task the bot fetches a tool of the right tier, food,
  blocks and materials. A broken pickaxe gets replaced.
- **Auto-sort**: loot from inbox chests goes into 9 categories; an empty chest
  takes the category of what it already holds.
- **Goals instead of commands**: "obtain a wooden pickaxe" or "progress to iron".
  Recipes, drops and tool tiers come straight from the 26.2 game files: 1491
  recipes, 1113 loot tables.
- **7 kinds of shared projects**: build, gather to a quota, clear, farm, ranch,
  sort, smelt. Ten roles are assigned automatically and move to where hands
  are missing.
- **Standing orders, schedules, if-then rules**: "always 64 bread in storage",
  "harvest every 30 minutes", "health below 6 — go home".
- **Anti-xray servers**: with hidden ores the bot branch-mines at the best height
  and only takes ore it can see.
- **Companion plugin** for your own server: token check for bots, block journal
  with rollback, AuthMe/nLogin auto-login. Everything else works without it.

## Warning

This is a beta. Mod, manager and plugin build and pass 211 tests, but some
features have not been tried in a live game yet, and the panel has no screens
for projects, schedules and rules yet — use the API for those. The table below
lists what was checked on a real server.

There is no anticheat evasion. Running Baritone on someone else's server is at
your own risk and subject to that server's rules.

## Checked on a server

Paper 26.2 build 129, offline mode, flat world, 2026-10-04:

| What | Result |
|---|---|
| Bot start until it joins | 50–65 s |
| `goto` over 22 blocks | 4 s, stopped 2 blocks from the target, tolerance 2 |
| Gear from a chest | took the iron set, sword, shovel and 8 of 16 bread, put the armour on |
| `mine` dirt, target 6 | collected 9, picked the shovel itself |
| Goal "wooden pickaxe" from an empty inventory | logs → planks → crafting table, placed it → sticks → pickaxe, 118 s |
| Auto-supply | inspected 3 chests, took logs, crafted 8 planks |
| Idle autopilot | carried raw iron from storage into a furnace by itself |
| Companion plugin | bot passed the token check, journal answers |

Not tried in game yet: schematic building, animals, a full smelting round,
multi-bot projects, Microsoft accounts.

## Resource use

| Process | RAM |
|---|---|
| bot game | 900 MB, of which heap 300–430 MB with a 1024 MB cap |
| HeadlessMC launcher | 102 MB per bot |
| manager | 111 MB in total |

An idle bot uses 0.07 CPU seconds per second, about 7 % of one core. Five bots
come to about 5 GB of RAM. The mod skips frame rendering and sounds and keeps
render distance at 4 chunks. Memory per bot follows a preset: eco 768 MB,
normal 1024 MB or performance 2048 MB.

## Quick start

### Release build

1. Install Java 25, for example Temurin.
2. Download `baritonebots-manager-*.jar` from [Releases](https://github.com/KrekerDM/BaritoneBots/releases) into an empty folder.
3. Run:
   ```bash
   java -jar baritonebots-manager-0.2.0-beta.1.jar
   ```
   The panel opens in the browser; the manager prints the address with the token.
4. Settings → Servers: server address. Turn on anti-xray in the profile if the
   server hides ores.
5. Settings → Bots: add bots with offline accounts.
6. Settings → Runtime → Install. The manager downloads HeadlessMC, Fabric,
   Fabric API, Baritone, FerriteCore and Minecraft 26.2.
7. Start the bots and give tasks from the bot page.

Data lives in `BaritoneBots-data` next to the jar: settings, the panel token,
bot passwords. Do not publish that folder.

### From source

```bash
./gradlew build
```

Requires Java 25. Jars land in `manager/build/libs`, `bot-mod/build/libs` and
`companion-plugin/build/libs`; the mod is already inside the manager jar.

## On your own server

- **AuthMe / nLogin.** The bot types `/register` and `/login` itself with a
  password the manager generates per bot (Settings → Bots).
- **DiscordSRV.** If the server requires linking, add the bots to
  `plugins/DiscordSRV/linking.yml` → `Bypass names`.
- **GrimAC.** Exempt your bots with LuckPerms:
  ```
  lp creategroup bots
  lp group bots permission set grim.exempt true
  lp user Bot1 parent add bots
  ```
  Grim checks the permission every tick, so no rejoin is needed. In offline mode
  it follows the name, so give bots long passwords. With the companion plugin you
  can grant `grim.exempt` only to bots that passed the token check.
- **Companion plugin.** Install and settings: [docs/PLUGIN.md](docs/PLUGIN.md).

## How it works

| Part | What it does |
|---|---|
| `manager` | One jar: installs Minecraft, Fabric and mods through HeadlessMC 2.10.0, starts and restarts bots, keeps queues, projects, autopilot and world knowledge, serves the panel on `127.0.0.1:8765`. |
| `bot-mod` | Fabric mod inside each bot: runs tasks through the Baritone API, reports status, logs in, reconnects, eats, fights back, respawns. |
| `companion-plugin` | Optional Paper 26.2 plugin for your own server. |
| `common` | Protocol between the parts and a reader for `.schem` and `.litematic`. |

The manager talks to bots over TCP on `127.0.0.1`; the server needs no extra
port. Every 5 seconds the planner hands work to free bots, and manual tasks
always win over project work. Protocol and every setting: [docs/SPEC.md](docs/SPEC.md);
decisions taken during development: [docs/dev/DECISIONS.md](docs/dev/DECISIONS.md).

## Limits

- One bot is one process of about 1 GB. Baritone cannot run several bots in one JVM.
- Bots walk; there is no teleporting.
- The manager knows what is in a chest only after a bot has opened it; a new
  storage gets inspected first.
- Honest resources only: whatever a bot cannot mine, craft or smelt ends up on a
  "please supply" list.
- Mining under anti-xray is slower: the bot branch-mines instead of heading to the ore.
- The AI dispatcher through a local Ollama and the project screens in the panel
  are still in progress.

## Licence

MIT. Baritone is LGPL-3.0; the manager downloads it separately and it is not
part of this repository.
