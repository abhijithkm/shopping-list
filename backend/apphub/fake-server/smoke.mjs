#!/usr/bin/env node
// smoke.mjs: starts the fake AppHub (server.mjs) on a random localhost port and runs a scripted scenario against it.
//   node smoke.mjs            (add --verbose to print every passing check too)
// Exits 0 only when every check passed, 1 otherwise. It makes no request to anything but its own in-process fake: no
// network, no real AppHub, no credentials (the passwords below are throwaway test values for the fake).

import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { startServer } from './server.mjs';

const verbose = process.argv.includes('--verbose');
let passed = 0;
let failed = 0;
const failures = [];

function check(name, cond, detail) {
  if (cond) {
    passed++;
    if (verbose) console.log(`  ok   ${name}`);
  } else {
    failed++;
    failures.push(name);
    console.log(`  FAIL ${name}${detail === undefined ? '' : `  -> ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`}`);
  }
}
const section = (title) => console.log(`\n== ${title}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const uuid = () => crypto.randomUUID();
const NOW_MS = 1767225600000;

// ---------------------------------------------------------------------------------------------------------------------
// A tiny client over fetch
// ---------------------------------------------------------------------------------------------------------------------
function client(srv, defaultApp = 'shoppinglistdev') {
  const call = async (method, p, { body, token, app = defaultApp, headers = {}, rawBody, noContentType } = {}) => {
    const h = { ...headers };
    if (token) h.authorization = `Bearer ${token}`;
    let payload;
    if (rawBody !== undefined) payload = rawBody;
    else if (body !== undefined) payload = JSON.stringify(body);
    if (payload !== undefined && !noContentType) h['content-type'] = 'application/json';
    const url = p.startsWith('/__') ? srv.url + p : `${srv.url}/${app}${p}`;
    const res = await fetch(url, { method, headers: h, body: payload });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* not json */
    }
    return { status: res.status, headers: res.headers, json, text };
  };
  return {
    call,
    get: (p, o) => call('GET', p, o),
    post: (p, body, o) => call('POST', p, { ...o, body }),
    patch: (p, body, o) => call('PATCH', p, { ...o, body }),
    dev: (method, p, body) => call(method, p, { body }),
  };
}

async function signUp(c, email, password = 'correct horse battery 9', displayName = 'Tester') {
  const reg = await c.post('/auth/register', { email, password, display_name: displayName });
  const login = await c.post('/auth/login', { email, password });
  return { reg, login, token: login.json?.data?.access_token, refresh: login.json?.data?.refresh_token, user: login.json?.data?.user, password };
}

const list = (name, extra = {}) => ({ id: uuid(), name, created_at_ms: NOW_MS, ...extra });
const item = (listId, name, extra = {}) => ({ id: uuid(), list_id: listId, name, created_at_ms: NOW_MS, ...extra });
const message = (r) => r.json?.error?.message;

// ---------------------------------------------------------------------------------------------------------------------
// Main scenario
// ---------------------------------------------------------------------------------------------------------------------
async function mainScenario() {
  const srv = await startServer({ port: 0, quiet: true });
  const c = client(srv);
  try {
    section('contract files and basics');
    check('schema.sql and the config agree (no drift warnings)', srv.driftWarnings.length === 0, srv.driftWarnings);
    let r = await c.call('GET', '', { app: '' }); // GET <url>/ (the health check)
    check('health check answers 200 {ok,data:{status:ok}}', r.status === 200 && r.json?.ok === true && r.json?.data?.status === 'ok', r.text);
    check('every answer carries a 16-hex X-Request-Id', /^[0-9a-f]{16}$/.test(r.headers.get('x-request-id') || ''), r.headers.get('x-request-id'));
    r = await c.get('/shopping_lists', { app: 'nope' });
    check('unknown app answers 401 Authentication required', r.status === 401 && message(r) === 'Authentication required', r.text);
    r = await c.get('/shopping_lists');
    check('no token answers 401 with WWW-Authenticate: Bearer', r.status === 401 && r.headers.get('www-authenticate') === 'Bearer', r.status);
    check('error envelope: ok false, status, message, request_id equal to the header', r.json?.ok === false && r.json?.error?.status === 401 && r.json?.error?.request_id === r.headers.get('x-request-id'), r.text);
    check('responses are no-store JSON', r.headers.get('cache-control') === 'no-store' && (r.headers.get('content-type') || '').startsWith('application/json; charset=utf-8'));
    r = await c.get('/shopping_lists', { token: 'not-a-jwt' });
    check('malformed token answers 401 Invalid or expired token / Malformed Authorization header', r.status === 401 && /Malformed Authorization header|Invalid or expired token/.test(message(r)), r.text);
    r = await c.get('/shopping_lists', { headers: { authorization: 'Basic abc' } });
    check('non-Bearer Authorization header answers 401 Malformed Authorization header', r.status === 401 && message(r) === 'Malformed Authorization header', r.text);

    section('register, login, me, access');
    r = await c.post('/auth/register', { email: 'ann@example.com', password: 'short' });
    check('register: password under 10 characters answers 422 with the AppHub message', r.status === 422 && message(r) === 'Password must be at least 10 characters' && r.json.error.details?.field === 'password', r.text);
    r = await c.post('/auth/register', { email: 'not-an-email', password: 'correct horse battery 9' });
    check('register: invalid email answers 422', r.status === 422 && message(r) === 'A valid email is required', r.text);
    r = await c.post('/auth/register', { email: 'ann@example.com', password: 'password123' });
    check('register: a common password answers 422 Password is too common', r.status === 422 && message(r) === 'Password is too common', r.text);
    r = await c.post('/auth/register', { email: 'ann@example.com', password: 'aaaaaaaaaaaaaa' });
    check('register: a too simple password answers 422', r.status === 422 && message(r) === 'Password is too simple', r.text);
    r = await c.post('/auth/register', { email: 'annabel@example.com', password: 'my Annabel passphrase', display_name: 'x' });
    check('register: a password containing the email name (4+ characters) answers 422', r.status === 422 && message(r) === 'Password must not contain your email name', r.text);
    r = await c.post('/auth/register', { email: 'ann@example.com', password: 'correct horse battery 9', role: 'admin' });
    check('register: choosing a role answers 422 These fields cannot be set at registration', r.status === 422 && message(r) === 'These fields cannot be set at registration' && r.json.error.details?.fields?.[0] === 'role', r.text);
    const ann = await signUp(c, 'ann@example.com', 'correct horse battery 9', 'Ann');
    check('register answers 201 with the user (role forced to user, no password key, verification_email_sent false)', ann.reg.status === 201 && ann.reg.json.data.user.role === 'user' && !('password' in ann.reg.json.data.user) && ann.reg.json.data.verification_email_sent === false && ann.reg.json.data.user.display_name === 'Ann', ann.reg.text);
    r = await c.post('/auth/register', { email: 'ANN@example.com', password: 'correct horse battery 9' });
    check('register: duplicate email (any case) answers 409 This email is already registered', r.status === 409 && message(r) === 'This email is already registered', r.text);
    r = await c.post('/auth/register', { email: 'long@example.com', password: 'correct horse battery 9', display_name: 'x'.repeat(61) });
    check('register: display_name over VARCHAR(60) answers 500 (as MySQL strict mode would)', r.status === 500 && message(r) === 'Internal server error', r.text);
    r = await c.post('/auth/login', { email: 'ann@example.com', password: 'wrong password 123' });
    check('login: wrong password answers 401 Invalid email or password', r.status === 401 && message(r) === 'Invalid email or password', r.text);
    r = await c.post('/auth/login', { email: 'nobody@example.com', password: 'wrong password 123' });
    check('login: unknown email answers the same 401', r.status === 401 && message(r) === 'Invalid email or password', r.text);
    const d = ann.login.json.data;
    check('login: token_type Bearer, expires_in 900, refresh_expires_in 1209600, user present', ann.login.status === 200 && d.token_type === 'Bearer' && d.expires_in === 900 && d.refresh_expires_in === 1209600 && d.user.email === 'ann@example.com', ann.login.text);
    check('login: refresh token looks like {app}.{43 url-safe chars}', /^shoppinglistdev\.[A-Za-z0-9_-]{43}$/.test(d.refresh_token), d.refresh_token);
    check('login: access token is a 3-part JWT', d.access_token.split('.').length === 3);
    r = await c.get('/auth/me', { token: ann.token });
    check('/auth/me answers the user row without the password', r.status === 200 && r.json.data.email === 'ann@example.com' && !('password' in r.json.data) && r.json.data.id === d.user.id, r.text);
    r = await c.get('/auth/access', { token: ann.token });
    const acc = r.json?.data;
    check('/auth/access: app, principal and auth_table', r.status === 200 && acc.app === 'shoppinglistdev' && acc.principal.role === 'user' && acc.principal.id === String(d.user.id) && acc.auth_table === 'users', r.text);
    check('/auth/access: the four data tables allow list/read/create/update as own and no delete', ['shopping_lists', 'shopping_items', 'categories', 'tags'].every((t) => JSON.stringify(acc.tables[t].actions) === '{"list":"own","read":"own","create":"own","update":"own"}'), acc.tables);
    check('/auth/access: users allows read and update only', JSON.stringify(acc.tables.users.actions) === '{"read":"own","update":"own"}' && JSON.stringify(acc.tables.users.update_fields) === '["display_name"]');
    check('/auth/access: create_fields has id, never user_id or updated_at; update_fields has no id', acc.tables.shopping_items.create_fields.includes('id') && !acc.tables.shopping_items.create_fields.includes('user_id') && !acc.tables.shopping_items.create_fields.includes('updated_at') && !acc.tables.shopping_items.update_fields.includes('id'));
    check('/auth/access: sortable has updated_at, created_at_ms; items filterable has list_id', acc.tables.shopping_items.sortable.includes('updated_at') && acc.tables.shopping_items.sortable.includes('created_at_ms') && acc.tables.shopping_items.filterable.includes('list_id') && acc.tables.shopping_lists.filterable.includes('id'));
    check('/auth/access: categories accept builtin_key on create and update and filter on it', acc.tables.categories.create_fields.includes('builtin_key') && acc.tables.categories.update_fields.includes('builtin_key') && acc.tables.categories.filterable.includes('builtin_key'), acc.tables.categories);
    r = await c.post('/auth/forgot', { email: 'ann@example.com' });
    check('forgot answers 404 while the config has no reset_url (no email in this phase)', r.status === 404, r.text);
    r = await c.post('/auth/google', { id_token: 'x' });
    check('google sign-in answers 404 (not configured)', r.status === 404, r.text);
    r = await c.get('/auth/login');
    check('GET on a POST-only auth route answers 405 with Allow: POST', r.status === 405 && r.headers.get('allow') === 'POST', r.status);
    r = await c.post('/auth/login', undefined, { rawBody: '{"email":', headers: {} });
    check('login: invalid JSON answers 400 Request body is not valid JSON', r.status === 400 && message(r) === 'Request body is not valid JSON', r.text);
    r = await c.post('/auth/login', undefined, { rawBody: '{"email":"a"}', noContentType: true });
    check('login: no JSON content type answers 415', r.status === 415 && message(r) === 'Content-Type must be application/json', r.text);

    section('create with client UUIDs, duplicates, idempotent retry');
    const listA = list('Groceries', { icon: '\u{1F6D2}', color: -16777216, is_pinned: true, sort_key: 1024 });
    r = await c.post('/shopping_lists', listA, { token: ann.token });
    const row = r.json?.data;
    check('create list answers 201 with the client UUID as id', r.status === 201 && row.id === listA.id, r.text);
    check('list row: user_id is the caller, owner forced by the server', row.user_id === d.user.id, row);
    check('list row types: TINYINT as 0/1 numbers, BIGINT as numbers, DOUBLE as 1024.0, color signed int', row.is_pinned === 1 && row.is_archived === 0 && row.created_at_ms === NOW_MS && row.deleted_at_ms === null && row.color === -16777216 && /"sort_key":1024\.0/.test(r.text), r.text);
    check('list row: updated_at is a DATETIME(3) UTC string', /^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}$/.test(row.updated_at), row.updated_at);
    check('list row: emoji icon survives (utf8mb4)', row.icon === '\u{1F6D2}');
    const itemA = item(listA.id, 'Milk', { quantity: 2.5, unit: 'l', category_key: 'dairy', notes: 'semi-skimmed', tag_ids: '["t1","t2"]', sort_order: 1024, is_bought: false });
    r = await c.post('/shopping_items', itemA, { token: ann.token });
    check('create item answers 201; DECIMAL quantity comes back as the STRING "2.500"', r.status === 201 && r.json.data.quantity === '2.500' && r.json.data.list_id === listA.id, r.text);
    check('item row: tag_ids is a string, is_bought false became 0, bought_at_ms null', r.json.data.tag_ids === '["t1","t2"]' && r.json.data.is_bought === 0 && r.json.data.bought_at_ms === null);
    r = await c.post('/shopping_items', itemA, { token: ann.token });
    check('duplicate create answers 409 with the fixed text', r.status === 409 && message(r) === 'The data conflicts with existing records (duplicate value or invalid reference)', r.text);
    r = await c.get(`/shopping_items/${itemA.id}`, { token: ann.token });
    check('GET by id after the 409 answers 200 (the idempotent retry pattern)', r.status === 200 && r.json.data.name === 'Milk' && r.json.data.id === itemA.id, r.text);
    r = await c.get(`/shopping_items/${itemA.id.toUpperCase()}`, { token: ann.token });
    check('ids compare case-insensitively (utf8mb4_unicode_ci)', r.status === 200, r.status);
    r = await c.post('/shopping_items', item(uuid(), 'Orphan'), { token: ann.token });
    check('item with an unknown list_id answers 409 (foreign key)', r.status === 409, r.text);
    const annDairy = { id: uuid(), builtin_key: 'dairy', name: 'Milk and eggs', color: -256, created_at_ms: NOW_MS };
    r = await c.post('/categories', annDairy, { token: ann.token });
    check('built-in override: a UUID id with builtin_key "dairy", rename and colour, is created', r.status === 201 && r.json.data.id === annDairy.id && r.json.data.builtin_key === 'dairy' && r.json.data.name === 'Milk and eggs' && r.json.data.color === -256, r.text);
    await sleep(4);
    r = await c.post('/categories', { id: uuid(), builtin_key: 'bread', color: 5, created_at_ms: NOW_MS }, { token: ann.token });
    check('built-in override with a colour only (name null) is accepted', r.status === 201 && r.json.data.name === null && r.json.data.builtin_key === 'bread', r.text);
    await sleep(4);
    r = await c.post('/categories', { id: uuid(), name: 'Snacks', color: 7, created_at_ms: NOW_MS }, { token: ann.token });
    check('custom category: builtin_key is null', r.status === 201 && r.json.data.builtin_key === null && r.json.data.name === 'Snacks', r.text);
    r = await c.post('/categories', { id: uuid(), builtin_key: 'dairy', created_at_ms: NOW_MS }, { token: ann.token });
    check('a category with neither name nor color violates the CHECK and answers 500', r.status === 500, r.text);
    r = await c.post('/categories', { id: uuid(), name: '  ', created_at_ms: NOW_MS }, { token: ann.token });
    check('a blank category name violates the CHECK and answers 500', r.status === 500, r.text);
    r = await c.post('/categories', { id: uuid(), builtin_key: 'k'.repeat(41), name: 'x', created_at_ms: NOW_MS }, { token: ann.token });
    check('builtin_key over VARCHAR(40) answers 500', r.status === 500, r.text);
    r = await c.post('/tags', { id: uuid(), name: 'organic', created_at_ms: NOW_MS }, { token: ann.token });
    check('tag created', r.status === 201 && r.json.data.name === 'organic', r.text);

    section('input the database rejects (what AppHub answers for MySQL strict mode and CHECKs)');
    r = await c.post('/shopping_lists', list('x'.repeat(81)), { token: ann.token });
    check('name over VARCHAR(80) answers 500', r.status === 500 && message(r) === 'Internal server error', r.text);
    r = await c.post('/shopping_lists', list('   '), { token: ann.token });
    check('blank name violates the CHECK and answers 500', r.status === 500, r.text);
    r = await c.post('/shopping_lists', list('Flag', { is_archived: 2 }), { token: ann.token });
    check('is_archived 2 violates the CHECK and answers 500', r.status === 500, r.text);
    r = await c.post('/shopping_lists', { id: uuid(), name: 'No created time' }, { token: ann.token });
    check('omitting created_at_ms (NOT NULL, no default) answers 500', r.status === 500, r.text);
    r = await c.post('/shopping_lists', { ...list('Null name'), name: null }, { token: ann.token });
    check('an explicit null for a NOT NULL column answers 409 (SQLSTATE 23000)', r.status === 409, r.text);
    r = await c.post('/shopping_items', item(listA.id, 'Bad qty', { quantity: 'lots' }), { token: ann.token });
    check('a non-numeric quantity answers 500', r.status === 500, r.text);
    r = await c.post('/shopping_items', item(listA.id, 'Zero qty', { quantity: 0 }), { token: ann.token });
    check('quantity 0 violates CHECK (quantity > 0) and answers 500', r.status === 500, r.text);
    r = await c.post('/shopping_items', item(listA.id, 'Rounded', { quantity: 1.23456 }), { token: ann.token });
    check('quantity 1.23456 is rounded to the DECIMAL scale: "1.235"', r.status === 201 && r.json.data.quantity === '1.235', r.text);
    r = await c.post('/shopping_items', item(listA.id, 'Nested', { notes: { a: 1 } }), { token: ann.token });
    check('an object as a field value answers 422', r.status === 422 && /must be a string, number, boolean or null/.test(message(r)), r.text);
    r = await c.post('/shopping_items', item(listA.id, 'Huge', { notes: 'x'.repeat(65536) }), { token: ann.token });
    check('a text value over 65,535 bytes answers 422 Field is too long', r.status === 422 && message(r) === "Field 'notes' is too long", r.text);

    section('non-writable and owner fields');
    r = await c.post('/shopping_lists', list('Hijack', { user_id: 99 }), { token: ann.token });
    check('create with user_id answers 422 These fields cannot be written (details.fields has user_id)', r.status === 422 && message(r) === 'These fields cannot be written' && r.json.error.details.fields.includes('user_id'), r.text);
    check('the 422 lists the writable fields (details.writable) without user_id and updated_at', Array.isArray(r.json.error.details.writable) && !r.json.error.details.writable.includes('user_id') && !r.json.error.details.writable.includes('updated_at') && r.json.error.details.writable.includes('id'));
    r = await c.post('/shopping_lists', list('Stamp', { updated_at: '2020-01-01 00:00:00.000' }), { token: ann.token });
    check('create with updated_at answers 422', r.status === 422 && r.json.error.details.fields.includes('updated_at'), r.text);
    r = await c.patch(`/shopping_lists/${listA.id}`, { user_id: 99 }, { token: ann.token });
    check('PATCH with user_id answers 422', r.status === 422 && r.json.error.details.fields.includes('user_id'), r.text);
    r = await c.patch(`/shopping_lists/${listA.id}`, { id: uuid() }, { token: ann.token });
    check('PATCH with id answers 422 (a client key is accepted on create only)', r.status === 422 && r.json.error.details.fields.includes('id'), r.text);
    r = await c.patch(`/shopping_lists/${listA.id}`, { updated_at: '2020-01-01 00:00:00.000' }, { token: ann.token });
    check('PATCH with updated_at answers 422', r.status === 422, r.text);
    r = await c.patch(`/shopping_lists/${listA.id}`, {}, { token: ann.token });
    check('PATCH with an empty body answers 422 No writable fields were provided', r.status === 422 && message(r) === 'No writable fields were provided', r.text);
    r = await c.post('/shopping_lists', undefined, { token: ann.token, rawBody: '[1,2]' });
    check('a JSON array body answers 400 Request body must be a JSON object', r.status === 400 && message(r) === 'Request body must be a JSON object', r.text);

    section('listing, sorting, paging, filters');
    const stamps = [];
    for (let i = 1; i <= 5; i++) {
      const l = list(`Paged list ${i}`);
      await sleep(4);
      const rr = await c.post('/shopping_lists', l, { token: ann.token });
      stamps.push(rr.json.data.updated_at);
    }
    r = await c.get('/shopping_lists?sort=updated_at&order=desc&limit=3&page=1', { token: ann.token });
    check('list: meta is {page,limit,total,pages}', r.status === 200 && JSON.stringify(Object.keys(r.json.meta)) === '["page","limit","total","pages"]' && r.json.meta.page === 1 && r.json.meta.limit === 3 && r.json.meta.total === 6 && r.json.meta.pages === 2, r.json?.meta);
    const page1 = r.json.data;
    r = await c.get('/shopping_lists?sort=updated_at&order=desc&limit=3&page=2', { token: ann.token });
    const page2 = r.json.data;
    const all = [...page1, ...page2].map((x) => x.updated_at);
    check('list: updated_at desc across pages, newest first', all.length === 6 && all.every((v, i) => i === 0 || all[i - 1] >= v) && page1[0].name === 'Paged list 5', all);
    r = await c.get('/shopping_lists?sort=updated_at&order=asc&limit=2', { token: ann.token });
    check('list: order=asc puts the oldest first', r.json.data[0].name === 'Groceries', r.json.data.map((x) => x.name));
    r = await c.get('/shopping_lists?sort=created_at_ms&order=ASC&limit=100', { token: ann.token });
    check('list: order is case-insensitive and sort=created_at_ms is allowed', r.status === 200, r.text);
    r = await c.get('/shopping_lists', { token: ann.token });
    const ids = r.json.data.map((x) => x.id);
    check('list: the default order is id DESC (NOT chronological) and the default limit is 20', r.json.meta.limit === 20 && JSON.stringify(ids) === JSON.stringify([...ids].sort().reverse()), ids);
    r = await c.get('/shopping_lists?page=3&limit=3', { token: ann.token });
    check('list: a page past the end answers 200 with no rows and the real total', r.status === 200 && r.json.data.length === 0 && r.json.meta.total === 6 && r.json.meta.pages === 2, r.json);
    r = await c.get('/tags?limit=100&sort=id', { token: ann.token });
    check('list: limit 100 is allowed', r.status === 200 && r.json.meta.limit === 100);
    r = await c.get('/shopping_lists?limit=101', { token: ann.token });
    check('list: limit 101 answers 400 limit must be a whole number from 1 to 100', r.status === 400 && message(r) === 'limit must be a whole number from 1 to 100', r.text);
    r = await c.get('/shopping_lists?page=0', { token: ann.token });
    check('list: page 0 answers 400', r.status === 400 && message(r) === 'page must be a whole number from 1 to 1000000', r.text);
    r = await c.get('/shopping_lists?page=abc', { token: ann.token });
    check('list: page=abc answers 400', r.status === 400, r.text);
    r = await c.get('/shopping_lists?sort=name', { token: ann.token });
    check('list: sorting on a column that is not sortable answers 400 with details.sortable', r.status === 400 && message(r) === 'Cannot sort by this field' && r.json.error.details.sortable.includes('updated_at'), r.text);
    r = await c.get('/shopping_lists?order=sideways', { token: ann.token });
    check('list: a bad order answers 400', r.status === 400 && message(r) === "order must be 'asc' or 'desc'", r.text);
    r = await c.get('/shopping_lists?since=2020', { token: ann.token });
    check('list: an unknown parameter (no since filter exists) answers 400 with details.params', r.status === 400 && message(r) === 'Unknown query parameters' && r.json.error.details.params[0] === 'since', r.text);
    r = await c.get('/shopping_lists?name=Groceries', { token: ann.token });
    check('list: a filter on a column that is not filterable answers 400', r.status === 400 && message(r) === 'Unknown query parameters', r.text);
    r = await c.get('/shopping_lists?q=milk', { token: ann.token });
    check('list: q on a table with no searchable columns answers 400', r.status === 400 && message(r) === 'This resource does not support search', r.text);
    r = await c.get(`/shopping_items?list_id=${listA.id}`, { token: ann.token });
    check('list: equality filter list_id returns the items of that list', r.status === 200 && r.json.meta.total >= 1 && r.json.data.every((x) => x.list_id === listA.id), r.json);
    r = await c.get(`/shopping_lists?id=${listA.id}`, { token: ann.token });
    check('list: equality filter id works', r.status === 200 && r.json.meta.total === 1 && r.json.data[0].id === listA.id, r.json);
    r = await c.get(`/shopping_lists?id[]=${listA.id}`, { token: ann.token });
    check('list: an array filter value answers 400 Invalid filter value', r.status === 400 && message(r) === "Invalid filter value for 'id'", r.text);

    section('updated_at: bumped by a real change, never by a no-op');
    r = await c.get(`/shopping_lists/${listA.id}`, { token: ann.token });
    const before = r.json.data.updated_at;
    await sleep(6);
    r = await c.patch(`/shopping_lists/${listA.id}`, { name: 'Groceries', is_pinned: 1, sort_key: 1024 }, { token: ann.token });
    check('PATCH with identical values leaves updated_at unchanged (no-op)', r.status === 200 && r.json.data.updated_at === before, [before, r.json?.data?.updated_at]);
    await sleep(6);
    r = await c.patch(`/shopping_lists/${listA.id}`, { name: 'Weekly groceries' }, { token: ann.token });
    check('PATCH changing a field bumps updated_at and returns the row', r.status === 200 && r.json.data.name === 'Weekly groceries' && r.json.data.updated_at > before, [before, r.json?.data?.updated_at]);
    const afterChange = r.json.data.updated_at;
    r = await c.get(`/shopping_items/${itemA.id}`, { token: ann.token });
    const itemBefore = r.json.data.updated_at;
    await sleep(6);
    r = await c.patch(`/shopping_items/${itemA.id}`, { quantity: 2.5, notes: 'semi-skimmed' }, { token: ann.token });
    check('PATCH quantity 2.5 over "2.500" is a no-op (compared as DECIMAL)', r.status === 200 && r.json.data.updated_at === itemBefore, [itemBefore, r.json?.data?.updated_at]);
    await sleep(6);
    r = await c.patch(`/shopping_items/${itemA.id}`, { is_bought: true, bought_at_ms: NOW_MS + 5 }, { token: ann.token });
    check('PATCH is_bought true stores 1 and bumps updated_at', r.status === 200 && r.json.data.is_bought === 1 && r.json.data.bought_at_ms === NOW_MS + 5 && r.json.data.updated_at > itemBefore, r.text);
    r = await c.call('PUT', `/shopping_lists/${listA.id}`, { token: ann.token, body: { description: 'via PUT' } });
    check('PUT behaves like PATCH (partial update)', r.status === 200 && r.json.data.description === 'via PUT' && r.json.data.name === 'Weekly groceries' && r.json.data.updated_at >= afterChange, r.text);
    await sleep(6);
    r = await c.patch(`/shopping_lists/${listA.id}`, { deleted_at_ms: NOW_MS + 9 }, { token: ann.token });
    check('tombstone: PATCH deleted_at_ms sets it and bumps updated_at', r.status === 200 && r.json.data.deleted_at_ms === NOW_MS + 9 && r.json.data.updated_at > afterChange, r.text);
    r = await c.get('/shopping_lists?sort=updated_at&order=desc&limit=1', { token: ann.token });
    check('tombstone: the deleted row stays in the normal list (it is the newest change)', r.json.data[0].id === listA.id && r.json.data[0].deleted_at_ms === NOW_MS + 9, r.json.data);
    r = await c.patch(`/shopping_lists/${listA.id}`, { deleted_at_ms: null }, { token: ann.token });
    check('tombstone: PATCH deleted_at_ms null undoes it', r.status === 200 && r.json.data.deleted_at_ms === null, r.text);
    r = await c.patch(`/shopping_lists/${listA.id}`, { name: null }, { token: ann.token });
    check('PATCH an explicit null into a NOT NULL column answers 409', r.status === 409, r.text);

    section('updated_at_ms (the client edit clock) and PATCH semantics: null, omitted, not writable');
    const ms1 = NOW_MS + 1000;
    const editedList = list('Edited list', { updated_at_ms: ms1 });
    r = await c.post('/shopping_lists', editedList, { token: ann.token });
    check('create a list with updated_at_ms: returned as a JSON number (no quotes, no fraction)', r.status === 201 && r.json.data.updated_at_ms === ms1 && new RegExp('"updated_at_ms":' + ms1 + '[,}]').test(r.text), r.text);
    check('updated_at_ms is separate from the server-managed updated_at (a DATETIME(3) string)', typeof r.json.data.updated_at === 'string' && r.json.data.updated_at !== String(ms1));
    r = await c.post('/shopping_lists', list('No edit clock'), { token: ann.token });
    check('omitting updated_at_ms on create stores the default 0', r.status === 201 && r.json.data.updated_at_ms === 0, r.text);
    const editedItem = item(listA.id, 'Edited item', { updated_at_ms: ms1, notes: 'keep me', unit: 'kg', quantity: 3 });
    r = await c.post('/shopping_items', editedItem, { token: ann.token });
    check('create an item with updated_at_ms: a JSON number', r.status === 201 && r.json.data.updated_at_ms === ms1 && new RegExp('"updated_at_ms":' + ms1 + '[,}]').test(r.text), r.text);
    const editedBefore = r.json.data.updated_at;
    await sleep(6);
    r = await c.patch(`/shopping_items/${editedItem.id}`, { updated_at_ms: ms1 }, { token: ann.token });
    check('PATCH updated_at_ms with the same value is a no-op (server updated_at unchanged)', r.status === 200 && r.json.data.updated_at === editedBefore, r.text);
    r = await c.patch(`/shopping_items/${editedItem.id}`, { updated_at_ms: ms1 + 500 }, { token: ann.token });
    check('PATCH updated_at_ms changes it (number) and moves the server updated_at', r.status === 200 && r.json.data.updated_at_ms === ms1 + 500 && r.json.data.updated_at > editedBefore, r.text);
    r = await c.patch(`/shopping_lists/${editedList.id}`, { updated_at_ms: ms1 + 7 }, { token: ann.token });
    check('PATCH updated_at_ms on a list works', r.status === 200 && r.json.data.updated_at_ms === ms1 + 7, r.text);
    r = await c.patch(`/shopping_items/${editedItem.id}`, { updated_at_ms: null }, { token: ann.token });
    check('PATCH updated_at_ms to null answers 409 (NOT NULL)', r.status === 409, r.text);
    r = await c.get('/shopping_items?sort=updated_at_ms', { token: ann.token });
    check('updated_at_ms is not sortable (400)', r.status === 400 && message(r) === 'Cannot sort by this field', r.text);
    r = await c.get('/shopping_items?updated_at_ms=1', { token: ann.token });
    check('updated_at_ms is not filterable (400 Unknown query parameters)', r.status === 400 && message(r) === 'Unknown query parameters', r.text);
    r = await c.get('/auth/access', { token: ann.token });
    check('/auth/access: updated_at_ms is a create and update field of lists and items only', ['shopping_lists', 'shopping_items'].every(function (t) { return r.json.data.tables[t].create_fields.includes('updated_at_ms') && r.json.data.tables[t].update_fields.includes('updated_at_ms'); }) && !r.json.data.tables.tags.create_fields.includes('updated_at_ms') && !r.json.data.tables.categories.create_fields.includes('updated_at_ms'));
    r = await c.patch(`/shopping_items/${editedItem.id}`, { notes: null }, { token: ann.token });
    check('PATCH with an explicit null on a nullable column SETS it to NULL, other columns untouched', r.status === 200 && r.json.data.notes === null && r.json.data.unit === 'kg' && r.json.data.name === 'Edited item' && r.json.data.quantity === '3.000', r.text);
    r = await c.patch(`/shopping_items/${editedItem.id}`, { name: 'Renamed item' }, { token: ann.token });
    check('a field omitted from the PATCH body is left unchanged (notes stays NULL, unit stays kg)', r.status === 200 && r.json.data.name === 'Renamed item' && r.json.data.notes === null && r.json.data.unit === 'kg' && r.json.data.quantity === '3.000', r.text);
    r = await c.patch(`/shopping_items/${editedItem.id}`, { quantity: null, unit: null, category_key: null, tag_ids: null, bought_at_ms: null }, { token: ann.token });
    check('explicit nulls on several nullable columns (DECIMAL, VARCHAR, BIGINT, TEXT) all set NULL', r.status === 200 && r.json.data.quantity === null && r.json.data.unit === null && r.json.data.tag_ids === null && r.json.data.bought_at_ms === null, r.text);
    r = await c.patch(`/shopping_items/${editedItem.id}`, { name: 'Should not apply', updated_at: '2020-01-01 00:00:00.000' }, { token: ann.token });
    check('a column that is not writable answers 422 (never silently ignored) and nothing in the body is applied', r.status === 422 && r.json.error.details.fields.length === 1 && r.json.error.details.fields[0] === 'updated_at', r.text);
    r = await c.get(`/shopping_items/${editedItem.id}`, { token: ann.token });
    check('...the row is unchanged after the refused PATCH', r.status === 200 && r.json.data.name === 'Renamed item', r.text);
    r = await c.patch(`/shopping_items/${editedItem.id}`, { colour: 'red' }, { token: ann.token });
    check('a field that is not a column at all is also refused with 422', r.status === 422 && r.json.error.details.fields[0] === 'colour', r.text);

    section('no delete, method and route rules');
    r = await c.call('DELETE', `/shopping_lists/${listA.id}`, { token: ann.token });
    check('DELETE answers 403 Your role cannot delete shopping_lists (real AppHub: no delete right, not 405)', r.status === 403 && message(r) === 'Your role cannot delete shopping_lists', r.text);
    r = await c.get(`/shopping_lists/${listA.id}`, { token: ann.token });
    check('the row is still there after the refused DELETE', r.status === 200);
    r = await c.call('PUT', '/shopping_lists', { token: ann.token, body: {} });
    check('PUT on the collection answers 405 with Allow: GET, POST', r.status === 405 && r.headers.get('allow') === 'GET, POST', r.status);
    r = await c.call('POST', `/shopping_lists/${listA.id}`, { token: ann.token, body: {} });
    check('POST on a row answers 405 with Allow: GET, PUT, PATCH, DELETE', r.status === 405 && r.headers.get('allow') === 'GET, PUT, PATCH, DELETE', r.status);
    r = await c.get('/shopping_lists/has%20space', { token: ann.token });
    check('an id outside [A-Za-z0-9_-]{1,64} answers 404', r.status === 404, r.text);
    r = await c.get(`/shopping_lists/${'a'.repeat(65)}`, { token: ann.token });
    check('an id longer than 64 characters answers 404', r.status === 404, r.text);
    r = await c.get(`/shopping_lists/${uuid()}`, { token: ann.token });
    check('an unknown id answers 404 Not found', r.status === 404 && message(r) === 'Not found', r.text);
    r = await c.get('/nothing_here', { token: ann.token });
    check('an unknown table answers 404', r.status === 404, r.text);
    r = await c.get('/users', { token: ann.token });
    check('listing users answers 403 Your role cannot list users', r.status === 403 && message(r) === 'Your role cannot list users', r.text);
    r = await c.post('/users', { email: 'x@example.com' }, { token: ann.token });
    check('creating a user through the table API answers 403', r.status === 403 && message(r) === 'Your role cannot create users', r.text);
    r = await c.get(`/shopping_lists/${listA.id}/restore`, { token: ann.token });
    check('restore route: GET answers 405', r.status === 405, r.status);
    r = await c.post(`/shopping_lists/${listA.id}/restore`, {}, { token: ann.token });
    check('restore route: POST answers 404 (no soft_delete column in use)', r.status === 404, r.status);
    r = await c.post('/files', {}, { token: ann.token });
    check('uploads are off: POST /files answers 404', r.status === 404, r.status);

    section('users table: own row only');
    r = await c.get(`/users/${d.user.id}`, { token: ann.token });
    check('GET /users/{me} answers my row without password', r.status === 200 && r.json.data.email === 'ann@example.com' && !('password' in r.json.data), r.text);
    r = await c.patch(`/users/${d.user.id}`, { display_name: 'Ann K' }, { token: ann.token });
    check('PATCH /users/{me} display_name works', r.status === 200 && r.json.data.display_name === 'Ann K', r.text);
    for (const f of ['email', 'role', 'status', 'password', 'email_verified_at', 'id']) {
      r = await c.patch(`/users/${d.user.id}`, { [f]: 'x' }, { token: ann.token });
      check(`PATCH /users/{me} with ${f} answers 422`, r.status === 422 && r.json.error.details.fields.includes(f), r.text);
    }
    r = await c.patch(`/users/${d.user.id}`, { display_name: 'y'.repeat(61) }, { token: ann.token });
    check('PATCH display_name over 60 characters answers 500', r.status === 500, r.text);

    section('second user: isolation (cross-user answers 404)');
    const bob = await signUp(c, 'bob@example.com', 'another long secret 7', 'Bob');
    r = await c.get(`/shopping_lists/${listA.id}`, { token: bob.token });
    check('cross-user GET answers 404', r.status === 404, r.text);
    r = await c.patch(`/shopping_lists/${listA.id}`, { name: 'Stolen' }, { token: bob.token });
    check('cross-user PATCH answers 404', r.status === 404, r.text);
    r = await c.get(`/shopping_lists?id=${listA.id}`, { token: bob.token });
    check('cross-user list with a filter on the id finds nothing', r.status === 200 && r.json.meta.total === 0, r.json);
    r = await c.get('/shopping_lists', { token: bob.token });
    check("the other user's list is empty", r.status === 200 && r.json.meta.total === 0 && r.json.data.length === 0, r.json);
    r = await c.get(`/users/${d.user.id}`, { token: bob.token });
    check("cross-user GET /users/{other} answers 404", r.status === 404, r.text);
    r = await c.patch(`/users/${d.user.id}`, { display_name: 'Hacked' }, { token: bob.token });
    check('cross-user PATCH /users/{other} answers 404', r.status === 404, r.text);
    r = await c.post('/shopping_lists', { ...listA }, { token: bob.token });
    check('creating a row with another user\'s id answers 409 (the primary key is global)', r.status === 409, r.text);
    r = await c.get(`/shopping_lists/${listA.id}`, { token: bob.token });
    check('...and a GET of that id is still 404 for the second user (the retry pattern cannot recover it)', r.status === 404);
    section('categories: per-account built-in overrides (builtin_key + deterministic per-user ids)');
    const bobDairy = { id: uuid(), builtin_key: 'dairy', color: 123456, created_at_ms: NOW_MS };
    r = await c.post('/categories', bobDairy, { token: bob.token });
    check('a second account can store its own override of the same built-in ("dairy") with a different id', r.status === 201 && r.json.data.builtin_key === 'dairy' && r.json.data.name === null && r.json.data.color === 123456, r.text);
    r = await c.get('/categories?sort=updated_at&order=desc', { token: ann.token });
    const annIds = (r.json.data || []).map(function (x) { return x.id; });
    check('each account only sees its own categories (Ann: 3 rows, none of Bob\'s)', r.status === 200 && r.json.meta.total === 3 && !annIds.includes(bobDairy.id) && annIds.includes(annDairy.id), r.json);
    r = await c.get('/categories', { token: bob.token });
    check('...and Bob sees only his one row', r.status === 200 && r.json.meta.total === 1 && r.json.data[0].id === bobDairy.id, r.json);
    r = await c.get('/categories?builtin_key=dairy', { token: ann.token });
    check('filter builtin_key=dairy returns only the caller\'s override', r.status === 200 && r.json.meta.total === 1 && r.json.data[0].id === annDairy.id, r.json);
    r = await c.get('/categories?builtin_key=dairy', { token: bob.token });
    check('...for the second account too', r.status === 200 && r.json.meta.total === 1 && r.json.data[0].id === bobDairy.id, r.json);
    r = await c.post('/categories', bobDairy, { token: bob.token });
    check('creating the same override id twice answers 409 (idempotent retry)', r.status === 409 && message(r) === 'The data conflicts with existing records (duplicate value or invalid reference)', r.text);
    r = await c.get(`/categories/${bobDairy.id}`, { token: bob.token });
    check('...and GET by id returns the row', r.status === 200 && r.json.data.id === bobDairy.id && r.json.data.builtin_key === 'dairy', r.text);
    const bobBefore = r.json.data.updated_at;
    r = await c.get(`/categories/${annDairy.id}`, { token: bob.token });
    check('the other account cannot read an override by its id (404)', r.status === 404, r.text);
    r = await c.patch(`/categories/${annDairy.id}`, { name: 'Dairy (renamed)' }, { token: bob.token });
    check('the other account cannot change it either (404)', r.status === 404, r.text);
    await sleep(5);
    r = await c.patch(`/categories/${bobDairy.id}`, { name: 'Dairy (Bob)', color: 654321 }, { token: bob.token });
    check('PATCH name and color on an override works and bumps updated_at', r.status === 200 && r.json.data.name === 'Dairy (Bob)' && r.json.data.color === 654321 && r.json.data.updated_at > bobBefore, [bobBefore, r.json?.data?.updated_at]);
    r = await c.patch(`/categories/${bobDairy.id}`, { color: null }, { token: bob.token });
    check('PATCH color to null is fine while the name is set (200)', r.status === 200 && r.json.data.color === null && r.json.data.name === 'Dairy (Bob)', r.text);
    r = await c.patch(`/categories/${bobDairy.id}`, { name: null }, { token: bob.token });
    check('PATCH that leaves neither name nor color violates the CHECK and answers 500', r.status === 500, r.text);
    r = await c.patch(`/categories/${bobDairy.id}`, { builtin_key: 'dairy' }, { token: bob.token });
    check('PATCH builtin_key with the same value is a no-op (200)', r.status === 200 && r.json.data.builtin_key === 'dairy', r.text);
    r = await c.get('/categories?sort=updated_at&order=desc', { token: ann.token });
    check('sort=updated_at works on categories (newest change first: Snacks, bread, dairy)', r.status === 200 && JSON.stringify(r.json.data.map(function (x) { return x.name || x.builtin_key; })) === JSON.stringify(['Snacks', 'bread', 'Milk and eggs']), r.json.data.map(function (x) { return x.name || x.builtin_key; }));
    r = await c.get('/categories?sort=updated_at&order=asc', { token: ann.token });
    check('...and ascending puts the oldest first', r.status === 200 && r.json.data[0].id === annDairy.id, r.json);
    r = await c.get('/categories?sort=created_at_ms&limit=2', { token: ann.token });
    check('sort=created_at_ms is allowed on categories', r.status === 200 && r.json.data.length === 2, r.text);
    section('second user, continued');
    r = await c.post('/shopping_items', item(listA.id, "Bob's item in Ann's list"), { token: bob.token });
    check('KNOWN LIMITATION (Phase 4 triggers): an item may reference another user\'s list_id (only a foreign key checks it)', r.status === 201 && r.json.data.user_id === bob.user.id, r.text);
    r = await c.get(`/shopping_items?list_id=${listA.id}`, { token: ann.token });
    check("...and the list owner does not see that item (owner column scoping)", r.status === 200 && r.json.data.every((x) => x.user_id === d.user.id), r.json);
    r = await c.get('/auth/me', { token: bob.token });
    check('/auth/me of the second user is the second user', r.json.data.email === 'bob@example.com');
    const dev1 = client(srv, 'shoppinglist');
    r = await dev1.post('/auth/login', { email: 'ann@example.com', password: 'correct horse battery 9' });
    check('an account of shoppinglistdev does not exist in shoppinglist (separate apps) -> 401', r.status === 401, r.text);
    r = await dev1.get('/shopping_lists', { token: ann.token });
    check('a dev token on the production app name is not accepted as a production token (404, the token decides the app)', r.status === 404, r.text);
    r = await c.get('/shopping_lists', { token: ann.token, app: 'nope' });
    check('a valid token with an unknown app name in the URL is treated as the token app and finds no such table (404)', r.status === 404, r.text);

    section('refresh rotation and reuse detection');
    const first = { access: ann.token, refresh: ann.refresh };
    r = await c.post('/auth/refresh', { refresh_token: first.refresh });
    const second = r.json?.data;
    check('refresh answers a new pair (+ user)', r.status === 200 && second.access_token && second.refresh_token && second.refresh_token !== first.refresh && second.user.email === 'ann@example.com', r.text);
    check('the new refresh token has the same format', /^shoppinglistdev\.[A-Za-z0-9_-]{43}$/.test(second.refresh_token));
    r = await c.get('/shopping_lists?limit=1', { token: first.access });
    check('the previous access token still works until it expires', r.status === 200, r.text);
    r = await c.post('/auth/refresh', { refresh_token: first.refresh });
    check('REUSE of the old refresh token answers 401 and says all sessions were signed out', r.status === 401 && message(r) === 'Refresh token was already used; all sessions from it were signed out', r.text);
    r = await c.post('/auth/refresh', { refresh_token: second.refresh_token });
    check('...and the whole family is dead: the newest refresh token no longer works either (401)', r.status === 401, r.text);
    r = await c.post('/auth/refresh', { refresh_token: 'shoppinglistdev.' + 'A'.repeat(43) });
    check('an unknown refresh token answers 401 Invalid refresh token', r.status === 401 && message(r) === 'Invalid refresh token', r.text);
    r = await c.post('/auth/refresh', { refresh_token: 'otherapp.' + 'A'.repeat(43) });
    check('a refresh token of another app answers 401 Invalid refresh token', r.status === 401 && message(r) === 'Invalid refresh token', r.text);
    r = await c.post('/auth/refresh', { refresh_token: 'garbage' });
    check('a malformed refresh token answers 401', r.status === 401, r.text);
    const relog = await c.post('/auth/login', { email: 'ann@example.com', password: 'correct horse battery 9' });
    const sessionB = relog.json.data;
    r = await c.post('/auth/login', { email: 'ann@example.com', password: 'correct horse battery 9' });
    const sessionC = r.json.data;
    r = await c.post('/auth/refresh', { refresh_token: sessionB.refresh_token });
    const b2 = r.json.data;
    await c.post('/auth/refresh', { refresh_token: sessionB.refresh_token });
    r = await c.post('/auth/refresh', { refresh_token: sessionC.refresh_token });
    check('reuse in one session (family) does not sign out another session of the same user', r.status === 200, r.text);
    r = await c.post('/auth/refresh', { refresh_token: b2.refresh_token });
    check('...but the reused session itself is signed out', r.status === 401, r.text);

    section('access token expiry, refresh expiry, logout, password change (virtual clock)');
    const live = (await c.post('/auth/login', { email: 'ann@example.com', password: 'correct horse battery 9' })).json.data;
    await c.dev('POST', '/__clock', { advance_seconds: 800 });
    r = await c.get('/auth/me', { token: live.access_token });
    check('800 s later the access token (900 s) is still valid', r.status === 200, r.text);
    await c.dev('POST', '/__clock', { advance_seconds: 120 });
    r = await c.get('/auth/me', { token: live.access_token });
    check('at 920 s the access token is past exp (900 s) but inside the 30 s leeway: still valid (Jwt::LEEWAY)', r.status === 200, r.text);
    await c.dev('POST', '/__clock', { advance_seconds: 20 });
    r = await c.get('/auth/me', { token: live.access_token });
    check('at 940 s the access token answers 401 Invalid or expired token', r.status === 401 && message(r) === 'Invalid or expired token', r.text);
    r = await c.post('/auth/refresh', { refresh_token: live.refresh_token });
    const renewed = r.json?.data;
    check('the refresh token (14 days) still works and yields a working access token', r.status === 200 && (await c.get('/auth/me', { token: renewed.access_token })).status === 200, r.text);
    await c.dev('POST', '/__clock', { advance_seconds: 1209600 + 10 });
    r = await c.post('/auth/refresh', { refresh_token: renewed.refresh_token });
    check('after 14 days the refresh token answers 401 Refresh token expired', r.status === 401 && message(r) === 'Refresh token expired', r.text);
    await c.dev('POST', '/__clock', { reset: true });
    const lo = (await c.post('/auth/login', { email: 'ann@example.com', password: 'correct horse battery 9' })).json.data;
    r = await c.post('/auth/logout', { refresh_token: lo.refresh_token });
    check('logout answers 200 Signed out', r.status === 200 && r.json.data.message === 'Signed out', r.text);
    r = await c.post('/auth/refresh', { refresh_token: lo.refresh_token });
    check('after logout the refresh token is dead (401)', r.status === 401, r.text);
    r = await c.post('/auth/logout', { refresh_token: 'whatever' });
    check('logout with garbage still answers 200 (it always succeeds)', r.status === 200, r.text);
    const pw = (await c.post('/auth/login', { email: 'ann@example.com', password: 'correct horse battery 9' })).json.data;
    r = await c.post('/auth/password', { current_password: 'wrong wrong wrong', new_password: 'brand new passphrase 5' }, { token: pw.access_token });
    check('password change with a wrong current password answers 403', r.status === 403 && message(r) === 'Current password is incorrect', r.text);
    r = await c.post('/auth/password', { current_password: 'correct horse battery 9', new_password: 'short' }, { token: pw.access_token });
    check('password change with a weak new password answers 422', r.status === 422, r.text);
    r = await c.post('/auth/password', { current_password: 'correct horse battery 9', new_password: 'correct horse battery 9' }, { token: pw.access_token });
    check('password change to the same password answers 422 New password must be different', r.status === 422 && message(r) === 'New password must be different', r.text);
    r = await c.post('/auth/password', { current_password: 'correct horse battery 9', new_password: 'brand new passphrase 5' }, { token: pw.access_token });
    const pwTokens = r.json?.data;
    check('password change answers 200 with a fresh token pair', r.status === 200 && pwTokens.access_token && pwTokens.refresh_token, r.text);
    r = await c.get('/auth/me', { token: pw.access_token });
    check('after a password change the old access token is invalid (fingerprint)', r.status === 401, r.text);
    r = await c.post('/auth/refresh', { refresh_token: pw.refresh_token });
    check('after a password change the old refresh token is revoked', r.status === 401, r.text);
    r = await c.get('/auth/me', { token: pwTokens.access_token });
    check('the new access token works', r.status === 200, r.text);
    r = await c.post('/auth/login', { email: 'ann@example.com', password: 'brand new passphrase 5' });
    check('login with the new password works', r.status === 200, r.text);
    ann.token = r.json.data.access_token;

    section('fault injection: lost response, lost request, error status, latency');
    const retryItem = item(listA.id, 'Retried item');
    r = await c.dev('POST', '/__fault', { mode: 'drop_after', count: 1, match: 'POST /shoppinglistdev/shopping_items$' });
    check('fault accepted (201)', r.status === 201 && r.json.data.mode === 'drop_after', r.text);
    let threw = false;
    try {
      await c.post('/shopping_items', retryItem, { token: ann.token });
    } catch {
      threw = true;
    }
    check('drop_after: the client sees a network failure instead of a response', threw);
    r = await c.post('/shopping_items', retryItem, { token: ann.token });
    check('drop_after: the retried create answers 409 (the server had processed the first one)', r.status === 409, r.text);
    r = await c.get(`/shopping_items/${retryItem.id}`, { token: ann.token });
    check('drop_after: GET by id then confirms the row exists (idempotent retry)', r.status === 200 && r.json.data.name === 'Retried item', r.text);
    r = await c.post('/shopping_items', item(listA.id, 'Not dropped'), { token: ann.token });
    check('drop_after: the fault was consumed (count 1): the next create works', r.status === 201, r.text);
    const lostItem = item(listA.id, 'Lost request item');
    await c.dev('POST', '/__fault', { mode: 'drop_before', count: 1, match: 'POST /shoppinglistdev/shopping_items$' });
    threw = false;
    try {
      await c.post('/shopping_items', lostItem, { token: ann.token });
    } catch {
      threw = true;
    }
    check('drop_before: the client sees a network failure', threw);
    r = await c.get(`/shopping_items/${lostItem.id}`, { token: ann.token });
    check('drop_before: the request was never processed (404)', r.status === 404, r.text);
    r = await c.post('/shopping_items', lostItem, { token: ann.token });
    check('drop_before: the retry creates the row (201)', r.status === 201, r.text);
    await c.dev('POST', '/__fault', { mode: 'status', status: 503, retry_after: 2, count: 2, match: 'GET /shoppinglistdev/tags' });
    r = await c.get('/tags', { token: ann.token });
    check('status fault: 503 with Retry-After 2 and an error envelope', r.status === 503 && r.headers.get('retry-after') === '2' && r.json.ok === false && r.json.error.status === 503 && message(r) === 'Service temporarily unavailable', r.text);
    r = await c.get('/tags', { token: ann.token });
    check('status fault: count 2, second request also 503', r.status === 503);
    r = await c.get('/tags', { token: ann.token });
    check('status fault: third request is normal', r.status === 200);
    await c.dev('POST', '/__fault', { mode: 'delay', ms: 300, count: 1, match: 'GET /shoppinglistdev/auth/me' });
    const t0 = Date.now();
    r = await c.get('/auth/me', { token: ann.token });
    check('delay fault: the answer takes at least 300 ms and is correct', Date.now() - t0 >= 280 && r.status === 200, Date.now() - t0);
    const refreshLoss = (await c.post('/auth/login', { email: 'ann@example.com', password: 'brand new passphrase 5' })).json.data;
    await c.dev('POST', '/__fault', { mode: 'drop_after', count: 1, match: 'POST /shoppinglistdev/auth/refresh$' });
    threw = false;
    try {
      await c.post('/auth/refresh', { refresh_token: refreshLoss.refresh_token });
    } catch {
      threw = true;
    }
    check('lost refresh response: the client sees a network failure', threw);
    r = await c.post('/auth/refresh', { refresh_token: refreshLoss.refresh_token });
    check('...retrying the refresh with the old token is REUSE: 401 and the session is dead (why the client must never auto-retry a refresh)', r.status === 401 && message(r) === 'Refresh token was already used; all sessions from it were signed out', r.text);
    r = await c.dev('POST', '/__fault', { mode: 'bogus' });
    check('fault with an unknown mode answers 422', r.status === 422, r.text);
    r = await c.dev('GET', '/__fault');
    check('GET /__fault lists no pending faults now', r.status === 200 && r.json.data.length === 0, r.text);
    await c.dev('POST', '/__fault', { mode: 'status', status: 500, count: -1 });
    r = await c.get('/tags', { token: ann.token });
    check('count -1 keeps a fault until it is cleared', r.status === 500 && message(r) === 'Internal server error', r.text);
    r = await c.dev('DELETE', '/__fault');
    r = await c.get('/tags', { token: ann.token });
    check('DELETE /__fault clears the faults', r.status === 200, r.text);

    section('state and reset');
    r = await c.dev('GET', '/__state');
    check('GET /__state reports row counts per app and no drift warnings', r.status === 200 && r.json.data.apps.shoppinglistdev.tables.shopping_lists >= 6 && r.json.data.drift_warnings.length === 0, r.text);
    r = await c.dev('GET', '/__state?dump=1');
    check('GET /__state?dump=1 dumps rows without password hashes', r.status === 200 && Array.isArray(r.json.data.apps.shoppinglistdev.tables.users) && !JSON.stringify(r.json.data).includes('scrypt1'), r.text.slice(0, 200));
    r = await c.dev('POST', '/__reset');
    check('POST /__reset answers 200', r.status === 200);
    r = await c.post('/auth/login', { email: 'ann@example.com', password: 'brand new passphrase 5' });
    check('after /__reset the accounts are gone (login 401)', r.status === 401, r.text);
    r = await c.get('/shopping_lists', { token: ann.token });
    check('after /__reset old tokens are dead (new secret)', r.status === 401, r.text);
    r = await c.dev('GET', '/__state');
    check('after /__reset the tables are empty', r.json.data.apps.shoppinglistdev.tables.users === 0 && r.json.data.apps.shoppinglistdev.tables.shopping_lists === 0, r.text);
  } finally {
    await srv.close();
  }
}

async function rateLimitScenario() {
  section('429 path: --rate-limit');
  const srv = await startServer({ port: 0, quiet: true, rateLimit: 5 });
  const c = client(srv);
  try {
    let last = null;
    const statuses = [];
    for (let i = 0; i < 6; i++) {
      last = await c.get('/shopping_lists');
      statuses.push(last.status);
    }
    check('requests 1 to 5 pass the limiter (401 without a token), the 6th answers 429', JSON.stringify(statuses) === '[401,401,401,401,401,429]', statuses);
    check('429: Retry-After is a positive number of seconds and the envelope is the AppHub one', Number(last.headers.get('retry-after')) >= 1 && last.json.error.status === 429 && last.json.error.message === 'Too many requests', last.text);
    const ok1 = await c.get('/shopping_lists', { app: 'shoppinglist' });
    check('the limit is per app and IP: the other app still answers (401)', ok1.status === 401, ok1.status);
    const first = await startServer({ port: 0, quiet: true, rateLimit: 5 });
    const c2 = client(first);
    const r1 = await c2.get('/shopping_lists');
    check('X-RateLimit-Limit and X-RateLimit-Remaining are sent when the limiter is on', r1.headers.get('x-ratelimit-limit') === '5' && r1.headers.get('x-ratelimit-remaining') === '4', [r1.headers.get('x-ratelimit-limit'), r1.headers.get('x-ratelimit-remaining')]);
    await first.close();
    const other = await c.call('GET', '/shopping_lists', { headers: { 'x-fake-client-ip': '10.9.9.9' } });
    check('the limit is per IP (X-Fake-Client-Ip simulates another address)', other.status === 401, other.status);
    await c.dev('POST', '/__clock', { advance_seconds: 61 });
    last = await c.get('/shopping_lists');
    check('after the window (60 s, virtual clock) requests pass again', last.status === 401, last.status);
    last = await c.dev('GET', '/__state');
    check('dev endpoints are never rate limited', last.status === 200);
  } finally {
    await srv.close();
  }

  section('429 path: --auth-rate-limit and --login-lockout');
  const a = await startServer({ port: 0, quiet: true, authRateLimit: 3 });
  const ca = client(a);
  try {
    const st = [];
    for (let i = 0; i < 4; i++) st.push((await ca.post('/auth/login', { email: 'x@example.com', password: 'whatever whatever' })).status);
    check('auth rate limit 3: the 4th login attempt answers 429', JSON.stringify(st) === '[401,401,401,429]', st);
    const me = await ca.get('/auth/me');
    check('me is not auth-rate-limited (401, not 429)', me.status === 401, me.status);
  } finally {
    await a.close();
  }
  const l = await startServer({ port: 0, quiet: true, loginLockout: 3 });
  const cl = client(l);
  try {
    await signUp(cl, 'lock@example.com', 'correct horse battery 9');
    const st = [];
    for (let i = 0; i < 3; i++) st.push((await cl.post('/auth/login', { email: 'lock@example.com', password: 'wrong wrong wrong' })).status);
    const locked = await cl.post('/auth/login', { email: 'lock@example.com', password: 'correct horse battery 9' });
    check('lockout: 3 wrong passwords lock the (email, IP) pair; even the right password then answers 429 with Retry-After', JSON.stringify(st) === '[401,401,401]' && locked.status === 429 && Number(locked.headers.get('retry-after')) > 0, [st, locked.status]);
    const otherEmail = await cl.post('/auth/login', { email: 'other@example.com', password: 'wrong wrong wrong' });
    check('lockout: another email from the same IP is not locked (401)', otherEmail.status === 401, otherEmail.status);
    await cl.dev('POST', '/__clock', { advance_seconds: 901 });
    const unlocked = await cl.post('/auth/login', { email: 'lock@example.com', password: 'correct horse battery 9' });
    check('lockout: after 15 minutes the right password works again (200)', unlocked.status === 200, unlocked.status);
  } finally {
    await l.close();
  }
}

async function mailScenario() {
  section('password reset and email verification (--mail)');
  const srv = await startServer({ port: 0, quiet: true, mail: true });
  const c = client(srv);
  try {
    const reg = await c.post('/auth/register', { email: 'mail@example.com', password: 'correct horse battery 9' });
    check('--mail: register reports verification_email_sent true', reg.status === 201 && reg.json.data.verification_email_sent === true, reg.text);
    let outbox = (await c.dev('GET', '/__outbox')).json.data;
    check('--mail: the "email" is in the outbox with a one-time token', outbox.length === 1 && outbox[0].purpose === 'verify' && /^shoppinglistdev\.[A-Za-z0-9_-]{43}$/.test(outbox[0].token), outbox);
    let r = await c.post('/auth/verify', { token: outbox[0].token });
    check('verify answers 200', r.status === 200 && r.json.data.message === 'Email address verified. You can sign in now.', r.text);
    r = await c.post('/auth/verify', { token: outbox[0].token });
    check('verify twice answers 422 (the link is used up)', r.status === 422 && message(r) === 'This link is invalid, already used or expired. Ask for a new one.', r.text);
    r = await c.post('/auth/forgot', { email: 'nobody@example.com' });
    check('forgot answers the same 200 for an unknown email', r.status === 200 && r.json.data.message === 'If an account with that email exists, an email is on its way.', r.text);
    r = await c.post('/auth/forgot', { email: 'mail@example.com' });
    outbox = (await c.dev('GET', '/__outbox')).json.data;
    const reset = outbox.find((m) => m.purpose === 'reset');
    check('forgot puts a reset token in the outbox for a known email', r.status === 200 && !!reset, outbox);
    const login = await c.post('/auth/login', { email: 'mail@example.com', password: 'correct horse battery 9' });
    r = await c.post('/auth/reset', { token: reset.token, new_password: 'short' });
    check('reset with a weak password answers 422 and does not use up the link', r.status === 422, r.text);
    r = await c.post('/auth/reset', { token: reset.token, new_password: 'a fresh passphrase 42' });
    check('reset answers 200', r.status === 200 && r.json.data.message === 'Password changed. Sign in with your new password.', r.text);
    r = await c.post('/auth/refresh', { refresh_token: login.json.data.refresh_token });
    check('reset signs out every session (the old refresh token is dead)', r.status === 401, r.text);
    r = await c.post('/auth/login', { email: 'mail@example.com', password: 'a fresh passphrase 42' });
    check('login with the reset password works', r.status === 200, r.text);
  } finally {
    await srv.close();
  }
}

async function persistenceScenario() {
  section('--data: state survives a restart');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fake-apphub-smoke-'));
  const file = path.join(dir, 'state.json');
  try {
    let srv = await startServer({ port: 0, quiet: true, data: file });
    let c = client(srv);
    const u = await signUp(c, 'keep@example.com', 'correct horse battery 9', 'Keeper');
    const l = list('Kept list');
    await c.post('/shopping_lists', l, { token: u.token });
    await srv.close();
    check('the data file was written', fs.existsSync(file) && fs.statSync(file).size > 10);
    srv = await startServer({ port: 0, quiet: true, data: file });
    c = client(srv);
    let r = await c.get(`/shopping_lists/${l.id}`, { token: u.token });
    check('after a restart the old access token still works and the list is there', r.status === 200 && r.json.data.name === 'Kept list', r.text);
    r = await c.post('/auth/login', { email: 'keep@example.com', password: 'correct horse battery 9' });
    check('after a restart the password still works', r.status === 200, r.text);
    r = await c.post('/auth/refresh', { refresh_token: u.refresh });
    check('after a restart the refresh token still works', r.status === 200, r.text);
    r = await c.post('/auth/register', { email: 'next@example.com', password: 'correct horse battery 9' });
    check('after a restart ids keep counting (AUTO_INCREMENT 2)', r.status === 201 && r.json.data.user.id === 2, r.text);
    await srv.close();
    check('the data file holds no plaintext password', !fs.readFileSync(file, 'utf8').includes('correct horse battery 9'));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

try {
  await mainScenario();
  await rateLimitScenario();
  await mailScenario();
  await persistenceScenario();
} catch (e) {
  failed++;
  failures.push('unexpected exception');
  console.log('\n  FAIL unexpected exception:', e);
}

console.log(`\nsmoke: ${passed} passed, ${failed} failed`);
if (failed > 0) {
  console.log('failed checks:\n  - ' + failures.join('\n  - '));
  process.exit(1);
}
process.exit(0);
