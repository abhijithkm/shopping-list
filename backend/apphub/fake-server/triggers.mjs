// triggers.mjs: the fake server's emulation of ../triggers.sql, trigger by trigger, in the same order of checks. It is a hand-written
// mirror (there is no MySQL here), so server.mjs also READS triggers.sql at start and warns when the two disagree about which
// triggers exist and which columns they set (see checkTriggerDrift in server.mjs). The comment above each trigger names the
// SQL trigger it mirrors; read them side by side. The tokens are the contract's `sl:` tokens (collab-contract.md section 1).
//
// What the fake does NOT model: lock waits (requests run one at a time, so "check the status after locking" is simply "check the
// status"), MySQL error 1442, deadlocks, and the exact collation folding (ids and statuses compare case-insensitively with
// trailing spaces ignored, like utf8mb4_unicode_ci, and nothing more).
//
// `db` is the facade server.mjs builds per write request (every change goes through it so a refusal can undo the whole statement):
//   db.rows(table)            live rows of a table (array)       db.byId(table, id)   one row by primary key, or null
//   db.insert(table, data)    insert (checks, foreign keys)      db.update(table, row, changes)
//   db.nowMs()                the fake clock in epoch ms         db.uuid()            a new UUID
//   db.user(id)               the users row, or null             db.pepper()          the code pepper, or null
//   db.refuse(token)          SIGNAL SQLSTATE '45000'            db.duplicate()       SIGNAL 23000 / 1062 (HTTP 409)
// `ctx` is {NEW, OLD, set(col, value), actor}: NEW is what the statement would store, set() assigns a NEW column.

import crypto from 'node:crypto';

const norm = (v) => String(v).toLowerCase().replace(/ +$/, '');
/** Case-insensitive id equality (utf8mb4_unicode_ci); false when either side is NULL. */
const ieq = (a, b) => a !== null && a !== undefined && b !== null && b !== undefined && norm(a) === norm(b);
/** MySQL's <=> on user ids: NULL equals NULL, numbers compare as numbers. */
const nsEq = (a, b) => {
  const x = a === null || a === undefined ? null : Number(a);
  const y = b === null || b === undefined ? null : Number(b);
  return x === y;
};
const MIN = 60 * 1000;
const HOUR = 60 * MIN;
const CODE_RE = /^[A-HJ-NP-Z2-9]{8}$/;
export const LOOKUP_FRESH_MS = 10 * MIN;
export const THROTTLES = {
  user_lookups: { max: 10, windowMs: HOUR },
  code_lookups_invalid: { max: 5, windowMs: 15 * MIN },
  list_invitations: { max: 30, windowMs: HOUR },
  join_requests: { max: 10, windowMs: HOUR },
};
export const LIMITS = { editors: 10, pendingInvitations: 20 };
export const INVITATION_TTL_MS = 7 * 24 * HOUR;
export const CODE_TTL_MS = 24 * HOUR;

const trim = (s) => (s === null || s === undefined ? null : String(s).replace(/^ +| +$/g, ''));

/** Mirror of the display_hint CASE in trg_user_lookups_bi: "Abhijith KM" -> "A. K.", "Ann" -> "A.", no name -> "?". */
export function displayHint(name) {
  const n = trim(name);
  if (n === null || [...n].length === 0) return '?';
  const first = [...n][0].toUpperCase();
  if (!n.includes(' ')) return `${first}.`;
  const last = n.split(' ').pop();
  return `${first}. ${[...last][0].toUpperCase()}.`;
}

/** Mirror of the guard on the pepper in the code triggers: missing, short or still the placeholder is sl:server_not_ready. */
function pepperOrRefuse(db) {
  const p = db.pepper();
  if (p === null || p === undefined || [...p].length < 32 || /^replace/i.test(p)) db.refuse('sl:server_not_ready');
  return p;
}

/** The format check of a join code: the regex, case-sensitive, AND an exact length (SQL: CHAR_LENGTH(code) = 8). */
function codeOrRefuse(db, raw) {
  // PHP binds every PDO parameter as a string, so a JSON number 23456789 reaches MySQL as '23456789' and is judged as such.
  const code = typeof raw === 'number' ? String(raw) : raw;
  if (code === null || code === undefined || typeof code !== 'string' || [...code].length !== 8 || !CODE_RE.test(code)) db.refuse('sl:invalid_code');
  return code;
}

const hashCode = (pepper, code) => crypto.createHash('sha256').update(pepper + code).digest('hex'); // SHA2(CONCAT(pepper, code), 256)

const isActiveMember = (db, listId, userId) =>
  db.rows('list_members').some((m) => ieq(m.list_id, listId) && Number(m.member_id) === Number(userId) && ['owner', 'editor'].includes(String(m.role).toLowerCase()));
const countIn = (rows, userId, since) => rows.filter((x) => Number(x.user_id) === Number(userId) && x.created_at_ms > since).length;
const editorCount = (db, listId) => db.rows('list_members').filter((m) => ieq(m.list_id, listId) && ieq(m.role, 'editor')).length;
const hasNewerCode = (db, code) =>
  db.rows('join_codes').some((n) => ieq(n.list_id, code.list_id) && (n.created_at_ms > code.created_at_ms || (n.created_at_ms === code.created_at_ms && norm(n.id) > norm(code.id))));

/** accept_invite / approve_join: upsert the editor row of `userId` in the list (a removed person's row is re-activated). */
function upsertEditor(db, listId, listOwner, userId, now) {
  const existing = db.rows('list_members').find((m) => ieq(m.list_id, listId) && Number(m.member_id) === Number(userId)) ?? null;
  if (existing && ['owner', 'editor'].includes(String(existing.role).toLowerCase())) db.refuse('sl:already_member');
  if (editorCount(db, listId) >= LIMITS.editors) db.refuse('sl:limit_reached');
  const name = db.user(userId)?.display_name ?? null;
  if (existing) db.update('list_members', existing, { role: 'editor', removal: null, display_name: name, created_at_ms: now });
  else db.insert('list_members', { id: db.uuid(), list_id: listId, user_id: listOwner, member_id: userId, role: 'editor', removal: null, display_name: name, created_at_ms: now });
}

/** After a member joins or leaves: close every other pending invitation / join request of that person for the list (SQL: the UPDATEs at the end of each branch). */
function closePending(db, listId, userId, { exceptInvitation = null, exceptRequest = null } = {}) {
  for (const inv of [...db.rows('list_invitations')]) {
    if (ieq(inv.list_id, listId) && Number(inv.invitee_id) === Number(userId) && inv.status === 'pending' && !(exceptInvitation !== null && ieq(inv.id, exceptInvitation))) {
      db.update('list_invitations', inv, { status: 'revoked' });
    }
  }
  for (const req of [...db.rows('join_requests')]) {
    if (ieq(req.list_id, listId) && Number(req.user_id) === Number(userId) && req.status === 'pending' && !(exceptRequest !== null && ieq(req.id, exceptRequest))) {
      db.update('join_requests', req, { status: 'cancelled' });
    }
  }
}

/** An editor may not write to a list its owner has deleted (the owner may: their unsent items still have to land). */
function refuseIfDeletedForEditor(db, listId, userId) {
  const list = db.byId('shopping_lists', listId);
  if (list && list.deleted_at_ms !== null && Number(list.user_id) !== Number(userId)) db.refuse('sl:not_found');
}

/** The live list row, or a refusal. `owner` (optional) is the user who must own it: someone else's list is sl:not_found, like an unknown one (no oracle). */
function liveListOrRefuse(db, listId, owner = null) {
  const list = listId === null || listId === undefined ? null : db.byId('shopping_lists', listId);
  if (!list) db.refuse('sl:not_found');
  if (owner !== null && Number(list.user_id) !== Number(owner)) db.refuse('sl:not_found');
  if (list.deleted_at_ms !== null) db.refuse('sl:not_found');
  return list;
}

export const TRIGGERS = [
  // trg_shopping_lists_ai: the creator gets their own list_members row, role owner.
  {
    name: 'trg_shopping_lists_ai',
    when: 'AFTER',
    event: 'INSERT',
    table: 'shopping_lists',
    run(db, { NEW }) {
      db.insert('list_members', {
        id: db.uuid(),
        list_id: NEW.id,
        user_id: NEW.user_id,
        member_id: NEW.user_id,
        role: 'owner',
        removal: null,
        display_name: db.user(NEW.user_id)?.display_name ?? null,
        created_at_ms: db.nowMs(),
      });
    },
  },

  // trg_shopping_items_bi: the caller must own the list or be an active editor; revision 1, updated_by, base_revision never stored.
  {
    name: 'trg_shopping_items_bi',
    when: 'BEFORE',
    event: 'INSERT',
    table: 'shopping_items',
    run(db, { NEW, set }) {
      const uid = Number(NEW.user_id);
      const owns = NEW.list_id !== undefined && db.rows('shopping_lists').some((l) => ieq(l.id, NEW.list_id) && Number(l.user_id) === uid);
      if (!(NEW.list_id !== undefined && isActiveMember(db, NEW.list_id, uid)) && !owns) db.refuse('sl:not_a_member');
      refuseIfDeletedForEditor(db, NEW.list_id, uid);
      set('revision', 1);
      set('updated_by', uid);
      set('base_revision', null);
    },
  },

  // trg_shopping_items_bu: list_id and user_id never change; the caller must still be an active member; base_revision mismatch is
  // sl:conflict; revision +1 on EVERY update (even one that changes nothing else); updated_by = the caller; base_revision -> NULL.
  {
    name: 'trg_shopping_items_bu',
    when: 'BEFORE',
    event: 'UPDATE',
    table: 'shopping_items',
    run(db, { NEW, OLD, set, actor }) {
      if (actor === null && !db.operator) db.refuse('sl:forbidden');
      if (!ieq(NEW.list_id, OLD.list_id) || Number(NEW.user_id) !== Number(OLD.user_id)) db.refuse('sl:forbidden');
      if (actor !== null) {
        const owns = db.rows('shopping_lists').some((l) => ieq(l.id, OLD.list_id) && Number(l.user_id) === actor);
        if (!isActiveMember(db, OLD.list_id, actor) && !owns) db.refuse('sl:not_a_member');
        refuseIfDeletedForEditor(db, OLD.list_id, actor);
        if (NEW.base_revision !== null && NEW.base_revision !== undefined && Number(NEW.base_revision) !== Number(OLD.revision)) db.refuse('sl:conflict');
      }
      set('revision', Number(OLD.revision) + 1);
      set('updated_by', actor);
      set('base_revision', null);
    },
  },

  // trg_user_lookups_bi: duplicate id -> 409; caller owns a live list (forbidden); 10 per hour (too_many_attempts); exact
  // case-insensitive email match on an ACTIVE user who is not the caller; found/not_found both stored; email nulled.
  {
    name: 'trg_user_lookups_bi',
    when: 'BEFORE',
    event: 'INSERT',
    table: 'user_lookups',
    run(db, { NEW, set }) {
      if (NEW.id !== undefined && db.byId('user_lookups', NEW.id)) db.duplicate();
      const uid = Number(NEW.user_id);
      const now = db.nowMs();
      if (!db.rows('shopping_lists').some((l) => Number(l.user_id) === uid && l.deleted_at_ms === null)) db.refuse('sl:forbidden');
      if (countIn(db.rows('user_lookups'), uid, now - THROTTLES.user_lookups.windowMs) >= THROTTLES.user_lookups.max) db.refuse('sl:too_many_attempts');
      const email = trim(NEW.email);
      let target = null;
      if (email !== null && email.length > 0) {
        target = db.rows('users').find((u) => String(u.email).toLowerCase() === email.toLowerCase() && Number(u.id) !== uid && String(u.status) === 'active') ?? null;
      }
      if (target === null) {
        set('status', 'not_found');
        set('display_hint', null);
        set('target_id', null);
      } else {
        set('status', 'found');
        set('display_hint', displayHint(target.display_name));
        set('target_id', Number(target.id));
      }
      set('created_at_ms', now);
      set('email', null);
    },
  },

  // trg_list_invitations_bi: list owned by the caller and live; lookup the caller's, found, fresh (10 min); 30 per hour; target not a
  // member, no live pending invitation, fewer than 20 pending and 10 editors; fills invitee, role, status, names, hint, expiry.
  {
    name: 'trg_list_invitations_bi',
    when: 'BEFORE',
    event: 'INSERT',
    table: 'list_invitations',
    run(db, { NEW, set }) {
      if (NEW.id !== undefined && db.byId('list_invitations', NEW.id)) db.duplicate();
      const uid = Number(NEW.user_id);
      const now = db.nowMs();
      const list = liveListOrRefuse(db, NEW.list_id, uid);
      const lk = NEW.lookup_id === undefined ? null : db.byId('user_lookups', NEW.lookup_id);
      if (!lk || Number(lk.user_id) !== uid || lk.status !== 'found' || lk.target_id === null) db.refuse('sl:not_found');
      if (lk.created_at_ms < now - LOOKUP_FRESH_MS) db.refuse('sl:expired');
      if (countIn(db.rows('list_invitations'), uid, now - THROTTLES.list_invitations.windowMs) >= THROTTLES.list_invitations.max) db.refuse('sl:too_many_attempts');
      const target = Number(lk.target_id);
      if (isActiveMember(db, list.id, target)) db.refuse('sl:already_member');
      const live = db.rows('list_invitations').filter((i) => ieq(i.list_id, list.id) && i.status === 'pending' && i.expires_at > db.now());
      if (live.some((i) => Number(i.invitee_id) === target)) db.refuse('sl:already_pending');
      if (live.length >= LIMITS.pendingInvitations) db.refuse('sl:limit_reached');
      if (editorCount(db, list.id) >= LIMITS.editors) db.refuse('sl:limit_reached');
      set('invitee_id', target);
      set('role', 'editor');
      set('status', 'pending');
      set('list_name', list.name);
      set('inviter_name', db.user(uid)?.display_name ?? null);
      set('display_hint', lk.display_hint);
      set('expires_at', db.now() + INVITATION_TTL_MS);
      set('created_at_ms', now);
    },
  },

  // trg_join_codes_bi: format first (sl:invalid_code), then the pepper (sl:server_not_ready), the list (owner, live), then the hash.
  // No supersede UPDATE (MySQL error 1442): an older code is superseded by derivation, see trg_code_lookups_bi.
  {
    name: 'trg_join_codes_bi',
    when: 'BEFORE',
    event: 'INSERT',
    table: 'join_codes',
    run(db, { NEW, set }) {
      if (NEW.id !== undefined && db.byId('join_codes', NEW.id)) db.duplicate();
      const code = codeOrRefuse(db, NEW.code);
      const pepper = pepperOrRefuse(db);
      liveListOrRefuse(db, NEW.list_id, Number(NEW.user_id));
      set('code_hash', hashCode(pepper, code));
      set('status', 'active');
      set('expires_at', db.now() + CODE_TTL_MS);
      set('created_at_ms', db.nowMs());
      set('code', null);
    },
  },

  // trg_code_lookups_bi: format (not stored), pepper, throttle (5 stored invalid in 15 min), hash, match an active, unexpired, newest
  // code of a live list; one `invalid` outcome for everything else. The code is nulled.
  {
    name: 'trg_code_lookups_bi',
    when: 'BEFORE',
    event: 'INSERT',
    table: 'code_lookups',
    run(db, { NEW, set }) {
      if (NEW.id !== undefined && db.byId('code_lookups', NEW.id)) db.duplicate();
      const code = codeOrRefuse(db, NEW.code);
      const pepper = pepperOrRefuse(db);
      const uid = Number(NEW.user_id);
      const now = db.nowMs();
      const invalid = db.rows('code_lookups').filter((x) => Number(x.user_id) === uid && x.status === 'invalid' && x.created_at_ms > now - THROTTLES.code_lookups_invalid.windowMs);
      if (invalid.length >= THROTTLES.code_lookups_invalid.max) db.refuse('sl:too_many_attempts');
      const hash = hashCode(pepper, code);
      const match =
        db
          .rows('join_codes')
          .filter((c) => c.code_hash === hash && c.status === 'active' && c.expires_at > db.now() && !hasNewerCode(db, c))
          .sort((a, b) => b.created_at_ms - a.created_at_ms)[0] ?? null;
      const list = match ? db.byId('shopping_lists', match.list_id) : null;
      if (match && list && list.deleted_at_ms === null) {
        set('status', 'found');
        set('list_name', list.name);
        set('inviter_name', db.user(list.user_id)?.display_name ?? null);
        set('code_id', match.id);
      } else {
        set('status', 'invalid');
        set('list_name', null);
        set('inviter_name', null);
        set('code_id', null);
      }
      set('created_at_ms', now);
      set('code', null);
    },
  },

  // trg_join_requests_bi: lookup the caller's (not_found), found (invalid_code), fresh (expired); 10 per hour; the code judged after
  // its lock (active and newest: invalid_code; unexpired: expired; list live); caller not a member, no pending request; then the
  // code is CONSUMED (used) and the row filled.
  {
    name: 'trg_join_requests_bi',
    when: 'BEFORE',
    event: 'INSERT',
    table: 'join_requests',
    run(db, { NEW, set }) {
      if (NEW.id !== undefined && db.byId('join_requests', NEW.id)) db.duplicate();
      const uid = Number(NEW.user_id);
      const now = db.nowMs();
      const lk = NEW.lookup_id === undefined ? null : db.byId('code_lookups', NEW.lookup_id);
      if (!lk || Number(lk.user_id) !== uid) db.refuse('sl:not_found');
      if (lk.status !== 'found' || lk.code_id === null) db.refuse('sl:invalid_code');
      if (lk.created_at_ms < now - LOOKUP_FRESH_MS) db.refuse('sl:expired');
      if (countIn(db.rows('join_requests'), uid, now - THROTTLES.join_requests.windowMs) >= THROTTLES.join_requests.max) db.refuse('sl:too_many_attempts');
      const code = db.byId('join_codes', lk.code_id);
      if (!code || code.status !== 'active') db.refuse('sl:invalid_code');
      if (code.expires_at === null || code.expires_at <= db.now()) db.refuse('sl:expired');
      if (hasNewerCode(db, code)) db.refuse('sl:invalid_code');
      const list = db.byId('shopping_lists', code.list_id);
      if (!list || list.deleted_at_ms !== null) db.refuse('sl:invalid_code');
      if (isActiveMember(db, list.id, uid)) db.refuse('sl:already_member');
      if (db.rows('join_requests').some((r) => Number(r.user_id) === uid && ieq(r.list_id, list.id) && r.status === 'pending')) db.refuse('sl:already_pending');
      db.update('join_codes', code, { status: 'used' });
      set('owner_id', Number(list.user_id));
      set('list_id', list.id);
      set('list_name', list.name);
      set('requester_name', db.user(uid)?.display_name ?? null);
      set('status', 'pending');
      set('created_at_ms', now);
    },
  },

  // trg_list_commands_bi: the nine commands. Same order of checks as the SQL: find -> right actor (forbidden) -> status
  // (not_pending) -> expiry -> list live -> already a member -> limit -> the changes. Unknown command: sl:forbidden.
  {
    name: 'trg_list_commands_bi',
    when: 'BEFORE',
    event: 'INSERT',
    table: 'list_commands',
    run(db, { NEW, set, actor }) {
      if (NEW.id !== undefined && db.byId('list_commands', NEW.id)) db.duplicate();
      const me = Number(NEW.user_id);
      // Fail closed: no actor is refused, like trg_shopping_items_bu.
      if (actor === null || actor !== me) db.refuse('sl:forbidden');
      const now = db.nowMs();
      const target = NEW.target_id;
      const find = (table) => {
        const row = target === undefined || target === null ? null : db.byId(table, target);
        if (!row) db.refuse('sl:not_found');
        return row;
      };
      // A row that is not the actor's to see answers sl:not_found, the same as an unknown id (no existence oracle).
      switch (NEW.command) {
        case 'accept_invite': {
          const inv = find('list_invitations');
          if (!nsEq(inv.invitee_id, me)) db.refuse('sl:not_found');
          if (inv.status !== 'pending') db.refuse('sl:not_pending');
          if (inv.expires_at === null || inv.expires_at <= db.now()) db.refuse('sl:expired');
          const list = liveListOrRefuse(db, inv.list_id);
          upsertEditor(db, list.id, list.user_id, me, now);
          db.update('list_invitations', inv, { status: 'accepted' });
          closePending(db, list.id, me, { exceptInvitation: inv.id });
          break;
        }
        case 'reject_invite': {
          const inv = find('list_invitations');
          if (!nsEq(inv.invitee_id, me)) db.refuse('sl:not_found');
          if (inv.status !== 'pending') db.refuse('sl:not_pending');
          db.update('list_invitations', inv, { status: 'rejected' });
          break;
        }
        case 'revoke_invite': {
          const inv = find('list_invitations');
          if (!nsEq(inv.user_id, me)) db.refuse('sl:not_found');
          if (inv.status !== 'pending') db.refuse('sl:not_pending');
          db.update('list_invitations', inv, { status: 'revoked' });
          break;
        }
        case 'approve_join': {
          const req = find('join_requests');
          if (!nsEq(req.owner_id, me)) db.refuse('sl:not_found');
          if (req.status !== 'pending') db.refuse('sl:not_pending');
          const list = liveListOrRefuse(db, req.list_id);
          if (Number(list.user_id) !== me) db.refuse('sl:forbidden');
          upsertEditor(db, list.id, list.user_id, Number(req.user_id), now);
          db.update('join_requests', req, { status: 'approved' });
          closePending(db, list.id, Number(req.user_id), { exceptRequest: req.id });
          break;
        }
        case 'reject_join': {
          const req = find('join_requests');
          if (!nsEq(req.owner_id, me)) db.refuse('sl:not_found');
          if (req.status !== 'pending') db.refuse('sl:not_pending');
          db.update('join_requests', req, { status: 'rejected' });
          break;
        }
        case 'cancel_request': {
          const req = find('join_requests');
          if (!nsEq(req.user_id, me)) db.refuse('sl:not_found');
          if (req.status !== 'pending') db.refuse('sl:not_pending');
          db.update('join_requests', req, { status: 'cancelled' });
          break;
        }
        case 'revoke_code': {
          const code = find('join_codes');
          if (!nsEq(code.user_id, me)) db.refuse('sl:not_found');
          if (code.status !== 'active') db.refuse('sl:not_pending');
          db.update('join_codes', code, { status: 'revoked' });
          break;
        }
        case 'remove_member':
        case 'leave_list': {
          const m = find('list_members');
          const leaving = NEW.command === 'leave_list';
          if (leaving ? !nsEq(m.member_id, me) : !nsEq(m.user_id, me)) {
            // A member of that list is told "forbidden"; a stranger only learns "not found".
            db.refuse(isActiveMember(db, m.list_id, me) ? 'sl:forbidden' : 'sl:not_found');
          }
          if (m.role === 'owner') db.refuse('sl:forbidden');
          if (m.role !== 'editor') db.refuse('sl:not_a_member');
          // DEVIATION (see triggers.sql): the leaving person's items are re-owned to the list owner, because AppHub's shared scope
          // always lets a row's owner through. The real trigger sets @sl_transfer so trg_shopping_items_bu allows exactly this.
          for (const it of db.rows('shopping_items')) {
            if (ieq(it.list_id, m.list_id) && Number(it.user_id) === Number(m.member_id)) db.update('shopping_items', it, { user_id: m.user_id });
          }
          db.update('list_members', m, { role: 'removed', removal: leaving ? 'left' : 'removed' });
          closePending(db, m.list_id, Number(m.member_id));
          break;
        }
        default:
          db.refuse('sl:forbidden');
      }
      set('created_at_ms', now);
    },
  },
];

/** The triggers for one (when, event, table); at most one each, as in triggers.sql. */
export function triggersFor(when, event, table) {
  return TRIGGERS.filter((t) => t.when === when && t.event === event && t.table === table);
}
