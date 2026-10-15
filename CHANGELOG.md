# Changelog

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
