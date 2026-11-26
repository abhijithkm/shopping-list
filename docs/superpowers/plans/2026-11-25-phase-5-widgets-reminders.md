# Phase 5 Implementation Plan: widgets, timed reminders, shopping mode

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Glance home-screen widget, per-list timed reminders and a per-list "Shopping mode" ongoing notification to the offline-first shopping list app, all reading and writing local Room data only.

**Architecture:** One `SurfaceUpdater` listens to Room's `InvalidationTracker` (tables `lists`, `items`, `reminders`, `list_prefs`), debounces 500 ms, then redraws every widget, posts or cancels shopping-mode notifications and (re)schedules reminder alarms. Reminders use inexact `AlarmManager.setAndAllowWhileIdle`. Pure logic (`ReminderMath`, `shoppingModeContent`) is unit-tested; platform glue is thin.

**Tech Stack:** Kotlin 2.4.21, Compose Material 3, Room 2.8.5 (auto-migration 2 to 3), Hilt 2.60.1, Glance 1.2.0 (`glance-appwidget`, `glance-material3`), AlarmManager, NotificationManagerCompat, JUnit4 + MockK + Turbine, AndroidX instrumented tests.

**Spec:** `docs/superpowers/specs/2026-11-25-phase-5-widgets-reminders-design.md` (read it first; section 8 holds the settled edge cases).

## Global Constraints

- Branch `feature/phase-5-widgets-reminders`. Package root `space.abhijithkm.shoppinglist`; new packages `surfaces`, `reminders`, `widget`, `feature/reminders`.
- Glance **1.2.0** only (1.3.0 is alpha). minSdk 24, compileSdk 37, targetSdk 36.
- Reminders: `AlarmManager.setAndAllowWhileIdle` only. No `SCHEDULE_EXACT_ALARM`, no foreground service, no periodic widget refresh, no network in any Phase 5 path.
- Debounce **500 ms**. Shopping-mode notification shows at most **5** item names. Missed-reminder grace **24 hours**, applied on boot only.
- Notification updates are silent (`setOnlyAlertOnce(true)`); channels: `reminders` (default importance), `shopping_mode` (low importance).
- Room: no destructive migration; schema JSON `app/schemas/.../3.json` committed; migration tested on a device.
- Git: personal identity (check `git config user.email` is `meabhijithkm@gmail.com` before each commit and confirm with `git log -1 --format='%an <%ae> | %cn <%ce>'`), no attribution lines, stage by explicit path (never `git add -A`; zero-byte junk files appear in the repo root; delete verified zero-byte ones). Never commit secrets.
- Run Gradle from PowerShell: `.\gradlew.bat ...`; filter output with `Select-String -CaseSensitive`; `.\gradlew.bat --stop` before emulator work. `connectedDebugAndroidTest` does not refresh the debug APK: run `assembleDebug` before `adb install -r`.
- Emulator AVD `shoppinglist_api36`; `MSYS_NO_PATHCONV=1` for adb paths in Git Bash.
- A task is not done on compilation alone; state what was and was not verified.

## Review Focus

1. **Alarm for a list deleted or archived while the reminder row stays** — must not fire; restoring the list brings it back (Task 8 test).
2. **Time-zone or DST change** moving a daily 02:30 reminder — fires once at the next valid local 02:30 (or the first valid time on the gap day), never twice (Task 3 test).
3. **Burst of 20 ticks** — exactly one refresh, no sound, final counts correct (Task 7 test).
4. **Widget bound to a list that was deleted, archived, or never existed** — shows "List not available", never crashes (Task 9 test).
5. **Notification permission denied** — reminder row saved, no crash, no notification, settings shows the "off" state (Task 10 test).

---

### Task 1: Glance dependency and widget skeleton

**Files:**
- Modify: `gradle/libs.versions.toml` (add `glance = "1.2.0"`; libraries `androidx-glance-appwidget`, `androidx-glance-material3`)
- Modify: `app/build.gradle.kts` (add both as `implementation`)
- Create: `app/src/main/kotlin/space/abhijithkm/shoppinglist/widget/ShoppingListWidget.kt`, `ShoppingListWidgetReceiver.kt`
- Create: `app/src/main/res/xml/shopping_list_widget_info.xml`
- Modify: `app/src/main/AndroidManifest.xml` (receiver)
- Modify: `app/src/main/res/values/strings.xml` (`widget_description`)

**Interfaces:**
- Produces: `class ShoppingListWidget : GlanceAppWidget` (placeholder content "Shopping list"); `class ShoppingListWidgetReceiver : GlanceAppWidgetReceiver` with `glanceAppWidget = ShoppingListWidget()`.

- [ ] **Step 1: Add the catalog entries and `implementation(libs.androidx.glance.appwidget)` / `implementation(libs.androidx.glance.material3)`.**
- [ ] **Step 2: Create the skeleton widget, receiver, provider XML (`minWidth 180dp`, `minHeight 110dp`, `resizeMode horizontal|vertical`, `updatePeriodMillis 0`; `android:configure` is added in Task 9) and the manifest `<receiver exported="true">` with the `APPWIDGET_UPDATE` filter.**
- [ ] **Step 3: Run `.\gradlew.bat :app:assembleDebug :app:lintDebug`.** Expected: BUILD SUCCESSFUL, 0 lint errors. Record the Compose BOM / Glance compatibility result in the commit message.
- [ ] **Step 4: Spike check on the emulator:** install, add the widget from the launcher, confirm it renders "Shopping list". Note the result.
- [ ] **Step 5: Commit** `feat: add Glance dependency and widget skeleton` (stage the files above by path).

### Task 2: Room v3 tables, DAOs and migration test

**Files:**
- Modify: `data/local/Entities.kt` (add `ReminderEntity`, `ListPrefsEntity`, `enum ReminderRepeat`)
- Create: `data/local/SurfaceDaos.kt` (`ReminderDao`, `ListPrefsDao`)
- Modify: `data/local/AppDatabase.kt` (version 3, `AutoMigration(from = 2, to = 3)`, two new DAO accessors)
- Modify: `di/AppModules.kt` (`provideReminderDao`, `provideListPrefsDao`)
- Create: `app/schemas/space.abhijithkm.shoppinglist.data.local.AppDatabase/3.json` (generated by the build)
- Test: `app/src/androidTest/kotlin/space/abhijithkm/shoppinglist/data/local/SurfaceDaoTest.kt`; extend the existing Room migration test class (find it with `Grep MigrationTestHelper`) with `migrate2To3KeepsListsAndItems`

**Interfaces:**
- Produces:
  - `enum class ReminderRepeat { NONE, DAILY, WEEKLY }`
  - `@Entity("reminders", FK listId -> lists.id CASCADE) data class ReminderEntity(@PrimaryKey val listId: String, val enabled: Boolean = true, val repeat: ReminderRepeat = ReminderRepeat.NONE, val hour: Int = 9, val minute: Int = 0, val daysOfWeekMask: Int = 0, val oneTimeAtMillis: Long? = null, val onlyIfItemsRemain: Boolean = true, val lastFiredAt: Long? = null)` (mask bit 0 = Monday ... bit 6 = Sunday)
  - `@Entity("list_prefs", FK listId -> lists.id CASCADE) data class ListPrefsEntity(@PrimaryKey val listId: String, val shoppingMode: Boolean = false)`
  - `ReminderDao`: `fun observe(listId: String): Flow<ReminderEntity?>`, `suspend fun get(listId: String): ReminderEntity?`, `suspend fun upsert(r: ReminderEntity)`, `suspend fun delete(listId: String)`, `suspend fun allEnabled(): List<ReminderEntity>`, `suspend fun markFired(listId: String, at: Long)`, `suspend fun disable(listId: String)`
  - `ListPrefsDao`: `fun observe(listId: String): Flow<ListPrefsEntity?>`, `suspend fun upsert(p: ListPrefsEntity)`, `suspend fun shoppingModeListIds(): List<String>`

- [ ] **Step 1: Write the failing tests:** `SurfaceDaoTest.upsertAndObserveReminder`, `purgingAListCascadesReminderAndPrefs` (call `ShoppingListDao.purge`, assert both rows gone), `softDeleteKeepsReminderRow`; `migrate2To3KeepsListsAndItems` (insert a list and an item at v2, migrate, assert both rows survive and both new tables exist, empty).
- [ ] **Step 2: Run** `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=space.abhijithkm.shoppinglist.data.local.SurfaceDaoTest"` (emulator up). Expected: FAIL to compile (types missing).
- [ ] **Step 3: Add the entities, DAOs, version 3 with the auto-migration, DI providers.**
- [ ] **Step 4: Re-run the tests and `.\gradlew.bat :app:testDebugUnitTest`.** Expected: PASS (read `app/build/outputs/androidTest-results/**/*.xml`; do not trust a bare BUILD SUCCESSFUL).
- [ ] **Step 5: Commit** (entities, DAOs, database, modules, `3.json`, tests).

### Task 3: `ReminderMath` (pure)

**Files:**
- Create: `app/src/main/kotlin/space/abhijithkm/shoppinglist/reminders/ReminderMath.kt`
- Test: `app/src/test/kotlin/space/abhijithkm/shoppinglist/reminders/ReminderMathTest.kt`

**Interfaces:**
- Consumes: `ReminderEntity`, `ReminderRepeat` (Task 2).
- Produces: `object ReminderMath { fun nextFire(now: Long, zone: ZoneId, r: ReminderEntity): Long?; fun lastOccurrenceAtOrBefore(now: Long, zone: ZoneId, r: ReminderEntity): Long?; const val GRACE_MS = 24L * 60 * 60 * 1000 }`. `nextFire` returns the first occurrence strictly after `now`, or null (disabled, one-time in the past, weekly with an empty mask). `lastOccurrenceAtOrBefore` returns the latest occurrence at or before `now`, or null (used for the boot catch-up; the caller applies `GRACE_MS` and `lastFiredAt`).

- [ ] **Step 1: Write failing tests** (JUnit, fixed `now`, explicit zones): `dailyTodayIfTimeNotPassed`, `dailyTomorrowIfPassed`, `weeklyPicksNextMaskedDay` (mask Mon+Thu, `now` on a Friday gives the next Monday), `weeklyEmptyMaskIsNull`, `oneTimeInFutureReturnsIt`, `oneTimeInPastIsNull`, `disabledIsNull`, `dstGapDailyFiresOnceAtNextValidTime` (Europe/Berlin, 2027-03-28 02:30), `dstOverlapFiresOnce`, `zoneChangeRecomputesLocalTime` (same reminder in `Asia/Kolkata` vs `America/New_York`), `lastOccurrenceBoundaries` (exactly at `now` counts; none before the first day).
- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*ReminderMathTest"`. Expected: FAIL (object missing).
- [ ] **Step 3: Implement** with `java.time` (`ZonedDateTime.ofLocal` for gaps; search up to 8 days ahead for weekly).
- [ ] **Step 4: Re-run.** Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 4: `shoppingModeContent` (pure)

**Files:**
- Create: `app/src/main/kotlin/space/abhijithkm/shoppinglist/surfaces/ShoppingModeContent.kt`
- Test: `app/src/test/kotlin/space/abhijithkm/shoppinglist/surfaces/ShoppingModeContentTest.kt`

**Interfaces:**
- Consumes: `ShoppingListEntity`, `ShoppingItemEntity`.
- Produces: `data class ShoppingModeContent(val listId: String, val listName: String, val openCount: Int, val totalCount: Int, val nextItems: List<String>)` and `fun shoppingModeContent(list: ShoppingListEntity?, items: List<ShoppingItemEntity>, maxItems: Int = 5): ShoppingModeContent?`. Returns null when the list is null, `deletedAt != null`, `isArchived`, or there is no open item. Counts ignore items with `deletedAt != null`. `nextItems` are item names only, open items by `sortOrder`, capped at `maxItems`.

- [ ] **Step 1: Write failing tests:** `nullForDeletedList`, `nullForArchivedList`, `nullWhenAllBought`, `nullForEmptyList`, `countsIgnoreDeletedItems`, `capsAtFiveInSortOrder`, `totalIncludesBought`.
- [ ] **Step 2: Run** `--tests "*ShoppingModeContentTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Re-run.** Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 5: Repository additions

**Files:**
- Modify: `data/local/Daos.kt` (`ItemDao`: add `markAllBought`; `ShoppingListDao`: add `shoppingModeLists`)
- Modify: `data/repository/LocalShoppingRepository.kt` (add `markAllBought`)
- Create: `data/repository/SurfacePrefsRepository.kt`
- Test: `app/src/androidTest/kotlin/space/abhijithkm/shoppinglist/data/repository/MarkAllBoughtTest.kt` (in-memory Room; reuse the harness of the existing repository instrumented test)

**Interfaces:**
- Produces:
  - `ItemDao.markAllBought(listId: String, now: Long)` = `UPDATE items SET isBought = 1, boughtAt = :now, updatedAt = :now, revision = revision + 1 WHERE listId = :listId AND deletedAt IS NULL AND isBought = 0`
  - `ShoppingListDao.shoppingModeLists(): List<ShoppingListEntity>` = lists joined with `list_prefs.shoppingMode = 1`
  - `LocalShoppingRepository.markAllBought(listId: String)`: one `db.withTransaction` calling `items.markAllBought` then `lists.touch` (same pattern as `setItemBought`). Returns Unit (no undo). The existing `ItemDao.openItemsInOrder` already gives open items; add nothing for that.
  - `SurfacePrefsRepository` (`@Singleton`, `@Inject constructor(reminderDao: ReminderDao, prefsDao: ListPrefsDao)`): `fun observeReminder(listId: String): Flow<ReminderEntity?>`, `suspend fun saveReminder(r: ReminderEntity)`, `suspend fun disableReminder(listId: String)`, `fun observeShoppingMode(listId: String): Flow<Boolean>`, `suspend fun setShoppingMode(listId: String, on: Boolean)`.

- [ ] **Step 1: Write failing tests:** `markAllBoughtTicksOnlyOpenItemsAndBumpsRevision`, `markAllBoughtOnEmptyListIsNoop`, `markAllBoughtIsDirtyForSync` (revision above serverRevision for an account list), `setShoppingModeUpserts`, `saveReminderReplacesExisting`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Re-run.** Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 6: Open-list link (`open_list` extra)

**Files:**
- Modify: `MainActivity.kt` (read `intent.getStringExtra(EXTRA_OPEN_LIST)` in `onCreate` and `onNewIntent`); `AndroidManifest.xml` (`android:launchMode="singleTop"` on `MainActivity`)
- Modify: `ui/AppNavHost.kt` (`fun AppNavHost(openListRequest: OpenListRequest? = null)`; when the request changes, navigate to `listDetailRoute(listId)` on top of `ROUTE_LISTS`; if the list does not exist or is deleted, stay on Lists and show a snackbar `list_not_available`)
- Create: `surfaces/OpenList.kt`
- Test: `app/src/androidTest/kotlin/space/abhijithkm/shoppinglist/ui/OpenListTest.kt`

**Interfaces:**
- Produces: `const val EXTRA_OPEN_LIST = "open_list"`; `data class OpenListRequest(val listId: String, val token: Long)` (the token makes a repeated identical request fire again); `fun openListIntent(context: Context, listId: String): Intent` (explicit `MainActivity` intent, flags `FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`, extra `open_list`). Every surface (widget title, reminder, both notification buttons) calls it.

- [ ] **Step 1: Write failing tests:** `openListExtraShowsListDetail`, `unknownListShowsListsAndMessage`, `newIntentWhileRunningNavigates`.
- [ ] **Step 2: Run** `OpenListTest`. Expected: FAIL.
- [ ] **Step 3: Implement (hold the request in a `mutableStateOf` in the activity).**
- [ ] **Step 4: Re-run + `.\gradlew.bat :app:testDebugUnitTest`.** Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 7: Notifier, channels, `SurfaceUpdater`

**Files:**
- Create: `surfaces/NotificationChannels.kt`, `surfaces/SurfaceNotifier.kt`, `surfaces/SurfaceUpdater.kt`, `surfaces/MarkAllDoneReceiver.kt`
- Modify: `ShoppingListApp.kt` (create channels; start the updater), `AndroidManifest.xml` (`POST_NOTIFICATIONS`; `MarkAllDoneReceiver` not exported), `res/values/strings.xml`, add `res/drawable/ic_stat_notify.xml` (24dp monochrome vector)
- Test: `app/src/test/kotlin/space/abhijithkm/shoppinglist/surfaces/SurfaceUpdaterTest.kt` (fake refresher, `TestScope`), `app/src/androidTest/kotlin/space/abhijithkm/shoppinglist/surfaces/SurfaceUpdaterWritePathsTest.kt` (in-memory Room)

**Interfaces:**
- Consumes: `shoppingModeContent` (Task 4), `ListPrefsDao.shoppingModeListIds`, `ShoppingListDao.getById`, `ItemDao.allOfList`.
- Produces:
  - `object NotificationChannels { const val REMINDERS = "reminders"; const val SHOPPING_MODE = "shopping_mode"; fun create(context: Context) }` (idempotent)
  - `interface SurfaceRefresher { suspend fun refreshAll() }` (the unit-test seam)
  - `class SurfaceUpdater @Inject constructor(db: AppDatabase, refresher: SurfaceRefresher)` with `fun start(scope: CoroutineScope, debounceMs: Long = 500)`; collects `db.invalidationTracker.createFlow("lists", "items", "reminders", "list_prefs").debounce(debounceMs)` and calls `refresher.refreshAll()` (a thrown exception is logged and collection continues)
  - `class DefaultSurfaceRefresher` implementing it: (1) `ShoppingListWidget().updateAll(context)`; (2) for each shopping-mode list build the content and `postShoppingMode` or cancel, and cancel every active notification with tag `"shopping_mode"` that is no longer wanted; (3) `ReminderScheduler.rescheduleAll(catchUp = false)` (Task 8)
  - `class SurfaceNotifier`: `fun canNotify(): Boolean` (API 33+ permission granted and channel not blocked), `fun postShoppingMode(c: ShoppingModeContent)` (tag `"shopping_mode"`, id `listId.hashCode()`, ongoing, `setOnlyAlertOnce(true)`, inbox style, actions Open list via `openListIntent` and Mark all done via `MarkAllDoneReceiver`), `fun cancelShoppingMode(listId: String)`, `fun postReminder(listId: String, listName: String, openCount: Int)` (tag `"reminder"`, taps open the list)
  - `MarkAllDoneReceiver` (`@AndroidEntryPoint`, `goAsync()`): reads extra `list_id`, calls `LocalShoppingRepository.markAllBought`

- [ ] **Step 1: Write failing tests:** unit `burstOfTwentyWritesRefreshesOnce`, `refreshRunsAfterDebounceNotBefore`, `refresherFailureDoesNotStopCollection`; instrumented `itemInsertTriggersRefresh`, `listSoftDeleteTriggersRefresh`, `syncAdapterApplyTriggersRefresh` (apply a remote item through the real `SyncAdapters` path), `reminderUpsertTriggersRefresh`.
- [ ] **Step 2: Run both test classes.** Expected: FAIL (classes missing).
- [ ] **Step 3: Implement. The Mark all done `PendingIntent` is `FLAG_IMMUTABLE` with a request code from the list id.**
- [ ] **Step 4: Re-run + the full unit suite.** Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 8: Reminder scheduling and receivers

**Files:**
- Create: `reminders/AlarmGateway.kt`, `reminders/ReminderScheduler.kt`, `reminders/ReminderReceiver.kt`, `reminders/RescheduleReceiver.kt`
- Modify: `AndroidManifest.xml` (`RECEIVE_BOOT_COMPLETED`; `ReminderReceiver` not exported; `RescheduleReceiver` exported with filters `BOOT_COMPLETED`, `TIMEZONE_CHANGED`, `TIME_SET`, `MY_PACKAGE_REPLACED`)
- Modify: `di/AppModules.kt` (bind `AlarmGateway`; provide `ZoneProvider`)
- Test: `app/src/test/kotlin/space/abhijithkm/shoppinglist/reminders/ReminderSchedulerTest.kt` (fake `AlarmGateway`, MockK DAOs), `app/src/androidTest/kotlin/space/abhijithkm/shoppinglist/reminders/ReminderReceiversTest.kt`

**Interfaces:**
- Consumes: `ReminderMath` (Task 3), `ReminderDao`, `ShoppingListDao`, `ItemDao`, `Clock`, `SurfaceNotifier` (Task 7).
- Produces:
  - `interface AlarmGateway { fun schedule(listId: String, atMillis: Long); fun cancel(listId: String) }`; `class AlarmManagerGateway` (`setAndAllowWhileIdle`, `PendingIntent.getBroadcast` to `ReminderReceiver`, extra `list_id`, request code `listId.hashCode()`, `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`)
  - `fun interface ZoneProvider { fun zone(): ZoneId }`
  - `class ReminderScheduler @Inject constructor(...)`: `suspend fun rescheduleAll(catchUp: Boolean)` (per reminder: cancel if disabled, list deleted or archived, or `nextFire == null`; else `schedule`; with `catchUp` also fire once for a missed occurrence within `GRACE_MS` and after `lastFiredAt`), `suspend fun onFired(listId: String): ReminderDecision` (decide, `markFired`, disable a one-time reminder, schedule the next)
  - `sealed interface ReminderDecision { data class Post(val listName: String, val openCount: Int) : ReminderDecision; data object Skip : ReminderDecision }` (`Skip` when the list is deleted or archived, or `onlyIfItemsRemain` and no open item)
  - `ReminderReceiver.onReceive`: `goAsync()`, `onFired`, then `SurfaceNotifier.postReminder` only when `canNotify()` and the global Reminders toggle (Task 10) is on
  - `RescheduleReceiver.onReceive`: `rescheduleAll(catchUp = intent.action == Intent.ACTION_BOOT_COMPLETED)`

- [ ] **Step 1: Write failing tests:** `schedulesNextFireForEnabledReminder`, `cancelsWhenListDeleted`, `cancelsWhenListArchived`, `restoredListRearms` (tombstone cleared, then `rescheduleAll`), `onFiredSkipsWhenNothingLeftAndFlagOn`, `onFiredPostsWhenItemsLeft`, `oneTimeDisablesAfterFire`, `repeatingSchedulesNext`, `bootCatchUpFiresOnceWithinGrace`, `bootCatchUpIgnoresOlderThanGrace`, `timeChangeDoesNotCatchUp`, `deniedPermissionDoesNotPost`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Re-run; instrumented `ReminderReceiversTest.rescheduleAllRebuildsAlarmsAfterBootBroadcast`.** Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 9: Widget content, configuration, tick, quick-add

**Files:**
- Modify: `widget/ShoppingListWidget.kt` (real content), `res/xml/shopping_list_widget_info.xml` (`android:configure`)
- Create: `widget/WidgetState.kt`, `widget/WidgetRows.kt`, `widget/ToggleItemAction.kt`, `widget/WidgetConfigActivity.kt`, `widget/QuickAddActivity.kt`
- Modify: `AndroidManifest.xml` (`WidgetConfigActivity` exported with `APPWIDGET_CONFIGURE`; `QuickAddActivity` not exported, translucent dialog theme), `res/values/strings.xml`, `res/values/themes.xml`
- Test: `app/src/test/kotlin/space/abhijithkm/shoppinglist/widget/WidgetRowsTest.kt`, `app/src/androidTest/kotlin/space/abhijithkm/shoppinglist/widget/ToggleItemActionTest.kt`, `WidgetConfigActivityTest.kt`

**Interfaces:**
- Consumes: `LocalShoppingRepository.setItemBought/addItem`, `openListIntent` (Task 6).
- Produces:
  - `sealed interface WidgetModel { data class Content(val listId: String, val listName: String, val openCount: Int, val totalCount: Int, val rows: List<WidgetRow>) : WidgetModel; data object Unavailable : WidgetModel; data object NotConfigured : WidgetModel }`; `data class WidgetRow(val itemId: String, val label: String, val bought: Boolean)`
  - `fun buildWidgetModel(list: ShoppingListEntity?, items: List<ShoppingItemEntity>, maxRows: Int = 30): WidgetModel` (null, deleted or archived list gives `Unavailable`; open items first by `sortOrder`, then bought; label like `"2 kg Milk"` when quantity and unit exist)
  - Per-instance binding in Glance state (`PreferencesGlanceStateDefinition`, key `list_id`): `suspend fun bindWidget(context: Context, glanceId: GlanceId, listId: String)`
  - `class ToggleItemAction : ActionCallback` with parameters `item_id`, `bought`: calls `repository.setItemBought` through a Hilt `EntryPoint`; never waits on the network
  - `WidgetConfigActivity`: lists non-archived, non-deleted lists; on pick calls `bindWidget`, returns `RESULT_OK` with `EXTRA_APPWIDGET_ID`, then `updateAll`
  - `QuickAddActivity` (extra `list_id`): one text field, calls `repository.addItem(listId, text)`, then `finish()`

- [ ] **Step 1: Write failing tests:** unit `unavailableForDeletedList`, `unavailableForArchivedList`, `unavailableForUnknownList`, `openItemsBeforeBought`, `labelUsesQuantityAndUnit`, `capsRows`; instrumented `toggleActionTicksItemInAirplaneMode`, `widgetConfigBindsChosenList`, `twoInstancesKeepSeparateLists`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement. Glance UI: header (tap opens the list), progress text, a `LazyColumn` of rows with a `CheckBox` wired to `ToggleItemAction`, a "+" button starting `QuickAddActivity`; `Unavailable` and `NotConfigured` show a message and open `WidgetConfigActivity`.**
- [ ] **Step 4: Re-run + `:app:lintDebug`.** Expected: PASS, 0 lint errors.
- [ ] **Step 5: Commit.**

### Task 10: Reminder and Shopping-mode UI, settings, permission flow

**Files:**
- Create: `feature/reminders/ReminderSheet.kt`, `feature/reminders/ReminderViewModel.kt`, `feature/reminders/NotificationPermissionHelper.kt`
- Modify: `feature/listdetail/ListDetailScreen.kt` (two `DropdownMenuItem`s in the overflow menu: "Reminder" opens the sheet; "Shopping mode" toggles), `feature/listdetail/ListDetailViewModel.kt` (expose `shoppingMode` and the reminder state)
- Modify: `feature/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `data/repository/SettingsRepository.kt`, `core/model/Models.kt` (`AppSettings.remindersEnabled = true`, `shoppingModeEnabled = true`; DataStore keys `reminders_enabled`, `shopping_mode_enabled`), `res/values/strings.xml`
- Test: `app/src/test/kotlin/space/abhijithkm/shoppinglist/feature/reminders/ReminderViewModelTest.kt`, `SettingsRepositoryTest` additions, `app/src/androidTest/kotlin/space/abhijithkm/shoppinglist/feature/reminders/ReminderSheetTest.kt`

**Interfaces:**
- Consumes: `SurfacePrefsRepository` (Task 5), `SurfaceNotifier.canNotify` (Task 7).
- Produces: `@HiltViewModel class ReminderViewModel` (`SavedStateHandle` key `listId`): `val reminder: StateFlow<ReminderEntity?>`, `fun save(r: ReminderEntity)`, `fun disable()`. `@Composable fun rememberNotificationPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit` (API 33+ launcher; below 33 calls `onResult(true)`). Turning a reminder or shopping mode on requests the permission first; denial still saves, and the sheet and the Settings row show "Notifications are off, tap to fix" opening the system notification settings. The two global toggles in Settings make `DefaultSurfaceRefresher` (shopping mode) and `ReminderReceiver` (reminders) do nothing when off.

- [ ] **Step 1: Write failing tests:** `savingReminderPersistsRow`, `deniedPermissionStillSavesReminder`, `globalToggleOffSuppressesShoppingMode`, `settingsRoundTripNewKeys`; UI `reminderSheetShowsDeniedState`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement. Sheet fields: switch, repeat (once, daily, weekly), date picker for once, weekday chips for weekly, time picker, "Only if items are left" switch (default on).**
- [ ] **Step 4: Re-run + the full unit suite.** Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 11: Verification, docs and completion report

**Files:**
- Modify: `docs/TESTING.md` (Phase 5 section: instrumented tests and the emulator matrix), `docs/IMPLEMENTATION_STATUS.md` (Phase 5 report with "What is NOT verified" and "Left for the owner"; update the header and the "Phases 4 to 6" paragraph: Phase 4 is parked), `README.md`, `CHANGELOG.md`, `docs/DATABASE_SCHEMA.md` (v3 tables)

- [ ] **Step 1: Run the full suite:** `.\gradlew.bat --stop`, then `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`; start the emulator; `.\gradlew.bat :app:connectedDebugAndroidTest`; read the XML results. Expected: all pass, lint 0 errors.
- [ ] **Step 2: Emulator pass (`assembleDebug`, then `adb install -r`):** two widget instances with different lists; tick from the widget in airplane mode, reboot, state persists; an app edit and a sync update widget and notification; reminder create, edit, cancel, reboot, permission denied, time-zone change, clock change; shopping mode on, swipe away on Android 14+, change an item, the notification returns; Mark all done clears it. Record each result as pass, fail or not run.
- [ ] **Step 3: Fix defects found (new failing test first), re-run.**
- [ ] **Step 4: Write the docs above, stating Doze timing of inexact alarms and a physical device as NOT verified.**
- [ ] **Step 5: Commit the docs, push the branch, open a PR into `develop`, bind it with `mcp__ccd_pr__get_status` / `bind_pr`; do not poll CI.**

---

## Self-review (spec coverage)

- Widget per instance, tick, quick-add, open list, unavailable state: Tasks 1, 6, 9. Debounced, no periodic refresh: Task 7. Timed reminders (once, daily, weekly, only-if-left), reboot, time-zone, clock, app update: Tasks 3, 8, 10. Shopping mode (ongoing, 5 items, Open list, Mark all done, no undo, silent, returns on change, permission): Tasks 4, 5, 7, 10. Settings toggles: Task 10. Room v3: Task 2. Open-list link: Task 6. Glance dependency: Task 1. Docs and exit criteria: Task 11.
- Type names are consistent across tasks: `ReminderEntity`, `ListPrefsEntity`, `ReminderRepeat`, `ReminderMath.nextFire` / `lastOccurrenceAtOrBefore`, `shoppingModeContent`, `ShoppingModeContent`, `SurfaceRefresher.refreshAll`, `SurfaceNotifier.canNotify` / `postShoppingMode` / `postReminder`, `ReminderScheduler.rescheduleAll` / `onFired`, `openListIntent`.
- Proportion: 11 tasks for the 7 design sections; code bodies are left to the implementer, signatures and test names are pinned.
