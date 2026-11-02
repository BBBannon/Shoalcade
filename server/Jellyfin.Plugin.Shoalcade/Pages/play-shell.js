/*
 * Shoalcade player page, /shoalcade/play?id=<itemId> (security review M3, docs/plan-player-sandbox.md).
 *
 * This page is the trusted shell: it is the ONLY part of the player that holds the Jellyfin login. The game itself
 * (play.js, play-ui.js and EmulatorJS, ~1 MB of third-party code) runs in a sandboxed frame (sandbox="allow-scripts"
 * and no other token), so it has an opaque origin: it can't read the login, storage or cookies, call the server,
 * open windows or move this page. It asks this page instead, and this page only does:
 *  - server calls on THIS game's routes (the id comes from this page's own address, never from the frame);
 *  - this game's download (the bytes are moved into the frame);
 *  - EmulatorJS's two unzip workers (their own eval-only policy; a worker can't be started from an opaque origin);
 *  - going to a fixed list of places (library, Jellyfin home, saves, sign in, reload);
 *  - saving a screenshot (PNG only).
 * Saves are precious: the frame keeps this page told about any save not yet on the server (the "close-time packet"),
 * and this page sends it with keepalive when the tab closes, because the frame's own last messages may never run then.
 * It also asks "Leave anyway?" when that save hasn't reached the server (D118): a sandboxed frame can't.
 */
(function () {
  "use strict";

  const api = window.SC.api;
  const params = new URLSearchParams(location.search);
  const rawId = params.get("id") || "";
  const G = rawId.replace(/-/g, "").toLowerCase();
  const ID_OK = /^[0-9a-f]{32}$/.test(G);
  const EJS_DIR = api.BASE + "/shoalcade/ejs/";
  const KEEPALIVE_MAX = 60000;            // browsers only send small bodies while a page is closing (as play.js)
  const BODY_MAX = 130 * 1024 * 1024;     // above every upload cap the server has
  const PICTURE_MAX = 50 * 1024 * 1024;
  const CONTENT_TYPES = ["application/octet-stream", "application/json", "image/png"];

  // ---------------------------------------------------------------------------------------------
  // What the frame may ask for. {G} is this page's game; everything else is refused (403) and never sent.
  // Prefs scopes and cheat ids are checked again by the server (scopes: console keys or "global").
  // ---------------------------------------------------------------------------------------------
  const Q = {
    base: /^\d{1,9}$/, mode: /^(normal|force|history)$/, source: /^browser$/, afterSnapshot: /^true$/, cheats: /^true$/,
    core: /^[a-z0-9_-]{1,40}$/,
  };
  const SLOT = "(?:1|2|3|auto)";
  const ROUTES = [
    ["/shoalcade/info", ["GET"]],
    ["/shoalcade/games/{G}", ["GET"]],
    ["/shoalcade/games/{G}/playtime", ["POST"]],
    ["/shoalcade/prefs/[a-z0-9]{1,16}", ["GET", "PUT"]],
    ["/shoalcade/save/{G}", ["GET"]],
    ["/shoalcade/save/{G}", ["PUT"], ["base", "mode", "source", "afterSnapshot", "cheats"]],
    ["/shoalcade/save/{G}/history", ["GET"]],
    ["/shoalcade/state/{G}", ["GET"]],
    ["/shoalcade/state/{G}/" + SLOT, ["GET", "DELETE"]],
    ["/shoalcade/state/{G}/" + SLOT, ["PUT"], ["core"]],
    ["/shoalcade/state/{G}/" + SLOT + "/image", ["GET", "PUT"]],
    ["/shoalcade/state/{G}/" + SLOT + "/cheats", ["PUT"]],
    ["/shoalcade/cheats/{G}", ["GET", "POST"]],
    ["/shoalcade/cheats/{G}/active", ["PUT"]],
    ["/shoalcade/cheats/{G}/[0-9a-f]{12}", ["PATCH", "DELETE"]],
  ].map(([p, methods, query]) => ({ re: new RegExp("^" + p.replace(/\{G\}/g, G) + "$"), methods, query: query || [] }));

  /** The checked request, or null if the frame may not make it. */
  function allowed(m) {
    if (!ID_OK || typeof m.path !== "string" || m.path.length > 600 || typeof m.method !== "string") return null;
    const method = m.method.toUpperCase();
    const at = m.path.indexOf("?");
    const path = at < 0 ? m.path : m.path.slice(0, at);
    const route = ROUTES.find(r => r.methods.includes(method) && r.re.test(path));
    if (!route) return null;
    const out = new URLSearchParams();
    for (const [k, v] of new URLSearchParams(at < 0 ? "" : m.path.slice(at + 1))) {
      if (!route.query.includes(k) || out.has(k) || !Q[k].test(v)) return null;
      out.set(k, v);
    }
    const body = m.body === undefined ? null : m.body;
    let size = 0;
    if (body !== null) {
      if (method === "GET" || method === "DELETE") return null;
      if (typeof body === "string") size = body.length * 3;
      else if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) size = body.byteLength;
      else if (body instanceof Blob) size = body.size;
      else return null;
    }
    if (size > BODY_MAX) return null;
    const headers = {};
    if (m.contentType !== null && m.contentType !== undefined) {
      if (!CONTENT_TYPES.includes(m.contentType)) return null;
      headers["Content-Type"] = m.contentType;
    }
    const qs = out.toString();
    return { method, path: path + (qs ? "?" + qs : ""), headers, body: body === null ? undefined : body, keepalive: m.keepalive === true && size <= KEEPALIVE_MAX };
  }

  // ---------------------------------------------------------------------------------------------
  // The frame
  // ---------------------------------------------------------------------------------------------
  const frame = document.createElement("iframe");
  frame.id = "sc-frame";
  frame.title = "Game player";
  frame.setAttribute("sandbox", "allow-scripts");
  frame.setAttribute("allow", "gamepad *; fullscreen *; autoplay *; screen-wake-lock *");
  const fq = new URLSearchParams();
  fq.set("id", ID_OK ? G : rawId.slice(0, 64));
  if (params.get("from")) fq.set("from", params.get("from").slice(0, 400));
  frame.src = "play-frame?" + fq.toString();

  const send = (m, transfer) => { if (frame.contentWindow) frame.contentWindow.postMessage(m, "*", transfer || []); };
  const isId = id => typeof id === "number" && id > 0 && id < 1e12;

  window.addEventListener("message", e => {
    // Only the frame this page made; an opaque origin is "null" (so replies go to "*", but only to that window).
    if (e.source !== frame.contentWindow || e.origin !== "null") return;
    const m = e.data;
    if (!m || typeof m !== "object" || typeof m.t !== "string") return;
    switch (m.t) {
      case "hello": send({ t: "init", signedIn: api.hasLogin() }); break;
      case "req": if (isId(m.id)) broker(m); break;
      case "rom": if (isId(m.id)) rom(m.id); break;
      case "unzip": if (isId(m.id)) unzip(m); break;
      case "packet": takePacket(m); break;
      case "go": go(m.to); break;
      case "title": if (typeof m.text === "string") document.title = m.text.slice(0, 200).replace(/( - Shoalcade)?$/, " - Shoalcade"); break;
      case "download": download(m); break;
    }
  });

  async function broker(m) {
    const answer = (x, transfer) => send(Object.assign({ t: "res", id: m.id }, x), transfer);
    const r = allowed(m);
    if (!r) {
      console.warn("Shoalcade: the player frame asked for something it may not have:", String(m.method).slice(0, 10), String(m.path).slice(0, 120));
      const body = new TextEncoder().encode(JSON.stringify({ error: "not_allowed", message: "The player isn't allowed to do that." })).buffer;
      answer({ status: 403, headers: { "content-type": "application/json; charset=utf-8" }, body }, [body]);
      return;
    }
    let res;
    try {
      res = await api.request(r.path, { method: r.method, headers: r.headers, body: r.body, keepalive: r.keepalive, raw: true });
    } catch (e) {
      answer({ error: "network" });
      return;
    }
    const headers = {};
    res.headers.forEach((v, k) => { if (k === "content-type" || k === "content-disposition" || k.startsWith("x-shoalcade-")) headers[k] = v; });
    let body = null;
    if (![204, 205, 304].includes(res.status)) {
      try { body = await res.arrayBuffer(); } catch (e) { answer({ error: "network" }); return; }
    }
    answer({ status: res.status, headers, body }, body ? [body] : []);
  }

  async function rom(id) {
    let last = 0;
    try {
      if (!ID_OK) throw new api.ApiError(404, "not_found", "Game not found.");
      // A disc set (D-8) plays its first disc here: EmulatorJS gets one file.
      const game = await api.game(G);
      const r = await api.rom(G, (got, total) => {
        const now = Date.now();
        if (now - last < 100 && got !== total) return;
        last = now;
        send({ t: "progress", id, got, total });
      }, game && game.discs > 1 ? { disc: 0 } : undefined);
      let buf = r.bytes.buffer;
      if (r.bytes.byteOffset !== 0 || r.bytes.byteLength !== buf.byteLength) buf = r.bytes.slice().buffer;
      send({ t: "rom", id, bytes: buf, name: r.name }, [buf]);
    } catch (e) {
      send({ t: "rom", id, error: true, status: e.status || 0, code: e.code || "rom", message: e.message || "Couldn't download the game." });
    }
  }

  // EmulatorJS's unzip workers: started here from their own URL (their own eval-only policy, see WebController).
  const workers = new Map();
  function unzip(m) {
    const file = { "7z": "compression/extract7z.js", zip: "compression/extractzip.js" }[m.method];
    if (!file || !(m.data instanceof Uint8Array) || workers.size >= 4) { send({ t: "done", id: m.id, error: "refused" }); return; }
    const tag = typeof m.tag === "string" && /^[\w. -]{0,40}$/.test(m.tag) ? m.tag : "0";
    // "?v=" so a browser never reuses a copy cached before the worker headers changed.
    const w = new Worker(EJS_DIR + file + "?v=" + encodeURIComponent(tag));
    workers.set(m.id, w);
    const end = () => { w.terminate(); workers.delete(m.id); };
    w.onerror = ev => { end(); send({ t: "done", id: m.id, error: String(ev.message || "unzip failed") }); };
    w.onmessage = ev => {
      const d = ev.data;
      if (!d) return;
      if (d.t === 1) { end(); send({ t: "done", id: m.id }); return; }
      const tr = d.data && d.data.buffer instanceof ArrayBuffer ? [d.data.buffer] : [];
      send({ t: "progress", id: m.id, d }, tr);
    };
    w.postMessage(m.data, [m.data.buffer]);
  }

  // ---------------------------------------------------------------------------------------------
  // The close-time packet (saves are precious)
  // ---------------------------------------------------------------------------------------------
  let packet = null;   // { save: Uint8Array|null, base, afterSnapshot, cheats, dirty, play }
  let leaving = false; // the frame already saved and asked (Back to library etc.): no second prompt

  function takePacket(m) {
    const save = m.save instanceof Uint8Array && m.save.length > 0 && m.save.length <= BODY_MAX ? m.save : null;
    const base = Number.isInteger(m.base) && m.base >= 0 ? m.base : 0;
    let play = null;
    const p = m.play;
    if (p && typeof p === "object" && Array.isArray(p.pending) && typeof p.nextId === "string" && p.nextId.length <= 64) {
      const pending = p.pending.slice(0, 100).filter(s => s && typeof s.id === "string" && s.id.length <= 64
        && Number.isFinite(s.seconds) && s.seconds >= 0 && s.seconds <= 86400 && Number.isFinite(s.endedAt))
        .map(s => ({ id: s.id, seconds: Math.round(s.seconds), endedAt: s.endedAt }));
      play = { pending, ms: Number.isFinite(p.ms) && p.ms > 0 ? p.ms : 0, since: Number.isFinite(p.since) && p.since > 0 ? p.since : 0, nextId: p.nextId };
    }
    packet = { save, base, afterSnapshot: m.afterSnapshot === true, cheats: m.cheats === true, dirty: m.dirty === true, play };
  }

  window.addEventListener("beforeunload", e => {
    if (leaving || !packet || !packet.dirty) return;
    e.preventDefault();
    e.returnValue = "Your latest save hasn't reached the server yet.";
  });

  window.addEventListener("pagehide", () => {
    const p = packet;
    if (!p || !ID_OK) return;
    if (p.save && p.dirty) {
      // The same bytes twice are "unchanged" on the server, so this is safe even if the frame's own upload got there.
      api.putSave(G, p.save, { base: p.base, keepalive: p.save.length <= KEEPALIVE_MAX, afterSnapshot: p.afterSnapshot, cheats: p.cheats })
        .catch(() => {});
    }
    if (p.play) {
      const sessions = p.play.pending.slice();
      const secs = Math.round((p.play.ms + (p.play.since ? Math.max(0, Date.now() - p.play.since) : 0)) / 1000);
      // The open stretch carries the id the frame would give it, so it is never counted twice.
      if (secs >= 10) sessions.push({ id: p.play.nextId, seconds: Math.min(secs, 24 * 3600), endedAt: Date.now() });
      if (sessions.length) api.addPlayTime(G, sessions.slice(0, 100), { keepalive: true }).catch(() => {});
    }
  });

  // ---------------------------------------------------------------------------------------------
  // Going somewhere (a sandboxed frame can't move this page; only these places are allowed)
  // ---------------------------------------------------------------------------------------------
  /** Address of the library view the person came from (?c=&q= kept, the game's sheet open). As play-ui.js. */
  function libraryHref() {
    const from = new URLSearchParams(params.get("from") || "");
    const keep = new URLSearchParams();
    for (const k of ["c", "q"]) if (from.get(k)) keep.set(k, from.get(k));
    const s = keep.toString();
    return "./" + (s ? "?" + s : "") + (ID_OK ? "#game=" + G : "");
  }

  function go(to) {
    if (to === "reload") { leaving = true; location.reload(); return; }
    const where = { library: libraryHref(), home: api.homeUrl, saves: "saves-manager?id=" + encodeURIComponent(G), signin: api.signInUrl }[to];
    if (typeof to !== "string" || !where) return;
    leaving = true;
    if (to === "library" && params.has("from") && history.length > 1) history.back();
    else location.href = where;
    setTimeout(() => { leaving = false; }, 1500);
  }

  /** A screenshot the frame took: saved as a PNG download (nothing else, at most one a second). */
  let lastDownload = 0;
  function download(m) {
    const b = m.bytes;
    if (!(b instanceof Uint8Array) || b.length < 8 || b.length > PICTURE_MAX || Date.now() - lastDownload < 1000) return;
    if (b[0] !== 0x89 || b[1] !== 0x50 || b[2] !== 0x4e || b[3] !== 0x47) return;
    lastDownload = Date.now();
    const name = (typeof m.name === "string" ? m.name : "").replace(/[\\/:*?"<>|\u0000-\u001f]+/g, "").trim().slice(0, 120).replace(/\.png$/i, "") || "screenshot";
    const url = URL.createObjectURL(new Blob([b], { type: "image/png" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = name + ".png";
    a.hidden = true;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  // Keys go to the focused document: keep the frame focused (on load, and whenever this page gets focus back).
  frame.addEventListener("load", () => frame.focus());
  window.addEventListener("focus", () => frame.focus());
  document.body.append(frame);

  window.SC.shell = { allowed, frame };
})();
