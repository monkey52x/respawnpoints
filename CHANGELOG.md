# Changelog

## 1.0.9 — 3-step handshake, JourneyMap support, instant sync

### Added
- **JourneyMap waypoint sync (API v2.0.0)** — respawn points in dedicated "Respawn Points" group, two-way colour sync
- **3-step handshake** — `ServerHelloPacket` → `ClientReadyPacket` → `RespawnListPacket`. No race conditions, instant initial sync
- **Instant initial sync** — `ClientReadyPacket` sent immediately on join (in `onHello`), server responds with `RespawnListPacket` instantly
- **JourneyMap deferred sync** — entries cached before JM ready, synced on `MAPPING_STARTED`
- **Instant sync for alive players** — `isInitialSync` flag gives one-time marker sync on join
- **JourneyMap plugin** — handles waypoint events + mapping lifecycle for auto group management
- **JourneyMap removed from incompatibilities** — mod loads with JM installed
- **New config** — `nearDeathMaxScansPerTick` (1–64, default 8), `enableNearDeath` master switch, `showNearDeath`

### Changed
- **Restored `ServerHelloPacket`** — 3-step handshake: `ServerHello` → `ClientReady` → `RespawnList`. No race conditions
- **Mandatory map-manager choice** — no "None" option. Three modes: Xaero, JourneyMap, Disabled
- **Mandatory map choice screen** — cannot close with Escape. "Disabled" always available
- **YACL map choice** — removed "None", default reset = "Disabled", internal `NONE` hidden
- **DISABLED mode valid** — entry allowed. Disconnect only when `NONE` (not chosen)
- **Map choice at main menu** — fixed `client.screen == null` bug (main menu = `MainMenuScreen`)
- **JourneyMap group recreation** — auto-recreate on delete, waypoints re-linked via Waystones pattern
- **JourneyMap GUID preservation** — recreation re-links via `addWaypoint → setLocked(false) → group.addWaypoint → setLocked(true)`
- **JourneyMap reconciliation** on `MAPPING_STARTED` — re-binds all tracked waypoints
- **JourneyMap state cleanup split** — `MAPPING_STOPPED` clears runtime only; full cleanup on server switch only
- **JourneyMap waypoints in "Respawn Points" group** — Waystones pattern: `addWaypoint` → `setLocked(false)` → `addWaypoint` → `setLocked(true)`
- **`clearTracking()` on `MAPPING_STOPPED`** no longer resets group
- **MapChoiceScreen at main menu** — fixed `client.screen == null` bug (main menu = `MainMenuScreen`)
- **Join blocking only for `NONE`** — `DISABLED` allows entry
- **Hard Xaero dependency removed** — works with JM, Xaero, or none (standalone)
- **Four map-manager modes** — `NONE` (prompt), `XAERO`, `JOURNEYMAP`, `DISABLED` (built-in)
- **YACL "Map" category** only in main menu (`level == null`)
- **MapChoiceScreen for any installed map** — shows for `NONE`, buttons for installed mods + "Disabled"
- **JourneyMapWaypointSync refactored** — `Map<String, String>` (entryId → GUID), `createClientWaypoint()`, buffered sync before `MAPPING_STARTED`
- **JourneyMap colour sync = Xaero** — `LAST_KNOWN_COLOR` tracks our writes; user edit = `RecolorPointPacket`
- **MapIntegrations sync skipped for `NONE`/`DISABLED`** — JM cache cleared only for `JOURNEYMAP` mode
- **MapManagerChoice decoupled from server** — global client choice in `RespawnClientConfig`
- **`PendingRespawn.disarm()`** preserves `emptyOffer` flag
- **`ClientReadyPacket` immediate on join** — no connection blocking, vanilla respawn works
- **`RespawnJourneyMapPlugin`** unsubscribed from `CommonEventRegistry.WAYPOINT_GROUP_EVENT` (wrong bus)
- **`enableNearDeath` removed from incompatibilities**
- **Stale `hasEmptyOffer` cleared** on `disarm()` + DeathScreen open

### Fixed
- Stale `hasEmptyOffer` → cleared on `disarm()` + DeathScreen open
- JourneyMap waypoint recreation on group delete — no duplicates
- JourneyMap waypoint persistence — recreate with new name/color persists to JM save
- **Fabric DISCONNECT crash** — client-side handler in `RespawnPointsFabricClient`
- **Rate limit on `onReady()`** — initial `ClientReadyPacket` never throttled; cleared on join
- **Xaero session tracking** — `cachedSession` cleared in `clearTracking()`
- **JourneyMap tracking cleanup** — `pendingEntries` cleared on `MAPPING_STOPPED` + disconnect

### Removed
- `None` map choice option — removed from UI/YACL (internal `NONE` remains)

## 1.0.8 — Fabric JourneyMap block, settings polish
... (unchanged)