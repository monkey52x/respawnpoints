# Changelog

## Unreleased — переработка выбора карты и JourneyMap-синка (не в релиз, версия не меняется)

- Changed: четыре режима выбора карты в `RespawnClientConfig.ActiveMap`: `NONE` (по умолчанию), `XAERO`, `JOURNEYMAP`, `DISABLED`. `NONE` — менеджер не выбран, при каждом запуске показывается окно выбора. `DISABLED` — встроенное меню мода без интеграции с картами, окно выбора не появляется. Даёт: полный контроль над тем, какая карта (если вообще какая) используется для маркеров и экрана респауна.

- Changed: YACL категория «Карта» видна только в главном меню (`minecraft.level == null`). В одиночном мире и на сервере категория полностью скрыта. Даёт: настройка выбора карты не мешает в игре и не отображается на чужом сервере.

- Fixed: `MapChoiceScreen` теперь показывается когда `activeMap() == NONE`, независимо от количества установленных картомодов (ранее требовались обе сразу). В окне есть кнопки только для тех модов, которые реально установлены, плюс «Disabled». Закрытие через Escape оставляет `NONE` в конфиге и только подавляет повторное показ в текущей сессии — окно появится снова только после перезапуска клиента. Даёт: окно выбора больше не пропадает, работает корректно при любой конфигурации картомодов.

- Changed: `JourneyMapWaypointSync` переработан по паттерну Xaero-синка. Вме хранение объектов `Waypoint` в памяти заменено на `Map<String, String>` (entryId → JourneyMap GUID). Используется `WaypointFactory.createClientWaypoint()` вместо `createWaypoint()`. На `MAPPING_STARTED` Tracking восстанавливается из API JourneyMap через чтение custom data `respawnpoints:id`. Если данные для синка пришли раньше `MAPPING_STARTED`, они буферизуются и синк выполняется сразу после готовности JourneyMap. Даёт: метки не теряются при перезагрузке JourneyMap, корректная работа даже если оффер точек пришёл раньше инициализации карты.

- Fixed: цвет метки в JourneyMap синхронизируется как в Xaero — `LAST_COLOR` отслеживает последний цвет, который мы записали в карту; несовпадение означает правку пользователя в интерфейсе JourneyMap, и только тогда уходит `RecolorPointPacket` на сервер. `internalUpdating` блокирует наши программные записи, чтобы они не интерпретировались как действия пользователя и не вызывали обратную отправку пакетов. Даёт: нет лишнего сетевого трафика, нет «перетягивания» цвета между сервером и картой.

- Changed: `MapIntegrations.syncClientWaypoints()` не синкает метки никуда при режимах `NONE` и `DISABLED`. Очистка кэша JourneyMap в `ClientHandlers` выполняется только когда активен режим `JOURNEYMAP`. Даёт: лишние запросы к JourneyMap API не выполняются, когда карта не выбрана или отключена.

- Changed: `MapManagerChoice` больше не привязан к конкретному серверу (убран `currentServer`, `onHello(UUID)`, серверный конфиг выбора). Выбор глобальный для клиента, хранится в `RespawnClientConfig`. Метод `resolve()` возвращает сохранённый выбор, сверяя с тем, что реально установлено. Даёт: простая предсказуемая логика, выбор сделан один раз на клиенте.

- Fixed: `PendingRespawn.disarm()` больше не сбрасывает флаг `emptyOffer`. Логика быстрого ванильного фолбэка при пустом оффере не ломается.

- Fixed: `ClientReadyPacket` отправляется сразу при входе на сервер, не зависит от выбора карты. Подключение никогда не блокируется, ванильное возрождение работает всегда.

- Fixed: `RespawnJourneyMapPlugin` больше не подписывается на `CommonEventRegistry.WAYPOINT_GROUP_EVENT` (общая/серверная шина, не для клиентского плагина). Восстановление группы после удаления пользователем через `ensureGroup()` при следующем синке.

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
