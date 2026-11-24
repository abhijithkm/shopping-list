#!/usr/bin/env node
// server.mjs: an in-memory FAKE of the AppHub API for local development and testing of the Shopping List Android client.
//
//   node server.mjs --port 8787 [--access-ttl 900] [--refresh-ttl 1209600] [--data file.json] [--rate-limit 0]
//
// This is NOT AppHub and it is NOT a security product: no MySQL, no HTTPS, no CORS, no email, passwords hashed with a weak
// scrypt setting for speed. It makes no network request, reads no credential and listens on localhost only (127.0.0.1 and,
// when available, ::1). Its behaviour mirrors the AppHub PHP source read for Phase 3 (Kernel, CrudController,
// AuthController, TokenService, Passwords, RateLimiter): envelope, status codes and messages. The tables are NOT written
// here: they are loaded from ../schema.sql (column types, NOT NULL, defaults, keys, foreign keys, CHECKs) and from
// ../shoppinglist.config.json (owner, writable, sortable, filterable, roles, shared_via, members, hidden), so the fake cannot drift
// from the files an operator will apply. Since Phase 4 it also emulates the `shared` scope (AppHub src/Controllers/CrudController.php
// sharedCondition) and the MySQL triggers of ../triggers.sql (triggers.mjs, one function per trigger, compared with the SQL file at
// start). README.md lists every known difference from real AppHub.
//
// No dependencies: Node 18 or newer (written and tested on Node 26).

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { TRIGGERS, triggersFor } from './triggers.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------------------------------------------------
// Constants copied from the AppHub source
// ---------------------------------------------------------------------------------------------------------------------
const MAX_BODY = 1048576; // Config 'max_body_bytes'
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/; // Kernel: row id in the URL
const TABLE_RE = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const APP_NAME_RE = /^[a-z][a-z0-9_-]{0,31}$/; // AppRegistry::isValidName
const RESERVED_APP_NAMES = ['admin', 'auth', 'files', 'platform', 'console', 'sdk', 'docs', 'home', 'public'];
const TOKEN_RE = /^([a-z][a-z0-9_-]{0,31})\.[A-Za-z0-9_-]{43}$/; // TokenService::tenantOf
const AUTH_ENTRY = ['login', 'google', 'register', 'refresh', 'logout', 'forgot', 'reset', 'verify', 'resend-verification'];
const LIST_PARAMS = ['q', 'sort', 'order', 'page', 'limit'];
const MAX_LIMIT = 100;
const ACTIONS = ['list', 'read', 'create', 'update', 'delete'];
const MAIL_SENT = 'If an account with that email exists, an email is on its way.';
const BAD_LINK = 'This link is invalid, already used or expired. Ask for a new one.';
const DB_CONFLICT = 'The data conflicts with existing records (duplicate value or invalid reference)';
const MAILS_PER_HOUR = 3;
const USER_TOKEN_TTL = { reset: 3600, verify: 172800 };
const COMMON_PASSWORDS = [
  'password', 'password1', 'password123', '12345678', '123456789', '1234567890', 'qwerty123', 'qwertyuiop', '11111111',
  'iloveyou', 'admin123', 'administrator', 'welcome1', 'welcome123', 'letmein123', 'abc12345', 'changeme', 'passw0rd',
  'football', 'baseball', 'sunshine', 'princess',
];

export const DEFAULTS = {
  port: 8787,
  apps: ['shoppinglistdev', 'shoppinglist'],
  config: path.resolve(HERE, '..', 'shoppinglist.config.json'),
  schema: path.resolve(HERE, '..', 'schema.sql'),
  triggers: path.resolve(HERE, '..', 'triggers.sql'), // only READ, to compare with the emulation in triggers.mjs
  noPepper: false, // true: the app_secrets row code_pepper is missing at start (code operations answer sl:server_not_ready)
  accessTtl: null, // null: the config's jwt.access_ttl (900)
  refreshTtl: null, // null: the config's jwt.refresh_ttl (1209600)
  jwtLeeway: 30, // Jwt::LEEWAY
  data: null,
  rateLimit: 0, // 0 = off; real AppHub: 120 requests per window per IP per app
  rateWindow: 60,
  authRateLimit: 0, // 0 = off; real AppHub: 10 per window per IP per app on login/register/refresh/...
  loginLockout: 0, // 0 = off; real AppHub: 5 wrong passwords lock (email, IP) for lockoutSeconds
  lockoutSeconds: 900,
  mail: false,
  requireVerified: false,
  quiet: false,
};

// ---------------------------------------------------------------------------------------------------------------------
// Errors and the response envelope
// ---------------------------------------------------------------------------------------------------------------------
class HttpError extends Error {
  constructor(status, message, { headers = {}, details = null } = {}) {
    super(message);
    this.status = status;
    this.headers = headers;
    this.details = details;
  }
}
const E = {
  badRequest: (m, d = null) => new HttpError(400, m, { details: d }),
  unauthorized: (m = 'Authentication required') => new HttpError(401, m, { headers: { 'WWW-Authenticate': 'Bearer' } }),
  forbidden: (m = 'Forbidden') => new HttpError(403, m),
  notFound: (m = 'Not found') => new HttpError(404, m),
  unprocessable: (m, d = null) => new HttpError(422, m, { details: d }),
  tooMany: (retry) => new HttpError(429, 'Too many requests', { headers: { 'Retry-After': String(Math.max(1, retry)) } }),
  notAllowed: (...methods) => new HttpError(405, 'Method not allowed', { headers: { Allow: methods.join(', ') } }),
};

/** A database error: 'dup', 'fk' and 'null' answer 409 (SQLSTATE 23000); anything else a generic 500 (AppHub Kernel). */
class DbError extends Error {
  constructor(kind, message) {
    super(message);
    this.kind = kind;
  }
}

/** Marks a JS number that MySQL DOUBLE returns as a PHP float: JSON_PRESERVE_ZERO_FRACTION prints 1024.0, not 1024. */
class Dbl {
  constructor(n) {
    this.n = n;
  }
}

function jsonStr(v) {
  if (v === null || v === undefined) return 'null';
  if (v instanceof Dbl) {
    if (!Number.isFinite(v.n)) return 'null';
    const s = JSON.stringify(v.n);
    return /[.eE]/.test(s) ? s : s + '.0';
  }
  if (Array.isArray(v)) return '[' + v.map(jsonStr).join(',') + ']';
  if (typeof v === 'object') {
    return '{' + Object.entries(v).filter(([, x]) => x !== undefined).map(([k, x]) => JSON.stringify(k) + ':' + jsonStr(x)).join(',') + '}';
  }
  return JSON.stringify(v);
}

// ---------------------------------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------------------------------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const b64u = (buf) => Buffer.from(buf).toString('base64url');
const isStr = (v) => typeof v === 'string';
const hasOwn = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
const pad = (n, w = 2) => String(n).padStart(w, '0');

/** DATETIME(3) as MySQL returns it with a native prepared statement: 'YYYY-MM-DD HH:MM:SS.mmm' (UTC). */
function fmtDatetime(ms, fsp) {
  const d = new Date(ms);
  const base = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
  return fsp > 0 ? `${base}.${pad(d.getUTCMilliseconds(), 3).slice(0, fsp)}` : base;
}

function parseQuery(search) {
  const q = Object.create(null);
  for (const part of search.replace(/^\?/, '').split('&')) {
    if (part === '') continue;
    const eq = part.indexOf('=');
    const dec = (s) => {
      try {
        return decodeURIComponent(s.replace(/\+/g, ' '));
      } catch {
        return s;
      }
    };
    let key = dec(eq < 0 ? part : part.slice(0, eq));
    const val = eq < 0 ? '' : dec(part.slice(eq + 1));
    const br = key.indexOf('[');
    if (br > 0) {
      key = key.slice(0, br); // PHP: a[]=1 or a[b]=1 makes an array: never a plain string
      q[key] = Array.isArray(q[key]) ? [...q[key], val] : [val];
    } else {
      q[key] = val;
    }
  }
  return q;
}

// ---------------------------------------------------------------------------------------------------------------------
// schema.sql reader: columns, keys, foreign keys and CHECK constraints (one definition per line, see the file's header)
// ---------------------------------------------------------------------------------------------------------------------
function columnKind(type) {
  const t = type.toUpperCase().replace(/\s+/g, ' ').trim();
  let m;
  if ((m = /^(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT)(?:\(\d+\))?( UNSIGNED)?$/.exec(t))) {
    const unsigned = !!m[2];
    const bits = { TINYINT: 8, SMALLINT: 16, MEDIUMINT: 24, INT: 32, INTEGER: 32, BIGINT: 64 }[m[1]];
    const max = unsigned ? 2 ** bits : 2 ** (bits - 1);
    return { kind: 'int', min: unsigned ? 0 : -max, max: unsigned ? max - 1 : max - 1 };
  }
  if ((m = /^DECIMAL\((\d+),\s*(\d+)\)$/.exec(t))) return { kind: 'decimal', precision: Number(m[1]), scale: Number(m[2]) };
  if (t === 'DOUBLE' || t === 'FLOAT') return { kind: 'double' };
  if ((m = /^(CHAR|VARCHAR)\((\d+)\)$/.exec(t))) return { kind: 'string', len: Number(m[2]) };
  if (t === 'TEXT') return { kind: 'text', len: 65535 };
  if ((m = /^(DATETIME|TIMESTAMP)(?:\((\d)\))?$/.exec(t))) return { kind: 'datetime', fsp: m[2] ? Number(m[2]) : 0 };
  throw new Error(`schema.sql: the fake does not know the column type "${type}"`);
}

function parseSchema(text) {
  const tables = {};
  let cur = null;
  let lineNo = 0;
  for (const raw of text.split(/\r?\n/)) {
    lineNo++;
    const line = raw.replace(/--.*$/, '').trim();
    if (!line) continue;
    const create = /^CREATE TABLE(?: IF NOT EXISTS)?\s+`?(\w+)`?\s*\($/i.exec(line);
    if (create) {
      cur = { name: create[1], columns: [], pk: [], uniques: [], fks: [], checks: [] };
      continue;
    }
    if (!cur) throw new Error(`schema.sql line ${lineNo}: a statement outside CREATE TABLE is not understood by the fake: ${line}`);
    if (line.startsWith(')')) {
      if (!cur.pk.length) throw new Error(`schema.sql: table ${cur.name} has no primary key`);
      tables[cur.name] = cur;
      cur = null;
      continue;
    }
    const def = line.replace(/,$/, '');
    const cols = (s) => s.split(',').map((c) => c.trim().replace(/`/g, ''));
    let m;
    if ((m = /^PRIMARY KEY\s*\(([^)]+)\)$/i.exec(def))) cur.pk = cols(m[1]);
    else if ((m = /^UNIQUE(?: KEY| INDEX)?\s+`?\w+`?\s*\(([^)]+)\)$/i.exec(def))) cur.uniques.push(cols(m[1]));
    else if (/^(KEY|INDEX)\s+/i.test(def)) continue;
    else if ((m = /^CONSTRAINT\s+`?\w+`?\s+FOREIGN KEY\s*\(([^)]+)\)\s+REFERENCES\s+`?(\w+)`?\s*\(([^)]+)\)/i.exec(def))) {
      cur.fks.push({ cols: cols(m[1]), refTable: m[2], refCols: cols(m[3]) });
    } else if ((m = /^CONSTRAINT\s+`?(\w+)`?\s+CHECK\s*\((.*)\)$/i.exec(def))) {
      cur.checks.push({ name: m[1], source: m[2], ast: parseExpr(m[2]) });
    } else if ((m = /^`?(\w+)`?\s+(.+)$/.exec(def))) {
      const rest = m[2];
      const t = /^(TINYINT(?:\(\d+\))?(?: UNSIGNED)?|SMALLINT(?: UNSIGNED)?|MEDIUMINT(?: UNSIGNED)?|BIGINT(?: UNSIGNED)?|INT(?: UNSIGNED)?|INTEGER(?: UNSIGNED)?|DECIMAL\(\d+,\s*\d+\)|DOUBLE|FLOAT|CHAR\(\d+\)|VARCHAR\(\d+\)|TEXT|DATETIME(?:\(\d\))?|TIMESTAMP(?:\(\d\))?)(?=\s|$)(.*)$/i.exec(rest);
      if (!t) throw new Error(`schema.sql line ${lineNo}: not understood by the fake: ${def}`);
      const mods = t[2];
      const col = { name: m[1], type: t[1], ...columnKind(t[1]) };
      col.notNull = /\bNOT NULL\b/i.test(mods);
      col.autoInc = /\bAUTO_INCREMENT\b/i.test(mods);
      col.onUpdateNow = /\bON UPDATE CURRENT_TIMESTAMP/i.test(mods);
      const d = /\bDEFAULT\s+('(?:[^']*)'|CURRENT_TIMESTAMP(?:\(\d\))?|NULL|-?\d+(?:\.\d+)?)/i.exec(mods);
      if (d) {
        if (/^CURRENT_TIMESTAMP/i.test(d[1])) col.default = { now: true };
        else if (/^NULL$/i.test(d[1])) col.default = { value: null };
        else if (d[1].startsWith("'")) col.default = { value: d[1].slice(1, -1) };
        else col.default = { value: d[1] };
      }
      if (/\bPRIMARY KEY\b/i.test(mods)) cur.pk = [col.name];
      cur.columns.push(col);
    } else {
      throw new Error(`schema.sql line ${lineNo}: not understood by the fake: ${def}`);
    }
  }
  if (cur) throw new Error(`schema.sql: table ${cur.name} is not closed`);
  for (const t of Object.values(tables)) {
    for (const k of [t.pk, ...t.uniques, ...t.fks.map((f) => f.cols)]) {
      for (const c of k) if (!t.columns.some((x) => x.name === c)) throw new Error(`schema.sql: ${t.name} names unknown column ${c}`);
    }
    for (const f of t.fks) if (!tables[f.refTable]) throw new Error(`schema.sql: ${t.name} references unknown table ${f.refTable}`);
  }
  return tables;
}

// A tiny SQL expression evaluator, enough for CHECK constraints: comparisons, IN, IS [NOT] NULL, BETWEEN, AND, OR, NOT,
// + - * /, CHAR_LENGTH, LENGTH, TRIM, LOWER, UPPER, JSON_VALID. Three-valued logic: a CHECK fails only on a definite false.
function tokenizeExpr(src) {
  const toks = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) {
      i++;
    } else if (c === "'") {
      let j = i + 1;
      let s = '';
      while (j < src.length) {
        if (src[j] === "'") {
          if (src[j + 1] === "'") {
            s += "'";
            j += 2;
            continue;
          }
          break;
        }
        s += src[j++];
      }
      toks.push({ t: 'str', v: s });
      i = j + 1;
    } else if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(src[i + 1] || ''))) {
      const m = /^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/.exec(src.slice(i));
      toks.push({ t: 'num', v: Number(m[0]) });
      i += m[0].length;
    } else if (/[A-Za-z_`]/.test(c)) {
      const m = /^`?([A-Za-z_][A-Za-z0-9_]*)`?/.exec(src.slice(i));
      toks.push({ t: 'id', v: m[1] });
      i += m[0].length;
    } else if (['<=', '>=', '<>', '!='].includes(src.slice(i, i + 2))) {
      toks.push({ t: 'op', v: src.slice(i, i + 2) });
      i += 2;
    } else if ('=<>(),+-*/'.includes(c)) {
      toks.push({ t: 'op', v: c });
      i++;
    } else {
      throw new Error(`schema.sql: the fake cannot read this CHECK expression near "${src.slice(i, i + 20)}"`);
    }
  }
  return toks;
}

function parseExpr(src) {
  const toks = tokenizeExpr(src);
  let p = 0;
  const peek = () => toks[p];
  const kw = (w) => peek() && peek().t === 'id' && peek().v.toUpperCase() === w;
  const op = (o) => peek() && peek().t === 'op' && peek().v === o;
  const take = () => toks[p++];
  const expect = (o) => {
    if (!op(o)) throw new Error(`schema.sql: CHECK expression "${src}": expected ${o}`);
    p++;
  };
  function parseOr() {
    let left = parseAnd();
    while (kw('OR')) {
      take();
      left = { k: 'or', a: left, b: parseAnd() };
    }
    return left;
  }
  function parseAnd() {
    let left = parseNot();
    while (kw('AND')) {
      take();
      left = { k: 'and', a: left, b: parseNot() };
    }
    return left;
  }
  function parseNot() {
    if (kw('NOT')) {
      take();
      return { k: 'not', a: parseNot() };
    }
    return parseCmp();
  }
  function parseCmp() {
    const left = parseAdd();
    if (kw('IS')) {
      take();
      let neg = false;
      if (kw('NOT')) {
        take();
        neg = true;
      }
      if (!kw('NULL')) throw new Error(`schema.sql: CHECK expression "${src}": expected NULL after IS`);
      take();
      return { k: 'isnull', a: left, neg };
    }
    let neg = false;
    if (kw('NOT') && toks[p + 1] && ['IN', 'BETWEEN'].includes(String(toks[p + 1].v).toUpperCase())) {
      take();
      neg = true;
    }
    if (kw('IN')) {
      take();
      expect('(');
      const list = [parseAdd()];
      while (op(',')) {
        take();
        list.push(parseAdd());
      }
      expect(')');
      return { k: 'in', a: left, list, neg };
    }
    if (kw('BETWEEN')) {
      take();
      const lo = parseAdd();
      if (!kw('AND')) throw new Error(`schema.sql: CHECK expression "${src}": expected AND in BETWEEN`);
      take();
      return { k: 'between', a: left, lo, hi: parseAdd(), neg };
    }
    if (peek() && peek().t === 'op' && ['=', '<>', '!=', '<', '<=', '>', '>='].includes(peek().v)) {
      return { k: 'cmp', o: take().v, a: left, b: parseAdd() };
    }
    return left;
  }
  function parseAdd() {
    let left = parseMul();
    while (op('+') || op('-')) left = { k: 'arith', o: take().v, a: left, b: parseMul() };
    return left;
  }
  function parseMul() {
    let left = parsePrimary();
    while (op('*') || op('/')) left = { k: 'arith', o: take().v, a: left, b: parsePrimary() };
    return left;
  }
  function parsePrimary() {
    const t = take();
    if (!t) throw new Error(`schema.sql: CHECK expression "${src}" ends too early`);
    if (t.t === 'num' || t.t === 'str') return { k: 'lit', v: t.v };
    if (t.t === 'op' && t.v === '(') {
      const e = parseOr();
      expect(')');
      return e;
    }
    if (t.t === 'op' && t.v === '-') return { k: 'arith', o: '-', a: { k: 'lit', v: 0 }, b: parsePrimary() };
    if (t.t === 'id') {
      const u = t.v.toUpperCase();
      if (u === 'NULL') return { k: 'lit', v: null };
      if (u === 'TRUE') return { k: 'lit', v: true };
      if (u === 'FALSE') return { k: 'lit', v: false };
      if (op('(')) {
        take();
        const args = [];
        if (!op(')')) {
          args.push(parseOr());
          while (op(',')) {
            take();
            args.push(parseOr());
          }
        }
        expect(')');
        if (!['CHAR_LENGTH', 'LENGTH', 'TRIM', 'LOWER', 'UPPER', 'JSON_VALID'].includes(u)) {
          throw new Error(`schema.sql: the fake does not know the function ${u} used in a CHECK`);
        }
        return { k: 'fn', name: u, args };
      }
      return { k: 'col', name: t.v };
    }
    throw new Error(`schema.sql: CHECK expression "${src}": unexpected ${t.v}`);
  }
  const ast = parseOr();
  if (p < toks.length) throw new Error(`schema.sql: CHECK expression "${src}": unexpected ${toks[p].v}`);
  return ast;
}

function sqlNumber(s) {
  const m = /^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/.exec(String(s));
  return m ? Number(m[0]) : 0;
}

function sqlCompare(a, b) {
  if (a === null || b === null || a === undefined || b === undefined) return null;
  if (typeof a === 'boolean') a = a ? 1 : 0;
  if (typeof b === 'boolean') b = b ? 1 : 0;
  if (typeof a === 'string' && typeof b === 'string') {
    const x = a.toLowerCase().replace(/ +$/, '');
    const y = b.toLowerCase().replace(/ +$/, '');
    return x < y ? -1 : x > y ? 1 : 0;
  }
  const x = typeof a === 'number' ? a : sqlNumber(a);
  const y = typeof b === 'number' ? b : sqlNumber(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

function evalExpr(n, row) {
  switch (n.k) {
    case 'lit':
      return n.v;
    case 'col': {
      if (!hasOwn(row, n.name)) throw new Error(`CHECK names unknown column ${n.name}`);
      return row[n.name];
    }
    case 'not': {
      const v = evalExpr(n.a, row);
      return v === null ? null : !v;
    }
    case 'and': {
      const a = evalExpr(n.a, row);
      const b = evalExpr(n.b, row);
      if (a === false || a === 0 || b === false || b === 0) return false;
      return a === null || b === null ? null : true;
    }
    case 'or': {
      const a = evalExpr(n.a, row);
      const b = evalExpr(n.b, row);
      if ((a !== null && a !== false && a !== 0) || (b !== null && b !== false && b !== 0)) return true;
      return a === null || b === null ? null : false;
    }
    case 'isnull': {
      const v = evalExpr(n.a, row);
      return n.neg ? v !== null : v === null;
    }
    case 'cmp': {
      const c = sqlCompare(evalExpr(n.a, row), evalExpr(n.b, row));
      if (c === null) return null;
      return { '=': c === 0, '<>': c !== 0, '!=': c !== 0, '<': c < 0, '<=': c <= 0, '>': c > 0, '>=': c >= 0 }[n.o];
    }
    case 'in': {
      const v = evalExpr(n.a, row);
      if (v === null) return null;
      const found = n.list.some((e) => sqlCompare(v, evalExpr(e, row)) === 0);
      return n.neg ? !found : found;
    }
    case 'between': {
      const v = evalExpr(n.a, row);
      const lo = sqlCompare(v, evalExpr(n.lo, row));
      const hi = sqlCompare(v, evalExpr(n.hi, row));
      if (lo === null || hi === null) return null;
      const inside = lo >= 0 && hi <= 0;
      return n.neg ? !inside : inside;
    }
    case 'arith': {
      const a = evalExpr(n.a, row);
      const b = evalExpr(n.b, row);
      if (a === null || b === null) return null;
      const x = typeof a === 'number' ? a : sqlNumber(a);
      const y = typeof b === 'number' ? b : sqlNumber(b);
      return n.o === '+' ? x + y : n.o === '-' ? x - y : n.o === '*' ? x * y : y === 0 ? null : x / y;
    }
    case 'fn': {
      const a = evalExpr(n.args[0], row);
      if (a === null) return null;
      const s = String(a);
      if (n.name === 'CHAR_LENGTH') return [...s].length;
      if (n.name === 'LENGTH') return Buffer.byteLength(s);
      if (n.name === 'TRIM') return s.replace(/^ +| +$/g, '');
      if (n.name === 'LOWER') return s.toLowerCase();
      if (n.name === 'UPPER') return s.toUpperCase();
      try {
        JSON.parse(s);
        return true;
      } catch {
        return false;
      }
    }
    default:
      throw new Error(`unknown expression node ${n.k}`);
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Column value coercion, as MySQL strict mode would do it for a value bound as a string by PDO
// ---------------------------------------------------------------------------------------------------------------------
function toNumeric(v) {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string' && /^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?\s*$/.test(v)) return Number(v.trim());
  return null;
}

function toDecimal(v, precision, scale) {
  const s = typeof v === 'number' ? (Number.isFinite(v) ? String(v) : null) : String(v).trim();
  if (s === null) return null;
  const m = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(s);
  if (!m || (m[2] === '' && !m[3])) return null;
  const neg = m[1] === '-';
  const ip = m[2] || '';
  const fp = m[3] || '';
  let digits = ip + fp;
  let point = ip.length + (m[4] ? parseInt(m[4], 10) : 0);
  if (point <= 0) {
    digits = '0'.repeat(1 - point) + digits;
    point = 1;
  }
  if (point > digits.length) digits += '0'.repeat(point - digits.length);
  const intPart = digits.slice(0, point).replace(/^0+(?=\d)/, '');
  let frac = digits.slice(point);
  let roundUp = false;
  if (frac.length > scale) {
    roundUp = frac[scale] >= '5';
    frac = frac.slice(0, scale);
  } else {
    frac = frac.padEnd(scale, '0');
  }
  let all = BigInt(intPart + frac);
  if (roundUp) all += 1n;
  const str = all.toString().padStart(scale + 1, '0');
  const ipart = str.slice(0, str.length - scale);
  const fpart = str.slice(str.length - scale);
  if (ipart.replace(/^0+/, '').length > precision - scale) return 'overflow';
  const zero = /^0*$/.test(ipart + fpart);
  return (neg && !zero ? '-' : '') + ipart + (scale > 0 ? '.' + fpart : '');
}

/** @returns the value as stored. Throws DbError. `value` is a string, a number or null (booleans are already 0/1). */
function coerce(col, value) {
  if (value === null || value === undefined) {
    if (col.notNull) throw new DbError('null', `Column '${col.name}' cannot be null`); // 1048, SQLSTATE 23000 -> 409
    return null;
  }
  switch (col.kind) {
    case 'int': {
      const n = toNumeric(value);
      if (n === null) throw new DbError('generic', `Incorrect integer value for column '${col.name}'`); // 1366 -> 500
      const r = Math.sign(n) * Math.round(Math.abs(n)) + 0;
      if (r < col.min || r > col.max) throw new DbError('generic', `Out of range value for column '${col.name}'`); // 1264 -> 500
      return r;
    }
    case 'double': {
      const n = toNumeric(value);
      if (n === null) throw new DbError('generic', `Incorrect double value for column '${col.name}'`);
      return n + 0;
    }
    case 'decimal': {
      const d = toDecimal(value, col.precision, col.scale);
      if (d === null) throw new DbError('generic', `Incorrect decimal value for column '${col.name}'`);
      if (d === 'overflow') throw new DbError('generic', `Out of range value for column '${col.name}'`);
      return d;
    }
    case 'string': {
      const s = String(value);
      if ([...s].length > col.len) throw new DbError('generic', `Data too long for column '${col.name}'`); // 1406 -> 500
      return s;
    }
    case 'text': {
      const s = String(value);
      if (Buffer.byteLength(s) > col.len) throw new DbError('generic', `Data too long for column '${col.name}'`);
      return s;
    }
    case 'datetime': {
      // Only the fake's own triggers write a DATETIME (a client cannot: no config lists one as writable). A number is epoch ms.
      if (typeof value !== 'number' || !Number.isFinite(value)) throw new DbError('generic', `Column '${col.name}' (${col.type}) cannot be written`);
      return col.fsp > 0 ? Math.round(value) : Math.floor(value / 1000) * 1000;
    }
    default:
      throw new DbError('generic', `Column '${col.name}' (${col.type}) cannot be written`);
  }
}

/** The value as a CHECK constraint sees it (decimals as numbers). */
function checkRow(schemaTable, row) {
  const view = {};
  for (const c of schemaTable.columns) view[c.name] = c.kind === 'decimal' && row[c.name] !== null ? Number(row[c.name]) : row[c.name];
  for (const ck of schemaTable.checks) {
    const r = evalExpr(ck.ast, view);
    if (r === false || r === 0) throw new DbError('generic', `Check constraint '${ck.name}' is violated.`); // 3819, HY000 -> 500
  }
}

const keyOfValue = (col, v) => {
  if (v === null || v === undefined) return null;
  if (col.kind === 'string' || col.kind === 'text') return String(v).toLowerCase().replace(/ +$/, ''); // utf8mb4_unicode_ci
  return Number(v);
};
const rowKey = (schemaTable, cols, row) => cols.map((c) => keyOfValue(schemaTable.columns.find((x) => x.name === c), row[c])).join('\u0001');

// ---------------------------------------------------------------------------------------------------------------------
// The world: config, schema, apps, clock, faults, rate limits
// ---------------------------------------------------------------------------------------------------------------------
function normalizeAuth(a, o) {
  const auth = {
    table: a.table || 'users',
    id: a.id || 'id',
    email: a.email || 'email',
    password: a.password || 'password',
    role: a.role ?? null,
    status: a.status ?? null,
    active_value: String(a.active_value ?? 'active'),
    default_role: String(a.default_role ?? 'user'),
    allow_register: a.allow_register === true,
    register_fields: [...(a.register_fields || [])],
    password_min: Math.max(8, parseInt(a.password_min ?? 10, 10)),
    reset_url: a.reset_url || null,
    verify_url: a.verify_url || null,
    verified_column: a.verified_column || null,
    require_verified: a.require_verified === true,
  };
  if (o.mail) {
    auth.reset_url = auth.reset_url || 'http://localhost/reset?token={token}';
    auth.verify_url = auth.verify_url || 'http://localhost/verify?token={token}';
    auth.verified_column = auth.verified_column || 'email_verified_at';
  }
  if (o.requireVerified) auth.require_verified = true;
  if (auth.require_verified && !auth.verify_url) throw new Error('auth.require_verified needs auth.verify_url (use --mail)');
  return auth;
}

/**
 * What triggers.sql assigns, read from the file: { table: { column: 'null' | 'value' } } for every `SET NEW.<col> = ...;` of a
 * trigger on that table ('null' only when EVERY assignment to the column is NULL: a transient control column such as `code` or
 * `base_revision`, which a client may still send), plus the trigger list. triggers.sql must keep one assignment per SET statement
 * (its header says so) for this to be exact. Returns null when the file is missing.
 */
function readTriggerFile(file) {
  if (!file || !fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8');
  const triggers = [];
  const assigned = {};
  const re = /CREATE TRIGGER\s+(\w+)\s+(BEFORE|AFTER)\s+(INSERT|UPDATE|DELETE)\s+ON\s+(\w+)/gi;
  const starts = [];
  let m;
  while ((m = re.exec(text))) starts.push({ name: m[1], when: m[2].toUpperCase(), event: m[3].toUpperCase(), table: m[4], at: m.index });
  starts.forEach((s, i) => {
    triggers.push({ name: s.name, when: s.when, event: s.event, table: s.table });
    const body = text.slice(s.at, i + 1 < starts.length ? starts[i + 1].at : text.length);
    const set = /\bSET\s+NEW\.(\w+)\s*=\s*([^;]+);/gi;
    let a;
    while ((a = set.exec(body))) {
      const kind = /^NULL$/i.test(a[2].trim()) ? 'null' : 'value';
      const t = (assigned[s.table] ??= {});
      t[a[1]] = t[a[1]] === 'value' || kind === 'value' ? 'value' : 'null';
    }
  });
  return { triggers, assigned };
}

/** Drift between triggers.sql and triggers.mjs: a trigger that exists on one side only. */
function checkTriggerDrift(info, warnings) {
  if (!info) {
    warnings.push('triggers.sql was not found: the fake cannot compare its trigger emulation with the SQL');
    return;
  }
  const key = (t) => `${t.name} ${t.when} ${t.event} ${t.table}`;
  const sql = new Set(info.triggers.map(key));
  const js = new Set(TRIGGERS.map(key));
  for (const k of sql) if (!js.has(k)) warnings.push(`triggers.sql defines "${k}" which the fake does not emulate (triggers.mjs)`);
  for (const k of js) if (!sql.has(k)) warnings.push(`the fake emulates "${k}" which triggers.sql does not define`);
}

function buildTableMeta(cfg, schema, auth, warnings, triggerInfo) {
  const meta = {};
  for (const [name, t] of Object.entries(cfg.tables || {})) {
    const st = schema[name];
    if (!st) throw new Error(`config table ${name} has no CREATE TABLE in schema.sql`);
    const colNames = st.columns.map((c) => c.name);
    const keep = (list, what) => {
      const out = [];
      for (const c of list || []) {
        if (colNames.includes(c)) out.push(c);
        else warnings.push(`config ${name}.${what} names column "${c}" that schema.sql does not have`);
      }
      return [...new Set(out)];
    };
    const pk = t.pk || 'id';
    let owner = t.owner || null;
    const isAuth = name === auth.table;
    if (isAuth && owner === null) owner = auth.id;
    if (owner && !colNames.includes(owner)) throw new Error(`config ${name}.owner "${owner}" is not a column of ${name}`);
    if (!colNames.includes(pk)) throw new Error(`config ${name}.pk "${pk}" is not a column of ${name}`);
    const m = {
      name,
      pk,
      owner,
      schema: st,
      hidden: keep(t.hidden, 'hidden'),
      writable: keep(t.writable, 'writable'),
      writableByRole: {},
      filterable: keep(t.filterable, 'filterable'),
      searchable: keep(t.searchable, 'searchable'),
      sortable: keep(t.sortable || [pk], 'sortable'),
      fileColumns: [],
      members: keep(t.members, 'members'),
      sharedVia: null,
    };
    if (t.shared_via) {
      // AppHub App::normalize / CrudController::assertShareable: the grant table must exist, differ from this table, and have the columns.
      const s = t.shared_via;
      const grant = schema[s.table];
      if (!grant) throw new Error(`config ${name}.shared_via.table "${s.table}" has no CREATE TABLE in schema.sql`);
      if (s.table.toLowerCase() === name.toLowerCase()) throw new Error(`config ${name}.shared_via.table must differ from the table itself (AppHub refuses a self-grant)`);
      const on = s.on || pk;
      if (!colNames.includes(on)) throw new Error(`config ${name}.shared_via.on "${on}" is not a column of ${name}`);
      for (const part of ['match', 'member', 'level']) {
        if (!grant.columns.some((c) => c.name === s[part])) throw new Error(`config ${name}.shared_via.${part} "${s[part]}" is not a column of ${s.table}`);
      }
      if (!Array.isArray(s.read) || s.read.length === 0) throw new Error(`config ${name}.shared_via.read must name at least one level`);
      m.sharedVia = { table: s.table, on, match: s.match, member: s.member, level: s.level, read: [...s.read], update: [...(s.update || [])] };
    }
    // AppHub also lets the first sign-in table's members columns act; they must be real columns.
    for (const c of t.members || []) if (!colNames.includes(c)) throw new Error(`config ${name}.members "${c}" is not a column of ${name}`);
    for (const [role, cols] of Object.entries(t.writable_by_role || {})) m.writableByRole[role] = keep(cols, `writable_by_role.${role}`);
    if (!m.sortable.includes(pk)) m.sortable.push(pk);
    if (isAuth) {
      const strip = (l) => l.filter((c) => c !== auth.password);
      m.hidden = [...new Set([...m.hidden, auth.password])];
      m.writable = strip(m.writable);
      m.filterable = strip(m.filterable);
      m.searchable = strip(m.searchable);
      m.sortable = strip(m.sortable);
      for (const r of Object.keys(m.writableByRole)) m.writableByRole[r] = strip(m.writableByRole[r]);
    }
    for (const l of ['filterable', 'searchable', 'sortable']) m[l] = m[l].filter((c) => !m.hidden.includes(c));
    // Reverse drift: a column the client is expected to write but the config forgot, or the opposite. A column a trigger sets to a
    // VALUE is server-managed (never writable); one a trigger only ever sets to NULL is a transient control column the client sends
    // (base_revision, email, code) and must stay writable. A table nobody may create in or update (list_members) has no writable list.
    if (!isAuth) {
      const set = triggerInfo?.assigned?.[name] ?? {};
      const clientWrites = Object.values(cfg.roles || {}).some((rules) => ['create', 'update'].some((a) => rules[name]?.[a]));
      for (const c of st.columns) {
        const serverManaged = c.name === m.owner || c.onUpdateNow || c.autoInc || set[c.name] === 'value';
        if (clientWrites && !serverManaged && !m.writable.includes(c.name)) warnings.push(`schema.sql column ${name}.${c.name} is not in the config's writable list (forgotten?)`);
        if (serverManaged && m.writable.includes(c.name)) warnings.push(`config ${name}.writable lists the server-managed column ${c.name}`);
        if (!clientWrites && m.writable.includes(c.name)) warnings.push(`config ${name}.writable lists ${c.name} but no role may create or update ${name}`);
      }
      for (const c of Object.keys(set)) if (!st.columns.some((x) => x.name === c)) warnings.push(`triggers.sql sets NEW.${c} on ${name} but schema.sql has no such column`);
    }
    meta[name] = m;
  }
  for (const [role, rules] of Object.entries(cfg.roles || {})) {
    for (const [table, actions] of Object.entries(rules)) {
      if (table !== '*' && !meta[table]) throw new Error(`config roles.${role} names unknown table ${table}`);
      for (const [action, scope] of Object.entries(actions)) {
        if (!ACTIONS.includes(action) || !['all', 'own', 'shared'].includes(scope)) throw new Error(`config roles.${role}.${table}: ${action} => ${scope} is not valid`);
        if (scope === 'shared') {
          // The same refusals as App::normalize.
          const t = meta[table];
          if (!['list', 'read', 'update'].includes(action)) throw new Error(`config roles.${role}.${table}: 'shared' is only valid for list, read and update, not '${action}'`);
          if (!t) throw new Error(`config roles.${role}.${table}: 'shared' needs a table of its own`);
          if (table === auth.table) throw new Error(`config roles.${role}.${table}: 'shared' cannot be used on the users table`);
          if (action === 'update' && (!t.sharedVia || t.sharedVia.update.length === 0)) throw new Error(`config roles.${role}.${table}: 'update' => 'shared' needs shared_via with a non-empty update list`);
          if (!t.sharedVia && t.members.length === 0) throw new Error(`config roles.${role}.${table}: 'shared' needs shared_via or members`);
        }
        if (action === 'delete') throw new Error('the fake does not implement delete (no role gets delete: clients tombstone with deleted_at_ms)');
      }
    }
  }
  return meta;
}

class Limiter {
  constructor(world) {
    this.world = world;
    this.map = new Map();
  }

  update(key, window, increment) {
    const now = Math.floor(this.world.now() / 1000);
    let d = this.map.get(key);
    if (!d || now - d.start >= window) d = { start: now, count: 0 };
    if (increment) d.count++;
    this.map.set(key, d);
    return [d.count, Math.max(1, d.start + window - now)];
  }

  hit(key, max, window) {
    const [count, resetIn] = this.update(key, window, true);
    if (count > max) throw E.tooMany(resetIn);
    return max - count;
  }

  peek(key, window) {
    return this.update(key, window, false);
  }

  increment(key, window) {
    return this.update(key, window, true)[0];
  }

  reset(key) {
    this.map.delete(key);
  }
}

/** The operator's seed: a random code pepper in app_secrets (unless --no-pepper). Real value comes from `openssl rand`, see seed.sql. */
function seedPepper(world, app, force = false) {
  const st = world.schema.app_secrets;
  if (!st || (world.opts.noPepper && !force)) return;
  const row = { name: 'code_pepper', value: crypto.randomBytes(32).toString('hex'), updated_at: tsNow(world, 3) };
  app.tables.app_secrets.rows.set(rowKey(st, st.pk, row), row);
}

function newAppState(world, name, secret) {
  const tables = {};
  for (const t of Object.values(world.schema)) tables[t.name] = { rows: new Map(), autoInc: 1 };
  const app = { name, secret: secret || crypto.randomBytes(32).toString('hex'), tables, refresh: [], userTokens: [], outbox: [] };
  seedPepper(world, app);
  return app;
}

function createWorld(o) {
  const cfg = JSON.parse(fs.readFileSync(o.config, 'utf8'));
  const schema = parseSchema(fs.readFileSync(o.schema, 'utf8'));
  const warnings = [];
  const auth = normalizeAuth(cfg.auth || {}, o);
  const triggerInfo = readTriggerFile(o.triggers);
  checkTriggerDrift(triggerInfo, warnings);
  const meta = buildTableMeta(cfg, schema, auth, warnings, triggerInfo);
  if (!meta[auth.table]) throw new Error(`the auth table ${auth.table} is not in the config`);
  for (const name of o.apps) {
    if (!APP_NAME_RE.test(name) || RESERVED_APP_NAMES.includes(name)) throw new Error(`"${name}" is not a valid app name`);
  }
  const world = {
    opts: o,
    cfg,
    schema,
    meta,
    auth,
    roles: cfg.roles || {},
    warnings,
    accessTtl: o.accessTtl ?? cfg.jwt?.access_ttl ?? 900,
    refreshTtl: o.refreshTtl ?? cfg.jwt?.refresh_ttl ?? 1209600,
    clockOffsetMs: 0,
    faults: [],
    accessLog: [], // "METHOD path -> status ms" lines (never a body, a query or a token), for GET /__log and the log scan
    apps: new Map(),
    limiter: null,
    saveTimer: null,
    now() {
      return Date.now() + this.clockOffsetMs;
    },
    markDirty() {
      if (!o.data || this.saveTimer) return;
      this.saveTimer = setTimeout(() => {
        this.saveTimer = null;
        this.save();
      }, 20);
      this.saveTimer.unref?.();
    },
    save() {
      if (!o.data) return;
      const snap = { version: 1, apps: {} };
      for (const [name, app] of this.apps) {
        snap.apps[name] = {
          secret: app.secret,
          tables: Object.fromEntries(Object.entries(app.tables).map(([t, s]) => [t, { autoInc: s.autoInc, rows: [...s.rows.values()] }])),
          refresh: app.refresh,
          userTokens: app.userTokens,
          outbox: app.outbox,
        };
      }
      fs.writeFileSync(o.data, JSON.stringify(snap));
    },
    reset() {
      this.apps = new Map(o.apps.map((n) => [n, newAppState(this, n)]));
      this.limiter = new Limiter(this);
      this.faults = [];
      this.clockOffsetMs = 0;
      this.accessLog = [];
      this.markDirty();
    },
    load() {
      this.limiter = new Limiter(this);
      this.apps = new Map(o.apps.map((n) => [n, newAppState(this, n)]));
      if (!o.data || !fs.existsSync(o.data)) return false;
      const snap = JSON.parse(fs.readFileSync(o.data, 'utf8'));
      for (const [name, app] of this.apps) {
        const s = snap.apps?.[name];
        if (!s) continue;
        app.secret = s.secret;
        for (const [t, st] of Object.entries(s.tables || {})) {
          if (!app.tables[t]) continue;
          app.tables[t].autoInc = st.autoInc;
          const sch = schema[t];
          app.tables[t].rows.clear(); // the saved table replaces the freshly seeded one (a pepper deleted before the save stays deleted)
          for (const row of st.rows) app.tables[t].rows.set(rowKey(sch, sch.pk, row), row);
        }
        app.refresh = s.refresh || [];
        app.userTokens = s.userTokens || [];
        app.outbox = s.outbox || [];
      }
      return true;
    },
  };
  world.load();
  return world;
}

// ---------------------------------------------------------------------------------------------------------------------
// Request object
// ---------------------------------------------------------------------------------------------------------------------
function buildRequest(world, nodeReq, bodyBuf, tooLarge, id) {
  const url = new URL(nodeReq.url, 'http://localhost');
  const segments = [];
  for (const s of url.pathname.split('/')) {
    if (s === '') continue;
    try {
      segments.push(decodeURIComponent(s));
    } catch {
      segments.push(s);
    }
  }
  const headers = nodeReq.headers;
  const r = {
    id,
    method: (nodeReq.method || 'GET').toUpperCase(),
    pathname: url.pathname,
    segments,
    query: parseQuery(url.search),
    ip: String(headers['x-fake-client-ip'] || nodeReq.socket.remoteAddress || '0.0.0.0').replace(/^::ffff:/, ''),
    header(name) {
      const v = headers[name.toLowerCase()];
      return v === undefined || v === '' ? null : Array.isArray(v) ? v.join(', ') : String(v);
    },
    contentType() {
      return (r.header('content-type') || '').trim().toLowerCase();
    },
    bearerToken() {
      const h = r.header('authorization');
      if (h === null) return null;
      const m = /^Bearer\s+([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/.exec(h.trim());
      if (h.length > 4096 || !m) throw E.unauthorized('Malformed Authorization header');
      return m[1];
    },
    _json: null,
    json() {
      if (r._json !== null) return r._json;
      if (!r.contentType().startsWith('application/json')) throw new HttpError(415, 'Content-Type must be application/json');
      if (tooLarge) throw new HttpError(413, 'Request body too large');
      const raw = bodyBuf.toString('utf8');
      if (raw.trim() === '') throw E.badRequest('Request body is empty');
      let data;
      try {
        data = JSON.parse(raw);
      } catch {
        throw E.badRequest('Request body is not valid JSON');
      }
      const isObject = data !== null && typeof data === 'object';
      if (!isObject || (Array.isArray(data) && data.length > 0) || (!Array.isArray(data) && Object.keys(data).some((k) => /^(0|-?[1-9]\d*)$/.test(k)))) {
        throw E.badRequest('Request body must be a JSON object');
      }
      r._json = Array.isArray(data) ? {} : data; // PHP: [] is an empty array, which passes as an empty object
      return r._json;
    },
  };
  return r;
}

// ---------------------------------------------------------------------------------------------------------------------
// JWT (HS256), passwords, tokens
// ---------------------------------------------------------------------------------------------------------------------
const jwtEncode = (claims, secret) => {
  const head = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64u(JSON.stringify(claims));
  return `${head}.${body}.${crypto.createHmac('sha256', secret).update(`${head}.${body}`).digest('base64url')}`;
};

function jwtDecode(token, secret, nowS, leeway) {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [head, body, sig] = parts;
  const expected = crypto.createHmac('sha256', secret).update(`${head}.${body}`).digest('base64url');
  const a = Buffer.from(expected);
  const b = Buffer.from(sig);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const header = JSON.parse(Buffer.from(head, 'base64url').toString('utf8'));
    if (!header || header.alg !== 'HS256' || (hasOwn(header, 'typ') && header.typ !== 'JWT')) return null;
    const claims = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    if (!claims || typeof claims !== 'object' || !Number.isInteger(claims.exp)) return null;
    if (claims.exp < nowS - leeway) return null;
    if (hasOwn(claims, 'nbf') && (!Number.isInteger(claims.nbf) || claims.nbf > nowS + leeway)) return null;
    if (hasOwn(claims, 'iat') && (!Number.isInteger(claims.iat) || claims.iat > nowS + leeway)) return null;
    return claims;
  } catch {
    return null;
  }
}

function peekAudience(token) {
  try {
    const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    return claims && typeof claims.aud === 'string' ? claims.aud : null;
  } catch {
    return null;
  }
}

const hashPassword = (pw) => {
  const salt = crypto.randomBytes(16).toString('hex');
  return `scrypt1$${salt}$${crypto.scryptSync(pw, salt, 32, { N: 1024 }).toString('hex')}`; // weak on purpose: a fake
};
const verifyPassword = (pw, stored) => {
  const m = /^scrypt1\$([0-9a-f]{32})\$([0-9a-f]{64})$/.exec(stored || '');
  if (!m) return false;
  const h = crypto.scryptSync(pw, m[1], 32, { N: 1024 });
  return crypto.timingSafeEqual(h, Buffer.from(m[2], 'hex'));
};

function assertAcceptablePassword(password, min, email = '') {
  const length = [...password].length;
  const at = email.indexOf('@');
  const local = at >= 0 ? email.slice(0, at) : '';
  let problem = null;
  if (length < min) problem = `Password must be at least ${min} characters`;
  else if (length > 128) problem = 'Password must be at most 128 characters';
  else if (COMMON_PASSWORDS.includes(password.toLowerCase())) problem = 'Password is too common';
  else if (new Set([...password]).size < 4) problem = 'Password is too simple';
  else if (Buffer.byteLength(local) >= 4 && password.toLowerCase().includes(local.toLowerCase())) problem = 'Password must not contain your email name';
  if (problem) throw E.unprocessable(problem, { field: 'password' });
}

// FILTER_VALIDATE_EMAIL, approximately: dotted local part, a domain with at least one dot, no spaces.
const EMAIL_RE = /^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/;

// ---------------------------------------------------------------------------------------------------------------------
// Database layer over the in-memory tables
// ---------------------------------------------------------------------------------------------------------------------
function defaultValue(world, col) {
  if (!col.default) return undefined;
  if (col.default.now) return tsNow(world, col.fsp);
  if (col.default.value === null) return null;
  return coerce({ ...col, notNull: false }, col.default.value);
}

function insertRow(world, app, tableName, data) {
  const st = world.schema[tableName];
  const store = app.tables[tableName];
  const row = {};
  for (const col of st.columns) {
    if (hasOwn(data, col.name)) {
      row[col.name] = coerce(col, data[col.name]);
    } else if (col.autoInc) {
      row[col.name] = null; // assigned below
    } else {
      const d = defaultValue(world, col);
      if (d !== undefined) row[col.name] = d;
      else if (!col.notNull) row[col.name] = null;
      else throw new DbError('generic', `Field '${col.name}' doesn't have a default value`); // 1364, HY000 -> 500
    }
  }
  const auto = st.columns.find((c) => c.autoInc);
  if (auto && row[auto.name] === null) row[auto.name] = store.autoInc;
  checkRow(st, row);
  const pk = rowKey(st, st.pk, row);
  if (store.rows.has(pk)) throw new DbError('dup', `Duplicate entry for key 'PRIMARY'`);
  for (const u of st.uniques) {
    const k = rowKey(st, u, row);
    for (const other of store.rows.values()) if (rowKey(st, u, other) === k) throw new DbError('dup', `Duplicate entry for key '${u.join(',')}'`);
  }
  checkForeignKeys(world, app, st, row);
  if (auto && row[auto.name] >= store.autoInc) store.autoInc = row[auto.name] + 1;
  store.rows.set(pk, row);
  world.markDirty();
  return row;
}

function checkForeignKeys(world, app, st, row, onlyCols = null) {
  for (const fk of st.fks) {
    if (onlyCols && !fk.cols.some((c) => onlyCols.includes(c))) continue;
    if (fk.cols.some((c) => row[c] === null)) continue;
    const parent = world.schema[fk.refTable];
    const k = fk.cols.map((c, i) => keyOfValue(parent.columns.find((x) => x.name === fk.refCols[i]), row[c])).join('\u0001');
    let found = false;
    if (fk.refCols.length === parent.pk.length && fk.refCols.every((c, i) => c === parent.pk[i])) found = app.tables[fk.refTable].rows.has(k);
    else for (const p of app.tables[fk.refTable].rows.values()) if (rowKey(parent, fk.refCols, p) === k) found = true;
    if (!found) throw new DbError('fk', `Cannot add or update a child row: a foreign key constraint fails (${fk.cols.join(',')})`);
  }
}

/** UPDATE: ON UPDATE CURRENT_TIMESTAMP columns move only when a column value really changes (MySQL semantics). */
function updateRow(world, app, tableName, row, data) {
  const st = world.schema[tableName];
  const next = { ...row };
  const changedCols = [];
  for (const [name, value] of Object.entries(data)) {
    const col = st.columns.find((c) => c.name === name);
    const nv = coerce(col, value);
    if (nv !== row[name]) {
      next[name] = nv;
      changedCols.push(name);
    }
  }
  checkRow(st, next);
  for (const u of st.uniques) {
    if (!u.some((c) => changedCols.includes(c))) continue;
    const k = rowKey(st, u, next);
    for (const other of app.tables[tableName].rows.values()) if (other !== row && rowKey(st, u, other) === k) throw new DbError('dup', `Duplicate entry for key '${u.join(',')}'`);
  }
  checkForeignKeys(world, app, st, next, changedCols);
  if (changedCols.length > 0) {
    for (const col of st.columns) if (col.onUpdateNow && !changedCols.includes(col.name)) next[col.name] = tsNow(world, col.fsp);
    Object.assign(row, next);
    world.markDirty();
  }
  return row;
}

// ---------------------------------------------------------------------------------------------------------------------
// Statement atomicity and the trigger facade. MySQL rolls back everything a statement did, triggers' changes to other tables
// included, when any part of it fails (a SIGNAL, a duplicate key, a foreign key, a CHECK). The journal does the same: every change
// made through `db` registers an undo, and a failure replays them backwards.
// ---------------------------------------------------------------------------------------------------------------------
function transact(fn) {
  const journal = [];
  try {
    return fn(journal);
  } catch (e) {
    for (let i = journal.length - 1; i >= 0; i--) journal[i]();
    throw e;
  }
}

function makeDb(world, app, journal) {
  const pkValueRow = (st, id) => {
    const pkCol = st.columns.find((c) => c.name === st.pk[0]);
    return { [st.pk[0]]: pkCol.kind === 'int' ? Number(id) : id };
  };
  const db = {
    operator: false, // the real trigger's @sl_operator: never set through the API
    now: () => world.now(),
    nowMs: () => Math.floor(world.now()),
    uuid: () => crypto.randomUUID(),
    rows: (table) => [...app.tables[table].rows.values()],
    byId(table, id) {
      const st = world.schema[table];
      if (id === null || id === undefined || st.pk.length !== 1) return null;
      return app.tables[table].rows.get(rowKey(st, st.pk, pkValueRow(st, id))) ?? null;
    },
    user: (id) => (id === null || id === undefined ? null : findUserById(world, app, id)),
    pepper() {
      const st = world.schema.app_secrets;
      if (!st) return null;
      return app.tables.app_secrets.rows.get('code_pepper')?.value ?? null;
    },
    insert(table, data) {
      const row = insertRow(world, app, table, data);
      const st = world.schema[table];
      const key = rowKey(st, st.pk, row);
      journal.push(() => app.tables[table].rows.delete(key));
      return row;
    },
    update(table, row, changes) {
      const before = { ...row };
      updateRow(world, app, table, row, changes);
      journal.push(() => Object.assign(row, before));
      return row;
    },
    refuse(token) {
      throw E.unprocessable(token); // SIGNAL SQLSTATE '45000': HTTP 422, error.message is the sl: token, no details
    },
    duplicate() {
      throw new DbError('dup', 'Duplicate entry (SIGNAL 23000 / 1062 in a trigger)'); // AppHub answers 409
    },
  };
  return db;
}

/** Runs the triggers for (when, event, table). `NEW` is the values the statement would store; set(col, v) assigns one. */
function fireTriggers(db, when, event, table, ctx) {
  for (const t of triggersFor(when, event, table)) t.run(db, ctx);
}

function presentRow(meta, row) {
  const out = {};
  for (const col of meta.schema.columns) {
    if (meta.hidden.includes(col.name)) continue;
    const v = row[col.name];
    if (v === null || v === undefined) out[col.name] = null;
    else if (col.kind === 'datetime') out[col.name] = fmtDatetime(v, col.fsp);
    else if (col.kind === 'double') out[col.name] = new Dbl(v);
    else out[col.name] = v;
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------------
// Authentication of a request (Authenticator)
// ---------------------------------------------------------------------------------------------------------------------
const usersOf = (world, app) => app.tables[world.auth.table].rows;
const userId = (world, user) => String(user[world.auth.id]);
const findUserByEmail = (world, app, email) => {
  const e = email.toLowerCase();
  for (const u of usersOf(world, app).values()) if (String(u[world.auth.email]).toLowerCase() === e) return u;
  return null;
};
const findUserById = (world, app, id) => (/^\d+$/.test(String(id)) ? usersOf(world, app).get(String(Number(id))) || null : null);
/** NOW() for a DATETIME(fsp) column: whole seconds when the column has no fractional part. */
const tsNow = (world, fsp) => (fsp > 0 ? world.now() : Math.floor(world.now() / 1000) * 1000);
const userRole = (world, user) => (world.auth.role && user[world.auth.role]) || world.auth.default_role;
const userActive = (world, user) => world.auth.status === null || String(user[world.auth.status] ?? '') === world.auth.active_value;
const userVerified = (world, user) => world.auth.verified_column === null || !!user[world.auth.verified_column];
const fingerprint = (world, app, user) => crypto.createHmac('sha256', app.secret).update(String(user[world.auth.password] || '')).digest('hex').slice(0, 16);
const publicUser = (world, user) => presentRow(world.meta[world.auth.table], user);

function authenticate(world, app, r) {
  const bearer = r.bearerToken();
  if (bearer !== null) {
    const nowS = Math.floor(world.now() / 1000);
    const claims = jwtDecode(bearer, app.secret, nowS, world.opts.jwtLeeway);
    if (!claims || claims.iss !== 'apphub' || claims.aud !== app.name || typeof claims.sub !== 'string' || !hasOwn(claims, 'fp')) {
      throw E.unauthorized('Invalid or expired token');
    }
    const user = findUserById(world, app, claims.sub);
    if (!user || !userActive(world, user) || fingerprint(world, app, user) !== String(claims.fp)) throw E.unauthorized('Invalid or expired token');
    return { type: 'user', id: userId(world, user), role: userRole(world, user), label: 'user:' + userId(world, user) };
  }
  if (r.header('x-api-key') !== null) throw E.unauthorized('Invalid API key'); // the fake has no API keys
  throw E.unauthorized();
}

function issueTokens(world, app, user, r, family = null) {
  const nowMs = world.now();
  const nowS = Math.floor(nowMs / 1000);
  const access = jwtEncode(
    {
      iss: 'apphub',
      aud: app.name,
      sub: userId(world, user),
      role: userRole(world, user),
      fp: fingerprint(world, app, user),
      iat: nowS,
      nbf: nowS,
      exp: nowS + world.accessTtl,
      jti: crypto.randomBytes(8).toString('hex'),
    },
    app.secret,
  );
  const refresh = `${app.name}.${b64u(crypto.randomBytes(32))}`;
  app.refresh.push({
    user_id: userId(world, user),
    token_hash: sha256(refresh),
    family: family || crypto.randomBytes(16).toString('hex'),
    expires_at: nowMs + world.refreshTtl * 1000,
    created_at: nowMs,
    revoked_at: null,
    ip: r.ip,
  });
  world.markDirty();
  return { token_type: 'Bearer', access_token: access, expires_in: world.accessTtl, refresh_token: refresh, refresh_expires_in: world.refreshTtl };
}

function rotateRefresh(world, app, token, r) {
  const m = TOKEN_RE.exec(token);
  if (!m || m[1] !== app.name) throw E.unauthorized('Invalid refresh token');
  const hash = sha256(token);
  const row = app.refresh.find((t) => t.token_hash === hash);
  if (!row) throw E.unauthorized('Invalid refresh token');
  const now = world.now();
  if (row.revoked_at !== null) {
    for (const t of app.refresh) if (t.family === row.family && t.revoked_at === null) t.revoked_at = now; // reuse detected
    world.markDirty();
    throw E.unauthorized('Refresh token was already used; all sessions from it were signed out');
  }
  row.revoked_at = now;
  world.markDirty();
  if (row.expires_at <= now) throw E.unauthorized('Refresh token expired');
  const user = findUserById(world, app, row.user_id);
  if (!user || !userActive(world, user)) throw E.unauthorized('Account is not active');
  return [issueTokens(world, app, user, r, row.family), user];
}

function revokeFamily(world, app, token) {
  const m = TOKEN_RE.exec(token);
  if (!m || m[1] !== app.name) return;
  const row = app.refresh.find((t) => t.token_hash === sha256(token));
  if (!row) return;
  const now = world.now();
  for (const t of app.refresh) if (t.family === row.family && t.revoked_at === null) t.revoked_at = now;
  world.markDirty();
}

function revokeAllForUser(world, app, id) {
  const now = world.now();
  for (const t of app.refresh) if (t.user_id === id && t.revoked_at === null) t.revoked_at = now;
  world.markDirty();
}

// One-time email tokens (reset / verify). The fake sends no mail: the "email" goes to the outbox (GET /__outbox).
function createUserToken(world, app, id, purpose) {
  const now = world.now();
  for (const t of app.userTokens) if (t.user_id === id && t.purpose === purpose && t.used_at === null) t.used_at = now;
  const token = `${app.name}.${b64u(crypto.randomBytes(32))}`;
  app.userTokens.push({ user_id: id, purpose, token_hash: sha256(token), expires_at: now + USER_TOKEN_TTL[purpose] * 1000, created_at: now, used_at: null });
  return token;
}
function findUserToken(world, app, token, purpose) {
  const m = TOKEN_RE.exec(token);
  if (!m || m[1] !== app.name) return null;
  const h = sha256(token);
  const now = world.now();
  return app.userTokens.find((t) => t.token_hash === h && t.purpose === purpose && t.used_at === null && t.expires_at > now) || null;
}
const peekUserToken = (world, app, token, purpose) => findUserToken(world, app, token, purpose)?.user_id ?? null;
function consumeUserToken(world, app, token, purpose) {
  const t = findUserToken(world, app, token, purpose);
  if (!t) return null;
  t.used_at = world.now();
  world.markDirty();
  return t.user_id;
}

// ---------------------------------------------------------------------------------------------------------------------
// Auth controller
// ---------------------------------------------------------------------------------------------------------------------
function bodyString(body, key, maxBytes) {
  const value = body[key] ?? ''; // PHP: $body[$key] ?? '' (null counts as missing)
  if (!isStr(value)) throw E.unprocessable(`'${key}' must be a string`, { field: key });
  if (Buffer.byteLength(value) > maxBytes) throw E.unprocessable(`'${key}' is too long`, { field: key });
  return key === 'email' ? value.trim() : value;
}

const ok = (data, status = 200, meta = undefined) => ({ status, headers: {}, body: meta ? { ok: true, data, meta } : { ok: true, data } });

function mailAllowed(world, app, purpose, email) {
  const key = `mail:${purpose}:${app.name}:${email.toLowerCase()}`;
  const [count] = world.limiter.peek(key, 3600);
  if (count >= MAILS_PER_HOUR) return false;
  world.limiter.increment(key, 3600);
  return true;
}

function sendMail(world, app, user, purpose) {
  const token = createUserToken(world, app, userId(world, user), purpose);
  const url = purpose === 'reset' ? world.auth.reset_url : world.auth.verify_url;
  app.outbox.push({ at: fmtDatetime(world.now(), 3), to: user[world.auth.email], purpose, token, link: url.replace('{token}', token) });
  world.markDirty();
}

const authController = {
  login(world, app, r) {
    const body = r.json();
    const email = bodyString(body, 'email', 254);
    const password = bodyString(body, 'password', 1024);
    if (email === '' || password === '') throw E.unprocessable('Email and password are required');
    const lockKey = `login:${app.name}:${email.toLowerCase()}:${r.ip}`;
    const max = world.opts.loginLockout;
    if (max > 0) {
      const [failures, resetIn] = world.limiter.peek(lockKey, world.opts.lockoutSeconds);
      if (failures >= max) throw E.tooMany(resetIn);
    }
    const user = findUserByEmail(world, app, email);
    const okPw = user ? verifyPassword(password, user[world.auth.password]) && userActive(world, user) : (verifyPassword(password, 'x'), false);
    if (!okPw) {
      if (max > 0) world.limiter.increment(lockKey, world.opts.lockoutSeconds);
      throw E.unauthorized('Invalid email or password');
    }
    if (max > 0) world.limiter.reset(lockKey);
    if (world.auth.require_verified && !userVerified(world, user)) {
      throw new HttpError(403, 'Verify your email address first. Check your inbox, or ask for a new link.', { details: { reason: 'email_not_verified' } });
    }
    return ok({ ...issueTokens(world, app, user, r), user: publicUser(world, user) });
  },

  google() {
    throw E.notFound(); // no auth.google in the config
  },

  register(world, app, r) {
    if (!world.auth.allow_register) throw E.notFound();
    const body = r.json();
    const unknown = Object.keys(body).filter((k) => !['email', 'password', ...world.auth.register_fields].includes(k));
    if (unknown.length) throw E.unprocessable('These fields cannot be set at registration', { fields: unknown });
    const email = bodyString(body, 'email', 254);
    if (!EMAIL_RE.test(email) || email.length > 254) throw E.unprocessable('A valid email is required', { field: 'email' });
    const password = bodyString(body, 'password', 1024);
    assertAcceptablePassword(password, world.auth.password_min, email);
    if (findUserByEmail(world, app, email)) throw new HttpError(409, 'This email is already registered');
    const data = { [world.auth.email]: email, [world.auth.password]: hashPassword(password) };
    if (world.auth.role !== null) data[world.auth.role] = world.auth.default_role;
    if (world.auth.status !== null) data[world.auth.status] = world.auth.active_value;
    for (const f of world.auth.register_fields) if (hasOwn(body, f)) data[f] = bodyString(body, f, 255);
    const row = insertRow(world, app, world.auth.table, data);
    let sent = false;
    if (world.auth.verify_url && world.auth.verified_column && mailAllowed(world, app, 'verify', email)) {
      sendMail(world, app, row, 'verify');
      sent = true;
    }
    return ok({ user: publicUser(world, row), verification_email_sent: sent }, 201);
  },

  forgot(world, app, r) {
    if (!world.auth.reset_url) throw E.notFound();
    const email = bodyString(r.json(), 'email', 254);
    if (email === '') throw E.unprocessable('Email is required', { field: 'email' });
    const user = findUserByEmail(world, app, email);
    if (user && userActive(world, user) && mailAllowed(world, app, 'reset', email)) sendMail(world, app, user, 'reset');
    return ok({ message: MAIL_SENT });
  },

  reset(world, app, r) {
    if (!world.auth.reset_url) throw E.notFound();
    const body = r.json();
    const token = bodyString(body, 'token', 128);
    const pw = bodyString(body, 'new_password', 1024);
    const id = peekUserToken(world, app, token, 'reset');
    const user = id !== null ? findUserById(world, app, id) : null;
    if (!user || !userActive(world, user)) throw E.unprocessable(BAD_LINK, { field: 'token' });
    assertAcceptablePassword(pw, world.auth.password_min, user[world.auth.email]);
    if (consumeUserToken(world, app, token, 'reset') !== id) throw E.unprocessable(BAD_LINK, { field: 'token' });
    user[world.auth.password] = hashPassword(pw);
    revokeAllForUser(world, app, id);
    if (world.auth.verified_column && !user[world.auth.verified_column]) user[world.auth.verified_column] = tsNow(world, 0);
    world.markDirty();
    return ok({ message: 'Password changed. Sign in with your new password.' });
  },

  verify(world, app, r) {
    if (!world.auth.verify_url) throw E.notFound();
    const token = bodyString(r.json(), 'token', 128);
    const id = consumeUserToken(world, app, token, 'verify');
    const user = id !== null ? findUserById(world, app, id) : null;
    if (!user) throw E.unprocessable(BAD_LINK, { field: 'token' });
    if (world.auth.verified_column && !user[world.auth.verified_column]) user[world.auth.verified_column] = tsNow(world, 0);
    world.markDirty();
    return ok({ message: 'Email address verified. You can sign in now.' });
  },

  'resend-verification'(world, app, r) {
    if (!world.auth.verify_url) throw E.notFound();
    const email = bodyString(r.json(), 'email', 254);
    if (email === '') throw E.unprocessable('Email is required', { field: 'email' });
    const user = findUserByEmail(world, app, email);
    if (user && userActive(world, user) && !userVerified(world, user) && mailAllowed(world, app, 'verify', email)) sendMail(world, app, user, 'verify');
    return ok({ message: MAIL_SENT });
  },

  refresh(world, app, r) {
    const token = bodyString(r.json(), 'refresh_token', 128);
    const [tokens, user] = rotateRefresh(world, app, token, r);
    return ok({ ...tokens, user: publicUser(world, user) });
  },

  logout(world, app, r) {
    revokeFamily(world, app, bodyString(r.json(), 'refresh_token', 128));
    return ok({ message: 'Signed out' });
  },

  me(world, app, r) {
    const who = authenticate(world, app, r);
    const user = findUserById(world, app, who.id);
    if (!user) throw E.unauthorized();
    return ok(publicUser(world, user));
  },

  access(world, app, r) {
    const who = authenticate(world, app, r);
    const tables = {};
    for (const name of Object.keys(world.meta)) {
      const d = describeTable(world, who, name);
      if (d) tables[name] = d;
    }
    return ok({
      app: app.name,
      principal: { type: who.type, id: who.id, role: who.role },
      auth_table: world.auth.table,
      tables,
      uploads: { allowed: false, max_mb: 5, types: [] },
    });
  },

  password(world, app, r) {
    const who = authenticate(world, app, r);
    const body = r.json();
    const current = bodyString(body, 'current_password', 1024);
    const next = bodyString(body, 'new_password', 1024);
    const user = findUserById(world, app, who.id);
    if (!user) throw E.unauthorized();
    if (!verifyPassword(current, user[world.auth.password])) throw E.forbidden('Current password is incorrect');
    assertAcceptablePassword(next, world.auth.password_min, String(user[world.auth.email] ?? ''));
    if (current === next) throw E.unprocessable('New password must be different', { field: 'new_password' });
    user[world.auth.password] = hashPassword(next);
    revokeAllForUser(world, app, who.id);
    world.markDirty();
    return ok(issueTokens(world, app, user, r));
  },
};
const AUTH_LIMITED = ['forgot', 'reset', 'verify', 'resend-verification', 'login', 'google', 'register', 'refresh', 'password'];

// ---------------------------------------------------------------------------------------------------------------------
// CRUD controller
// ---------------------------------------------------------------------------------------------------------------------
function scopeOf(world, role, table, action) {
  const rules = world.roles[role];
  if (!rules || !world.meta[table]) return null;
  const tableRules = rules[table] ?? rules['*'] ?? {};
  const scope = tableRules[action];
  return ['all', 'own', 'shared'].includes(scope) ? scope : null;
}

function requireScope(world, who, table, action) {
  const scope = scopeOf(world, who.role, table, action);
  if (scope === null) throw E.forbidden(`Your role cannot ${action} ${table}`);
  if ((scope === 'own' || scope === 'shared') && who.id === null) throw E.forbidden('This action needs a user account');
  return scope;
}

function tableMeta(world, table) {
  const m = world.meta[table];
  if (!m) throw E.notFound();
  return m;
}

function allowedColumns(world, meta, who, scope, creating) {
  const allowed = meta.writableByRole[who.role] ?? meta.writable;
  const never = [];
  if (!creating || !allowed.includes(meta.pk)) never.push(meta.pk);
  if (scope === 'own' || scope === 'shared') {
    // AppHub (CrudController::allowedColumns): under `shared` the members columns are never writable either (a members column would
    // let an editor hand out read access). Under `own` they are not excluded: the config must simply not list them.
    if (scope === 'shared') never.push(...meta.members);
    never.push(meta.owner);
    if (meta.name === world.auth.table) never.push(world.auth.role, world.auth.status, world.auth.email, world.auth.verified_column);
  }
  return allowed.filter((c) => !never.includes(c));
}

function describeTable(world, who, name) {
  const actions = {};
  for (const action of ACTIONS) {
    const scope = scopeOf(world, who.role, name, action);
    if (scope !== null && !((scope === 'own' || scope === 'shared') && who.id === null)) actions[action] = scope;
  }
  if (!Object.keys(actions).length) return null;
  const m = world.meta[name];
  return {
    pk: m.pk,
    actions,
    create_fields: actions.create ? allowedColumns(world, m, who, actions.create, true) : [],
    update_fields: actions.update ? allowedColumns(world, m, who, actions.update, false) : [],
    filterable: m.filterable,
    searchable: m.searchable.length > 0,
    sortable: m.sortable,
    file_columns: actions.update ? m.fileColumns : [],
    trash: false,
  };
}

const ownedBy = (world, meta, who, row) => meta.owner !== null && row[meta.owner] !== undefined && keyOfValue(meta.schema.columns.find((c) => c.name === meta.owner), row[meta.owner]) === Number(who.id);

/**
 * The one place a scope becomes a yes or no for a row: AppHub's ownerCondition / sharedCondition (src/Controllers/CrudController.php).
 * `own`: the owner column is the caller. `shared`: the owner, OR (list and read only, never update) a members column is the caller, OR a
 * grant row exists whose match column equals this row's `on` column, whose member column is the caller and whose level is one of
 * shared_via.read (list, read) or shared_via.update (update). The grant table is evaluated on EVERY request, so a person whose grant
 * row changed to a level that is not listed (role removed) gets 404 on their next call. NB the owner term is always OR-ed in.
 */
function rowVisible(world, app, meta, who, row, scope, action) {
  if (scope === 'all') return true;
  const owned = ownedBy(world, meta, who, row);
  if (scope === 'own' || owned) return owned;
  const uid = Number(who.id);
  if (action !== 'update' && meta.members.some((c) => row[c] !== null && row[c] !== undefined && Number(row[c]) === uid)) return true;
  const via = meta.sharedVia;
  if (!via) return false;
  const levels = action === 'update' ? via.update : via.read;
  if (levels.length === 0) return false;
  const on = row[via.on];
  if (on === null || on === undefined) return false;
  for (const g of app.tables[via.table].rows.values()) {
    if (g[via.member] === null || g[via.member] === undefined || Number(g[via.member]) !== uid) continue;
    if (g[via.match] === null || g[via.match] === undefined || String(g[via.match]).toLowerCase().replace(/ +$/, '') !== String(on).toLowerCase().replace(/ +$/, '')) continue;
    if (levels.some((l) => String(l).toLowerCase() === String(g[via.level]).toLowerCase())) return true;
  }
  return false;
}

function findScoped(world, app, meta, who, id, scope, action = 'read') {
  const pkCol = meta.schema.columns.find((c) => c.name === meta.pk);
  const k = pkCol.kind === 'int' ? (/^\s*\d+\s*$/.test(id) ? String(Number(id)) : null) : id.toLowerCase().replace(/ +$/, '');
  if (k === null || meta.schema.pk.length !== 1) return null;
  const row = app.tables[meta.name].rows.get(k);
  if (!row) return null;
  return rowVisible(world, app, meta, who, row, scope, action) ? row : null;
}

function positiveInt(value, name, max) {
  if (!isStr(value) || !/^\d+$/.test(value) || Number(value) < 1 || Number(value) > max) throw E.badRequest(`${name} must be a whole number from 1 to ${max}`);
  return Number(value);
}

function compareForSort(col, a, b) {
  if (a === null && b === null) return 0;
  if (a === null) return -1; // MySQL: NULLs first ascending
  if (b === null) return 1;
  if (col.kind === 'string' || col.kind === 'text') {
    const x = String(a).toLowerCase();
    const y = String(b).toLowerCase();
    return x < y ? -1 : x > y ? 1 : 0;
  }
  return Number(a) - Number(b);
}

function filterMatches(col, rowVal, param) {
  if (rowVal === null) return false;
  if (col.kind === 'string' || col.kind === 'text') return String(rowVal).toLowerCase().replace(/ +$/, '') === param.toLowerCase().replace(/ +$/, '');
  if (col.kind === 'datetime') return fmtDatetime(rowVal, col.fsp) === param;
  return Number(rowVal) === sqlNumber(param);
}

const crud = {
  list(world, app, who, table, query) {
    const meta = tableMeta(world, table);
    const scope = requireScope(world, who, table, 'list');
    const unknown = Object.keys(query).filter((k) => !LIST_PARAMS.includes(k) && !meta.filterable.includes(k));
    if (unknown.length) throw E.badRequest('Unknown query parameters', { params: unknown });
    const filters = [];
    for (const col of meta.filterable) {
      if (hasOwn(query, col)) {
        const v = query[col];
        if (!isStr(v) || Buffer.byteLength(v) > 255) throw E.badRequest(`Invalid filter value for '${col}'`);
        filters.push([meta.schema.columns.find((c) => c.name === col), v]);
      }
    }
    if (hasOwn(query, 'q')) {
      const q = query.q;
      if (!isStr(q) || [...q].length > 100) throw E.badRequest('Search text must be at most 100 characters');
      if (meta.searchable.length === 0) throw E.badRequest('This resource does not support search');
    }
    const sort = hasOwn(query, 'sort') ? (isStr(query.sort) ? query.sort : 'Array') : meta.pk;
    if (!meta.sortable.includes(sort)) throw E.badRequest('Cannot sort by this field', { sortable: meta.sortable });
    const order = (hasOwn(query, 'order') ? (isStr(query.order) ? query.order : 'Array') : 'desc').toLowerCase();
    if (!['asc', 'desc'].includes(order)) throw E.badRequest("order must be 'asc' or 'desc'");
    const page = positiveInt(hasOwn(query, 'page') ? query.page : '1', 'page', 1000000);
    const limit = positiveInt(hasOwn(query, 'limit') ? query.limit : '20', 'limit', MAX_LIMIT);

    let rows = [...app.tables[table].rows.values()].filter((row) => rowVisible(world, app, meta, who, row, scope, 'list') && filters.every(([col, v]) => filterMatches(col, row[col.name], v)));
    const total = rows.length;
    const sortCol = meta.schema.columns.find((c) => c.name === sort);
    const dir = order === 'asc' ? 1 : -1;
    rows = rows.map((row, i) => [row, i]).sort((a, b) => dir * compareForSort(sortCol, a[0][sort], b[0][sort]) || a[1] - b[1]).map(([row]) => row);
    const slice = rows.slice((page - 1) * limit, (page - 1) * limit + limit);
    return ok(slice.map((row) => presentRow(meta, row)), 200, { page, limit, total, pages: Math.ceil(total / limit) });
  },

  read(world, app, who, table, id) {
    const meta = tableMeta(world, table);
    const scope = requireScope(world, who, table, 'read');
    const row = findScoped(world, app, meta, who, id, scope, 'read');
    if (!row) throw E.notFound();
    return ok(presentRow(meta, row));
  },

  create(world, app, who, table, body) {
    const meta = tableMeta(world, table);
    const scope = requireScope(world, who, table, 'create');
    const data = writableData(world, meta, who, scope, body, true);
    if (scope === 'own') {
      if (meta.owner === null || who.id === null) throw E.forbidden();
      data[meta.owner] = who.id;
    } else if (meta.owner !== null && !hasOwn(data, meta.owner) && who.type === 'user' && meta.name !== world.auth.table) {
      data[meta.owner] = who.id;
    }
    if (Object.keys(data).length === 0) throw E.unprocessable('No writable fields were provided');
    // One INSERT statement: BEFORE INSERT trigger(s), the row (defaults, CHECKs, keys, foreign keys), AFTER INSERT trigger(s). A failure
    // anywhere undoes the trigger's changes to other tables too. Then AppHub reads the row back WITHOUT a scope (`all`), so the
    // 201 carries every column a trigger set (hidden ones are left out by presentRow).
    const row = transact((journal) => {
      const db = makeDb(world, app, journal);
      const actor = who.type === 'user' ? Number(who.id) : null;
      const ctx = { NEW: data, OLD: null, actor, set: (col, value) => (data[col] = value) };
      fireTriggers(db, 'BEFORE', 'INSERT', table, ctx);
      const inserted = db.insert(table, data);
      fireTriggers(db, 'AFTER', 'INSERT', table, { NEW: inserted, OLD: null, actor, set: () => {} });
      return inserted;
    });
    return ok(presentRow(meta, row), 201);
  },

  update(world, app, who, table, id, body) {
    const meta = tableMeta(world, table);
    const scope = requireScope(world, who, table, 'update');
    const row = findScoped(world, app, meta, who, id, scope, 'update');
    if (!row) throw E.notFound();
    const data = writableData(world, meta, who, scope, body, false);
    if (Object.keys(data).length === 0) throw E.unprocessable('No writable fields were provided');
    // One UPDATE statement: BEFORE UPDATE trigger(s) may refuse or assign NEW columns, then the row changes.
    transact((journal) => {
      const db = makeDb(world, app, journal);
      const actor = who.type === 'user' ? Number(who.id) : null;
      const changes = { ...data };
      const NEW = { ...row, ...changes };
      const ctx = {
        NEW,
        OLD: { ...row },
        actor,
        set: (col, value) => {
          NEW[col] = value;
          changes[col] = value;
        },
      };
      fireTriggers(db, 'BEFORE', 'UPDATE', table, ctx);
      db.update(table, row, changes);
    });
    // A shared row is read back with the same rule: someone whose access this update ended gets 404 and no row.
    const after = findScoped(world, app, meta, who, id, scope === 'shared' ? 'shared' : 'all', 'update');
    if (!after && scope === 'shared') throw E.notFound();
    return ok(presentRow(meta, after ?? row));
  },

  /** The real CrudController checks the table and the role's delete scope before it looks at the id or the query. */
  delete(world, app, who, table) {
    tableMeta(world, table);
    requireScope(world, who, table, 'delete'); // 403 "Your role cannot delete <table>": no role gets delete in Phase 3
    throw new Error('unreachable: the fake refuses to start with a config that grants delete');
  },
};

function writableData(world, meta, who, scope, body, creating) {
  const allowed = allowedColumns(world, meta, who, scope, creating);
  const rejected = Object.keys(body).filter((k) => !allowed.includes(k));
  if (rejected.length) throw E.unprocessable('These fields cannot be written', { fields: rejected, writable: allowed });
  const data = {};
  for (const [col, raw] of Object.entries(body)) {
    let value = raw;
    if (typeof value === 'boolean') value = value ? 1 : 0;
    else if (value !== null && typeof value !== 'string' && typeof value !== 'number') throw E.unprocessable(`Field '${col}' must be a string, number, boolean or null`);
    if (isStr(value) && Buffer.byteLength(value) > 65535) throw E.unprocessable(`Field '${col}' is too long`);
    data[col] = value;
  }
  return data;
}

// ---------------------------------------------------------------------------------------------------------------------
// Kernel: routing
// ---------------------------------------------------------------------------------------------------------------------
function allow(r, ...methods) {
  if (!methods.includes(r.method)) throw E.notAllowed(...methods);
}

function resolveTenant(world, r) {
  const seg = r.segments;
  const has = (n) => world.apps.has(n);
  if (seg.length === 3 && seg[1] === 'auth' && AUTH_ENTRY.includes(seg[2]) && has(seg[0])) return [world.apps.get(seg[0]), seg.slice(1)];
  if (seg.length === 2 && seg[0] === 'auth' && AUTH_ENTRY.includes(seg[1])) throw E.notFound(); // tenant-style URLs: not in the fake
  if (seg[1] === 'public' && has(seg[0])) return [world.apps.get(seg[0]), seg.slice(1)];
  const bearer = r.bearerToken();
  if (bearer !== null) {
    const aud = peekAudience(bearer);
    if (aud === null || !APP_NAME_RE.test(aud) || RESERVED_APP_NAMES.includes(aud)) throw E.unauthorized('Invalid or expired token');
    if (!has(aud)) throw E.unauthorized('Invalid or expired credentials');
    return [world.apps.get(aud), seg[0] === aud ? seg.slice(1) : seg];
  }
  if (has(seg[0])) return [world.apps.get(seg[0]), seg.slice(1)];
  if (r.header('x-api-key') !== null) throw E.badRequest('Send the X-Tenant header with X-API-Key, or put the app name in the URL');
  throw E.unauthorized();
}

function route(world, r, app, path) {
  const n = path.length;
  if (n === 0) throw E.notFound();

  if (path[0] === 'auth') {
    const name = n === 2 ? path[1] : '';
    if (name === 'me' || name === 'access') allow(r, 'GET');
    else if (hasOwn(authController, name)) allow(r, 'POST');
    else throw E.notFound();
    if (AUTH_LIMITED.includes(name) && world.opts.authRateLimit > 0) world.limiter.hit(`auth:${app.name}:${r.ip}`, world.opts.authRateLimit, world.opts.rateWindow);
    return authController[name](world, app, r);
  }

  if (path[0] === 'files') {
    if (n === 1) {
      allow(r, 'POST');
      authenticate(world, app, r);
      throw E.notFound(); // uploads are off for this app
    }
    if (n === 2) {
      allow(r, 'GET');
      authenticate(world, app, r);
      throw E.notFound();
    }
    throw E.notFound();
  }

  if (path[0] === 'public') {
    allow(r, 'GET');
    throw E.notFound(); // no table publishes public_columns
  }

  const who = authenticate(world, app, r);
  const table = path[0];
  if (n > 3 || (n === 3 && path[2] !== 'restore') || !TABLE_RE.test(table)) throw E.notFound();
  if (n === 1) {
    allow(r, 'GET', 'POST');
    return r.method === 'GET' ? crud.list(world, app, who, table, r.query) : crud.create(world, app, who, table, r.json());
  }
  const id = path[1];
  if (!ID_RE.test(id)) throw E.notFound();
  if (n === 3) {
    allow(r, 'POST');
    tableMeta(world, table);
    throw E.notFound(); // restore needs a soft_delete column
  }
  allow(r, 'GET', 'PUT', 'PATCH', 'DELETE');
  if (r.method === 'GET') return crud.read(world, app, who, table, id);
  if (r.method === 'DELETE') return crud.delete(world, app, who, table);
  return crud.update(world, app, who, table, id, r.json());
}

function dispatch(world, r) {
  if (r.segments.length === 0) {
    allow(r, 'GET');
    return ok({ status: 'ok' });
  }
  if (['admin', 'console', 'docs', 'home', 'sdk', 'platform'].includes(r.segments[0])) throw E.notFound(); // no panel in the fake
  if (r.method === 'OPTIONS') return { status: 204, headers: {}, body: null }; // a preflight: the fake sends no CORS headers
  const [app, path] = resolveTenant(world, r);
  const headers = {};
  const limit = world.opts.rateLimit;
  if (limit > 0) {
    const remaining = world.limiter.hit(`app:${app.name}:${r.ip}`, limit, world.opts.rateWindow);
    headers['X-RateLimit-Limit'] = String(limit);
    headers['X-RateLimit-Remaining'] = String(remaining);
  }
  try {
    const result = route(world, r, app, path);
    result.headers = { ...headers, ...result.headers };
    return result;
  } catch (e) {
    if (e instanceof HttpError) e.headers = { ...headers, ...e.headers }; // AppHub sets X-RateLimit-* before routing
    throw e;
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Dev-only control endpoints, outside the app namespace
// ---------------------------------------------------------------------------------------------------------------------
const FAULT_MODES = ['drop_after', 'drop_before', 'status', 'delay'];

function devBody(r) {
  const raw = r._raw.toString('utf8').trim();
  if (raw === '') return {};
  try {
    const v = JSON.parse(raw);
    if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('not an object');
    return v;
  } catch {
    throw E.badRequest('Request body must be a JSON object');
  }
}

/** Placeholder, missing or real: never the value (the pepper is a secret like a password hash). */
function pepperStatus(app) {
  const p = app.tables.app_secrets?.rows.get('code_pepper')?.value;
  if (p === undefined || p === null) return 'missing';
  return [...p].length < 32 || /^replace/i.test(p) ? 'placeholder' : 'set';
}

/**
 * Inspection for tests. `dump=1` lists the rows as the API would show them (hidden columns left out); `hidden=1` adds the hidden
 * columns too (code_hash, code_id, target_id, and the plaintext columns `email` and `code`, which are always null: that is the point).
 * Password hashes and the pepper are NEVER shown, and no plaintext join code can appear: the fake never stores one.
 */
function stateSummary(world, dump, withHidden = false) {
  const apps = {};
  const nowMs = world.now();
  for (const [name, app] of world.apps) {
    const tables = {};
    for (const [t, store] of Object.entries(app.tables)) {
      if (!dump) {
        tables[t] = store.rows.size;
      } else if (t === 'app_secrets') {
        tables[t] = [...store.rows.values()].map((r) => ({ name: r.name, value: '<redacted>' }));
      } else {
        const hidden = withHidden ? (t === world.auth.table ? [world.auth.password] : []) : world.meta[t]?.hidden ?? [];
        tables[t] = [...store.rows.values()].map((row) => presentRow({ schema: world.schema[t], hidden }, row));
      }
    }
    apps[name] = {
      tables,
      refresh_tokens: { total: app.refresh.length, active: app.refresh.filter((t) => t.revoked_at === null && t.expires_at > nowMs).length },
      outbox: app.outbox.length,
      secrets: { code_pepper: pepperStatus(app) },
    };
  }
  return {
    now: fmtDatetime(nowMs, 3),
    clock_offset_seconds: Math.round(world.clockOffsetMs / 1000),
    access_ttl: world.accessTtl,
    refresh_ttl: world.refreshTtl,
    options: { ...world.opts, data: world.opts.data ? '(set)' : null },
    faults: world.faults.map(faultView),
    drift_warnings: world.warnings,
    apps,
  };
}

const faultView = (f) => ({ id: f.id, mode: f.mode, match: f.matchSource, remaining: f.remaining === Infinity ? -1 : f.remaining, status: f.status, retry_after: f.retryAfter, ms: f.ms });

async function devRoute(world, r) {
  const name = r.segments[0];
  if (name === '__reset' && r.method === 'POST') {
    world.reset();
    return ok({ reset: true });
  }
  if (name === '__state' && r.method === 'GET') return ok(stateSummary(world, r.query.dump === '1', r.query.hidden === '1'));
  if (name === '__clock' && r.method === 'POST') {
    const b = devBody(r);
    if (b.reset === true) world.clockOffsetMs = 0;
    else if (typeof b.advance_ms === 'number' && Number.isFinite(b.advance_ms)) world.clockOffsetMs += Math.round(b.advance_ms);
    else if (typeof b.advance_seconds === 'number' && Number.isFinite(b.advance_seconds)) world.clockOffsetMs += Math.round(b.advance_seconds * 1000);
    else throw E.unprocessable('Send {"advance_ms": N}, {"advance_seconds": N} or {"reset": true}');
    world.markDirty();
    return ok({ now: fmtDatetime(world.now(), 3), now_ms: Math.floor(world.now()), clock_offset_seconds: Math.round(world.clockOffsetMs / 1000) });
  }
  // The operator's seed step: put the code pepper into the three states the triggers tell apart (see seed.sql).
  if (name === '__pepper' && r.method === 'POST') {
    const b = devBody(r);
    const app = world.apps.get(b.app || world.opts.apps[0]);
    if (!app || !world.schema.app_secrets) throw E.notFound();
    if (!['set', 'placeholder', 'missing'].includes(b.mode)) throw E.unprocessable('mode must be set, placeholder or missing', { field: 'mode' });
    app.tables.app_secrets.rows.clear();
    if (b.mode === 'set') seedPepper(world, app, true);
    else if (b.mode === 'placeholder') app.tables.app_secrets.rows.set('code_pepper', { name: 'code_pepper', value: 'REPLACE-ME-the-operator-sets-a-random-64-hex-value-here-never-commit-it', updated_at: tsNow(world, 3) });
    world.markDirty();
    return ok({ code_pepper: pepperStatus(app) });
  }
  if (name === '__log' && r.method === 'GET') return ok({ lines: world.accessLog });
  if (name === '__outbox' && r.method === 'GET') {
    const app = world.apps.get(r.query.app || world.opts.apps[0]);
    if (!app) throw E.notFound();
    return ok(app.outbox);
  }
  if (name === '__fault') {
    if (r.method === 'GET') return ok(world.faults.map(faultView));
    if (r.method === 'DELETE') {
      world.faults = [];
      return ok({ cleared: true });
    }
    if (r.method === 'POST') {
      const b = devBody(r);
      if (!FAULT_MODES.includes(b.mode)) throw E.unprocessable(`mode must be one of ${FAULT_MODES.join(', ')}`, { field: 'mode' });
      let re = null;
      try {
        re = b.match === undefined ? null : new RegExp(String(b.match));
      } catch {
        throw E.unprocessable('match is not a valid regular expression', { field: 'match' });
      }
      const count = b.count === undefined ? 1 : b.count;
      if (!Number.isInteger(count) || count < -1 || count === 0) throw E.unprocessable('count must be a positive integer (or -1 for "until cleared")', { field: 'count' });
      const status = b.status === undefined ? 503 : b.status;
      if (!Number.isInteger(status) || status < 100 || status > 599) throw E.unprocessable('status must be 100 to 599', { field: 'status' });
      const ms = b.ms === undefined ? 500 : b.ms;
      if (!Number.isInteger(ms) || ms < 0 || ms > 60000) throw E.unprocessable('ms must be 0 to 60000', { field: 'ms' });
      const fault = {
        id: crypto.randomBytes(4).toString('hex'),
        mode: b.mode,
        re,
        matchSource: b.match ?? null,
        remaining: count === -1 ? Infinity : count,
        status,
        retryAfter: b.retry_after === undefined ? null : Number(b.retry_after),
        ms,
      };
      world.faults.push(fault);
      return ok(faultView(fault), 201);
    }
    throw E.notAllowed('GET', 'POST', 'DELETE');
  }
  throw E.notFound();
}

function takeFault(world, r) {
  const subject = `${r.method} ${r.pathname}`;
  for (const f of world.faults) {
    if (f.remaining > 0 && (f.re === null || f.re.test(subject))) {
      f.remaining--;
      if (f.remaining <= 0) world.faults = world.faults.filter((x) => x !== f);
      return f;
    }
  }
  return null;
}

const FAULT_MESSAGES = { 429: 'Too many requests', 500: 'Internal server error', 502: 'Bad gateway', 503: 'Service temporarily unavailable', 504: 'Gateway timeout' };

// ---------------------------------------------------------------------------------------------------------------------
// HTTP transport
// ---------------------------------------------------------------------------------------------------------------------
function errorResult(e, id, world) {
  if (e instanceof HttpError) {
    const error = { status: e.status, message: e.message };
    if (e.details !== null) error.details = e.details;
    error.request_id = id;
    return { status: e.status, headers: e.headers, body: { ok: false, error } };
  }
  if (e instanceof DbError) {
    const conflict = e.kind === 'dup' || e.kind === 'fk' || e.kind === 'null';
    const status = conflict ? 409 : 500;
    const message = conflict ? DB_CONFLICT : 'Internal server error';
    if (!world.opts.quiet) console.log(`  [db ${status}] ${e.message}`); // messages name a column, never a value
    return { status, headers: { 'X-Fake-Db-Error': e.message.replace(/[^\x20-\x7E]/g, '?') }, body: { ok: false, error: { status, message, request_id: id } } };
  }
  console.error('fake server bug:', e);
  return { status: 500, headers: {}, body: { ok: false, error: { status: 500, message: 'Internal server error', request_id: id } } };
}

const SECURITY_HEADERS = {
  'Content-Type': 'application/json; charset=utf-8',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; sandbox",
  'Referrer-Policy': 'no-referrer',
  'Cache-Control': 'no-store',
  Pragma: 'no-cache',
};

async function readBody(req) {
  const chunks = [];
  let size = 0;
  let tooLarge = false;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) tooLarge = true;
    else chunks.push(chunk);
  }
  return { buf: Buffer.concat(chunks), tooLarge };
}

async function onRequest(world, req, res) {
  const t0 = Date.now();
  const id = crypto.randomBytes(8).toString('hex');
  let r = null;
  let result;
  let drop = null;
  try {
    const { buf, tooLarge } = await readBody(req);
    r = buildRequest(world, req, buf, tooLarge, id);
    r._raw = buf;
    if (r.segments[0] && r.segments[0].startsWith('__')) {
      result = await devRoute(world, r);
    } else {
      const fault = takeFault(world, r);
      if (fault && fault.mode === 'drop_before') {
        drop = 'before';
      } else if (fault && fault.mode === 'status') {
        const headers = fault.retryAfter !== null ? { 'Retry-After': String(fault.retryAfter) } : {};
        throw new HttpError(fault.status, FAULT_MESSAGES[fault.status] || 'Injected fault', { headers });
      } else {
        if (fault && fault.mode === 'delay') await sleep(fault.ms);
        result = dispatch(world, r);
        if (fault && fault.mode === 'drop_after') drop = 'after';
      }
    }
  } catch (e) {
    result = errorResult(e, id, world);
  }
  {
    // The access log: method, PATH (never the query string, never a body or a header), status, time. A join code only ever travels in
    // a request body, so it cannot get here; smoke.mjs and tests/logscan.mjs prove it. It is kept in memory for GET /__log.
    const tag = drop ? ` DROPPED (${drop})` : '';
    const line = `${r ? r.method : '?'} ${r ? r.pathname : String(req.url).split('?')[0]} -> ${drop ? '-' : result.status} ${Date.now() - t0}ms${tag}`;
    world.accessLog.push(line);
    if (world.accessLog.length > 20000) world.accessLog.splice(0, 5000);
    if (!world.opts.quiet) console.log(line);
  }
  if (drop) {
    req.socket.destroy(); // the client sees a network failure, never a response
    return;
  }
  const headers = { ...SECURITY_HEADERS, 'X-Request-Id': id, ...result.headers };
  if (result.status === 204 || result.body === null) {
    delete headers['Content-Type'];
    res.writeHead(result.status, headers);
    res.end();
    return;
  }
  res.writeHead(result.status, headers);
  res.end(jsonStr(result.body));
}

// ---------------------------------------------------------------------------------------------------------------------
// Start and stop
// ---------------------------------------------------------------------------------------------------------------------
export async function startServer(options = {}) {
  const o = { ...DEFAULTS, ...options };
  const world = createWorld(o);
  const sockets = new Set();
  const handler = (req, res) => {
    onRequest(world, req, res).catch((e) => {
      console.error('fake server bug:', e);
      req.socket.destroy();
    });
  };
  const listen = (host, port) =>
    new Promise((resolve, reject) => {
      const s = http.createServer(handler);
      s.on('connection', (sock) => {
        sock.setNoDelay(true); // no Nagle delay: keeps each local request in the low milliseconds
        sockets.add(sock);
        sock.on('close', () => sockets.delete(sock));
      });
      s.once('error', reject);
      s.listen(port, host, () => resolve(s));
    });
  const primary = await listen('127.0.0.1', o.port);
  const port = primary.address().port;
  const servers = [primary];
  if (o.port !== 0) {
    try {
      servers.push(await listen('::1', port)); // "localhost" may resolve to ::1; best effort, never exposed beyond loopback
    } catch {
      /* no IPv6 loopback: fine */
    }
  }
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    world,
    driftWarnings: world.warnings,
    async close() {
      if (world.saveTimer) clearTimeout(world.saveTimer);
      world.save();
      for (const s of sockets) s.destroy();
      await Promise.all(servers.map((s) => new Promise((resolve) => s.close(resolve))));
    },
  };
}

// ---------------------------------------------------------------------------------------------------------------------
// Command line
// ---------------------------------------------------------------------------------------------------------------------
const FLAG_DEFS = {
  port: ['port', 'int'],
  apps: ['apps', 'list'],
  config: ['config', 'path'],
  schema: ['schema', 'path'],
  triggers: ['triggers', 'path'],
  'no-pepper': ['noPepper', 'bool'],
  'access-ttl': ['accessTtl', 'int'],
  'refresh-ttl': ['refreshTtl', 'int'],
  'jwt-leeway': ['jwtLeeway', 'int'],
  data: ['data', 'path'],
  'rate-limit': ['rateLimit', 'int'],
  'rate-window': ['rateWindow', 'int'],
  'auth-rate-limit': ['authRateLimit', 'int'],
  'login-lockout': ['loginLockout', 'int'],
  'lockout-seconds': ['lockoutSeconds', 'int'],
  mail: ['mail', 'bool'],
  'require-verified': ['requireVerified', 'bool'],
  quiet: ['quiet', 'bool'],
};

export function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    let a = argv[i];
    if (!a.startsWith('--')) throw new Error(`unexpected argument "${a}"`);
    a = a.slice(2);
    let value = null;
    const eq = a.indexOf('=');
    if (eq >= 0) {
      value = a.slice(eq + 1);
      a = a.slice(0, eq);
    }
    const def = FLAG_DEFS[a];
    if (!def) throw new Error(`unknown option --${a}`);
    const [key, type] = def;
    if (type === 'bool') {
      out[key] = value === null ? true : value !== 'false' && value !== '0';
      continue;
    }
    if (value === null) value = argv[++i];
    if (value === undefined) throw new Error(`--${a} needs a value`);
    if (type === 'int') {
      if (!/^-?\d+$/.test(value)) throw new Error(`--${a} needs a whole number`);
      out[key] = parseInt(value, 10);
    } else if (type === 'list') {
      out[key] = value.split(',').map((s) => s.trim()).filter(Boolean);
    } else {
      out[key] = path.resolve(value);
    }
  }
  return out;
}

const USAGE = `Usage: node server.mjs [--port 8787] [--apps shoppinglistdev,shoppinglist] [--access-ttl 900] [--refresh-ttl 1209600]
                      [--data file.json] [--rate-limit 0] [--rate-window 60] [--auth-rate-limit 0] [--login-lockout 0]
                      [--lockout-seconds 900] [--jwt-leeway 30] [--mail] [--require-verified] [--no-pepper] [--quiet]
                      [--config ../shoppinglist.config.json] [--schema ../schema.sql] [--triggers ../triggers.sql]
See README.md. Dev endpoints: POST /__reset, GET /__state[?dump=1[&hidden=1]], POST|GET|DELETE /__fault, POST /__clock,
GET /__outbox, GET /__log, POST /__pepper.`;

async function main() {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    console.log(USAGE);
    return;
  }
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`${e.message}\n${USAGE}`);
    process.exit(2);
  }
  const srv = await startServer(opts);
  const w = srv.world;
  console.log('FAKE AppHub for local development (in-memory, not AppHub, no credentials, localhost only).');
  console.log(`listening on ${srv.url}   apps: ${[...w.apps.keys()].join(', ')}   e.g. POST ${srv.url}/${[...w.apps.keys()][0]}/auth/register`);
  console.log(`access ttl ${w.accessTtl}s, refresh ttl ${w.refreshTtl}s, data file: ${w.opts.data || '(none: state is lost on exit)'}`);
  console.log(`rate limit ${w.opts.rateLimit || 'off'}, auth rate limit ${w.opts.authRateLimit || 'off'}, login lockout ${w.opts.loginLockout || 'off'}`);
  for (const warning of srv.driftWarnings) console.warn(`DRIFT WARNING: ${warning}`);
  const stop = async () => {
    await srv.close();
    process.exit(0);
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(e.message || e);
    process.exit(1);
  });
}
