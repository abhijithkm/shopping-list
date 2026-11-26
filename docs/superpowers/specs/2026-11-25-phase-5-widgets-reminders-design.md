# Phase 5 design: widgets, timed reminders and shopping-mode notification

Date: 2026-11-25. Branch: `feature/phase-5-widgets-reminders` (from `develop`, Phase 3 state; sharing is parked, see below).
Status: draft for the owner's approval. Nothing is built until approved.

## 1. Intent and why

The owner wants a simple shopping list that is useful in the three moments a shopping list matters:

1. **Before leaving**: a nudge to look at the list (timed reminder).
2. **In the shop**: the list in front of you without opening the app (home-screen widget, and a notification that stays until the shopping is done).
3. **Glancing**: a count of what is left, from anywhere.

The owner's words: a "sticky notification" that stays "till we mark all of the list checked", in addition to the timed reminders, and "just a simple app" (no sharing for now). Everything here works offline and reads or writes local Room data only. No analytics, no ads, no network in any of these paths.

## 2. Goals, non-goals, anti-goals

**Goals**
- Home-screen widget, one list per widget instance (chosen when the widget is added); tick items off; quick-add an item; open the list in the app.
- Per-list timed reminder: one-time, daily or weekly at a chosen time; optionally skipped when the list is already done.
- Per-list "Shopping mode": an ongoing notification ("Groceries: 5 of 12 left", the next few items, buttons Open list and Mark all done) that goes away by itself when the last item is ticked, or the list is archived or deleted.
- Notification permission asked in context, with a visible "denied" state; the app works fully without it.
- Reminders survive reboot, app update, time-zone change and clock change.

**Non-goals (v1)**
- Reminders or shopping mode synced to other devices (they are local settings, not cloud data).
- Location-based reminders, per-item reminders, snooze, lock-screen widgets, more than one widget size beyond what Glance gives for free.
- Exact alarms (Play restricts them to alarm and calendar apps; see Constraints).
- Any sharing or collaboration notification (Phase 4 is parked on `feature/phase-4-collaboration`, PR 2 draft).

**Anti-goals (a failure even if it technically works)**
- A periodic widget refresh or any polling job that wakes the phone for nothing.
- A foreground service just to make the notification undismissable.
- A notification that keeps beeping on every tick (updates must be silent).
- Widget or notification showing stale data after a tick, a sync, or an app edit.
- A reminder that fires for a deleted or archived list, or twice.

## 3. Constraints (found in the code and docs)

- Stack and versions are fixed: Glance **1.2.0** (1.3.0 is alpha), WorkManager 2.12.0, Room 2.8.5, Hilt, minSdk 24, targetSdk 36. Build and test from PowerShell, not Git Bash.
- `docs/ARCHITECTURE_PROPOSAL.md` decision **D6**: reminders use `AlarmManager.setAndAllowWhileIdle` (inexact, may fire a few minutes late, no exact-alarm permission). The earlier hand-off wording "exact alarm handling" is superseded by D6 (this document's call; the owner can overrule).
- The proposal's "widget writes an outbox op" is outdated: Phase 3 replaced the outbox with dirty-row tracking (`revision > serverRevision`). A widget tick therefore calls the same repository method the app uses (`LocalShoppingRepository.setItemBought`) and sync picks it up like any other edit.
- All writers run in the app process. Sync writes through `SyncAdapters`/DAOs, not only through `LocalShoppingRepository`, so "hook every repository method" would miss sync and migration.
- Room is at v2 (`app/schemas/.../2.json`). Phase 5 adds v3. The parked Phase 4 branch also has a v3; if Phase 4 returns, its schema must be renumbered to v4 on top of this one.
- Android 14+ lets users swipe away an ongoing notification (only foreground services and media are exempt); Android 13 and below do not. Using a foreground service for this is the wrong tool (battery, Play review). So the notification is sticky by default but can be dismissed on 14+, and comes back on the next change.
- Android 13+ needs the runtime `POST_NOTIFICATIONS` permission. `RECEIVE_BOOT_COMPLETED` is a normal permission.
- Gradle daemons plus the emulator are memory heavy on this machine: run `gradlew --stop` before emulator work.

## 4. Approaches considered

| | Approach | Trade-off |
|---|---|---|
| A | Each surface (widget, notification) observes Room itself | Only runs while the process is alive; a tick from a widget or a sync in a closed app can leave the other surface stale; needs extra wake-up code |
| **B** | **One `SurfaceUpdater` that redraws all surfaces when the data changes, debounced (chosen)** | One place holds the rules; debounce gives "no periodic refresh"; works in the app, in a widget callback and in the sync worker |
| C | Periodic WorkManager job refreshes everything | Simplest, but violates "no periodic refresh", wastes battery, and always lags a tick |

**Chosen: B** (the owner picked it). **Refinement (my call):** instead of asking every write path to send a "changed" signal (which sync and migration would silently miss), `SurfaceUpdater` listens to Room's own `InvalidationTracker` for the tables `lists` and `items` (plus the new reminder tables). Every committed write in the process triggers it, whoever wrote. It starts in `Application.onCreate`, which runs in any process start, including one started by a widget tap or an alarm. A test proves that a write through each path (repository, sync adapter apply, migration) triggers a refresh.

## 5. Design

### 5.1 Data (Room v3, auto-migration 2 to 3, new tables only)

- `reminders(listId PK, enabled, repeat NONE|DAILY|WEEKLY, hour, minute, daysOfWeekMask, oneTimeAtMillis, onlyIfItemsRemain, lastFiredAt)`. One reminder per list. Foreign key to `lists` with cascade delete so a purged list loses its reminder.
- `list_prefs(listId PK, shoppingMode)`. Local-only per-list switches. Kept out of `lists` on purpose so the synced entity and its field list (`SyncFields`) do not change.
- Neither table is synced; neither is in the cloud config. Sign-out keeps them (they belong to the device, like guest lists).
- The Glance per-instance list choice (appWidgetId to listId) lives in Glance's own per-widget state, not in Room.

### 5.2 `SurfaceUpdater` (new, `surfaces/`)

- Started from `ShoppingListApp.onCreate`. Collects `invalidationTracker.createFlow(...)`, debounced **500 ms** (merges bursts such as "Mark all done"), then runs `refreshAll()`:
  1. `ShoppingListWidget().updateAll(context)`.
  2. For every list with `shoppingMode = true`: re-post or cancel its ongoing notification.
  3. Reschedule alarms when a reminder row changed (cheap; idempotent).
- Pure, unit-tested rules: `ShoppingModeContent.from(list, items)` returns null (cancel) when there is no open item, the list is archived or deleted, or the permission is missing; otherwise title, count text and up to 5 item names.
- Notification updates are silent (`setOnlyAlertOnce(true)`, low-importance channel) so a tick never makes a sound.

### 5.3 Widget (Glance 1.2.0, `widget/`)

- `ShoppingListWidget` (a `GlanceAppWidget`) plus receiver; a **configuration activity** lets the owner pick the list when adding the widget (`android:configure`), stored in Glance state per `appWidgetId`.
- Content: list name (tap opens the list via deep link), progress, open items first (capped, scrollable), each row has a check target; a "+" button. Light and dark follow the system.
- Tick: `ActionCallback` calls `repository.setItemBought(id, true/false)`. It never waits on the network; sync is requested by the existing coordinator. Works in airplane mode.
- Quick-add: Glance cannot host a text field, so "+" opens a small translucent `QuickAddActivity` dialog that calls `repository.addItem(listId, input)` (same quick-add parser as the app) and finishes.
- Empty, deleted or archived list: the widget shows "List not available, tap to choose" and opens the configuration. Multiple instances with different lists must work.

### 5.4 Timed reminders (`reminders/`)

- UI: on the list screen (list menu, "Reminder"): switch, date or weekday picker, time picker, "only if items are left" switch (default on). Asking for `POST_NOTIFICATIONS` happens when the owner turns a reminder on; denial leaves the reminder saved but shows "Notifications are off, tap to fix".
- `ReminderMath.nextFire(now, zone, reminder)` is a pure function (unit-tested: DST, month ends, one-time in the past, weekly masks).
- `ReminderScheduler` sets one alarm per enabled reminder with `AlarmManager.setAndAllowWhileIdle` and a `PendingIntent` keyed by `listId`; cancels on disable, archive, delete.
- `ReminderReceiver` on fire: reload the list; skip (and just reschedule) if deleted, archived, or `onlyIfItemsRemain` and nothing is left; otherwise post "Groceries: 5 items left" (channel **Reminders**, default importance, tap opens the list), then schedule the next occurrence, or disable a one-time reminder.
- Reschedule-all receivers: `BOOT_COMPLETED`, `TIMEZONE_CHANGED`, `TIME_SET`, `MY_PACKAGE_REPLACED`; each runs `rescheduleAll()` from Room. A missed one-time reminder (phone off at the time) fires once at next boot if it was less than 24 h late, else is dropped and disabled.

### 5.5 Shopping mode (ongoing notification)

- Per-list switch in the list menu, off by default. Channel **Shopping mode** (low importance, no sound). Posted and updated by `SurfaceUpdater`.
- Content: `Groceries - 5 of 12 left`, an inbox-style list of the next items, actions **Open list** and **Mark all done** (a broadcast that calls a new `repository.markAllBought(listId)`). There is **no undo button**: a notification has no screen to hold one. The items simply become bought and can be unticked in the app or the widget (see 8.1).
- Removed automatically when nothing is left, or when the list is archived or deleted, or the switch is turned off. If swiped away on Android 14+, it comes back at the next data change (the owner can turn the switch off to stop that).

### 5.6 Settings

Two toggles under Notifications: **Reminders** and **Shopping mode**, plus a row that opens the system notification settings when the permission or a channel is blocked. Channels are created in `ShoppingListApp.onCreate`.

### 5.7 Manifest

Adds `RECEIVE_BOOT_COMPLETED`, the widget provider and its configuration activity, `QuickAddActivity`, `ReminderReceiver`, the reschedule receiver, the shopping-mode action receiver. No exact-alarm, no foreground-service permission. `POST_NOTIFICATIONS` is added (the Phase 4 branch also adds it; the merge is trivial).

## 6. Testing and exit criteria

- **Unit:** `ReminderMath`, `ShoppingModeContent`, `SurfaceUpdater` debounce and trigger-on-every-write-path (fake clock), scheduler decisions, widget row model.
- **Instrumented:** Room v2 to v3 migration with data; widget action callback ticks an item in airplane mode; reschedule receivers rebuild alarms; reminder skip rules.
- **Emulator matrix (manual, written up in `docs/TESTING.md`):** two widget instances with different lists; tick survives reboot; widget and notification update after an app edit and after a sync; reminder create, edit, cancel, reboot, permission denied, time-zone change, clock change; swipe away the notification on Android 14+ and see it return.
- A phase is **not** complete on compilation: the completion report states what was and was not verified (a physical device is not available here; Doze timing of inexact alarms cannot be proven on an emulator).

## 8. Settled by the builder check (all my calls; the owner can overrule)

1. **Mark all done has no undo.** It only ticks items (nothing is deleted); the owner unticks in the app or widget. `markAllBought` returns nothing special.
2. **Sign-out and account switch.** Phase 3 keeps every list as local guest data on sign-out and detaches the previous account's lists on account switch; list ids do not change. So reminders, `list_prefs` and widget bindings keyed by `listId` stay valid and need no account column. Plan task: verify this once against `SyncRepository.detachAccountData` before relying on it.
3. **Deleted lists.** Delete is a tombstone (`deletedAt`) with a 7-day undo, so the foreign-key cascade only fires at purge. Every surface therefore checks `deletedAt IS NULL AND archived = 0` itself: the reminder and the notification stop the moment a list is deleted or archived, and the widget shows "List not available". The reminder row and switch are kept, so restoring the list brings them back. The cascade only cleans up at purge.
4. **"Open list" is a new explicit task.** The app has no deep link today (`MainActivity` handles only the launcher intent). Add an explicit intent extra `open_list = <listId>` read by `MainActivity` and passed to `AppNavHost`: it opens the list detail on top of the Lists screen (also when the app is already running). If the list no longer exists or is deleted, the Lists screen opens with a short message. The widget title, the reminder and both notification buttons all use it.
5. **Empty and fully ticked lists.** "Left" means open (not bought) items. A list with no open items shows no shopping-mode notification, so turning the switch on for an empty list shows nothing until an item is added. The widget for a fully ticked or empty list shows an "All done" or "No items yet" message. `onlyIfItemsRemain` means "at least one open item".
6. **Glance is not in the project yet.** The plan adds `androidx.glance:glance-appwidget` and `glance-material3` at 1.2.0 to the version catalog, and checks Compose BOM and R8 compatibility in its first task (a small spike: one widget that renders).
7. **Permission on shopping mode too.** Turning shopping mode on asks for `POST_NOTIFICATIONS` exactly like turning a reminder on, with the same denied state and "tap to fix" row.
8. **Missed repeating reminders** follow the same rule as one-time ones: a reminder missed while the phone was off fires once at boot if less than 24 hours late, otherwise it is skipped and the next occurrence is scheduled.

## 7. Decided by the owner / left to the builder

- **Decided by the owner:** sharing is skipped for v1; approach B; shopping mode is **in addition to** timed reminders; reminders are one-time, daily or weekly at a chosen time (confirmed as the proposal in conversation).
- **My calls (the owner can overrule):** inexact alarms (D6); `InvalidationTracker` as the trigger; 500 ms debounce; 5 items shown in the notification; per-list switch defaults to off; reminders and shopping mode are local-only; missed one-time reminders fire once if less than 24 h late.
- **Left to the builder:** exact widget layout and sizes within Glance's limits, notification icons and strings, the picker UI, class names beyond the packages above.
