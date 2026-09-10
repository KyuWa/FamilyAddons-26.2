// FamilyAddons name changer worker — shared custom display names, with owner approval.
//
// Mod endpoints (header X-FA-Secret: <FA_SECRET>, the token shipped in the jar):
//   PUT    /name    {"uuid","username","name"}  -> pending review (owner's own: approved at once)
//   DELETE /name    {"uuid"}                    -> remove own approved + pending
//   GET    /names                               -> {"username_lower": "template", ...} (approved only)
//   GET    /status?uuid=                        -> {"status":"pending"|"approved"|"denied"|"revoked"|"set"|"none", name, at}
//
// Owner endpoints (header X-Admin-Key: <ADMIN_KEY>):
//   GET    /pending                             -> [{uuid, username, name, at}]
//   POST   /review  {"uuid","approve":true|false}
//   GET    /approved                            -> [{uuid, username, name, approvedAt}]
//   POST   /revoke  {"uuid"}                    -> take an approved name down (player is told in game)
//   POST   /set     {"username","name"}         -> give a player a name directly, no approval step (IGN resolved via Mojang)
//   GET    /review?uuid=&action=approve|deny&sig=   (signed links, webhook mode)
//   POST   /discord                             (Discord interaction endpoint, bot mode)
//
// Storage (KV binding NAMES):
//   a:<uuid>  approved {uuid, username, name, at}
//   p:<uuid>  pending  {uuid, username, name, at}
//   rl:<uuid> "1" with RL_TTL_S ttl — one submission per window per player
//   d:<uuid>  last decision {approved, username, name, at} kept DECISION_TTL_S so the player is told in game
//
// Secrets: FA_SECRET, ADMIN_KEY, plus one of two Discord notification modes:
//   - bot mode (real buttons): DISCORD_BOT_TOKEN, DISCORD_PUBLIC_KEY, DISCORD_CHANNEL_ID, and the
//     application's Interactions Endpoint URL set to <worker>/discord. Clicking Approve / Deny
//     edits the message in place.
//   - webhook mode (fallback): DISCORD_WEBHOOK; the post carries signed Approve / Deny links.
//
// Deploy (from this folder):
//   npx wrangler kv namespace create NAMES -c names.wrangler.toml   (id -> names.wrangler.toml)
//   npx wrangler secret put FA_SECRET       -c names.wrangler.toml
//   npx wrangler secret put ADMIN_KEY       -c names.wrangler.toml
//   npx wrangler secret put DISCORD_WEBHOOK -c names.wrangler.toml
//   npx wrangler deploy -c names.wrangler.toml

const OWNER_UUID = "305bcf8c-a93d-4d52-9e8c-b925e8d25682";
const MAX_VISIBLE = 24;
const MAX_RAW = 200;
const RL_TTL_S = 600;
const DECISION_TTL_S = 30 * 24 * 3600;
const MAX_BODY = 2048; // a submission is ~300 bytes; refuse anything bigger before even reading it
const UUID_RE = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;

function json(obj, status = 200, extra = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
  });
}

function normUuid(u) {
  const hex = String(u).replace(/-/g, "").toLowerCase();
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Visible characters of a template: tags and colour codes removed. */
function visibleText(t) {
  return String(t)
    .replace(/<\/?(gradient|wave|rainbow)(:[^>]*)?>/gi, "")
    .replace(/<#[0-9a-fA-F]{6}>/g, "")
    .replace(/[&§][0-9a-fk-orA-FK-OR]/g, "");
}

function validName(t) {
  if (typeof t !== "string") return "not a string";
  if (t.length > MAX_RAW) return `template longer than ${MAX_RAW} characters`;
  for (const ch of t) if (ch.charCodeAt(0) < 32 || ch.charCodeAt(0) === 127) return "control characters are not allowed";
  const vis = visibleText(t);
  if (vis.trim().length === 0) return "nothing visible";
  if (vis.length > MAX_VISIBLE) return `visible text longer than ${MAX_VISIBLE} characters (${vis.length})`;
  return null;
}

async function hmac(secret, msg) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(msg));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

async function listAll(kv, prefix) {
  const keys = [];
  let cursor;
  do {
    const page = await kv.list({ prefix, cursor });
    keys.push(...page.keys.map((k) => k.name));
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return keys;
}

async function approvedMap(env) {
  const out = {};
  for (const k of await listAll(env.NAMES, "a:")) {
    const rec = await env.NAMES.get(k, "json");
    if (rec && rec.username && rec.name) out[String(rec.username).toLowerCase()] = rec.name;
  }
  return out;
}

/**
 * IGN -> {uuid, username}. Mojang's profile API often refuses requests coming
 * from Cloudflare's IP space, so PlayerDB is tried when Mojang does not answer 200.
 */
async function resolveIgn(ign) {
  const headers = { "User-Agent": "FamilyAddons-names-worker (github.com/KyuWa)" };
  const tried = [];
  try {
    const r = await fetch("https://api.mojang.com/users/profiles/minecraft/" + encodeURIComponent(ign), { headers });
    if (r.status === 200) {
      const m = await r.json();
      if (UUID_RE.test(m.id || "")) return { ok: true, uuid: normUuid(m.id), username: m.name };
    }
    if (r.status === 404) return { ok: false, error: "no Minecraft account called " + ign };
    tried.push("mojang " + r.status);
  } catch (e) { tried.push("mojang " + e.message); }
  try {
    const r = await fetch("https://playerdb.co/api/player/minecraft/" + encodeURIComponent(ign), { headers });
    if (r.status === 200) {
      const m = await r.json();
      const p = m && m.data && m.data.player;
      if (p && UUID_RE.test(p.id || "")) return { ok: true, uuid: normUuid(p.id), username: p.username };
      if (m && m.code === "minecraft.invalid_username") return { ok: false, error: "no Minecraft account called " + ign };
    }
    tried.push("playerdb " + r.status);
  } catch (e) { tried.push("playerdb " + e.message); }
  return { ok: false, error: "could not look up " + ign + " (" + tried.join(", ") + ")" };
}

async function applyReview(env, uuid, approve) {
  const pending = await env.NAMES.get("p:" + uuid, "json");
  if (!pending) return { ok: false, error: "nothing pending for that uuid" };
  await env.NAMES.delete("p:" + uuid);
  const now = Date.now();
  if (approve) await env.NAMES.put("a:" + uuid, JSON.stringify({ ...pending, approvedAt: now }));
  await env.NAMES.put("d:" + uuid, JSON.stringify({ approved: !!approve, username: pending.username, name: pending.name, at: now }), { expirationTtl: DECISION_TTL_S });
  return { ok: true, username: pending.username, name: pending.name, approved: !!approve };
}

// ── Discord ─────────────────────────────────────────────────────────────

function requestEmbed(rec) {
  const safe = rec.name.replace(/`/g, "'");
  return {
    title: `Name request from ${rec.username}`,
    description: "```\n" + safe + "\n```\nVisible: `" + visibleText(rec.name).replace(/`/g, "'") + "`",
    color: 0xC86EFF,
    footer: { text: rec.uuid },
  };
}

/** Bot mode: post with real buttons. Returns true when it posted. */
async function notifyDiscordBot(env, rec) {
  if (!env.DISCORD_BOT_TOKEN || !env.DISCORD_CHANNEL_ID) return false;
  const body = {
    embeds: [requestEmbed(rec)],
    components: [{
      type: 1,
      components: [
        { type: 2, style: 3, label: "Approve", custom_id: `approve:${rec.uuid}` },
        { type: 2, style: 4, label: "Deny", custom_id: `deny:${rec.uuid}` },
      ],
    }],
  };
  try {
    const r = await fetch(`https://discord.com/api/v10/channels/${env.DISCORD_CHANNEL_ID}/messages`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bot ${env.DISCORD_BOT_TOKEN}` },
      body: JSON.stringify(body),
    });
    return r.ok;
  } catch (e) { return false; }
}

/** Webhook mode: post with signed approve / deny links. */
async function notifyDiscordWebhook(env, rec, baseUrl) {
  if (!env.DISCORD_WEBHOOK || !env.ADMIN_KEY) return;
  const approveSig = await hmac(env.ADMIN_KEY, `${rec.uuid}:approve:${rec.name}`);
  const denySig = await hmac(env.ADMIN_KEY, `${rec.uuid}:deny:${rec.name}`);
  const approve = `${baseUrl}/review?uuid=${rec.uuid}&action=approve&sig=${approveSig}`;
  const deny = `${baseUrl}/review?uuid=${rec.uuid}&action=deny&sig=${denySig}`;
  const body = {
    username: "FamilyAddons names",
    embeds: [{
      ...requestEmbed(rec),
      fields: [
        { name: "Approve", value: `[click](${approve})`, inline: true },
        { name: "Deny", value: `[click](${deny})`, inline: true },
      ],
    }],
  };
  try {
    await fetch(env.DISCORD_WEBHOOK, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  } catch (e) { /* the in-game /fa names path still works */ }
}

async function notifyDiscord(env, rec, baseUrl) {
  if (await notifyDiscordBot(env, rec)) return;
  await notifyDiscordWebhook(env, rec, baseUrl);
}

/** Discord interaction endpoint: verifies the Ed25519 signature, answers PINGs, handles button clicks. */
async function handleInteraction(request, env) {
  if (!env.DISCORD_PUBLIC_KEY) return new Response("bot mode not configured", { status: 501 });
  const sig = request.headers.get("x-signature-ed25519") || "";
  const ts = request.headers.get("x-signature-timestamp") || "";
  const bodyText = await request.text();
  let ok = false;
  try {
    const key = await crypto.subtle.importKey("raw", hexToBytes(env.DISCORD_PUBLIC_KEY), { name: "Ed25519" }, false, ["verify"]);
    ok = await crypto.subtle.verify("Ed25519", key, hexToBytes(sig), new TextEncoder().encode(ts + bodyText));
  } catch (e) { ok = false; }
  if (!ok) return new Response("bad signature", { status: 401 });

  const it = JSON.parse(bodyText);
  if (it.type === 1) return json({ type: 1 }); // PING
  if (it.type === 3) { // MESSAGE_COMPONENT (button)
    const [action, uuid] = String((it.data && it.data.custom_id) || "").split(":");
    if (!["approve", "deny"].includes(action) || !UUID_RE.test(uuid || "")) {
      return json({ type: 4, data: { content: "Bad button", flags: 64 } });
    }
    const r = await applyReview(env, normUuid(uuid), action === "approve");
    const who = (it.member && it.member.user && it.member.user.username) || (it.user && it.user.username) || "someone";
    const embed = (it.message && it.message.embeds && it.message.embeds[0]) || {};
    const verdict = r.ok ? (r.approved ? `✅ Approved by ${who}` : `❌ Denied by ${who}`) : `⚠️ ${r.error}`;
    return json({
      type: 7, // UPDATE_MESSAGE: swap the buttons for the result
      data: {
        embeds: [{ ...embed, color: r.ok ? (r.approved ? 0x57F287 : 0xED4245) : 0xFEE75C, fields: [{ name: "Result", value: verdict }] }],
        components: [],
      },
    });
  }
  return json({ type: 4, data: { content: "Unsupported interaction", flags: 64 } });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Discord interactions (button clicks) — verified by signature inside.
    if (url.pathname === "/discord" && request.method === "POST") {
      const len = Number(request.headers.get("content-length") || "0");
      if (len > 65536) return new Response("too large", { status: 413 });
      return handleInteraction(request, env);
    }
    // Size gate for every other body-carrying request: never parse a large payload.
    if (request.method === "PUT" || request.method === "POST") {
      const len = Number(request.headers.get("content-length") || "0");
      if (!len || len > MAX_BODY) return json({ error: "body too large or missing length" }, 413);
    }

    const baseUrl = `${url.protocol}//${url.host}`;
    const modAuth = env.FA_SECRET && request.headers.get("X-FA-Secret") === env.FA_SECRET;
    const adminAuth = env.ADMIN_KEY && request.headers.get("X-Admin-Key") === env.ADMIN_KEY;

    // ── Mod: submit ─────────────────────────────────────────────────────
    if (url.pathname === "/name" && request.method === "PUT") {
      if (!modAuth) return json({ error: "unauthorized" }, 401);
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad json" }, 400); }
      if (!body || !UUID_RE.test(body.uuid || "") || !NAME_RE.test(body.username || "")) return json({ error: "bad uuid/username" }, 400);
      const uuid = normUuid(body.uuid);
      const why = validName(body.name);
      if (why) return json({ error: why }, 400);

      const rec = { uuid, username: body.username, name: body.name, at: Date.now() };
      if (uuid === OWNER_UUID) {
        await env.NAMES.put("a:" + uuid, JSON.stringify({ ...rec, approvedAt: Date.now() }));
        await env.NAMES.delete("p:" + uuid);
        return json({ ok: true, status: "approved" });
      }
      if (await env.NAMES.get("rl:" + uuid)) return json({ error: "you can submit once every 10 minutes" }, 429);
      await env.NAMES.put("rl:" + uuid, "1", { expirationTtl: RL_TTL_S });
      await env.NAMES.put("p:" + uuid, JSON.stringify(rec));
      await notifyDiscord(env, rec, baseUrl);
      return json({ ok: true, status: "pending" });
    }

    // ── Mod: remove own ─────────────────────────────────────────────────
    if (url.pathname === "/name" && request.method === "DELETE") {
      if (!modAuth) return json({ error: "unauthorized" }, 401);
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad json" }, 400); }
      if (!body || !UUID_RE.test(body.uuid || "")) return json({ error: "bad uuid" }, 400);
      const uuid = normUuid(body.uuid);
      await env.NAMES.delete("a:" + uuid);
      await env.NAMES.delete("p:" + uuid);
      await env.NAMES.delete("d:" + uuid);
      return json({ ok: true });
    }

    // ── Mod: where is my request? ───────────────────────────────────────
    if (url.pathname === "/status" && request.method === "GET") {
      if (!modAuth) return json({ error: "unauthorized" }, 401);
      const q = url.searchParams.get("uuid") || "";
      if (!UUID_RE.test(q)) return json({ error: "bad uuid" }, 400);
      const uuid = normUuid(q);
      const pending = await env.NAMES.get("p:" + uuid, "json");
      if (pending) return json({ status: "pending", name: pending.name, at: pending.at });
      const d = await env.NAMES.get("d:" + uuid, "json");
      if (d) return json({ status: d.revoked ? "revoked" : d.set ? "set" : d.approved ? "approved" : "denied", name: d.name, at: d.at });
      return json({ status: "none" });
    }

    // ── Mod: everyone's approved names ──────────────────────────────────
    if (url.pathname === "/names" && request.method === "GET") {
      if (!modAuth) return json({ error: "unauthorized" }, 401);
      return json(await approvedMap(env), 200, { "cache-control": "public, max-age=60" });
    }

    // ── Owner: pending list / review ────────────────────────────────────
    if (url.pathname === "/pending" && request.method === "GET") {
      if (!adminAuth) return json({ error: "unauthorized" }, 401);
      const out = [];
      for (const k of await listAll(env.NAMES, "p:")) {
        const rec = await env.NAMES.get(k, "json");
        if (rec) out.push(rec);
      }
      out.sort((a, b) => a.at - b.at);
      return json(out);
    }

    if (url.pathname === "/approved" && request.method === "GET") {
      if (!adminAuth) return json({ error: "unauthorized" }, 401);
      const out = [];
      for (const k of await listAll(env.NAMES, "a:")) {
        const rec = await env.NAMES.get(k, "json");
        if (rec) out.push(rec);
      }
      out.sort((a, b) => String(a.username).localeCompare(String(b.username)));
      return json(out);
    }

    if (url.pathname === "/set" && request.method === "POST") {
      if (!adminAuth) return json({ error: "unauthorized" }, 401);
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad json" }, 400); }
      if (!body || !NAME_RE.test(body.username || "")) return json({ error: "bad username" }, 400);
      const why = validName(body.name);
      if (why) return json({ ok: false, error: why });
      // The owner types an IGN, not a uuid: resolve it.
      const found = await resolveIgn(body.username);
      if (!found.ok) return json({ ok: false, error: found.error });
      const uuid = found.uuid, username = found.username;
      const now = Date.now();
      await env.NAMES.put("a:" + uuid, JSON.stringify({ uuid, username, name: body.name, at: now, approvedAt: now }));
      await env.NAMES.delete("p:" + uuid);
      await env.NAMES.put("d:" + uuid, JSON.stringify({ approved: true, set: true, username, name: body.name, at: now }), { expirationTtl: DECISION_TTL_S });
      return json({ ok: true, uuid, username, name: body.name });
    }

    if (url.pathname === "/revoke" && request.method === "POST") {
      if (!adminAuth) return json({ error: "unauthorized" }, 401);
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad json" }, 400); }
      if (!body || !UUID_RE.test(body.uuid || "")) return json({ error: "bad uuid" }, 400);
      const uuid = normUuid(body.uuid);
      const rec = await env.NAMES.get("a:" + uuid, "json");
      if (!rec) return json({ ok: false, error: "no approved name for that uuid" });
      await env.NAMES.delete("a:" + uuid);
      await env.NAMES.put("d:" + uuid, JSON.stringify({ approved: false, revoked: true, username: rec.username, name: rec.name, at: Date.now() }), { expirationTtl: DECISION_TTL_S });
      return json({ ok: true, username: rec.username, name: rec.name });
    }

    if (url.pathname === "/review" && request.method === "POST") {
      if (!adminAuth) return json({ error: "unauthorized" }, 401);
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad json" }, 400); }
      if (!body || !UUID_RE.test(body.uuid || "")) return json({ error: "bad uuid" }, 400);
      return json(await applyReview(env, normUuid(body.uuid), !!body.approve));
    }

    // ── Owner: signed link from the webhook post ────────────────────────
    if (url.pathname === "/review" && request.method === "GET") {
      const uuid = url.searchParams.get("uuid") || "";
      const action = url.searchParams.get("action") || "";
      const sig = url.searchParams.get("sig") || "";
      if (!UUID_RE.test(uuid) || !["approve", "deny"].includes(action) || !env.ADMIN_KEY) {
        return new Response("Bad link", { status: 400 });
      }
      const pending = await env.NAMES.get("p:" + normUuid(uuid), "json");
      if (!pending) return new Response("Nothing pending for that player (already reviewed?)", { status: 404 });
      const expected = await hmac(env.ADMIN_KEY, `${pending.uuid}:${action}:${pending.name}`);
      if (sig !== expected) return new Response("Bad signature", { status: 403 });
      const r = await applyReview(env, pending.uuid, action === "approve");
      const text = r.ok ? `${r.approved ? "Approved" : "Denied"}: ${r.username} -> ${r.name}` : r.error;
      return new Response(text, { status: 200, headers: { "content-type": "text/plain; charset=utf-8" } });
    }

    return json({ error: "not found" }, 404);
  },
};
