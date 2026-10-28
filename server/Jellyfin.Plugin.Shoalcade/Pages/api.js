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
    if (res.ok || (opts.allow && opts.allow.includes(res.status))) return res;
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

  const api = {
    BASE,
    ApiError,
    signInUrl: BASE + "/web/",
    /** Jellyfin's own home screen (the pixel logo at the top left). */
    homeUrl: BASE + "/web/#/home",
    hasLogin: () => !!(login || (login = findLogin())),
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
    game: id => json("/shoalcade/games/" + encodeURIComponent(id)),

    /**
     * A game's cover (Jellyfin's Primary image). Jellyfin serves item images without a login,
     * so this is a plain URL with no token in it. The tag changes when the picture does, so it caches well.
     */
    coverUrl: (id, tag, width) => BASE + "/Items/" + encodeURIComponent(id) + "/Images/Primary" + q({ tag, maxWidth: width || 400, quality: 90 }),
    prefs: scope => json("/shoalcade/prefs/" + encodeURIComponent(scope)),
    putPrefs: (scope, obj) => request("/shoalcade/prefs/" + encodeURIComponent(scope), {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj),
    }),

    /** Downloads the ROM with Jellyfin's own route (checks login, library access and download permission). */
    async rom(id, onProgress) {
      const res = await request("/Items/" + encodeURIComponent(id) + "/Download");
      let name = null;
      const cd = res.headers.get("Content-Disposition") || "";
      const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) || /filename="?([^";]+)"?/i.exec(cd);
      if (m) { try { name = decodeURIComponent(m[1]); } catch (e) { name = m[1]; } }
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
      const res = await request("/shoalcade/save/" + encodeURIComponent(id), { allow: [404] });
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
      const res = await request("/shoalcade/save/" + encodeURIComponent(id) + q({
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

    history: id => json("/shoalcade/save/" + encodeURIComponent(id) + "/history"),

    // ---------- cheats (D143, docs/cheats-api.md) ----------
    /** A game's cheats: { cheats: [{ id, name, code, added }], active: [ids] }. */
    cheats: id => json("/shoalcade/cheats/" + encodeURIComponent(id)),
    /** Adds a cheat; the same code twice gives back the one already there. */
    addCheat: (id, name, code) => json("/shoalcade/cheats/" + encodeURIComponent(id), {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name, code }),
    }),
    editCheat: (id, cheat, changes) => json("/shoalcade/cheats/" + encodeURIComponent(id) + "/" + encodeURIComponent(cheat), {
      method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(changes),
    }),
    deleteCheat: (id, cheat) => request("/shoalcade/cheats/" + encodeURIComponent(id) + "/" + encodeURIComponent(cheat), { method: "DELETE" }),
    /** Sets which cheats are on for the in-game save; answers with the whole list. */
    setActiveCheats: (id, ids) => json("/shoalcade/cheats/" + encodeURIComponent(id) + "/active", {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ids }),
    }),
  };

  window.SC = window.SC || {};
  window.SC.api = api;

  // ---------- admin game editor (library.js, D122). The server checks admin on every call (403 otherwise). ----------
  const editPath = id => "/shoalcade/admin/games/" + encodeURIComponent(id);
  const jsonBody = obj => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj) });

  Object.assign(api, {
    /** The game as the editor sees it: { name, nameEdited, coverSource, coverFrom, canFindCovers, ... }. */
    editGame: id => json(editPath(id)),
    /** Renames a game (locked against scans). */
    renameGame: (id, name) => json(editPath(id) + "/name", Object.assign({ method: "PUT" }, jsonBody({ name }))),
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
    pickCover: (id, name) => json(editPath(id) + "/cover-pick", Object.assign({ method: "POST" }, jsonBody({ name }))),
    /** Back to the automatic name and cover. */
    resetGame: id => json(editPath(id) + "/reset", { method: "POST" }),
  });

  // ---------- saves manager (saves.js) ----------
  const savePath = id => "/shoalcade/save/" + encodeURIComponent(id);
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
    updateSaveVersion: (id, version, changes) => json(versionPath(id, version), {
      method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(changes),
    }),

    /** Deletes an old version (the server refuses the current one and pinned ones). */
    deleteSaveVersion: (id, version) => json(versionPath(id, version), { method: "DELETE" }),

    /** "Start fresh": no save in use afterwards; every version stays in history. */
    clearSave: (id, opts) => json(savePath(id) + "/clear" + q({ base: opts && opts.base }), { method: "POST" }),

    /** Moves every version of a save to another game. */
    moveSave: (id, to) => json(savePath(id) + "/move" + q({ to }), { method: "POST" }),

    /** One version as a file: { blob, name }. Fetched with the login header, so the sign-in never goes in a URL. */
    async saveFile(id, version) {
      const res = await request(versionPath(id, version));
      let name = null;
      const cd = res.headers.get("Content-Disposition") || "";
      const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) || /filename="?([^";]+)"?/i.exec(cd);
      if (m) { try { name = decodeURIComponent(m[1]); } catch (e) { name = m[1]; } }
      return { blob: await res.blob(), name };
    },

    /** A game's browser snapshots (F8: also for a game no longer in the library): { snapshots, enabled }. */
    snapshots: id => json(statePath(id)),

    /** One snapshot as a Blob (fetched with the login header). */
    snapshotFile: async (id, slot) => (await request(statePath(id, slot))).blob(),

    /** Deletes a snapshot. */
    deleteSnapshot: (id, slot) => request(statePath(id, slot), { method: "DELETE" }),
  });
})();
