/*
 * Shoalcade player engine for /shoalcade/play?id=<itemId>.
 * Logic only: the Pocket UI on top of it lives in play-ui.js (it creates the Player and sets window.SC.player).
 *
 * How it works (D32-D35):
 *  1. Reads the game, the person's settings and the ROM from the server (ROM via Jellyfin's own
 *     /Items/{id}/Download with the login in the Authorization header - never in a URL).
 *  2. Boots EmulatorJS 4.2.3 (served by the plugin) with the ROM as a File. Its own UI, controls,
 *     localStorage and IndexedDB are all off: saves live in memory (MEMFS) and on the server only.
 *  3. Before the core starts, the server's current save is written to /data/saves/game.<ext>.
 *  4. While playing, the in-game save is checked every few seconds; when it changes (and has stopped
 *     changing) it is uploaded with the version it replaces. If the server has moved on (409) the game
 *     pauses and the person chooses which copy to keep. The other copy is always kept in history.
 *  5. Our own input layer (keyboard + Gamepad API + touch buttons, player 1) drives the core via simulateInput.
 *  6. Snapshots (save states, D37) go to the server's /shoalcade/state routes: slots 1-3 + "auto".
 *  7. Only saves made in the game sync (D90). A snapshot also carries the game's save as it was when the snapshot
 *     was taken, and loading one can put that copy in the emulator's memory (melonDS does). So right after a load the
 *     save is read again as a "baseline", and nothing is sent until the game itself writes a save that differs from
 *     it. That save is sent through the normal version check (the old one goes to history) and marked "saved after
 *     loading a snapshot".
 */
(function () {
  "use strict";

  const api = window.SC.api;
  const EJS_DIR = api.BASE + "/shoalcade/ejs/";
  const EJS_SCRIPTS = ["src/emulator.js", "src/nipplejs.js", "src/shaders.js", "src/storage.js",
    "src/gamepad.js", "src/GameManager.js", "src/compression.js"];

  const POLL_MS = 3000;          // how often the in-game save is checked
  const MIN_GAP_MS = 30000;      // least time between automatic uploads (a game that saves non-stop can't flood history)
  const KEEPALIVE_MAX = 60000;   // browsers only send small bodies while a page is closing

  // ---------------------------------------------------------------------------------------------
  // Controls (D33). Button names -> RetroPad ids used by simulateInput.
  // ---------------------------------------------------------------------------------------------
  const RETRO = { B: 0, Y: 1, Select: 2, Start: 3, Up: 4, Down: 5, Left: 6, Right: 7, A: 8, X: 9, L: 10, R: 11, L2: 12, R2: 13 };
  const DPAD = ["Up", "Down", "Left", "Right"];
  const BUTTONS = {
    gb: DPAD.concat(["A", "B", "Start", "Select"]),
    gbc: DPAD.concat(["A", "B", "Start", "Select"]),
    gba: DPAD.concat(["A", "B", "L", "R", "Start", "Select"]),
    nds: DPAD.concat(["A", "B", "X", "Y", "L", "R", "Start", "Select"]),
    // PS1 (D137), same order as the app: D-pad, triangle circle cross square (by position, as PSP: X A B Y), L1 R1 L2 R2.
    psx: DPAD.concat(["X", "A", "B", "Y", "L", "R", "L2", "R2", "Start", "Select"]),
  };
  // Keyboard: KeyboardEvent.code (same physical key on any layout).
  const DEFAULT_KEYS = {
    Up: ["ArrowUp"], Down: ["ArrowDown"], Left: ["ArrowLeft"], Right: ["ArrowRight"],
    A: ["KeyX"], B: ["KeyZ"], X: ["KeyS"], Y: ["KeyA"], L: ["KeyQ"], R: ["KeyW"], L2: ["KeyE"], R2: ["KeyR"],
    Start: ["Enter"], Select: ["ShiftRight", "Backspace"],
  };
  // Gamepad: names for the browser's "standard" mapping; "b<n>" = button index, "a<n>+"/"a<n>-" = axis.
  const STANDARD = ["south", "east", "west", "north", "l1", "r1", "l2", "r2", "select", "start",
    "l3", "r3", "up", "down", "left", "right", "home"];
  // Nintendo layout by position: right face button = A, bottom = B, top = X, left = Y.
  const DEFAULT_PAD = {
    Up: ["up", "a1-"], Down: ["down", "a1+"], Left: ["left", "a0-"], Right: ["right", "a0+"],
    A: ["east"], B: ["south"], X: ["north"], Y: ["west"], L: ["l1"], R: ["r1"], L2: ["l2"], R2: ["r2"],
    Start: ["start"], Select: ["select"],
  };

  function asList(v) {
    if (typeof v === "string") return [v];
    return Array.isArray(v) ? v.filter(x => typeof x === "string") : null;
  }

  /** Merges saved settings (prefs[console].controls) over the defaults, for this console's buttons only. */
  function buildMap(buttons, defaults, saved) {
    const map = {};
    for (const b of buttons) {
      const s = saved && Object.prototype.hasOwnProperty.call(saved, b) ? asList(saved[b]) : null;
      map[b] = s || defaults[b] || [];
    }
    return map;
  }

  class Input {
    constructor(consoleKey, controls, send) {
      this.buttons = BUTTONS[consoleKey] || BUTTONS.gba;
      this.send = send;
      this.down = new Map();    // button -> pressed (combined)
      this.keyDown = new Set(); // codes held
      this.padDown = new Set(); // buttons held on the pad
      this.touchDown = new Set(); // buttons held on the touch overlay
      this.setControls(controls);
      this.enabled = false;
      this.raf = 0;
      this.onKey = this.onKey.bind(this);
      this.releaseAll = this.releaseAll.bind(this);
      this.poll = this.poll.bind(this);
    }

    attach() {
      window.addEventListener("keydown", this.onKey, true);
      window.addEventListener("keyup", this.onKey, true);
      window.addEventListener("blur", this.releaseAll);
      this.enabled = true;
      this.raf = requestAnimationFrame(this.poll);
    }

    setEnabled(on) {
      if (!on) this.releaseAll();
      this.enabled = on;
    }

    /** New saved controls (after a rebind): rebuilds the keyboard map and forgets cached pad maps. */
    setControls(controls) {
      if (this.keys) this.releaseAll();
      this.controls = controls || {};
      this.keys = buildMap(this.buttons, DEFAULT_KEYS, this.controls.keyboard);
      this.padMaps = new Map();
    }

    /** A touch button pressed or let go. */
    setTouch(b, on) {
      if (!this.buttons.includes(b) || (on && !this.enabled)) return;
      if (on) this.touchDown.add(b); else this.touchDown.delete(b);
      this.update(b);
    }

    padMap(pad) {
      let m = this.padMaps.get(pad.id);
      if (!m) {
        const saved = this.controls.gamepads && this.controls.gamepads[pad.id];
        m = buildMap(this.buttons, DEFAULT_PAD, saved);
        this.padMaps.set(pad.id, m);
      }
      return m;
    }

    static isHeld(pad, bind) {
      let m = /^a(\d+)([+-])$/.exec(bind);
      if (m) {
        const v = pad.axes[+m[1]] || 0;
        return m[2] === "+" ? v > 0.5 : v < -0.5;
      }
      let i = -1;
      m = /^b(\d+)$/.exec(bind);
      if (m) i = +m[1];
      else i = STANDARD.indexOf(bind);
      const btn = i >= 0 ? pad.buttons[i] : null;
      return !!(btn && (btn.pressed || btn.value > 0.5));
    }

    poll() {
      this.raf = requestAnimationFrame(this.poll);
      const pads = navigator.getGamepads ? navigator.getGamepads() : [];
      let pad = null;
      for (const p of pads) { if (p && p.connected) { pad = p; break; } } // player 1 only
      const now = new Set();
      if (pad && this.enabled) {
        const map = this.padMap(pad);
        for (const b of this.buttons) {
          if (map[b].some(bind => Input.isHeld(pad, bind))) now.add(b);
        }
      }
      for (const b of this.buttons) {
        const was = this.padDown.has(b);
        if (now.has(b) !== was) {
          if (now.has(b)) this.padDown.add(b); else this.padDown.delete(b);
          this.update(b);
        }
      }
    }

    onKey(e) {
      if (!this.enabled || e.ctrlKey || e.metaKey || e.altKey) return;
      const t = e.target;
      if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT|BUTTON)$/.test(t.tagName))) return;
      const hit = this.buttons.filter(b => this.keys[b].includes(e.code));
      if (hit.length === 0) return;
      e.preventDefault();
      if (e.type === "keydown") this.keyDown.add(e.code); else this.keyDown.delete(e.code);
      hit.forEach(b => this.update(b));
    }

    update(b) {
      const pressed = this.padDown.has(b) || this.touchDown.has(b) || this.keys[b].some(c => this.keyDown.has(c));
      if (!!this.down.get(b) === pressed) return;
      this.down.set(b, pressed);
      this.send(RETRO[b], pressed ? 1 : 0);
    }

    releaseAll() {
      this.keyDown.clear();
      this.padDown.clear();
      this.touchDown.clear();
      for (const b of this.buttons) this.update(b);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Save sync (D35)
  // ---------------------------------------------------------------------------------------------
  function sameBytes(a, b) {
    if (!a || !b || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  /**
   * True if the save changed in a way worth keeping. Saves are a multiple of 512 bytes plus an optional
   * clock footer (GB/GBA real-time clock). If only the footer changed, the game didn't save: the clock
   * just ticked, and the old footer still gives the right time on the next load.
   */
  function meaningfulChange(now, before) {
    if (!before || now.length !== before.length) return true;
    let i = 0;
    while (i < now.length && now[i] === before[i]) i++;
    if (i === now.length) return false;
    const footer = now.length % 512;
    return !(footer > 0 && footer <= 64 && i >= now.length - footer);
  }

  /**
   * The save-after-snapshot rule (D90), separate from the emulator so it can be tested on its own.
   * After a snapshot load the save in memory may be the snapshot's copy, which must never be sent. So:
   *  - begin(): a load is under way; nothing counts as a new save until the baseline is taken;
   *  - setBase(bytes): the save as it is just after the load (null = the game has no save yet);
   *  - isGameSave(bytes): true only if the game has written a save since (a real change, not just the clock footer).
   * Once the game's own save is on the server, clear() goes back to the normal rule; `after` stays true, so later
   * saves this session are still marked "saved after loading a snapshot".
   */
  class SnapshotGuard {
    constructor() { this.active = false; this.waiting = false; this.base = null; this.after = false; }
    begin() { this.active = true; this.waiting = true; this.base = null; this.after = true; }
    setBase(bytes) { this.base = bytes ? bytes.slice() : null; this.waiting = false; }
    isGameSave(bytes) {
      if (!this.active) return true;
      if (this.waiting) return false;
      return !!bytes && meaningfulChange(bytes, this.base);
    }
    clear() { this.active = false; this.waiting = false; this.base = null; }
    reset() { this.clear(); this.after = false; }
  }

  class SaveSync {
    constructor(player, itemId, version, bytes) {
      this.p = player;
      this.id = itemId;
      this.base = version;     // version this browser last saw on the server (0 = none)
      this.synced = bytes;     // bytes the server has as that version
      this.pending = null;     // changed bytes waiting to settle
      this.refused = null;     // bytes the server refused (don't resend until they change)
      this.inFlight = null;
      this.conflict = null;
      this.lastUpload = 0;
      this.timer = 0;
      this.guard = new SnapshotGuard();
    }

    start() { this.timer = setInterval(() => this.tick(), POLL_MS); }
    stop() { clearInterval(this.timer); }

    /** True if these bytes are a save the game made that the server hasn't got (and didn't refuse). */
    isNew(b) {
      return !!b && meaningfulChange(b, this.synced) && !sameBytes(b, this.refused) && this.guard.isGameSave(b);
    }

    /** True if the game has saved something the server doesn't have yet. */
    hasUnsaved() {
      if (this.conflict) return true;
      if (this.guard.waiting) return false; // mid snapshot load: anything unsent was sent before it
      let b;
      try { b = this.p.readSave(); } catch (e) { return true; } // can't tell: keep the "leave page?" guard
      return this.isNew(b);
    }

    /** A snapshot is about to be loaded: from now until afterSnapshotLoad() nothing is sent. */
    beforeSnapshotLoad() { this.guard.begin(); this.pending = null; }

    /** The snapshot is loaded: the save in memory now is the baseline (never sent as it is). */
    afterSnapshotLoad() {
      try {
        this.guard.setBase(this.p.readSave());
      } catch (e) {
        // Can't read it: stay "waiting", so nothing is sent; tick() tries again.
        this.p.saveReadFailed(e);
      }
    }

    async tick() {
      if (this.conflict || this.inFlight || !this.p.running) return;
      if (this.guard.waiting) { if (!this.p.loadingSnapshot) this.afterSnapshotLoad(); return; }
      let b;
      try { b = this.p.readSave(); } catch (e) { this.p.saveReadFailed(e); return; }
      if (!this.isNew(b)) { this.pending = null; return; }
      if (!sameBytes(b, this.pending)) { // still changing: wait for it to settle
        this.pending = b;
        this.p.status("changed", "Game saved, sending to the server shortly…");
        return;
      }
      if (Date.now() - this.lastUpload < MIN_GAP_MS) return;
      await this.upload(b, {});
    }

    /** Sends the current save now (Save button, tab hidden, page closing). */
    async flush(opts) {
      opts = opts || {};
      if (this.conflict) return false;
      if (this.inFlight) await this.inFlight.catch(() => {});
      if (this.guard.waiting) return true; // mid snapshot load: nothing of the game's own to send
      let b;
      try { b = this.p.readSave(); } catch (e) { this.p.saveReadFailed(e); return false; }
      if (!this.isNew(b)) {
        if (opts.manual) this.p.status("ok", this.base ? "Save is up to date on the server (version " + this.base + ")." : "Nothing to save yet.");
        return true;
      }
      return this.upload(b, { keepalive: opts.closing && b.length <= KEEPALIVE_MAX });
    }

    upload(bytes, opts) {
      const run = (async () => {
        this.p.status("saving", "Saving to the server…");
        let r;
        try {
          r = await api.putSave(this.id, bytes, { base: this.base, keepalive: opts.keepalive, afterSnapshot: this.guard.after, cheats: this.p.cheatsUsed });
        } catch (e) {
          if ([413, 422, 507].includes(e.status)) {
            this.refused = bytes;
            if (e.code === "blank") { this.p.status("ok", "Nothing to save yet."); return true; }
            this.p.status("error", "The server refused this save: " + e.message);
          } else {
            this.p.status("error", (e.status === 0 ? "Can't reach the server. " : e.message + " ") + "Your save is safe in this tab; it will retry.");
          }
          return false;
        }
        if (r.status === 409) {
          this.enterConflict(bytes, r.body.current);
          return false;
        }
        this.base = r.body.current ? r.body.current.version : this.base;
        this.synced = bytes;
        this.pending = null;
        this.lastUpload = Date.now();
        this.guard.clear(); // the game's own save is on the server: back to the normal rule
        this.p.cheatsUsed = this.p.activeCheats().length > 0; // a later save counts as "with cheats" only while some are on
        const warn = (r.body.warnings || []).join(" ");
        this.p.status("saved", "Saved to the server (version " + this.base + ")." +
          (this.guard.after ? " Saved after loading a snapshot." : "") + (warn ? " " + warn : ""));
        this.p.emit("saved", { version: this.base, status: r.body.status });
        return true;
      })();
      this.inFlight = run;
      return run.finally(() => { if (this.inFlight === run) this.inFlight = null; });
    }

    enterConflict(local, server) {
      this.conflict = { local, server };
      this.p.pause();
      this.p.status("conflict", "This save was changed somewhere else. Choose which one to keep.");
      this.p.emit("conflict", { server, localSize: local.length });
    }

    /**
     * The person's choice after a 409. Nothing is lost either way: the copy not kept goes into history.
     *  "browser": this tab's save becomes current (the server's goes to history).
     *  "server":  this tab's save goes to history; the server's save is loaded and the game restarts.
     */
    async resolve(choice) {
      const c = this.conflict;
      if (!c) return;
      try {
        if (choice === "browser") {
          const r = await api.putSave(this.id, c.local, { mode: "force", afterSnapshot: this.guard.after });
          this.base = r.body.current ? r.body.current.version : this.base;
          this.synced = c.local;
          this.guard.clear();
        } else if (choice === "server") {
          await api.putSave(this.id, c.local, { mode: "history", afterSnapshot: this.guard.after });
          const s = await api.getSave(this.id);
          this.p.loadSave(s.bytes);
          this.base = s.version;
          this.synced = s.bytes;
          this.guard.reset(); // the game restarts on the server's save: nothing from the snapshot is left
        } else {
          throw new Error("choice must be browser or server");
        }
      } catch (e) {
        this.p.status("error", "Couldn't finish that: " + e.message + " Nothing has been lost; try again.");
        return;
      }
      this.conflict = null;
      this.pending = null;
      this.refused = null;
      this.lastUpload = Date.now();
      this.p.status("saved", "Kept the " + (choice === "browser" ? "save from this browser" : "save from the server") + " (version " + this.base + "). The other copy stays in the list.");
      this.p.emit("resolved", { choice, version: this.base });
      this.p.resume();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // EmulatorJS loading
  // ---------------------------------------------------------------------------------------------
  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = src;
      s.async = false;
      s.onload = resolve;
      s.onerror = () => reject(new Error("Couldn't load " + src));
      document.head.appendChild(s);
    });
  }

  let ejsLoaded = null;
  let workerTag = "0";
  function loadEmulatorJs() {
    if (ejsLoaded) return ejsLoaded;
    ejsLoaded = (async () => {
      const css = document.createElement("link");
      css.rel = "stylesheet";
      css.href = EJS_DIR + "emulator.css";
      document.head.appendChild(css);
      for (const f of EJS_SCRIPTS) await loadScript(EJS_DIR + f);
      patchEmulatorJs();
    })();
    return ejsLoaded;
  }

  /** Changes to EmulatorJS's behaviour, made once on its classes. */
  function patchEmulatorJs() {
    const EJS = window.EmulatorJS;
    const GM = window.EJS_GameManager;
    // Never call out to emulatorjs.org (the CSP blocks it anyway).
    EJS.prototype.checkForUpdates = function () {};
    // The cores' RetroArch keeps the game canvas at CSS-pixel size but multiplies every mouse and touch position
    // by window.devicePixelRatio. On phones (ratio 2-3) and scaled PC screens (1.25-1.5) DS touches landed far
    // down-right of the finger, often off the screen. Nothing else on this page reads the ratio, and the canvas
    // really is at ratio 1, so say so.
    Object.defineProperty(window, "devicePixelRatio", { get: () => 1, configurable: true });
    // EmulatorJS starts its unzip workers from blob: copies, which inherit this page's no-eval policy and
    // fail (the old Emscripten unzip code needs eval). Start them from their own URL instead: the server
    // gives just those two worker files their own policy (eval allowed, nothing else - see WebController).
    window.EJS_COMPRESSION.prototype.decompressFile = function (method, data, updateMsg, fileCbFunc) {
      const file = { "7z": "compression/extract7z.js", zip: "compression/extractzip.js" }[method];
      if (!file) {
        this.EJS.startGameError("This file type (" + method + ") isn't supported. Use .zip or .7z.");
        return new Promise(() => {});
      }
      return new Promise(callback => {
        // "?v=" so a browser never reuses a copy cached before the worker headers changed.
        const worker = new Worker(EJS_DIR + file + "?v=" + encodeURIComponent(workerTag));
        const files = {};
        worker.onerror = e => {
          console.error("Shoalcade: unzip failed", e.message);
          this.EJS.startGameError("Couldn't unpack " + (method === "7z" ? "the emulator" : "the game") + ".");
        };
        worker.onmessage = msg => {
          const d = msg.data;
          if (!d) return;
          if (d.t === 4) { // progress
            const num = Math.floor(d.current / d.total * 100);
            if (!isNaN(num)) updateMsg(" " + num + "%", true);
          } else if (d.t === 2) { // one file
            if (typeof fileCbFunc === "function") { fileCbFunc(d.file, d.data); files[d.file] = true; }
            else files[d.file] = d.data;
          } else if (d.t === 1) { // done
            worker.terminate();
            callback(files);
          }
        };
        worker.postMessage(data);
      });
    };
    // Saves live in memory only, never IndexedDB: the server is the one store.
    GM.prototype.mountFileSystems = function () {
      this.mkdir("/data");
      this.mkdir("/data/saves");
      this.FS.mount(this.FS.filesystems.MEMFS, {}, "/data/saves");
      return Promise.resolve();
    };
    // RetroArch would put saves in a per-core folder (/data/saves/mGBA/...). Keep them flat.
    const cfg = GM.prototype.getRetroArchCfg;
    GM.prototype.getRetroArchCfg = function () {
      // Centre the picture in a tall game area too (the page sizes and places the game area itself).
      // (RetroArch keeps the first copy of a setting, so EmulatorJS's own line is changed, not added to.)
      return cfg.apply(this, arguments).replace(/^video_top_portrait_viewport = .*$/m, "video_top_portrait_viewport = false") +
        "sort_savefiles_enable = false\nsort_savefiles_by_content_enable = false\n";
    };
    // Always run the ROM as /game.<ext> (a zipped ROM keeps its own name inside the zip), so the
    // core's save file is always /data/saves/game.<save ext>.
    const startGame = EJS.prototype.startGame;
    EJS.prototype.startGame = function () {
      try {
        const name = String(this.fileName || "").replace(/^\/+/, "");
        const ext = name.includes(".") ? name.split(".").pop() : "";
        const want = "game" + (ext ? "." + ext : "");
        if (name && name !== want) {
          this.gameManager.FS.rename("/" + name, "/" + want);
          this.fileName = want;
        }
      } catch (e) {
        console.warn("Shoalcade: couldn't rename the ROM", e);
      }
      return startGame.apply(this, arguments);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // The player
  // ---------------------------------------------------------------------------------------------
  function isPlainObject(v) { return v !== null && typeof v === "object" && !Array.isArray(v); }

  /**
   * Settings merge after a failed load: `server` is the base, every key in `mine` wins, and plain
   * objects are merged key by key (so `ds: {layout}` set offline keeps the server's `ds.swap`).
   * Lists and plain values from `mine` replace the server's whole value.
   */
  function mergePrefs(server, mine) {
    const out = Object.assign({}, server);
    for (const k of Object.keys(mine)) {
      out[k] = isPlainObject(mine[k]) && isPlainObject(server[k]) ? mergePrefs(server[k], mine[k]) : mine[k];
    }
    return out;
  }

  const ROM_EXT = { gb: "gb", gbc: "gbc", gba: "gba", nds: "nds", psx: "chd" };
  /**
   * Core settings every game of a console starts with. melonDS's own default touch mode ("Mouse") moves a
   * hidden cursor by how far the mouse moves while the pointer is locked, so clicks and taps never landed where
   * they were made (or at all, for fingers). "Touch" touches the exact spot clicked or tapped.
   */
  const CORE_DEFAULTS = { nds: { melonds_touch_mode: "Touch" } };
  /**
   * Core settings that are always set, after everything else (D137). PS1: the built-in (HLE) BIOS, so no BIOS file is
   * ever needed or sent (never set EmulatorJS's biosUrl), and memory card 2 off, so the one save synced is card 1 = the
   * core's save RAM (the same 128 KB card the app keeps). The app's newer core names these memcard1 "libretro" and
   * memcard2 "none"; this EmulatorJS 4.2.3 build (June 2025) has no memcard1 option (card 1 is always the save RAM)
   * and its memcard2 takes "disabled" / "enabled". Checked against the core's own option list by
   * tools/testbed/browser_ps1.mjs.
   */
  const CORE_FORCED = { psx: { pcsx_rearmed_bios: "HLE", pcsx_rearmed_memcard2: "disabled" } };
  const BIG_GAME_BYTES = 300 * 1024 * 1024; // above this, say the game needs a lot of memory (PS1 discs)
  const NO_BUTTONS = ["playPause", "restart", "mute", "settings", "fullscreen", "saveState", "loadState",
    "screenRecord", "gamepad", "cheat", "volumeSlider", "saveSavFiles", "loadSavFiles", "quickSave",
    "quickLoad", "screenshot", "cacheManager", "exitEmulation", "netplay", "diskButton"]
    .reduce((o, k) => { o[k] = false; return o; }, {});

  /** A big game (a PS1 disc) is held in the browser's memory while it plays: say so, so a phone that runs out isn't a mystery. */
  function bigGameNote(bytes) {
    return "This is a big game (" + Math.round(bytes / (1024 * 1024)) + " MB). It is kept in this browser's memory while you play, so on a phone it may be slow to start or not start. The phone app plays it straight from storage.";
  }

  const SNAP_SLOTS = ["1", "2", "3", "auto"];
  const AUTO_GAP_MS = 60000;   // least time between automatic snapshots when the tab is hidden

  class Player {
    constructor(container) {
      this.container = container;
      this.handlers = {};
      this.state = "idle";
      this.running = false;
      this.paused = false;
      this.ff = false;
      this.ffSpeed = 2;
      this.coreOptions = {};   // core settings applied at start (DS screen layout); set by the UI
      this.emu = null;
      this.sync = null;
      this.input = null;
      this.savePath = null;
      this.lastAuto = 0;
      this.prefsLoadFailed = false; // true while the server's settings couldn't be read: nothing is written back
      this.prefsRetries = 0;
      this.prefsRetryTimer = 0;
      this.prefsReloading = null;
      this.cheats = null;        // { cheats: [...], active: [ids] } from the server (D143), or null if not available
      this.cheatsNote = "";      // why cheats aren't available, if they aren't
      this.cheatsUsed = false;   // a cheat was on since the last save reached the server (that save is marked)
      this.appliedCodes = [];    // codes the core has now (browser DS can't take one back until the game is started again)
    }

    /** Tries the settings load again later (5 s, 15 s, 30 s, 60 s, then every 2 minutes). */
    schedulePrefsRetry() {
      clearTimeout(this.prefsRetryTimer);
      const wait = [5000, 15000, 30000, 60000][this.prefsRetries++] || 120000;
      this.prefsRetryTimer = setTimeout(() => {
        this.reloadPrefs().then(ok => { if (!ok) this.schedulePrefsRetry(); });
      }, wait);
    }

    /**
     * After a failed settings load: reads them again. On success the server copy is the base and only
     * the keys changed in this tab meanwhile are laid over it, key by key at every depth (they started
     * from nothing, so every key this tab has was set here). So an offline DS layout change keeps the
     * server's `ds.swap`. Returns true when settings are safe to save.
     */
    reloadPrefs() {
      if (!this.prefsLoadFailed) return Promise.resolve(true);
      if (this.prefsReloading) return this.prefsReloading;
      const run = (async () => {
        let server;
        try { server = await api.prefs(this.game.console); } catch (e) { return false; }
        server = server && typeof server === "object" ? server : {};
        // In place, so any code still holding this.prefs sees the merged copy.
        const merged = mergePrefs(server, this.prefs);
        for (const k of Object.keys(this.prefs)) delete this.prefs[k];
        Object.assign(this.prefs, merged);
        this.prefsLoadFailed = false;
        clearTimeout(this.prefsRetryTimer);
        if (this.prefs.controls) this.setControls(this.prefs.controls);
        this.emit("prefs", this.prefs);
        this.emit("prefsLoaded", this.prefs);
        return true;
      })();
      this.prefsReloading = run;
      return run.finally(() => { this.prefsReloading = null; });
    }

    on(evt, fn) { (this.handlers[evt] = this.handlers[evt] || []).push(fn); }
    emit(evt, data) { (this.handlers[evt] || []).forEach(fn => { try { fn(data); } catch (e) { console.error(e); } }); }
    status(kind, text) { this.emit("status", { kind, text }); }
    setState(s, extra) { this.state = s; this.emit("state", Object.assign({ state: s }, extra || {})); }

    fail(e) {
      const msg = e && e.message ? e.message : String(e);
      this.setState("error", { message: msg, signIn: e && e.status === 401 });
      this.status("error", msg);
    }

    /** Step 1: everything that can be checked and downloaded before the person presses Play. */
    async prepare(itemId) {
      try {
        if (!/^[0-9a-f]{32}$/i.test((itemId || "").replace(/-/g, ""))) throw new Error("No game chosen.");
        this.itemId = itemId.replace(/-/g, "").toLowerCase();
        if (!api.hasLogin()) throw new api.ApiError(401, "signed_out", "Sign in to Jellyfin first, then come back to this page.");
        this.setState("loading");
        this.status("loading", "Loading game…");
        const [info, game] = await Promise.all([api.info(), api.game(this.itemId)]);
        this.info = info;
        workerTag = info.serverVersion || "0";
        api.showVersion(info);
        this.game = game;
        this.emit("game", game);
        const c = (info.consoles || []).find(x => x.key === game.console);
        if (!info.browserPlayer) throw new Error("The browser player is switched off on this server.");
        if (!game.browserPlayable || !c || !c.browserCore) throw new Error("This game can't be played in the browser. Use the Shoalcade app.");
        if (!info.user.canDownload) throw new Error("Your Jellyfin account isn't allowed to download, so games can't be played here. Ask the server admin.");
        this.consoleInfo = c;
        this.prefs = {};
        try {
          const prefs = await api.prefs(game.console);
          if (prefs && typeof prefs === "object") this.prefs = prefs;
        } catch (e) {
          // Never treat a failed load as "no settings": writing that back would wipe the server copy (H1).
          // Defaults are used for this visit and nothing is saved until a later load works.
          console.warn("Shoalcade: settings didn't load", e.message);
          this.prefsLoadFailed = true;
          this.schedulePrefsRetry();
        }
        this.emit("prefs", this.prefs);
        await this.loadCheats();
        this.rom = await api.rom(this.itemId, (got, total) => {
          this.status("loading", "Downloading game… " + (total ? Math.round(100 * got / total) + "%" : Math.round(got / 1024) + " KB")
            + (total > BIG_GAME_BYTES ? " " + bigGameNote(total) : ""));
        });
        this.launchSnapshots = await this.launchChoices();
        this.setState("ready");
        const big = this.rom.bytes.length > BIG_GAME_BYTES ? " " + bigGameNote(this.rom.bytes.length) : "";
        this.status("ready", (this.prefsLoadFailed
          ? "Ready to play. Your settings couldn't be loaded, so defaults are used and changes won't be saved until the server answers."
          : "Ready to play.") + big);
      } catch (e) {
        this.fail(e);
      }
    }

    /**
     * Step 2 (after a click, so the browser allows sound): fetch the save and boot the emulator.
     * snapshotSlot = the launch choice (D90): start from that snapshot instead of straight from the save.
     */
    async start(snapshotSlot) {
      if (this.state !== "ready") return;
      this.setState("starting");
      try {
        this.status("loading", "Getting your save…");
        const save = await api.getSave(this.itemId);
        this.serverSave = save;
        this.sync = new SaveSync(this, this.itemId, save.version, save.bytes);
        this.startSnapshot = null;
        if (snapshotSlot) {
          this.status("loading", "Getting the snapshot…");
          try {
            this.startSnapshot = { slot: snapshotSlot, bytes: await this.fetchSnapshot(snapshotSlot) };
            this.sync.beforeSnapshotLoad(); // from boot until the snapshot is in, nothing counts as a save
            this.loadingSnapshot = true;
          } catch (e) {
            this.startError = "Couldn't get the snapshot (" + e.message + "), so the game started from your save.";
          }
        }
        this.status("loading", "Starting the emulator…");
        await loadEmulatorJs();
        this.boot();
      } catch (e) {
        this.fail(e);
      }
    }

    boot() {
      const romName = this.rom.name || "";
      let ext = romName.includes(".") ? romName.split(".").pop().toLowerCase() : "";
      if (!/^[a-z0-9]{1,5}$/.test(ext)) ext = ROM_EXT[this.game.console] || "bin";
      // The bytes go to EmulatorJS as they are, named by gameName (never as a File/Blob: a big PS1 disc as a Blob is
      // one more full copy, and Chrome may move a big Blob to disk and then fail to read it back, D137).
      const bytes = this.rom.bytes;
      this.rom = null; // EmulatorJS copies it into its own file system and lets go of this one

      // Registered before EmulatorJS's own listener, which would restart the game and unmount the
      // saves on "beforeunload" even if the person then chooses to stay.
      window.addEventListener("beforeunload", e => this.onBeforeUnload(e), true);
      window.addEventListener("pagehide", () => this.onPageHide());
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState !== "hidden" || !this.running) return;
        this.sync.flush({});
        if (Date.now() - this.lastAuto > AUTO_GAP_MS) this.autoSnapshot().catch(() => {});
      });

      const vol = typeof this.prefs.volume === "number" ? this.prefs.volume : 0.5;
      const emu = new window.EmulatorJS("#" + this.container.id, {
        gameUrl: bytes,
        gameName: "game." + ext,
        dataPath: EJS_DIR,
        system: this.consoleInfo.browserCore,
        startOnLoad: true,
        defaultControllers: { 0: {}, 1: {}, 2: {}, 3: {} },
        buttonOpts: Object.assign({}, NO_BUTTONS),
        disableDatabases: true,
        disableLocalStorage: true,
        threads: false,
        volume: vol,
        defaultOptions: Object.assign({}, CORE_DEFAULTS[this.game.console], this.coreOptions, CORE_FORCED[this.game.console]),
      });
      this.emu = emu;
      emu.storage.states = new window.EJS_DUMMYSTORAGE();

      emu.on("saveDatabaseLoaded", FS => {
        // The core is loaded by now, so its save extension is known.
        this.savePath = "/data/saves/game." + (emu.saveFileExt || "srm");
        if (this.serverSave.bytes) FS.writeFile(this.savePath, this.serverSave.bytes);
      });
      emu.on("start", () => this.onStart());
      emu.on("exit", () => { this.running = false; });
      // EmulatorJS shows its own error text in the game area; report it too.
      const startGameError = emu.startGameError.bind(emu);
      emu.startGameError = msg => { startGameError(msg); this.fail(new Error("The emulator couldn't start: " + msg)); };
    }

    onStart() {
      const gm = this.emu.gameManager;
      const real = gm.getSaveFilePath();
      if (real && real !== this.savePath) {
        // Safety net: the core uses a different save name than expected. Put the save where it looks.
        console.warn("Shoalcade: core save path is", real, "not", this.savePath);
        this.savePath = real;
        if (this.serverSave.bytes) this.loadSave(this.serverSave.bytes);
      }
      this.serverSave = null;
      gm.setFastForwardRatio(this.ffSpeed);
      // Never lock the mouse pointer to the game: it hides the cursor and breaks touching the DS screen.
      this.emu.enableMouseLock = false;
      this.input = new Input(this.game.console, this.prefs.controls, (id, v) => this.emu.gameManager.simulateInput(0, id, v));
      this.input.attach();
      this.running = true;
      this.sync.start();
      this.setState("playing");
      if (this.startSnapshot) {
        const s = this.startSnapshot;
        this.startSnapshot = null;
        this.status("loading", "Loading the snapshot…");
        this.startFromSnapshot(s.slot, s.bytes);
      } else if (this.startError) {
        this.applyCheats();
        this.status("error", this.startError);
      } else {
        this.applyCheats();
        this.status("ok", this.sync.base ? "Playing with your server save (version " + this.sync.base + ")." : "Playing. No save on the server yet.");
      }
    }

    /**
     * The game's save as the core has it right now (flushed first), or null if the game hasn't made one.
     * Throws if the save couldn't be read: that is never treated as "no save".
     */
    readSave() {
      if (!this.emu || !this.emu.gameManager || !this.savePath) return null;
      const gm = this.emu.gameManager;
      gm.functions.saveSaveFiles();
      const p = gm.getSaveFilePath() || this.savePath;
      if (!gm.FS.analyzePath(p).exists) return null;
      return gm.FS.readFile(p);
    }

    /** Reading the save from the emulator failed: say so (once per error text) and keep trying. */
    saveReadFailed(e) {
      const msg = e && e.message ? e.message : String(e);
      if (msg !== this.lastReadError) console.warn("Shoalcade: couldn't read the game's save", msg);
      this.lastReadError = msg;
      this.status("error", "Couldn't read the game's save from the emulator (" + msg + "). Don't close this tab yet; Shoalcade keeps trying.");
    }

    /** Replaces the running game's save and restarts the game so it reads it. */
    loadSave(bytes) {
      const gm = this.emu.gameManager;
      const p = gm.getSaveFilePath() || this.savePath;
      try { gm.FS.unlink(p); } catch (e) { /* none yet */ }
      if (bytes) gm.FS.writeFile(p, bytes);
      gm.loadSaveFiles();
      gm.restart();
    }

    // ----- controls the UI calls (window.SC.player) -----
    /**
     * Pauses the game. A still of the game is taken first (the core can't make one while paused), so the
     * page can show it: the game's canvas goes blank if it's resized while paused.
     */
    async pause() {
      if (this.paused || !this.emu) return;
      this.paused = true;
      if (this.input) this.input.setEnabled(false);
      if (this.ff) this.fastForward(false);
      let picture = null;
      if (this.running) {
        picture = await this.grabPicture(600).catch(() => null);
      }
      if (!this.paused) return; // resumed meanwhile
      this.emu.pause(true);
      this.pausePicture = picture;
      this.emit("paused", { paused: true, picture });
    }

    resume() {
      if (!this.emu || (this.sync && this.sync.conflict)) return;
      const was = this.paused;
      this.paused = false;
      this.emu.play(true);
      if (this.input) this.input.setEnabled(true);
      if (was) this.emit("paused", { paused: false });
    }

    togglePause() { if (this.paused) this.resume(); else this.pause(); }
    restart() { if (this.emu && this.emu.gameManager) this.emu.gameManager.restart(); }
    setVolume(v) { if (this.emu) this.emu.setVolume(Math.max(0, Math.min(1, v))); }

    fastForward(on) {
      on = !!on && !this.paused;
      if (this.emu && this.emu.gameManager && this.running) this.emu.gameManager.toggleFastForward(on ? 1 : 0);
      if (on !== this.ff) { this.ff = on; this.emit("ff", on); }
    }

    /** Fast-forward speed (2-4x). */
    setFastForwardSpeed(n) {
      this.ffSpeed = Math.max(2, Math.min(4, Number(n) || 2));
      if (this.emu && this.emu.gameManager && this.running) this.emu.gameManager.setFastForwardRatio(this.ffSpeed);
    }

    /** Changes a core setting while playing (e.g. melonds_screen_layout). Also used at the next start. */
    setCoreOption(name, value) {
      this.coreOptions[name] = value;
      if (this.emu && this.emu.gameManager && this.running) this.emu.gameManager.setVariable(name, value);
    }

    /** Live keyboard/controller maps after a rebind (same shape as prefs.controls). */
    setControls(controls) {
      this.prefs.controls = controls;
      if (this.input) this.input.setControls(controls);
    }

    /** Turns game input off while the person types in the settings panel (and back on after). */
    setInputEnabled(on) { if (this.input) this.input.setEnabled(on && !this.paused); }

    /** A touch button: name (A, B, Up...) and pressed or not. */
    touch(button, on) { if (this.input) this.input.setTouch(button, on); }

    /** A PNG of the game picture (core output, no scaling). */
    async screenshot() {
      if (!this.running) throw new Error("The game isn't running.");
      if (this.paused) {
        if (this.pausePicture) return this.pausePicture;
        throw new Error("Resume the game first.");
      }
      return this.grabPicture(3000);
    }

    /**
     * Asks the core for a PNG and waits at most ms for it. Same steps as EmulatorJS's own
     * gameManager.screenshot(), but that one polls every 50 ms for ever if no picture comes (L7).
     */
    grabPicture(ms) {
      const gm = this.emu.gameManager;
      const until = Date.now() + ms;
      return new Promise((resolve, reject) => {
        try { gm.FS.unlink("/screenshot.png"); } catch (e) { /* none yet */ }
        gm.functions.screenshot(); // a throw here rejects the promise
        const check = () => {
          let done = false;
          try { gm.FS.stat("/screenshot.png"); done = true; } catch (e) { /* not written yet */ }
          if (done) { try { resolve(gm.FS.readFile("/screenshot.png")); } catch (e) { reject(e); } return; }
          if (Date.now() >= until) { reject(new Error("The emulator didn't make a picture.")); return; }
          setTimeout(check, 50);
        };
        check();
      });
    }

    saveNow() { return this.sync ? this.sync.flush({ manual: true }) : Promise.resolve(false); }
    resolveConflict(choice) { return this.sync ? this.sync.resolve(choice) : Promise.resolve(); }

    // ----- snapshots (save states, D37/D38): slots 1-3 + auto, on the server -----
    /** Core name stored with a snapshot: a snapshot only loads in the same core build. */
    get snapshotCore() {
      return (this.consoleInfo.browserCore + "-ejs" + String(this.info.emulatorJs || "")).replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 32);
    }

    get supportsSnapshots() {
      try { return !!(this.emu && this.emu.gameManager && this.emu.gameManager.functions.supportsStates()); } catch (e) { return false; }
    }

    statePath(slot) { return "/shoalcade/state/" + encodeURIComponent(this.itemId) + (slot ? "/" + encodeURIComponent(slot) : ""); }

    /** { enabled, snapshots: [{ slot, size, created, core, hasImage }] } */
    async snapshots() {
      return (await api.request(this.statePath())).json();
    }

    /**
     * The snapshots offered at launch (D90), newest first: only ones this core can load. Empty if snapshots are
     * off or the list can't be read (then the game simply starts from the save, as before).
     */
    async launchChoices() {
      if (this.prefs.snapshots === false) return [];
      try {
        const d = await this.snapshots();
        if (!d || !d.enabled) return [];
        return (d.snapshots || [])
          .filter(s => s && SNAP_SLOTS.includes(s.slot) && !s.damaged && (!s.core || s.core === this.snapshotCore))
          .sort((a, b) => (Date.parse(b.created) || 0) - (Date.parse(a.created) || 0));
      } catch (e) {
        console.warn("Shoalcade: couldn't list snapshots", e.message);
        return [];
      }
    }

    async saveSnapshot(slot) {
      if (!SNAP_SLOTS.includes(slot)) throw new Error("Unknown slot.");
      if (!this.running) throw new Error("The game isn't running.");
      const data = this.emu.gameManager.getState();
      let picture = this.paused ? this.pausePicture : null;
      let pictureFailed = false;
      if (!this.paused) {
        try { picture = await this.screenshot(); } catch (e) { pictureFailed = true; /* the snapshot still counts */ }
      }
      const res = await api.request(this.statePath(slot) + "?core=" + encodeURIComponent(this.snapshotCore), {
        method: "PUT", headers: { "Content-Type": "application/octet-stream" }, body: data,
      });
      const info = await res.json();
      if (picture) {
        try {
          await api.request(this.statePath(slot) + "/image", { method: "PUT", headers: { "Content-Type": "image/png" }, body: picture });
          info.hasImage = true;
        } catch (e) { pictureFailed = true; /* picture is optional */ }
      }
      const on = this.activeCheats();
      if (on.length) {
        try {
          const r = await api.request(this.statePath(slot) + "/cheats", {
            method: "PUT", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ cheats: on.map(c => ({ name: c.name, code: c.code })) }),
          });
          info.cheats = (await r.json()).cheats;
        } catch (e) { info.cheatsFailed = true; /* the snapshot still counts, without its cheats list */ }
      }
      if (pictureFailed) info.pictureFailed = true; // the page says "without a picture" (L9)
      this.emit("snapshot", { slot, info });
      return info;
    }

    /**
     * Loads a snapshot (D90). Any save the game made before is sent first: the snapshot can put its own old copy
     * of the save in memory, which would otherwise hide it. Then the save-after-snapshot rule takes over (see
     * SnapshotGuard): only a save the game writes after this is ever sent.
     */
    async loadSnapshot(slot) {
      if (!this.running) throw new Error("The game isn't running.");
      if (this.loadingSnapshot) throw new Error("A snapshot is already loading.");
      if (this.sync.conflict) throw new Error("Choose which save to keep first.");
      this.loadingSnapshot = true;
      try {
        const bytes = await this.fetchSnapshot(slot);
        let snapInfo = null;
        try { snapInfo = ((await this.snapshots()).snapshots || []).find(s => s.slot === slot) || {}; } catch (e) { /* cheats stay as they are */ }
        const sent = await this.sync.flush({});
        if (this.sync.conflict || (!sent && this.sync.hasUnsaved())) {
          throw new Error("Your latest in-game save hasn't reached the server yet, so the snapshot wasn't loaded " +
            "(it would hide that save). Try again in a moment.");
        }
        this.sync.beforeSnapshotLoad();
        await this.applySnapshot(bytes);
        if (snapInfo !== null) await this.useSnapshotCheats(snapInfo);
      } finally {
        this.loadingSnapshot = false;
      }
      if (this.paused) this.resume();
      this.emit("snapshotLoaded", { slot });
      this.status("ok", "Snapshot loaded. Your save on the server only changes when you save in the game.");
    }

    /** A snapshot's bytes, refused if another core build made it. */
    async fetchSnapshot(slot) {
      const res = await api.request(this.statePath(slot));
      const core = res.headers.get("X-Shoalcade-Core");
      if (core && core !== this.snapshotCore) {
        throw new Error("This snapshot was made by a different emulator version, so it can't be loaded.");
      }
      return new Uint8Array(await res.arrayBuffer());
    }

    /** Loads the bytes into the core (the guard is already waiting), then takes the save baseline. */
    async applySnapshot(bytes) {
      try {
        this.emu.gameManager.loadState(bytes);
        await this.waitFrames(10, 2000); // let the core finish the load before the save is read
      } finally {
        this.loadingSnapshot = false;
        this.sync.afterSnapshotLoad();
      }
    }

    /**
     * Launch choice "a snapshot" (D90): the game boots on the server save as always, then the snapshot is loaded.
     * The guard was set before boot, so nothing the game does in between counts as a save.
     */
    async startFromSnapshot(slot, bytes) {
      try {
        await this.waitFrames(30, 3000);
        await this.applySnapshot(bytes);
        await this.useSnapshotCheats((this.launchSnapshots || []).find(s => s.slot === slot));
        this.emit("snapshotLoaded", { slot });
        this.status("ok", "Started from the snapshot. Your save on the server only changes when you save in the game.");
      } catch (e) {
        // Back to the normal rule: the game is running on the server save.
        this.loadingSnapshot = false;
        this.sync.guard.reset();
        this.applyCheats();
        this.status("error", "Couldn't load the snapshot (" + (e && e.message ? e.message : e) + "), so the game started from your save.");
      }
    }

    /** Waits until the core has drawn n more frames (or ms have passed, e.g. while paused). */
    waitFrames(n, ms) {
      const gm = this.emu.gameManager;
      let start;
      try { start = gm.getFrameNum(); } catch (e) { return Promise.resolve(); }
      const until = Date.now() + ms;
      return new Promise(resolve => {
        const check = () => {
          let now = start;
          try { now = gm.getFrameNum(); } catch (e) { /* treat as no progress */ }
          if (now - start >= n || Date.now() >= until) resolve(); else setTimeout(check, 30);
        };
        check();
      });
    }

    // ---------- cheats (D143, docs/cheats-api.md) ----------

    /** Reads this game's cheats from the server. Never stops the game from loading: on failure cheats are just off. */
    async loadCheats() {
      this.cheats = null;
      if (!(this.info.features || []).includes("cheats")) {
        this.cheatsNote = "This Shoalcade server is too old for cheats. Ask your Jellyfin admin to update the plugin.";
        return;
      }
      try {
        this.cheats = await api.cheats(this.itemId);
        this.cheatsNote = "";
      } catch (e) {
        this.cheatsNote = "Couldn't load your cheats: " + e.message;
      }
      this.emit("cheats", this.cheats);
    }

    /** The cheats that are on, in order. */
    activeCheats() {
      if (!this.cheats) return [];
      const byId = new Map(this.cheats.cheats.map(c => [c.id, c]));
      return this.cheats.active.map(id => byId.get(id)).filter(Boolean);
    }

    /**
     * Gives the core exactly the cheats that are on: clear them all, then send each with numbers 0, 1, 2…
     * (switching one off on its own doesn't work in every emulator). A code's lines are joined with "+".
     * Returns true if a cheat that was on couldn't be switched off (browser DS: only leaving the game and starting it again does that).
     */
    applyCheats() {
      if (!this.running || !this.emu || !this.emu.gameManager) return false;
      const codes = this.activeCheats().map(c => c.code.split("\n").join("+"));
      const gm = this.emu.gameManager;
      try {
        gm.resetCheat();
        codes.forEach((code, i) => gm.setCheat(i, 1, code));
      } catch (e) {
        console.warn("Shoalcade: couldn't apply cheats", e.message);
      }
      const stuck = this.game.console === "nds" && this.appliedCodes.some(c => !codes.includes(c));
      this.appliedCodes = stuck ? Array.from(new Set(this.appliedCodes.concat(codes))) : codes;
      if (codes.length) this.cheatsUsed = true;
      this.emit("cheats", this.cheats);
      return stuck;
    }

    /** Turns cheats on or off for this game (the in-game save's list), then applies them. */
    async setActiveCheats(ids) {
      this.cheats = await api.setActiveCheats(this.itemId, ids);
      return this.applyCheats();
    }

    /** Adds a cheat to the game's list (and turns it on if asked). Returns { cheat, stuck }. */
    async addCheat(name, code, turnOn) {
      const c = await api.addCheat(this.itemId, name, code);
      this.cheats = await api.cheats(this.itemId);
      if (turnOn && !this.cheats.active.includes(c.id)) return { cheat: c, stuck: await this.setActiveCheats(this.cheats.active.concat(c.id)) };
      this.emit("cheats", this.cheats);
      return { cheat: c, stuck: false };
    }

    async editCheat(id, changes) {
      await api.editCheat(this.itemId, id, changes);
      this.cheats = await api.cheats(this.itemId);
      if (this.cheats.active.includes(id)) return this.applyCheats();
      this.emit("cheats", this.cheats);
      return false;
    }

    async deleteCheat(id) {
      const wasOn = !!this.cheats && this.cheats.active.includes(id);
      await api.deleteCheat(this.itemId, id);
      this.cheats = await api.cheats(this.itemId);
      if (wasOn) return this.applyCheats();
      this.emit("cheats", this.cheats);
      return false;
    }

    /**
     * After a snapshot loads: exactly the cheats that were on when it was taken (none for a snapshot without any).
     * Any not in the game's list yet are added back. If the server can't be reached, they still go on for this play.
     */
    async useSnapshotCheats(info) {
      if (!this.cheats) return;
      const want = (info && info.cheats) || [];
      try {
        const ids = [];
        for (const c of want) ids.push((await api.addCheat(this.itemId, c.name, c.code)).id);
        this.cheats = await api.setActiveCheats(this.itemId, ids);
      } catch (e) {
        const extra = want.map((c, i) => ({ id: "snap" + i, name: c.name, code: c.code }));
        this.cheats = { cheats: this.cheats.cheats.concat(extra), active: extra.map(c => c.id) };
        console.warn("Shoalcade: couldn't store the snapshot's cheats", e.message);
      }
      if (this.applyCheats()) this.status("ok", "Snapshot loaded. A DS cheat that was on stays on until you leave the game and start it again.");
    }

    async deleteSnapshot(slot) {
      await api.request(this.statePath(slot), { method: "DELETE" });
    }

    /** The snapshot's picture as a Blob (the image route needs the login header, so no plain URL). */
    async snapshotImage(slot) {
      return (await api.request(this.statePath(slot) + "/image")).blob();
    }

    /** Automatic snapshot (slot "auto"), if snapshots are on for this console. Never throws for "off". */
    async autoSnapshot() {
      if (!this.running || this.prefs.snapshots === false || !this.supportsSnapshots) return null;
      this.lastAuto = Date.now();
      try {
        return await this.saveSnapshot("auto");
      } catch (e) {
        if (e.status !== 403) console.warn("Shoalcade: automatic snapshot failed", e.message);
        return null;
      }
    }

    /**
     * Getting ready to leave the page (Back to library): sends the save and takes the automatic snapshot,
     * each given a few seconds. Returns true if the save is safely on the server.
     */
    async leave() {
      if (!this.running || !this.sync) return true;
      const wait = (p, ms) => Promise.race([p, new Promise(r => setTimeout(() => r("timeout"), ms))]);
      const saved = await wait(this.sync.flush({}), 8000);
      await wait(this.autoSnapshot(), 5000);
      return saved === true && !this.sync.hasUnsaved();
    }

    onBeforeUnload(e) {
      // Stop EmulatorJS's own handler (see boot()).
      e.stopImmediatePropagation();
      if (!this.running || !this.sync) return;
      if (this.sync.hasUnsaved() || this.sync.inFlight) {
        this.sync.flush({ closing: true });
        if (this.leavingAnyway) return; // already answered "Leave anyway" on our own prompt
        e.preventDefault();
        e.returnValue = "Your latest save hasn't reached the server yet.";
      }
    }

    onPageHide() {
      if (this.running && this.sync) this.sync.flush({ closing: true });
    }
  }

  window.SC.Player = Player;
  window.SC.controls = { RETRO, BUTTONS, DEFAULT_KEYS, DEFAULT_PAD, STANDARD, buildMap, isHeld: Input.isHeld };
  window.SC._test = { meaningfulChange, sameBytes, buildMap, Input, SnapshotGuard };
})();
