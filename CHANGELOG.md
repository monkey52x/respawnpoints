# Changelog

## 1.0.8 — Fabric JourneyMap block, settings polish

- Added: Fabric blocks launch with JourneyMap installed (loader incompatibility window).
- Changed: clearer YACL names for the random-spawn options; the enable switch moved right after the bed limit; the no-YACL button now says Continue.
- Added: mod icon bundled in the jar (auto-copied from the logo on every build); preview images for the two show-toggles in the settings screen.

## 1.0.7 — Universal per-point cooldowns

- Added: cooldowns on any point (`neardeath`, `spawn`, `warp:<name>`, beds/anchors) with admin commands `admin player <player> cooldown <id> <get|reset|set|add|remove> [seconds]` and the `offline` branch (warp ids complete quoted; editing the dead refreshes their screen live). Expired cooldowns clean themselves up.
- Changed: any entry can show a cooldown (gray + live timer); spent anchors still show “depleted” first.
- Fixed: the missing `cooldown` word in the command path.

## 1.0.6 — Near-death gate fix + master switch

- Added: `enableNearDeath` master switch; live ticking cooldown timer; the server sends the update itself when the cooldown hits zero.
- Fixed: saved (but unloaded) chunks count for the search again; config key order; `admin reload` prints all 7 settings.

## 1.0.5 — Cooldown protocol cleanup

- Changed: internal encoding cleanup, no visible changes.

## 1.0.4 — Near-death rework

- Added: the random spot only lands on existing chunks (nothing new is generated); safe / risky / swim passes; Nether roof spawns on top; 5-minute cooldown after use; the entry is hidden when nothing suitable is around. Defaults are now 512–1024.

## 1.0.3 — Frozen confirm state

- Added: after confirming, the button turns dark green and the screen ignores clicks until vanilla takes over.

## 1.0.2 — Code health (no behavior changes)

- Changed: internal code cleanup, no visible changes.

## 1.0.1

- Empty death offer is silent: instant vanilla fallback, no toast, no stuck screen;
  vanishing last point returns to the vanilla death screen; all-depleted offers pass through.
- Player data no longer wiped on logout (save-then-evict); login heals desynced snapshots.
- Offline admin: `beds`, `bind`, `unbind` accept nicks/UUIDs of offline players, plus
  `offline` branches with offline-only Tab-complete; no Mojang lookups, garbage input
  fails instantly without stalling the server.
- Selectors back in plain branches (`@p/@s/@r`, vanilla errors).
- Rename Save triggers a waypoint sync and skips unchanged names; per-action rate limits.
- `admin tp warp <id>` without colon pain; `showNearDeath` default off.
- Correct errors: unknown player vs unknown point; repeat bind reports already-bound.

## 1.0.0 — Initial release (Minecraft 1.21.1, NeoForge)

- Death respawn selection screen: full-map view (Xaero) with sidebar, compact list fallback.
- Points: World Spawn, beds, respawn anchors with live charge counts, admin warps, near-death random spot (off by default).
- Beds & anchors: rename (Shift + Right-click), recolor via map menu, per-player names, anti-misclick blocklist.
- Xaero waypoint sync, two-way for colors.
- Commands: `warp`, `bed`, `spawn`, `admin` (list/reload/beds/bedinfo/tp/unbind/bind), `debug inspect`.
- Server config `respawnpoints-server.json` with live reload; host settings editable from the main menu (YACL).
- Server-authoritative validation with clean fallbacks; English + Russian localization.
- Requires both Xaero's Minimap and Xaero's World Map. JourneyMap is not supported.
