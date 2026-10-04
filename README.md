# BaritoneBots

[Русский](README.ru.md)

Bots for Minecraft Java 26.2 that move and work through Baritone 1.19.0. Each bot is a real client without a window. You control them from the manager's web panel on the PC that runs the bots. Press a button and a bot walks to a chest, takes armour, puts it on and goes mining.

Tested 2026-10-04 on a local Paper 26.2 build 129, offline mode, flat world:

| Task | Result |
|---|---|
| Bot start until it joins the server | 50 s |
| `goto` over 22 blocks | 4 s, stopped 2 blocks from the target, tolerance 2 |
| `take` from a chest | took 4 armour pieces, a sword, a shovel and 8 of 16 bread |
| `equip` | put on 4 armour pieces |
| `mine` dirt, target 6 | collected 9, picked the shovel by itself |
| `deposit` | stored 9 dirt, kept armour and bread per the `keep` rule |

## Parts

| Part | What it does |
|---|---|
| `manager` | One jar. Installs Minecraft, Fabric and the mods through HeadlessMC 2.10.0, starts and restarts bots, keeps task queues, scenarios, kits and world knowledge. Serves the web panel on `127.0.0.1:8765`. |
| `bot-mod` | Fabric mod inside every bot. Runs tasks through the Baritone API, reports status, logs in, reconnects, eats, fights mobs and respawns. |
| `common` | The protocol between them and a reader for `.schem` and `.litematic` schematics. |

The manager talks to the bots over TCP on `127.0.0.1`. The server needs no extra port; it sees the bots as ordinary players.

## Resource use

Measured with one bot, heap capped at 1024 MB:

| Process | RAM |
|---|---|
| bot game | 900 MB, of which heap 300–430 MB |
| HeadlessMC launcher | 102 MB per bot |
| manager | 111 MB in total |

An idle bot uses 0.07 CPU seconds per second. Five bots come to about 5 GB of RAM. There is no window: the mod skips frame rendering and sounds and keeps render distance at 4 chunks. Memory per bot follows a preset: eco 768 MB, normal 1024 MB or performance 2048 MB.

## Running it

1. Install Java 25, for example Temurin.
2. Download `baritonebots-manager-<version>.jar` from Releases into an empty folder.
3. Run:
   ```bash
   java -jar baritonebots-manager-0.1.0.jar
   ```
   The manager opens the panel in your browser and prints the address with the access token to the console.
4. In the panel: Settings → Servers → server address. Bots → add bots with offline accounts.
5. Settings → Runtime → Install. The manager downloads HeadlessMC, Fabric 0.19.5, Fabric API, Baritone, FerriteCore and Minecraft 26.2.
6. Start the bots and give them tasks from the bot page.

Data lives in `BaritoneBots-data` next to the jar: settings, the panel token, bot passwords, downloads. Do not publish that folder.

## On your own server

* **AuthMe / nLogin.** The bot reads the register or login prompt in chat and types `/register` or `/login` itself, with a password the manager generates per bot. Prompt patterns and commands are configurable per server profile.
* **GrimAC.** If you run the server, exempt the bots with LuckPerms:
  ```
  lp creategroup bots
  lp group bots permission set grim.exempt true
  lp user Bot1 parent add bots
  ```
  Grim checks `grim.exempt` every tick, so no rejoin is needed. After you remove it, the bot has to rejoin. In offline mode the permission follows the name, so give bots long AuthMe passwords.
* **Companion plugin (optional).** If you run a Paper 26.2 server, `baritonebots-companion-<version>.jar` gives token-verified bots smaller view distances, permission nodes such as `grim.exempt` (only for verified bots, not for anyone using a bot's name), AuthMe / nLogin auto-login, name protection by IP, a block journal and rollback. See [docs/PLUGIN.md](docs/PLUGIN.md).
* **Other servers.** The project contains no anticheat evasion. Running Baritone on someone else's server is at your own risk and subject to that server's rules.

## Tasks

`goto`, `goto_player`, `follow`, `explore`, `mine`, `farm`, area clearing, `collect_drops`, `take`, `deposit`, `inspect`, `equip`, `eat`, `recover` (go back for items after death) and `baritone` — any Baritone command without `#`. Manager steps: `kit` (gear from chests with the "kit" role), `deposit_storage`, `home`, `wait`, `goto_waypoint`. Scenarios chain these steps and can repeat.

## Not there yet

* Shared projects: building a schematic with several bots, automatic roles. That is phase 2.
* Crafting, smelting, animals and storage sorting.
* Microsoft accounts for online-mode servers. Offline accounts only for now.
* Several bots in one JVM. Baritone cannot do it, so each bot is its own process of about 1 GB.
* A test on a live public server: every number above comes from the local test server.

## Building

```bash
./gradlew build
```

Requires Java 25. Output: `manager/build/libs/baritonebots-manager-<version>.jar` with the mod inside, and the optional server plugin `companion-plugin/build/libs/baritonebots-companion-<version>.jar`. Design and protocol: [docs/SPEC.md](docs/SPEC.md); decisions taken during development: [docs/dev/DECISIONS.md](docs/dev/DECISIONS.md).

## Licence

MIT. Baritone is LGPL-3.0; the manager downloads it separately and it is not part of this repository.
