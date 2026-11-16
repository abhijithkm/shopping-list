<?php
// validate-config.php: runs shoppinglist.config.json through AppHub's own validator (App::normalize) WITHOUT a database, a
// registry or a secret: it supplies dummy database and JWT values, so only the parts this file owns are judged (identifiers,
// roles that name tables, auth, links, uploads, CORS). Pattern: C:\MyApps\ReadRhythm\apphub\validate-config.php.
//   php validate-config.php [path-to-apphub-checkout] [config.json] [app-name]
// The checkout defaults to C:\xampp\htdocs\apphub (read only: nothing there is changed, no server is started). The app name
// defaults to shoppinglistdev; the config does not depend on it (the same file is imported into shoppinglistdev and shoppinglist).
// It also checks the Phase 3 contract of this app (no delete right for any role, no wildcard role entry, owner columns,
// updated_at never writable, ids writable) and exits 0 only when AppHub accepts the file AND the contract holds.
// It cannot see the database, so it does not prove the config names real columns: AppHub's own check.php does that on the
// server (README.md, runbook step "check").
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
        printf("  %-6s %-16s %s\n", $role, $table, $actions ? implode(' ', $actions) : '(nothing)');
    }
}
echo 'auth: register ', $app->auth('allow_register') ? 'on' : 'off', ' (', implode(',', $app->auth('register_fields')), '), verified required ', $app->auth('require_verified') ? 'yes' : 'no', ', password_min ', $app->auth('password_min'), PHP_EOL;
echo 'jwt: access ', $app->accessTtl(), ' s, refresh ', $app->refreshTtl(), ' s', PHP_EOL;
echo 'cors: ', $app->corsOrigins() ? implode(' ', $app->corsOrigins()) : '(none: the Android app needs no CORS)', PHP_EOL;
echo 'actor_in_sql: ', $app->actorInSql() ? 'ON' : 'off', PHP_EOL;

// The Phase 3 contract, checked on the normalised config (what AppHub will really use).
$problems = [];
$data = ['shopping_lists', 'shopping_items', 'categories', 'tags'];
foreach ($app->roleNames() as $role) {
    if ($role !== 'user') {
        $problems[] = "role '$role' exists: this phase defines only the 'user' role";
    }
    foreach ($app->tableNames() as $table) {
        if ($app->scope($role, $table, 'delete') !== null) {
            $problems[] = "$role may delete $table: clients only tombstone (PATCH deleted_at_ms), nobody gets delete";
        }
        foreach (AppHub\App::ACTIONS as $action) {
            if ($app->scope($role, $table, $action) === 'shared' || $app->scope($role, $table, $action) === 'all') {
                $problems[] = "$role $action $table is not 'own': no shared or all scope in this phase";
            }
        }
    }
}
if ($app->actorInSql() || $app->emails() !== null) {
    $problems[] = 'actor_in_sql or emails are on: not in this phase';
}
foreach ($data as $table) {
    $t = $app->table($table);
    if ($t === null) {
        $problems[] = "table $table is missing";
        continue;
    }
    if ($t['owner'] !== 'user_id') {
        $problems[] = "$table owner is not user_id";
    }
    if (in_array('user_id', $t['writable'], true) || in_array('updated_at', $t['writable'], true)) {
        $problems[] = "$table: user_id and updated_at must never be writable";
    }
    if (!in_array('id', $t['writable'], true)) {
        $problems[] = "$table: id must be writable (the client supplies the UUID on create)";
    }
    foreach (['id', 'updated_at', 'created_at_ms'] as $col) {
        if (!in_array($col, $t['sortable'], true)) {
            $problems[] = "$table: $col must be sortable";
        }
    }
    if (!in_array('id', $t['filterable'], true)) {
        $problems[] = "$table: id must be filterable";
    }
    foreach (['created_at_ms', 'deleted_at_ms'] as $col) {
        if (!in_array($col, $t['writable'], true)) {
            $problems[] = "$table: $col must be writable";
        }
    }
    if ($t['soft_delete'] !== null || $t['shared_via'] !== null || $t['members'] !== []) {
        $problems[] = "$table uses soft_delete, shared_via or members: not in this phase";
    }
}
foreach (['shopping_lists', 'shopping_items'] as $table) {
    $t = $app->table($table);
    if ($t === null || !in_array('updated_at_ms', $t['writable'], true)) {
        $problems[] = "$table: updated_at_ms (the client's last-edit clock) must be writable";
    } elseif (in_array('updated_at_ms', $t['sortable'], true) || in_array('updated_at_ms', $t['filterable'], true)) {
        $problems[] = "$table: updated_at_ms must be neither sortable nor filterable";
    }
}
$categories = $app->table('categories');
if ($categories !== null) {
    if (!in_array('builtin_key', $categories['writable'], true) || !in_array('builtin_key', $categories['filterable'], true)) {
        $problems[] = 'categories: builtin_key must be writable and filterable (built-in overrides are rows with builtin_key set)';
    }
    if (!in_array('name', $categories['writable'], true) || !in_array('color', $categories['writable'], true)) {
        $problems[] = 'categories: name and color must be writable';
    }
}
$items = $app->table('shopping_items');
if ($items !== null && !in_array('list_id', $items['filterable'], true)) {
    $problems[] = 'shopping_items: list_id must be filterable';
}
$users = $app->table('users');
if ($users === null || $users['writable'] !== ['display_name']) {
    $problems[] = "users: only display_name may be written";
}
if ($app->auth('register_fields') !== ['display_name']) {
    $problems[] = 'auth.register_fields must be ["display_name"]';
}
if ($app->auth('default_role') !== 'user' || $app->auth('password_min') < 10) {
    $problems[] = 'auth: default_role must be user and password_min at least 10';
}
if ($problems !== []) {
    fwrite(STDERR, 'CONTRACT PROBLEMS:' . PHP_EOL . '  - ' . implode(PHP_EOL . '  - ', $problems) . PHP_EOL);
    exit(1);
}
echo 'contract: ok (user-only, own scope, no delete, ids writable, user_id and updated_at never writable)', PHP_EOL;
