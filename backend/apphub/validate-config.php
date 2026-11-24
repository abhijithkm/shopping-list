<?php
// validate-config.php: runs shoppinglist.config.json through AppHub's own validator (App::normalize) WITHOUT a database, a
// registry or a secret: it supplies dummy database and JWT values, so only the parts this file owns are judged (identifiers,
// roles that name tables, auth, links, uploads, CORS, the shared scope's own rules). Pattern: C:\MyApps\ReadRhythm\apphub\validate-config.php.
//   php validate-config.php [path-to-apphub-checkout] [config.json] [app-name]
// The checkout defaults to C:\xampp\htdocs\apphub (read only: nothing there is changed, no server is started). The app name
// defaults to shoppinglistdev; the config does not depend on it (the same file is imported into shoppinglistdev and shoppinglist).
// It also checks the Phase 4 contract of this app (collab-contract.md: who may do what, which columns a client may write, which
// are hidden, where the shared scope is used) and exits 0 only when AppHub accepts the file AND the contract holds.
// It cannot see the database, so it does not prove the config names real columns: AppHub's own check.php does that on the
// server (provision.md), and the fake server compares the config with schema.sql at every start.
declare(strict_types=1);

$root = $argv[1] ?? 'C:/xampp/htdocs/apphub';
$file = $argv[2] ?? __DIR__ . '/shoppinglist.config.json';
$name = $argv[3] ?? 'shoppinglistdev';
require rtrim($root, '/' . chr(92)) . '/src/bootstrap.php';

$config = json_decode((string) file_get_contents($file), true, 512, JSON_THROW_ON_ERROR);
$config['database'] = ['name' => $name, 'user' => 'apphub_' . $name, 'password' => str_repeat('p', 24)];
$config['jwt']['secret'] = str_repeat('s', 80);

try {
    $app = new AppHub\App($name, $config);
} catch (Throwable $e) {
    fwrite(STDERR, 'REFUSED: ' . $e->getMessage() . PHP_EOL);
    exit(1);
}

echo 'accepted by AppHub App::normalize', PHP_EOL;
echo 'tables: ', implode(', ', $app->tableNames()), PHP_EOL;
echo 'roles: ', implode(', ', $app->roleNames()), PHP_EOL;
foreach ($app->roleNames() as $role) {
    foreach ($app->tableNames() as $table) {
        $actions = [];
        foreach (AppHub\App::ACTIONS as $action) {
            $scope = $app->scope($role, $table, $action);
            if ($scope !== null) {
                $actions[] = "$action:$scope";
            }
        }
        printf("  %-6s %-17s %s\n", $role, $table, $actions ? implode(' ', $actions) : '(nothing)');
    }
}
echo 'auth: register ', $app->auth('allow_register') ? 'on' : 'off', ' (', implode(',', $app->auth('register_fields')), '), verified required ', $app->auth('require_verified') ? 'yes' : 'no', ', password_min ', $app->auth('password_min'), PHP_EOL;
echo 'jwt: access ', $app->accessTtl(), ' s, refresh ', $app->refreshTtl(), ' s', PHP_EOL;
echo 'cors: ', $app->corsOrigins() ? implode(' ', $app->corsOrigins()) : '(none: the Android app needs no CORS)', PHP_EOL;
echo 'actor_in_sql: ', $app->actorInSql() ? 'ON' : 'off', PHP_EOL;

// ---------------------------------------------------------------------------------------------------------------------------
// The Phase 4 contract, checked on the normalised config (what AppHub will really use).
// ---------------------------------------------------------------------------------------------------------------------------
$problems = [];
$same = static fn (array $a, array $b): bool => count($a) === count($b) && array_diff($a, $b) === [] && array_diff($b, $a) === [];
$list = static fn (array $a): string => '[' . implode(', ', $a) . ']';

// Who may do what, per table and action. Anything not listed is "no rule" (AppHub answers 403, or 404 for an unknown table).
// 'shared' appears only where the contract says so: it is what makes a row visible to people other than its owner.
$expected = [
    'users' => ['read' => 'own', 'update' => 'own'],
    'shopping_lists' => ['list' => 'shared', 'read' => 'shared', 'create' => 'own', 'update' => 'own'],
    'shopping_items' => ['list' => 'shared', 'read' => 'shared', 'create' => 'own', 'update' => 'shared'],
    'categories' => ['list' => 'own', 'read' => 'own', 'create' => 'own', 'update' => 'own'],
    'tags' => ['list' => 'own', 'read' => 'own', 'create' => 'own', 'update' => 'own'],
    // The grant table: clients read it and never write it (no create, no update, no delete: only triggers write it).
    'list_members' => ['list' => 'shared', 'read' => 'shared'],
    'user_lookups' => ['list' => 'own', 'read' => 'own', 'create' => 'own'],
    'list_invitations' => ['list' => 'shared', 'read' => 'shared', 'create' => 'own'],
    'join_codes' => ['list' => 'own', 'read' => 'own', 'create' => 'own'],
    'code_lookups' => ['list' => 'own', 'read' => 'own', 'create' => 'own'],
    'join_requests' => ['list' => 'shared', 'read' => 'shared', 'create' => 'own'],
    'list_commands' => ['list' => 'own', 'read' => 'own', 'create' => 'own'],
];
// What a client may send (the table's `writable` list). Trigger-filled columns are deliberately absent.
$writable = [
    'users' => ['display_name'],
    'shopping_lists' => ['id', 'name', 'description', 'icon', 'color', 'is_archived', 'is_pinned', 'sort_key', 'created_at_ms', 'updated_at_ms', 'deleted_at_ms'],
    'shopping_items' => ['id', 'list_id', 'name', 'quantity', 'unit', 'category_key', 'notes', 'is_bought', 'bought_at_ms', 'sort_order', 'tag_ids', 'created_at_ms', 'updated_at_ms', 'deleted_at_ms', 'base_revision'],
    'categories' => ['id', 'builtin_key', 'name', 'color', 'created_at_ms', 'deleted_at_ms'],
    'tags' => ['id', 'name', 'created_at_ms', 'deleted_at_ms'],
    'list_members' => [],
    'user_lookups' => ['id', 'email'],
    'list_invitations' => ['id', 'list_id', 'lookup_id'],
    'join_codes' => ['id', 'list_id', 'code'],
    'code_lookups' => ['id', 'code'],
    'join_requests' => ['id', 'lookup_id'],
    'list_commands' => ['id', 'command', 'target_id'],
];
// Columns the API must never return. A hidden column may still be writable (that is how a client SENDS an email or a code the
// trigger then nulls); AppHub does not cross-check the two lists, which is why this file does.
$hidden = [
    'user_lookups' => ['email', 'target_id'],
    'join_codes' => ['code', 'code_hash'],
    'code_lookups' => ['code', 'code_id'],
];
// The members columns: the people (other than the owner) who may read a row through the shared scope.
$members = ['list_members' => ['member_id'], 'list_invitations' => ['invitee_id'], 'join_requests' => ['owner_id']];

if (!$app->actorInSql()) {
    $problems[] = 'actor_in_sql must be on: without it a trigger cannot tell who acts and a SIGNAL answers an opaque 500 instead of its sl: token';
}
if ($app->emails() !== null) {
    $problems[] = 'emails are configured: not in this phase';
}
if ($app->auth('register_fields') !== ['display_name']) {
    $problems[] = 'auth.register_fields must be ["display_name"]';
}
if ($app->auth('default_role') !== 'user' || $app->auth('password_min') < 10) {
    $problems[] = 'auth: default_role must be user and password_min at least 10';
}
if (!$same($app->tableNames(), array_keys($expected))) {
    $problems[] = 'tables must be exactly ' . $list(array_keys($expected)) . ', found ' . $list($app->tableNames()) . ' (app_secrets must NOT be listed: every API call on it must answer 404)';
}

foreach ($app->roleNames() as $role) {
    if ($role !== 'user') {
        $problems[] = "role '$role' exists: this app defines only the 'user' role";
        continue;
    }
    foreach ($app->tableNames() as $table) {
        $actual = [];
        foreach (AppHub\App::ACTIONS as $action) {
            $scope = $app->scope($role, $table, $action);
            if ($scope !== null) {
                $actual[$action] = $scope;
            }
        }
        $want = $expected[$table] ?? [];
        ksort($actual);
        ksort($want);
        if ($actual !== $want) {
            $problems[] = "$role on $table: expected " . json_encode($want) . ', found ' . json_encode($actual);
        }
    }
}

foreach ($expected as $table => $rules) {
    $t = $app->table($table);
    if ($t === null) {
        $problems[] = "table $table is missing";
        continue;
    }
    if ($table !== 'users' && $t['owner'] !== 'user_id') {
        $problems[] = "$table: the owner column must be user_id";
    }
    if (!$same($t['writable'], $writable[$table])) {
        $problems[] = "$table: writable must be " . $list($writable[$table]) . ', found ' . $list($t['writable']);
    }
    $wantHidden = $table === 'users' ? ['password'] : ($hidden[$table] ?? []); // AppHub adds the password column of the auth table itself
    if (!$same($t['hidden'], $wantHidden)) {
        $problems[] = "$table: hidden must be " . $list($wantHidden) . ', found ' . $list($t['hidden']);
    }
    if (!$same($t['members'], $members[$table] ?? [])) {
        $problems[] = "$table: members must be " . $list($members[$table] ?? []) . ', found ' . $list($t['members']);
    }
    if ($t['soft_delete'] !== null || $t['public_columns'] !== [] || $t['file_columns'] !== [] || $t['writable_by_role'] !== []) {
        $problems[] = "$table uses soft_delete, public_columns, file_columns or writable_by_role: not in this app";
    }
    // Never writable, never filterable, never sortable: the owner column, and everything a trigger sets.
    foreach (array_merge($t['owner'] !== null ? [$t['owner']] : [], ['updated_at']) as $col) {
        if (in_array($col, $t['writable'], true)) {
            $problems[] = "$table: $col must never be writable";
        }
    }
    if ($table !== 'users') {
        if (!in_array('id', $t['sortable'], true) || !in_array('updated_at', $t['sortable'], true) || !in_array('created_at_ms', $t['sortable'], true)) {
            $problems[] = "$table: id, updated_at and created_at_ms must be sortable (updated_at is the sync cursor)";
        }
        if (!in_array('id', $t['filterable'], true)) {
            $problems[] = "$table: id must be filterable";
        }
        foreach (['created_at_ms', 'deleted_at_ms'] as $col) {
            $isData = in_array($table, ['shopping_lists', 'shopping_items', 'categories', 'tags'], true);
            if ($isData && !in_array($col, $t['writable'], true)) {
                $problems[] = "$table: $col must be writable";
            }
        }
    }
    foreach ($t['hidden'] as $col) {
        foreach (['filterable', 'searchable', 'sortable'] as $key) {
            if (in_array($col, $t[$key], true)) {
                $problems[] = "$table: hidden column $col must not be $key (AppHub strips it; the config must not pretend otherwise)";
            }
        }
    }
}

// Everything the triggers set must be unreachable for a client: not writable on any table.
$serverSet = [
    'shopping_items' => ['revision', 'updated_by', 'user_id'],
    'user_lookups' => ['status', 'display_hint', 'target_id', 'created_at_ms', 'user_id'],
    'list_invitations' => ['invitee_id', 'role', 'status', 'list_name', 'inviter_name', 'display_hint', 'expires_at', 'created_at_ms', 'user_id'],
    'join_codes' => ['code_hash', 'status', 'expires_at', 'created_at_ms', 'user_id'],
    'code_lookups' => ['status', 'list_name', 'inviter_name', 'code_id', 'created_at_ms', 'user_id'],
    'join_requests' => ['owner_id', 'list_id', 'list_name', 'requester_name', 'status', 'created_at_ms', 'user_id'],
    'list_commands' => ['created_at_ms', 'user_id'],
];
foreach ($serverSet as $table => $cols) {
    $t = $app->table($table);
    foreach ($cols as $col) {
        if ($t !== null && in_array($col, $t['writable'], true)) {
            $problems[] = "$table.$col is set by a trigger or AppHub and must never be writable";
        }
    }
}

// The shared scope's grant wiring. AppHub refuses a self-grant (shared_via.table must differ from the table itself), so list_members
// cannot offer "every active member sees every member row": its visibility is owner + own row, through `members`.
$lists = $app->table('shopping_lists');
$items = $app->table('shopping_items');
$wantGrant = static fn (string $on, array $update): array => ['table' => 'list_members', 'on' => $on, 'match' => 'list_id', 'member' => 'member_id', 'level' => 'role', 'read' => ['owner', 'editor'], 'update' => $update];
if ($lists === null || $lists['shared_via'] !== $wantGrant('id', [])) {
    $problems[] = 'shopping_lists.shared_via must be ' . json_encode($wantGrant('id', []));
}
if ($items === null || $items['shared_via'] !== $wantGrant('list_id', ['owner', 'editor'])) {
    $problems[] = 'shopping_items.shared_via must be ' . json_encode($wantGrant('list_id', ['owner', 'editor']));
}
foreach (['list_members', 'user_lookups', 'list_invitations', 'join_codes', 'code_lookups', 'join_requests', 'list_commands', 'users', 'categories', 'tags'] as $table) {
    $t = $app->table($table);
    if ($t !== null && $t['shared_via'] !== null) {
        $problems[] = "$table must not have shared_via";
    }
}
// A removed person must not satisfy the grant: only owner and editor are read levels.
foreach (['shopping_lists', 'shopping_items'] as $table) {
    $t = $app->table($table);
    if ($t !== null && $t['shared_via'] !== null && in_array('removed', array_merge($t['shared_via']['read'], $t['shared_via']['update']), true)) {
        $problems[] = "$table: the level 'removed' must never grant anything";
    }
}

// No role anywhere may delete, write the grant table, or reach the secrets table.
foreach ($app->roleNames() as $role) {
    foreach ($app->tableNames() as $table) {
        if ($app->scope($role, $table, 'delete') !== null) {
            $problems[] = "$role may delete $table: clients only tombstone (PATCH deleted_at_ms), nobody gets delete";
        }
        if ($app->scope($role, $table, 'all') !== null) {
            $problems[] = "$role has an 'all' scope on $table";
        }
    }
    foreach (['create', 'update', 'delete'] as $action) {
        if ($app->scope($role, 'list_members', $action) !== null) {
            $problems[] = "$role may $action list_members: only triggers write the grant table (a client rule would let anyone add themselves)";
        }
    }
}
foreach ((array) ($config['roles'] ?? []) as $role => $rules) {
    if (array_key_exists('*', (array) $rules)) {
        $problems[] = "role '$role' has a wildcard entry: every table must be named";
    }
    if (array_key_exists('app_secrets', (array) $rules)) {
        $problems[] = "role '$role' names app_secrets";
    }
}

// Table-specific Phase 3 facts that still hold.
foreach (['shopping_lists', 'shopping_items'] as $table) {
    $t = $app->table($table);
    if ($t !== null && (in_array('updated_at_ms', $t['sortable'], true) || in_array('updated_at_ms', $t['filterable'], true))) {
        $problems[] = "$table: updated_at_ms must be neither sortable nor filterable";
    }
}
if ($items !== null && !in_array('list_id', $items['filterable'], true)) {
    $problems[] = 'shopping_items: list_id must be filterable';
}
if ($items !== null && !in_array('base_revision', $items['writable'], true)) {
    $problems[] = 'shopping_items: base_revision must be writable (the client sends the revision its edit is based on)';
}
$categories = $app->table('categories');
if ($categories !== null && (!in_array('builtin_key', $categories['writable'], true) || !in_array('builtin_key', $categories['filterable'], true))) {
    $problems[] = 'categories: builtin_key must be writable and filterable (built-in overrides are rows with builtin_key set)';
}
$users = $app->table('users');
if ($users === null || $users['writable'] !== ['display_name']) {
    $problems[] = 'users: only display_name may be written';
}

if ($problems !== []) {
    fwrite(STDERR, 'CONTRACT PROBLEMS:' . PHP_EOL . '  - ' . implode(PHP_EOL . '  - ', $problems) . PHP_EOL);
    exit(1);
}
echo 'contract: ok (phase 4: user-only, actor_in_sql on, shared scope only on lists, items, members, invitations and join requests, no delete, no client write on list_members, no app_secrets, trigger-set columns never writable, secrets hidden)', PHP_EOL;
