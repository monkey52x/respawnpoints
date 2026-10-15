# Changelog

## 1.0.4 — Near-death rework (explored chunks only)

- Random spot only lands where chunks are loaded now or saved FULL on disk
  (vanilla `chunkScanner` gate with a Status check, shared region handles,
  per-search probe cache); new chunks are never generated, so no 3x3 map
  squares and no drift into the unknown.
- Three passes: dry-safe (heightmap first, then a small cave scan; lava, fire,
  cactus, magma, campfires, powder snow banned), dry-risky (walkable danger,
  lava/fire/water still banned), swim (water surface, no bubble columns).
  Above the Nether roof spawns straight onto the roof top.
- Nothing found means banned: the random entry is not offered (cheap
  feasibility precheck in the death offer), no more corpse fallback.
- 5-minute cooldown after using the random spot (Rust-style,
  `nearDeathCooldownSec`, 0 = disabled): the entry stays visible but gray with
  a live timer snapshot, unselectable on both screens, server-rejected if
  forged; survives logout via player NBT.
- Defaults are now 512–1024 (`nearDeathRadius=1024`, `nearDeathMinDistance=512`;
  clamps 16–2048 / 0–1024, YACL sliders match); sqrt-distributed ring sampling.
- Attempts and disk loads are bounded (48/32/16 tries, 8 loads max), all
  memory-first; `>200ms` searches still warn in the log.

## 1.0.3 — Frozen confirm state

- Xaero death screen: after Confirm the bottom-left button turns dark green
  (frozen) instead of gray and keeps the confirm label; no centered text.
- While frozen, all sidebar clicks, scroll and the dimension dropdown are
  swallowed until vanilla takes over (or a fresh server offer unfreezes).
- Rejected choices still unfreeze via a new server offer (`resetForNewOffer`).

## 1.0.2 — Code health (no behavior changes)

- Server click logic unified: Fabric and NeoForge share `ServerInteractionLogic`
  (capture → anchor-touch → charging refresh → shift-rename); loaders are thin adapters.
- Selection screens share ordering and delete: `RespawnScreenModel.sorted()`
  (near-death first, spawn last, rest by distance) and two-step `DeleteArm`;
  the fallback list now sorts like the Xaero view; charge texts share `ScreenTexts`.
- Point checks unified in `PointValidator` (bed/anchor/warp predicates, lenient
  display vs strict select); `DeathManager` delegates, charge refresh reuses it.
- Small dedups: single rate-limit path in `ServerHandlers`, `Slugs.slugify()`
  for warp ids, direct `ScreenUtils`/`EntryNames`/`MapIntegrations` calls (no proxies).
- Shared constants: `Limits` (ids, names, rate-limit) and `UiConstants` (layout).
- Warp NBT gains `Version` with a corruption log on load; empty-offer fallback
  is a named `ClientFeedback.fallBackToVanillaSilent()` policy.

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
