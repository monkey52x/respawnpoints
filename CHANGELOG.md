# Changelog

## Unreleased — рефакторинг интеграции миникарт (не в релиз, версия не меняется)

- Fixed: `PendingRespawn.disarm()` больше не сбрасывает флаг `emptyOffer`. Раньше флаг стирался при отсроченном снятии оффера (stale-arm sweep), и логика быстрого ванильного фолбэка при пустом оффере ломалась — клиент ждал оффер, который уже пришёл и был пустым. Теперь `emptyOffer` сбрасывается только при полном `clear()` или явном `clearEmptyOffer()` при открытии DeathScreen. Даёт: мгновенный переход к ванильному респауну, когда сервер сказал «точек нет», без застревания на экране смерти.

- Fixed: `ClientReadyPacket` теперь отправляется сразу при входе на сервер, как только мир загружен. Раньше отправка блокировалась, пока игрок не выберет карту (Xaero/JourneyMap/NONE) в окне выбора. Если игрок не делал выбор, сервер не получал READY и не присылал оффер точек — игрок мог застрять при смерти без экрана выбора и без ванильного фолбэка. Даёт: подключение к серверу никогда не блокируется выбором карты, ванильное возрождение работает всегда.

- Changed: выбор карты больше не происходит при входе на сервер. При запуске игры в главном меню мод делает автодетект: если установлен ровно один карто-мод (только Xaero или только JourneyMap), он выбирается автоматически и выбор сохраняется в клиентский конфиг. Если установлены обе карты и выбор ещё не сделан, показывается окно `MapChoiceScreen` прямо в главном меню (не на сервере). Игрок может нажать Escape — выбор «Никакая» тоже сохраняется, окно больше не показывается повторно. Даёт: нет назойливых попапов на сервере, выбор делается один раз и применяется на все серверы.

- Changed: `MapChoiceScreen.shouldCloseOnEsc()` теперь возвращает `true` — можно закрыть окно через Escape. Окно открывается только в главном меню (уровень не загружен), а не при входе на сервер. Даёт: игрок не заперт в окне выбора, удобный UX, нет блокировки при подключении.

- Changed: очистка кэша миникарт (Xaero + JourneyMap) происходит только при смене сервера (по UUID сервера), а не при каждом входе (`onHello`). При повторном входе на тот же сервер метки не мигают и не пересоздаются. Даёт: стабильные метки на карте при переподключении, нет лишних запросов к JourneyMap API.

- Fixed: `JourneyMapWaypointSync` сделан потокобезопасным. `TRACKED_WAYPOINTS` и `LAST_COLOR` заменены на `ConcurrentHashMap`, флаг `internalUpdating` и ссылка `respawnGroup` — `volatile`. В `syncInner` и `ensureGroup()` используется локальная копия ссылки на группу, чтобы另一个 поток не обнулл её между проверкой и использованием. Даёт: нет `ConcurrentModificationException` и race conditions, когда JourneyMap присылает события из фонового потока同时 с клиентским рендером.

- Fixed: `RespawnJourneyMapPlugin` больше не подписывается на `CommonEventRegistry.WAYPOINT_GROUP_EVENT` — это общая/серверная шина событий JourneyMap, клиентский плагин (`IClientPlugin`) не должен её использовать. Восстановление группы после удаления пользователем теперь происходит через `ensureGroup()` при следующем синке. Даёт: корректная работа на стороне клиента, нет неработающих подписок на события, которые никогда не придут.

- Changed: `MapManagerChoice` больше не привязан к конкретному серверу (убран `currentServer`, `onHello(UUID)`, серверный конфиг выбора). Выбор глобальный для клиента, хранится в `RespawnClientConfig`. Метод `resolve()` возвращает сохранённый выбор, сверяя с тем, что реально установлено. Даёт: простая предсказуемая логика, выбор сделан один раз на клиенте.

## 1.0.9 — JourneyMap support + YACL map choice

- Added: JourneyMap waypoint sync (API v2.0.0) — respawn points appear in a
  dedicated "Respawn Points" group, never mixed with the user's "Default"
  group. Two-way colour sync: recolour a marker in JourneyMap and the change
  is sent back to the server.
- Added: JourneyMap plugin (`@JourneyMapPlugin`) subscribes to waypoint
  events (colour changes, deletions) and mapping lifecycle events
  (`MAPPING_STARTED` / `MAPPING_STOPPED`) for automatic group creation and
  cleanup on server switch.
- Changed: map-manager choice is now configured via YACL config screen
  (`respawnpoints.config.map`) instead of a custom prompt. At first launch in
  the main menu, the YACL config opens automatically if Xaero or JourneyMap
  is installed. No auto-detection — the player must pick explicitly.
- Changed: removed JourneyMap from mod incompatibilities (`breaks` /
  `incompatible` dependency) — the mod now loads with JourneyMap installed.
- Fixed: stale `hasEmptyOffer` flag is now cleared on `disarm()` and when the
  DeathScreen opens, preventing an incorrect instant vanilla fallback after a
  previous empty death offer.

## 1.0.8 — Fabric JourneyMap block, settings polish

- Added: Fabric support (restored) — separate jar at parity with NeoForge (selection screen, points, cooldowns, commands); requires Fabric Loader, Fabric API and both Xaero maps.
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
