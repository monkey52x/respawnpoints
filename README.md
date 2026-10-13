# Respawn Points

<div align="center" dir="auto">
  <a href="https://modrinth.com/project/respawnpoints" rel="nofollow">
    <img alt="Download on Modrinth" src="https://img.shields.io/modrinth/v/respawnpoints?label=Modrinth&color=00AF5C&logo=modrinth" style="max-width: 100%;">
  </a>
</div>

Pick where you wake up. On death, the vanilla screen becomes a point-selection screen: World Spawn, your beds, charged anchors, admin warps, or a random near-death spot. Server-validated, vanilla respawn logic.

## Requirements

- NeoForge for Minecraft 1.21.1
- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map)
- [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap)
- Required on both client and server.
- ❌ Not compatible with [JourneyMap](https://modrinth.com/plugin/journeymap) — the game will refuse to load with it installed.
- Optional: [YACL](https://modrinth.com/mod/yacl) for the settings screen.

## Installation

1. Install [NeoForge](https://neoforged.net/) for Minecraft 1.21.1.
2. Install Xaero's World Map and Xaero's Minimap.
3. Drop `respawnpoints-neoforge-1.21.1-1.0.0.jar` into the `mods` folder (client **and** server).
4. (Optional) Install YACL to configure the mod in-game.

## Features

- **Selection screen on death** — full-map view (Xaero) with a sidebar, or a compact list fallback. Distance, dimensions and anchor charges at a glance.
- **Beds & anchors** — bind by sleeping / charging the anchor. Rename with Shift + Right-click, recolor via the map menu, per-player names. Blocklist beds you never want bound (`bed block`).
- **Admin warps** — server-wide points with teleport, visible to everyone.
- **Near-death random spot** — optional random revive near your death point (off by default).
- **Waypoints** — every point syncs to Xaero's map, both ways for colors.
- **Server-authoritative** — every choice is re-validated on select; broken beds, spent anchors and stale picks fall back cleanly.

<details>
<summary>Commands & config</summary>

Warps (admin):
- `/respawnpoints warp add <name>` — create a warp where you stand
- `/respawnpoints warp del <id>` — delete a warp
- `/respawnpoints warp list` — list all warps

Beds & anchors (your own points):
- `/respawnpoints bed list` — show your tracked beds and anchors
- `/respawnpoints bed rename <id> custom <name>` — set a personal name
- `/respawnpoints bed rename <id> default` — reset the name
- `/respawnpoints bed remove <id>` — unbind a point
- `/respawnpoints bed block` — next clicked bed/anchor never binds (anti-misclick)
- `/respawnpoints bed unblock` — next click removes a block
- `/respawnpoints bed blocked` — list blocked spots

World spawn (personal):
- `/respawnpoints spawn hide` / `show` — hide or show the spawn entry
- `/respawnpoints spawn rename custom <name>` / `default` — rename or reset it

Admin:
- `/respawnpoints admin list` — all records with bindings and charges
- `/respawnpoints admin reload` — reload the server config without restart
- `/respawnpoints admin beds <player>` — points of any player
- `/respawnpoints admin bedinfo <id>` — card with unbind/bind buttons
- `/respawnpoints admin tp <id>` — teleport to a point (spawn or bed/anchor)
- `/respawnpoints admin tp warp <id>` — teleport to a warp
- `/respawnpoints admin unbind/bind <id> <player>` — manage anyone's bindings

Debug (admin):
- `/respawnpoints debug inspect` — click a bed/anchor to inspect it

Config `config/respawnpoints-server.json`: `bedLimit`, `nearDeathRadius`, `nearDeathMinDistance`, `showSpawn`, `showNearDeath` — reload live via `admin reload`, host settings also editable from the main menu (YACL).
</details>

## Roadmap

Short version — full text in [ROADMAP.md](ROADMAP.md):

- [ ] Vanilla-fallback cleanup after unbind
- [ ] Near-death rework (long range without chunk loading)
- [ ] Local/global and private warps
- [ ] Search, filters, pins, warp limits, rename from death screen

## Inspired by

- [respawn-selector](https://modrinth.com/mod/respawn-selector) and the game [Rust](https://rust.facepunch.com) — pick-your-spawn done right.

## About development

I don't know how to code, but I had a clear vision for this mod — so it was built entirely with AI assistance. Bug reports with logs are very welcome. Issues: [GitHub](https://github.com/monkey52x/respawnpoints/issues).

## License

MIT — see [LICENSE](LICENSE).
