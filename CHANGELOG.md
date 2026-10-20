# Changelog

## 1.0.9 — JourneyMap support, instant initial sync, ServerHelloPacket removed

### Added
- **JourneyMap waypoint sync (API v2.0.0)** — respawn points appear in a dedicated "Respawn Points" group, never mixed with the user's "Default" group. Two-way colour sync: recolour a marker in JourneyMap and the change is sent back to the server.
- **JourneyMap plugin** (`@JourneyMapPlugin`) subscribes to waypoint events (colour changes, deletions) and mapping lifecycle events (`MAPPING_STARTED` / `MAPPING_STOPPED`) for automatic group creation and cleanup on server switch.
- **Instant initial sync** — `ClientReadyPacket` sent immediately on join when world is already loaded (in `onHello`), instead of waiting for the tick loop (up to 10s). Server responds with `RespawnListPacket` immediately.
- **JourneyMap deferred sync** — entries received before JM is ready are cached (`pendingEntries`) and synced on `MAPPING_STARTED`. Cleaned up on `MAPPING_STOPPED`.
- **Instant initial sync for alive players** — `isInitialSync` flag in `DeathManager.pushUpdate()` allows one-time marker sync for alive players on join without waiting for `READY` set.
- **Map manager choice via YACL** — configured via YACL config screen (`respawnpoints.config.map`) instead of a custom prompt. At first launch in the main menu, the YACL config opens automatically if Xaero or JourneyMap is installed. No auto-detection — the player must pick explicitly.
- **JourneyMap removed from mod incompatibilities** — the mod now loads with JourneyMap installed.
- **New config options** — `nearDeathMaxScansPerTick` (1–64, default 8) to limit disk scans per tick for near-death search; `enableNearDeath` master switch; `showNearDeath` to toggle the entry in the list.

### Changed
- **Removed `ServerHelloPacket`** — server identity tracking moved to client-side detection in `ClientReadiness.tick()`. No extra packet on join.
- **Map-manager choice is now mandatory** — removed "None" option from map choice screen and YACL. Exactly three modes: Xaero, JourneyMap, Disabled. If config is empty/invalid, map choice screen shows on launch.
- **Map choice screen is mandatory** — cannot be closed with Escape. "Disabled" button always available so player cannot get stuck.
- **YACL map choice list** — removed "None" entry, default on reset is "Disabled". Internal `NONE` state no longer shown in UI.
- **DISABLED mode is now valid** — entering a world/server with `DISABLED` no longer disconnects. `DISABLED` = built-in list screen without map integration. Disconnect only happens when map is not chosen at all (`NONE`).
- **Map choice screen shows at main menu** — fixed incorrect `client.screen == null` check (main menu has `MainMenuScreen`, not `null`). Screen shows when `activeMap() == NONE` or `DISABLED`.
- **JourneyMap group recreation** — group "Respawn Points" is recreated if player deletes it in JourneyMap. Plugin re-subscribed to `CommonEventRegistry.WAYPOINT_GROUP_EVENT`. Recreation deferred via `Minecraft.execute(...)` to avoid `ConcurrentModificationException`.
- **JourneyMap GUID mapping preserved on group delete** — group recreation re-links existing waypoints via `addWaypoint → setLocked(false) → group.addWaypoint → setLocked(true)`. No duplicates, waypoints don't drift to Default group.
- **JourneyMap reconciliation on `MAPPING_STARTED`** — all tracked waypoints re-bound to the group. Covers case where group was deleted while mapping was stopped.
- **JourneyMap state cleanup split** — `MAPPING_STOPPED` (including dimension change) clears only runtime bindings and keeps last entry list, so waypoints restore without server round-trip. Full cleanup (including entry list) only on actual server switch/disconnect.
- **JourneyMap waypoints created in "Respawn Points" group** — follows Waystones pattern: `api.addWaypoint()` first, then `group.setLocked(false)` → `group.addWaypoint()` → `group.setLocked(true)`. Waypoints always go to our group, never mixed with user's Default group.
- **JourneyMap `clearTracking()` on `MAPPING_STOPPED` no longer resets group** — group persists in memory so reconnecting without client restart doesn't require group recreation.
- **MapChoiceScreen shows at main menu** — fixed broken `client.screen == null` check (main menu has `MainMenuScreen`, not `null`). Screen shows when `activeMap() == NONE` or `DISABLED`.
- **Join blocking only for `NONE`** — join blocked on server/singleplayer only when `activeMap() == NONE`. `DISABLED` is a valid choice (built-in list screen), entry allowed.
- **Hard dependency on Xaero removed** — `ClientPreconditions.checkMapsInstalled()` no longer throws. Mod loads with JourneyMap, Xaero, or no maps (standalone mode).
- **Four map-manager modes** — `NONE` (default, shows prompt on every launch), `XAERO`, `JOURNEYMAP`, `DISABLED` (built-in menu, no prompt). `NONE` = not chosen, prompts on every launch. `DISABLED` = built-in menu, no prompt.
- **YACL "Map" category visible only in main menu** (`minecraft.level == null`). Hidden in singleplayer world and on remote servers.
- **MapChoiceScreen shows for any installed map mod** — shows when `activeMap() == NONE`, regardless of how many map mods are installed. Buttons only for installed mods, plus "Disabled". Escape close keeps `NONE` in config and suppresses re-show for current session — screen reappears only after client restart.
- **JourneyMapWaypointSync refactored to Xaero-style** — in-memory `Waypoint` objects replaced with `Map<String, String>` (entryId → GUID). Uses `WaypointFactory.createClientWaypoint()`. On `MAPPING_STARTED` tracking restored from JM API via `respawnpoints:id` custom data. If data arrived before `MAPPING_STARTED`, it's buffered and synced after JM ready.
- **JourneyMap colour sync matches Xaero** — `LAST_KNOWN_COLOR` tracks last colour we wrote to map; mismatch means user edited in JM UI, only then `RecolorPointPacket` sent to server. `internalUpdating` blocks our programmatic writes from being interpreted as user edits.
- **MapIntegrations sync skipped for `NONE`/`DISABLED`** — no marker sync when map not chosen or disabled. JourneyMap cache cleared in `ClientHandlers` only when `JOURNEYMAP` mode active.
- **MapManagerChoice decoupled from server** — removed `currentServer`, `onHello(UUID)`, server-side config. Choice is global per client, stored in `RespawnClientConfig`. `resolve()` returns saved choice, validated against installed mods.
- **`PendingRespawn.disarm()` no longer clears `emptyOffer` flag** — vanilla fallback logic for empty death offer preserved.
- **`ClientReadyPacket` sent immediately on join** — independent of map choice. Connection never blocked, vanilla respawn always works.
- **`RespawnJourneyMapPlugin` no longer subscribes to `CommonEventRegistry.WAYPOINT_GROUP_EVENT`** — that's a common/server bus, not for client plugin. Group recreation handled via `ensureGroup()` on next sync.
- **Removed `enableNearDeath` from incompatibilities** — mod now loads with JourneyMap installed.
- **Stale `hasEmptyOffer` flag cleared on `disarm()` and DeathScreen open** — prevents incorrect instant vanilla fallback after previous empty death offer.

### Fixed
- **Stale `hasEmptyOffer` flag** — cleared on `disarm()` and when DeathScreen opens, preventing incorrect instant vanilla fallback after a previous empty death offer.
- **JourneyMap waypoint recreation on group delete** — group recreated, waypoints re-linked without duplicates.
- **JourneyMap waypoint persistence** — recreating waypoint with new name/color persists to JM save file (previously only in-memory update).

### Removed
- **`ServerHelloPacket`** — removed custom packet for server identity. Client now detects server change locally in `tick()`.
- **`None` map choice option** — removed from UI and YACL. Internal `NONE` state still exists but not user-facing.

## 1.0.8 — Fabric JourneyMap block, settings polish
... (rest unchanged)