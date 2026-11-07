/*
 * Shoalcade browser API client (shared by every page).
 * - Signs in with the Jellyfin web client's own login (same server, same origin), so there is no second password.
 * - The token only ever goes in the Authorization header: never in a URL, never logged.
 * - All calls are relative to the Jellyfin base URL, so a server behind "/jellyfin" still works.
 */
(function () {
  "use strict";

  // "/jellyfin/shoalcade/play" -> "/jellyfin"
  const BASE = (function () {
    const p = location.pathname;
    const i = p.indexOf("/shoalcade");
    return i > 0 ? p.slice(0, i) : "";
  })();

  /**
   * M3 (docs/plan-player-sandbox.md): inside the player frame (sandboxed, opaque origin "null") this script has no
   * login and can't reach the server. Every call goes to the player page around it (play-shell.js) by postMessage,
   * and that page only allows this one game's routes. findLogin() is never called in the frame.
   */
  const IN_FRAME = (function () {
    try { return String(self.origin) === "null" && window.parent !== window; } catch (e) { return false; }
  })();
  const shell = IN_FRAME ? (function () {
    const SHELL = new URL(location.href).origin; // the server's own origin: where the player page lives
    const waiting = new Map();
    const handlers = {};
    let next = 1;
    let initDone;
    const init = new Promise(r => { initDone = r; });
    window.addEventListener("message", e => {
      if (e.source !== window.parent || e.origin !== SHELL) return;
      const m = e.data;
      if (!m || typeof m !== "object" || typeof m.t !== "string") return;
      if (m.t === "init") { initDone(m); return; }
      const w = typeof m.id === "number" ? waiting.get(m.id) : null;
      if (w) {
        if (m.t === "progress") { if (w.progress) w.progress(m); return; }
        waiting.delete(m.id);
        w.resolve(m);
        return;
      }
      if (handlers[m.t]) handlers[m.t](m);
    });
    const post = (msg, transfer) => window.parent.postMessage(msg, SHELL, transfer || []);
    /** Sends a request; resolves with the shell's answer. progress(m) gets the "progress" messages before it. */
    const call = (msg, transfer, progress) => new Promise(resolve => {
      const id = next++;
      waiting.set(id, { resolve, progress });
      post(Object.assign({}, msg, { id }), transfer);
    });
    post({ t: "hello" });
    return { post, call, init, on: (t, fn) => { handlers[t] = fn; } };
  })() : null;
  let frameSignedIn = false;

  /** The Jellyfin web client's stored login for this server, or null. */
  function findLogin() {
    let creds;
    try {
      creds = JSON.parse(localStorage.getItem("jellyfin_credentials") || "null");
    } catch (e) {
      return null;
    }
    const servers = (creds && Array.isArray(creds.Servers) ? creds.Servers : []).filter(s => s && s.AccessToken && s.UserId);
    if (servers.length === 0) return null;
    const here = location.origin + BASE;
    const sameServer = s => [s.ManualAddress, s.LocalAddress, s.RemoteAddress]
      .some(a => typeof a === "string" && a.replace(/\/+$/, "").toLowerCase() === here.toLowerCase());
    const pick = servers.filter(sameServer);
    // No stored address matches (e.g. behind a reverse proxy): only trust the login if it is the only one.
    // With several servers stored, guessing could send another server's token here (D56).
    const list = pick.length ? pick : servers.length === 1 ? servers : [];
    if (list.length === 0) return null;
    list.sort((a, b) => (b.DateLastAccessed || 0) - (a.DateLastAccessed || 0));
    return { token: list[0].AccessToken, userId: list[0].UserId };
  }

  /** An error from the server or the network, with a message fit to show to the person. */
  class ApiError extends Error {
    constructor(status, code, message, body) {
      super(message);
      this.status = status; // 0 = network error
      this.code = code;
      this.body = body;
    }
  }

  let login = null;

  function authHeader() {
    if (!login) login = findLogin();
    if (!login) throw new ApiError(401, "signed_out", "Sign in to Jellyfin first.");
    // Token only: other fields would rename the Jellyfin web session's device.
    return 'MediaBrowser Token="' + login.token + '"';
  }

  /**
   * Calls the server. Returns the Response for 2xx, and also for any status listed in opts.allow.
   * Anything else throws an ApiError with the server's plain-English message.
   */
  async function request(path, opts) {
    opts = opts || {};
    if (IN_FRAME) return check(await frameFetch(path, opts), opts);
    const headers = Object.assign({ Authorization: authHeader() }, opts.headers || {});
    let res;
    try {
      res = await fetch(BASE + path, {
        method: opts.method || "GET",
        headers,
        body: opts.body,
        cache: "no-store",
        credentials: "omit",
        keepalive: !!opts.keepalive,
        signal: opts.signal,
      });
    } catch (e) {
      throw new ApiError(0, "network", "Can't reach the server.");
    }
    return check(res, opts);
  }

  /** The player frame's fetch: the shell makes the call (with the login) if it is one of this game's routes. */
  async function frameFetch(path, opts) {
    const h = opts.headers || {};
    const m = await shell.call({
      t: "req", method: opts.method || "GET", path, contentType: h["Content-Type"] || null,
      body: opts.body === undefined ? null : opts.body, keepalive: !!opts.keepalive,
    });
    if (m.error) throw new ApiError(0, "network", "Can't reach the server.");
    const empty = m.status === 204 || m.status === 205 || m.status === 304;
    return new Response(empty ? null : m.body, { status: m.status, headers: m.headers || {} });
  }

  /** 2xx (or a status in opts.allow; any status with opts.raw) comes back; anything else is an ApiError. */
  async function check(res, opts) {
    if (res.ok || opts.raw || (opts.allow && opts.allow.includes(res.status))) return res;
    let body = null;
    try { body = await res.json(); } catch (e) { /* not JSON */ }
    if (res.status === 401) {
      login = null;
      throw new ApiError(401, "signed_out", "Your Jellyfin sign-in has expired. Sign in again.", body);
    }
    const msg = body && body.message ? body.message
      : res.status === 403 ? "You don't have permission to do that."
      : res.status === 404 ? "Not found."
      : "The server said no (" + res.status + ").";
    throw new ApiError(res.status, body && body.error ? body.error : "http_" + res.status, msg, body);
  }

  async function json(path, opts) {
    return (await request(path, opts)).json();
  }

  function q(params) {
    const s = new URLSearchParams();
    for (const k in params) {
      if (params[k] !== undefined && params[k] !== null && params[k] !== "") s.set(k, String(params[k]));
    }
    const t = s.toString();
    return t ? "?" + t : "";
  }

  /** The file name from a download's Content-Disposition header, or null. */
  function fileName(res) {
    const cd = res.headers.get("Content-Disposition") || "";
    const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) || /filename="?([^";]+)"?/i.exec(cd);
    if (!m) return null;
    try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; }
  }

  const gamePath = id => "/shoalcade/games/" + encodeURIComponent(id);
  const cheatPath = id => "/shoalcade/cheats/" + encodeURIComponent(id);
  const savePath = id => "/shoalcade/save/" + encodeURIComponent(id);
  const jsonBody = obj => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj) });
  const sendJson = (method, obj) => Object.assign({ method }, jsonBody(obj));

  const api = {
    BASE,
    ApiError,
    signInUrl: BASE + "/web/",
    /** Jellyfin's own home screen (the pixel logo at the top left). */
    homeUrl: BASE + "/web/#/home",
    hasLogin: () => IN_FRAME ? frameSignedIn : !!(login || (login = findLogin())),
    /** True inside the sandboxed player frame (M3). */
    inFrame: IN_FRAME,
    /** The player page around the frame (postMessage), or null outside the frame. */
    shell,
    /** Resolves once the page may start: at once, or in the frame once the player page has said hello back. */
    ready: IN_FRAME ? shell.init.then(m => { frameSignedIn = !!m.signedIn; }) : Promise.resolve(),
    /** In the frame: asks the player page to go somewhere from its fixed list (library, home, saves, signin, reload). */
    go: where => { if (IN_FRAME) shell.post({ t: "go", to: where }); },
    /** Low-level call (login header added) for page scripts with their own routes, e.g. the player's snapshots. */
    request,

    info: () => json("/shoalcade/info"),
    /** Puts the plugin version ("s0.2 dev 1", D88) into the page's small #sc-ver line, if it has one. */
    showVersion(info) {
      const el = document.getElementById("sc-ver");
      const v = info && (info.display || info.server);
      if (el && v) el.textContent = "Shoalcade server " + v;
    },
    games: () => json("/shoalcade/games"),
    game: id => json(gamePath(id)),
    /** Stars or unstars a game (Jellyfin favourites, shared with the phone app). */
    setFavourite: (id, on) => request(gamePath(id) + "/favourite" + q({ on: on ? "true" : "false" }), { method: "POST" }),

    /**
     * A game's cover (Jellyfin's Primary image). Jellyfin serves item images without a login,
     * so this is a plain URL with no token in it. The tag changes when the picture does, so it caches well.
     */
    coverUrl: (id, tag, width) => BASE + "/Items/" + encodeURIComponent(id) + "/Images/Primary" + q({ tag, maxWidth: width || 400, quality: 90 }),
    prefs: scope => json("/shoalcade/prefs/" + encodeURIComponent(scope)),
    putPrefs: (scope, obj) => request("/shoalcade/prefs/" + encodeURIComponent(scope), sendJson("PUT", obj)),

    /**
     * Downloads the ROM with Jellyfin's own route (checks login, library access and download permission). For a disc
     * set (D-8) opts.disc picks one disc (the plugin's route checks the same things).
     */
    async rom(id, onProgress, opts) {
      if (IN_FRAME) {
        // The player page downloads this game (only ever its own id) and hands the bytes over (moved, never copied).
        const m = await shell.call({ t: "rom" }, null, p => { if (onProgress) onProgress(p.got, p.total); });
        if (m.error) throw new ApiError(m.status || 0, m.code || "rom", m.message || "Couldn't download the game.");
        return { bytes: new Uint8Array(m.bytes), name: m.name || null };
      }
      const disc = opts && Number.isInteger(opts.disc) && opts.disc >= 0 ? opts.disc : null;
      const res = await request(disc === null ? "/Items/" + encodeURIComponent(id) + "/Download"
        : gamePath(id) + "/discs/" + disc);
      const name = fileName(res);
      const total = parseInt(res.headers.get("Content-Length") || "0", 10);
      // L13: a download cut short (or longer than the server said) is an error, never a game. Content-Length counts the
      // compressed bytes when the response is compressed, so the check is skipped then.
      const enc = (res.headers.get("Content-Encoding") || "identity").toLowerCase();
      const whole = got => {
        if (total > 0 && enc === "identity" && got !== total) {
          throw new ApiError(0, "incomplete_download", "The game didn't download completely (" + got + " of " + total +
            " bytes). Check your connection and try again.");
        }
      };
      if (!res.body || !onProgress) {
        const bytes = new Uint8Array(await res.arrayBuffer());
        whole(bytes.length);
        return { bytes, name };
      }
      const reader = res.body.getReader();
      // Known size: fill one buffer as the pieces arrive, so a big game (a PS1 disc) is never held twice.
      let one = total > 0 ? new Uint8Array(total) : null;
      const parts = [];
      let got = 0;
      for (;;) {
        const r = await reader.read();
        if (r.done) break;
        if (one && got + r.value.length <= one.length) one.set(r.value, got);
        else {
          if (one) { parts.push(one.subarray(0, got)); one = null; } // more than the server said: collect pieces instead
          parts.push(r.value);
        }
        got += r.value.length;
        onProgress(got, total);
      }
      whole(got);
      if (one) return { bytes: got === one.length ? one : one.slice(0, got), name };
      const bytes = new Uint8Array(got);
      let o = 0;
      for (const p of parts) { bytes.set(p, o); o += p.length; }
      return { bytes, name };
    },

    /** The current save: { bytes, version } or { bytes: null, version: 0 } if there is none yet. */
    async getSave(id) {
      const res = await request(savePath(id), { allow: [404] });
      if (res.status === 404) {
        let body = null;
        try { body = await res.json(); } catch (e) { /* ignore */ }
        if (body && body.error === "no_save") return { bytes: null, version: 0 };
        throw new ApiError(404, body && body.error || "not_found", body && body.message || "Game not found.", body);
      }
      // No version header = not our reply (a proxy or sign-in page): never boot it as the save (D86, as the app).
      const version = parseInt(res.headers.get("X-Shoalcade-Save-Version") || "", 10);
      if (!(version > 0)) throw new ApiError(502, "bad_reply", "The server's reply wasn't a Shoalcade save.", null);
      return { bytes: new Uint8Array(await res.arrayBuffer()), version };
    },

    /**
     * Uploads a save. Never throws for 409: returns { status: 409, body } so the caller can ask the person.
     * opts: { base, mode: normal|force|history, keepalive, afterSnapshot (D90: the game saved after a snapshot load),
     * cheats (D143: the game saved while cheats were on) }.
     */
    async putSave(id, bytes, opts) {
      opts = opts || {};
      const res = await request(savePath(id) + q({
        base: opts.base, mode: opts.mode, source: "browser", afterSnapshot: opts.afterSnapshot ? "true" : null,
        cheats: opts.cheats ? "true" : null,
      }), {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream" },
        body: bytes,
        keepalive: opts.keepalive,
        allow: [409],
      });
      return { status: res.status, body: await res.json() };
    },

    history: id => json(savePath(id) + "/history"),

    // ---------- cheats (D143, docs/cheats-api.md) ----------
    /** A game's cheats: { cheats: [{ id, name, code, added }], active: [ids] }. */
    cheats: id => json(cheatPath(id)),
    /** Adds a cheat; the same code twice gives back the one already there. */
    addCheat: (id, name, code) => json(cheatPath(id), sendJson("POST", { name, code })),
    editCheat: (id, cheat, changes) => json(cheatPath(id) + "/" + encodeURIComponent(cheat), sendJson("PATCH", changes)),
    deleteCheat: (id, cheat) => request(cheatPath(id) + "/" + encodeURIComponent(cheat), { method: "DELETE" }),
    /** Sets which cheats are on for the in-game save; answers with the whole list. */
    setActiveCheats: (id, ids) => json(cheatPath(id) + "/active", sendJson("PUT", { ids })),

    /**
     * Adds finished play sessions ([{ id, seconds, endedAt }]) to this user's play time on a game; answers
     * { playSeconds, added }. A session id already counted is skipped, so resending is safe. keepalive lets the
     * last one go out while the page closes (sendBeacon can't send the Authorization header).
     */
    addPlayTime: (id, sessions, opts) => json(gamePath(id) + "/playtime",
      Object.assign(sendJson("POST", { sessions }), { keepalive: !!(opts && opts.keepalive) })),

    /**
     * Play time in words, the same rule as the phone app (PlayTime.format): under a minute "" (show nothing),
     * "45 min", "3 h 05 min", and from 100 hours just "120 h".
     */
    formatPlayTime(seconds) {
      const mins = Math.floor((Number(seconds) || 0) / 60);
      if (mins < 1) return "";
      if (mins < 60) return mins + " min";
      const h = Math.floor(mins / 60);
      if (h >= 100) return h + " h";
      const m = mins % 60;
      return h + " h " + (m < 10 ? "0" : "") + m + " min";
    },
  };

  window.SC = window.SC || {};
  window.SC.api = api;

  // ---------- admin game editor (library.js, D122). The server checks admin on every call (403 otherwise). ----------
  const editPath = id => "/shoalcade/admin/games/" + encodeURIComponent(id);

  Object.assign(api, {
    /** The game as the editor sees it: { name, nameEdited, coverSource, coverFrom, canFindCovers, ... }. */
    editGame: id => json(editPath(id)),
    /** Renames a game (locked against scans). */
    renameGame: (id, name) => json(editPath(id) + "/name", sendJson("PUT", { name })),
    /** Uploads a cover picture (a File/Blob). The server checks it is a real PNG, JPEG or WebP. */
    uploadCover: (id, file) => json(editPath(id) + "/cover", {
      method: "PUT", headers: { "Content-Type": file.type || "application/octet-stream" }, body: file,
    }),
    /** libretro names for a typed search: { names: [...] }. */
    findCovers: (id, text) => json(editPath(id) + "/cover-search" + q({ q: text })),
    /** One libretro cover as a Blob, or null if there is none by that name. Fetched with the login header. */
    async coverPreview(id, name, signal) {
      const res = await request(editPath(id) + "/cover-preview" + q({ name }), { allow: [404], signal });
      return res.status === 404 ? null : res.blob();
    },
    /** Uses a libretro cover for the game. */
    pickCover: (id, name) => json(editPath(id) + "/cover-pick", sendJson("POST", { name })),
    /** Back to the automatic name and cover. */
    resetGame: id => json(editPath(id) + "/reset", { method: "POST" }),
  });

  // ---------- saves manager (saves.js) ----------
  const versionPath = (id, v) => savePath(id) + "/versions/" + encodeURIComponent(v);
  const statePath = (id, slot) => "/shoalcade/state/" + encodeURIComponent(id) + (slot ? "/" + encodeURIComponent(slot) : "");

  Object.assign(api, {
    /** Every save this user has, including games no longer in the library, plus storage used. */
    saveList: () => json("/shoalcade/saves"),

    /**
     * Uploads a save file by hand (source "upload"). Goes through the normal version check:
     * a 409 is returned as { status: 409, body } so the page can ask. opts: { base, mode, allowBlank }.
     */
    async uploadSave(id, bytes, opts) {
      opts = opts || {};
      const res = await request(savePath(id) + q({
        base: opts.base, mode: opts.mode, source: "upload", allowBlank: opts.allowBlank ? "true" : null,
      }), {
        method: "PUT", headers: { "Content-Type": "application/octet-stream" }, body: bytes, allow: [409],
      });
      return { status: res.status, body: await res.json() };
    },

    /** Makes an old version current (the current save goes into history first). base = version the page last saw. */
    restoreSave: (id, version, opts) => json(versionPath(id, version) + "/restore" + q({ base: opts && opts.base }), { method: "POST" }),

    /** Pins/unpins or renames a version. changes: { pinned?, label? } ("" clears the label). */
    updateSaveVersion: (id, version, changes) => json(versionPath(id, version), sendJson("PATCH", changes)),

    /** Deletes an old version (the server refuses the current one and pinned ones). */
    deleteSaveVersion: (id, version) => json(versionPath(id, version), { method: "DELETE" }),

    /** "Start fresh": no save in use afterwards; every version stays in history. */
    clearSave: (id, opts) => json(savePath(id) + "/clear" + q({ base: opts && opts.base }), { method: "POST" }),

    /** Deletes every save and snapshot of a game no longer in the library; the counts shown must still be right. */
    deleteAllSaves: (id, o) => json(savePath(id) + "/delete-all" + q({ versions: o.versions, snapshots: o.snapshots }), { method: "POST" }),

    /** Moves every version of a save to another game (fromConsole: only when its console wasn't recorded, F7 gap 2). */
    moveSave: (id, to, fromConsole) => json(savePath(id) + "/move" + q({ to, fromConsole }), { method: "POST" }),

    /** Every save in use as one zip: { blob, name }. */
    async exportSaves() {
      const res = await request("/shoalcade/saves/export");
      return { blob: await res.blob(), name: fileName(res) };
    },

    /** One version as a file: { blob, name }. Fetched with the login header, so the sign-in never goes in a URL. */
    async saveFile(id, version) {
      const res = await request(versionPath(id, version));
      return { blob: await res.blob(), name: fileName(res) };
    },

    /** A game's browser snapshots (F8: also for a game no longer in the library): { snapshots, enabled }. */
    snapshots: id => json(statePath(id)),

    /** One snapshot as a Blob (fetched with the login header). */
    snapshotFile: async (id, slot) => (await request(statePath(id, slot))).blob(),

    /** Deletes a snapshot. */
    deleteSnapshot: (id, slot) => request(statePath(id, slot), { method: "DELETE" }),
  });

  // ---------- small page helpers shared by the library, saves and settings pages (and the player's icons/toast) ----------
  /** h("div", { class, text, attr: value }, [children]): false/null/undefined attributes and children are skipped. */
  function h(tag, attrs, kids) {
    const e = document.createElement(tag);
    for (const k in attrs || {}) {
      const v = attrs[k];
      if (v === undefined || v === null || v === false) continue;
      if (k === "class") e.className = v;
      else if (k === "text") e.textContent = v;
      else e.setAttribute(k, v === true ? "" : String(v));
    }
    for (const c of kids || []) if (c) e.append(c);
    return e;
  }

  /** A 24x24 line icon from its SVG path strings. */
  function icon(paths) {
    const NS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("class", "ico");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    for (const d of paths) {
      const p = document.createElementNS(NS, "path");
      p.setAttribute("d", d);
      svg.append(p);
    }
    return svg;
  }

  /** A steady colour (0-359) from a game id, for covers that have no picture. */
  function hue(id) {
    let n = 0;
    for (let i = 0; i < id.length; i++) n = (n * 31 + id.charCodeAt(i)) % 360;
    return n;
  }

  /** A game's cover box: its picture when it has one, else the name on a coloured tile. */
  function art(id, name, label, imageTag, width) {
    const box = h("div", { class: "art" }, [
      h("span", { class: "art-t", text: name }),
      h("span", { class: "art-c", text: label }),
    ]);
    box.style.setProperty("--h", String(hue(id)));
    if (imageTag) {
      const img = h("img", { src: api.coverUrl(id, imageTag, width), alt: "", loading: "lazy", decoding: "async" });
      img.addEventListener("error", () => { img.remove(); box.classList.remove("has-cover"); }, { once: true });
      box.classList.add("has-cover");
      box.prepend(img);
    }
    return box;
  }

  let toastTimer = 0;
  /** Shows msg in the page's #toast for ms (default 4 s). */
  function toast(msg, ms) {
    const t = document.getElementById("toast");
    t.textContent = msg;
    t.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("on"), ms || 4000);
  }

  /** Starts a browser download of a Blob (its object URL is freed after a minute) or of a URL. */
  function download(what, name) {
    const blob = what instanceof Blob;
    const url = blob ? URL.createObjectURL(what) : what;
    const a = h("a", { href: url, download: name, hidden: true });
    document.body.append(a);
    a.click();
    a.remove();
    if (blob) setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  /** A page's load failed: the sign-in view for 401 (with the server's words), else the "failed" view. */
  function loadFailed(e, show) {
    if (e && e.status === 401) {
      document.getElementById("signed-out-text").textContent = e.message;
      show("signed-out");
    } else {
      document.getElementById("failed-text").textContent = ui.message(e);
      show("failed");
    }
  }

  const ui = window.SC.ui = {
    h, icon, hue, art, toast, download, loadFailed,
    /** An error's words for the person. */
    message: e => e && e.message ? e.message : String(e),
    SHORT: { gb: "GB", gbc: "GBC", gba: "GBA", n64: "N64", nds: "DS", "3ds": "3DS", psx: "PS1", psp: "PSP", gc: "GC", wii: "Wii", ps2: "PS2", xbox360: "X360" },
  };
})();
