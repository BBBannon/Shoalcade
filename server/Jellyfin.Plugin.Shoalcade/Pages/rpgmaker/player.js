/*
 * Shoalcade RPG Maker player (D147) for /shoalcade/play-rpgmaker?id=<itemId>: the same page and controls as every other
 * console (play-ui.js on top), with mkxp-z (rpgmaker.js) underneath instead of EmulatorJS.
 *
 * Loaded after play.js and before play-ui.js: it replaces SC.Player with RpgPlayer, which keeps the Player's settings,
 * status and save-sync code (SaveSync: automatic upload a few seconds after the game saves, version checks, the
 * "which save do you keep?" choice) and swaps the emulator parts. No snapshots, cheats or patcher for RPG Maker.
 *
 * The save is a zip of the core's Saves folder (rpgmaker.js). The server's zip may come from the phone (packed
 * differently), so right after it is put in place the browser's own zip of the same files becomes the "already on the
 * server" copy: only a real change in the game's save files is ever sent.
 */
(function () {
  "use strict";
  const SC = window.SC;
  const api = SC.api;
  const R = SC.rpgmaker;
  const Base = SC.Player;

  const CORE_URL = api.BASE + "/shoalcade/ejs/mkxp-z/mkxp-z_libretro.js";
  const COMPAT_URL = api.BASE + "/shoalcade/web/rpgmaker/shoalcade_compat.rb";
  /** Games this big get the "Keep in browser" option (smaller ones stay in the browser's normal cache). */
  const KEEP_FROM_BYTES = 500 * 1024 * 1024;
  const MB = 1024 * 1024;
  const BUTTON_KEYS = { c: "C", b: "B", a: "A", x: "X", y: "Y", z: "Z", l: "L", r: "R" };

  function mb(n) { return n >= 1024 * MB ? (n / 1024 / MB).toFixed(1) + " GB" : Math.max(1, Math.round(n / MB)) + " MB"; }
  function store(op, key, value) {
    try {
      if (op === "get") return localStorage.getItem(key);
      if (op === "set") localStorage.setItem(key, value); else localStorage.removeItem(key);
    } catch (e) { /* private window: no memory, that's fine */ }
    return null;
  }

  class RpgPlayer extends Base {
    constructor(container) {
      super(container);
      this.isRpgMaker = true;
      this.engine = null;
      this.buttonNames = null; // mkxp.json bindingNames: { C: "Action", ... }
      this.list = null;        // { files, total, count, title, engine, mayNotWork }
      this.keeper = null;
      this.keepJob = null;
      this.typing = false;     // the game wants typed text (shoalcade_compat.rb's typing bridge)
      this.typingTimer = 0;
      // While the game wants text, the keyboard types instead of pressing buttons. Registered before every other key
      // listener on the page (the input layer, shortcuts, RetroArch), so a typed key goes nowhere else.
      window.addEventListener("keydown", e => this.onTypingKey(e), true);
      window.addEventListener("keyup", e => { if (this.typing && this.typingKey(e) !== null) { e.preventDefault(); e.stopImmediatePropagation(); } }, true);
    }

    /** What a key adds to the typing file, or null if it isn't a typing key (F keys, Tab, arrows stay as they are). */
    typingKey(e) {
      // AltGr (Windows reports it as Ctrl+Alt) types accents and symbols such as é and €: those are text, not shortcuts.
      const altGr = e.getModifierState && e.getModifierState("AltGraph");
      if (!altGr && (e.ctrlKey || e.metaKey || e.altKey)) return null;
      if (e.key === "Backspace") return "\b";
      if (e.key === "Enter") return "\n";
      if (e.key === "Escape") return "\x1b";
      if (!e.key || [...e.key].length !== 1) return null;
      // A real keyboard already gives "B" for Shift+B, but key events sent by software (automation, remote desktop,
      // some on-screen keyboards) can say "b" with Shift held. Shift flips the letter's case, as Caps Lock does.
      const caps = e.getModifierState && e.getModifierState("CapsLock");
      const up = e.key.toUpperCase(), low = e.key.toLowerCase();
      if (up !== low && up.length === 1 && e.key === low && !!e.shiftKey !== !!caps) return up;
      return e.key;
    }

    onTypingKey(e) {
      if (!this.typing || !this.engine) return;
      const t = e.target;
      // The settings panel's fields; the touch keyboard's field sends its own text (ui.js).
      if (t && (t.id === "kb-in" || t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
      const s = this.typingKey(e);
      if (s === null) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      this.engine.type(s);
    }

    /** Text from the touch keyboard (ui.js): typed text, "\b", "\n" or "\x1b". */
    typeText(s) { return this.engine ? this.engine.type(s) : false; }

    /** Checks every 250 ms whether the game wants text (as the phone, RpgTyping.kt). */
    pollTyping() {
      if (!this.engine) return;
      if (!this.typingReady) this.typingReady = this.engine.resetTyping();
      const want = this.typingReady && this.engine.typingWanted();
      if (want === this.typing) return;
      this.typing = want;
      // Game keys off while typing (letters would also press buttons); back on after.
      if (this.input) this.input.setEnabled(!want && !this.paused);
      this.engine.setKeys(!want && !this.paused);
      this.emit("typing", want);
    }

    // ---------- step 1: before Play ----------
    async prepare(itemId) {
      try {
        if (!/^[0-9a-f]{32}$/i.test((itemId || "").replace(/-/g, ""))) throw new Error("No game chosen.");
        this.itemId = itemId.replace(/-/g, "").toLowerCase();
        if (!api.hasLogin()) throw new api.ApiError(401, "signed_out", "Sign in to Jellyfin first, then come back to this page.");
        this.setState("loading");
        this.status("loading", "Loading game…");
        const [info, game] = await Promise.all([api.info(), api.game(this.itemId)]);
        this.info = info;
        api.showVersion(info);
        if (game.console !== "rpgmaker") { location.replace("play" + location.search); return; }
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
          console.warn("Shoalcade: settings didn't load", e.message);
          this.prefsLoadFailed = true;
          this.schedulePrefsRetry();
        }
        this.cheats = null;
        this.cheatsNote = "RPG Maker games have no cheats here.";
        this.status("loading", "Getting the game's file list…");
        const [list, compat] = await Promise.all([
          api.request("/shoalcade/games/" + encodeURIComponent(this.itemId) + "/files").then(r => r.json()),
          fetch(COMPAT_URL, { credentials: "omit" }).then(r => { if (!r.ok) throw new Error("The RPG Maker helper script is missing (" + r.status + "). Reinstall the plugin."); return r.text(); }),
        ]);
        if (!list || !Array.isArray(list.files) || !list.files.some(f => /^game\.ini$/i.test(f.path))) throw new Error("This game folder has no Game.ini, so it can't start.");
        this.list = list;
        this.compat = compat;
        await this.readButtonNames();
        this.emit("prefs", this.prefs);
        await this.openKeeper();
        this.launchSnapshots = [];
        this.setState("ready");
        const kept = this.keeper && this.keeper.keptBytes(list.files);
        this.status("ready", (this.prefsLoadFailed ? "Ready to play. Your settings couldn't be loaded, so defaults are used. " : "Ready to play. ")
          + mb(list.total) + " game: " + (kept && kept >= list.total ? "kept in this browser." : "files come from your server as the game needs them.")
          + (list.mayNotWork ? " This game ships Windows add-ons Shoalcade doesn't know, so it may not work." : ""));
      } catch (e) {
        this.fail(e);
      }
    }

    /** Button names from the game's mkxp.json ("bindingNames"), for the touch pad and the Controls list. */
    async readButtonNames() {
      const f = this.list.files.find(x => /^mkxp\.json$/i.test(x.path));
      if (!f || f.size > 256 * 1024) return;
      try {
        const res = await api.request(this.fileUrl(f).slice(api.BASE.length));
        const names = R.parseMkxpJson(await res.text()).bindingNames;
        if (!names || typeof names !== "object") return;
        const out = {};
        for (const k of Object.keys(names)) {
          const b = BUTTON_KEYS[k.toLowerCase()];
          if (b && typeof names[k] === "string" && names[k].trim()) out[b] = names[k].trim().slice(0, 24);
        }
        if (Object.keys(out).length) this.buttonNames = out;
      } catch (e) {
        console.warn("Shoalcade: couldn't read mkxp.json", e.message);
      }
    }

    /** Address of one game file (the hash makes it a new address when the file changes). */
    fileUrl(f) {
      return api.BASE + "/shoalcade/games/" + encodeURIComponent(this.itemId) + "/file?path=" + encodeURIComponent(f.path)
        + (f.sha256 ? "&sha256=" + encodeURIComponent(f.sha256) : "");
    }

    headers() { return { Authorization: api.authHeader() }; }

    // ---------- "Keep in browser" ----------
    get keepKey() { return "sc-rpg-keep:" + ((this.info && this.info.user && this.info.user.id) || "me") + ":" + this.itemId; }
    get canKeep() { return !!this.list && this.list.total >= KEEP_FROM_BYTES && R.Keeper.supported(); }
    get keepOn() { return store("get", this.keepKey) === "1"; }

    newKeeper() { return new R.Keeper(R.Keeper.cacheName(this.info.user && this.info.user.id, this.itemId)); }

    async openKeeper() {
      if (!this.canKeep || !this.keepOn) return;
      try {
        this.keeper = await this.newKeeper().open();
      } catch (e) {
        console.warn("Shoalcade: couldn't open the kept files", e.message);
        this.keeper = null;
      }
    }

    /** { can, on, kept, total, busy, why } for the panel. */
    keepState() {
      const total = this.list ? this.list.total : 0;
      const why = !this.list ? "" : this.list.total < KEEP_FROM_BYTES ? "small"
        : !R.Keeper.supported() ? (window.isSecureContext ? "browser" : "https") : "";
      return { can: this.canKeep, on: this.keepOn, kept: this.keeper ? this.keeper.keptBytes(this.list.files) : 0, total, busy: !!this.keepJob, why };
    }

    /** Turns "Keep in browser" on (downloads what isn't kept yet, in the background) or off (removes it). */
    async setKeep(on) {
      if (!this.canKeep) return;
      if (on) {
        store("set", this.keepKey, "1");
        try { if (navigator.storage && navigator.storage.persist) await navigator.storage.persist(); } catch (e) { /* asked */ }
        if (!this.keeper) await this.openKeeper();
        if (!this.keeper) throw new Error("This browser wouldn't let Shoalcade keep the game.");
        if (this.engine) this.engine.files.keeper = this.keeper;
        this.fillKeep();
      } else {
        store("del", this.keepKey);
        if (this.keepJob) { this.keepJob.abort(); this.keepJob = null; }
        const k = this.keeper || this.newKeeper();
        if (this.engine) this.engine.files.keeper = null;
        this.keeper = null;
        await k.clear();
        this.emit("keep", this.keepState());
      }
    }

    fillKeep() {
      if (this.keepJob || !this.keeper) return;
      const ctl = new AbortController();
      this.keepJob = ctl;
      let last = 0;
      this.emit("keep", this.keepState());
      this.keeper.download(this.list.files, f => this.fileUrl(f), () => this.headers(), (d, t) => {
        if (Date.now() - last > 500) { last = Date.now(); this.emit("keep", Object.assign(this.keepState(), { progress: t ? d / t : 1 })); }
      }, ctl.signal).then(r => {
        if (this.keepJob === ctl) this.keepJob = null;
        this.emit("keep", Object.assign(this.keepState(), { failed: r.failed }));
      }).catch(e => {
        if (this.keepJob === ctl) this.keepJob = null;
        this.emit("keep", Object.assign(this.keepState(), { error: e.message }));
      });
    }

    // ---------- step 2: Play ----------
    async start() {
      if (this.state !== "ready") return;
      this.setState("starting");
      try {
        this.status("loading", "Getting your save…");
        const save = await api.getSave(this.itemId);
        this.sync = new SC.SaveSync(this, this.itemId, save.version, save.bytes);
        const bootKey = "sc-rpg-boot:" + this.itemId;
        let bootList = [];
        try { bootList = JSON.parse(store("get", bootKey) || "[]"); } catch (e) { bootList = []; }
        this.engine = await R.boot({
          canvas: document.getElementById("canvas"), // in play.html: RetroArch listens for keys on "#canvas"
          coreUrl: new URL(CORE_URL, location.href).href,
          files: this.list.files,
          fileUrl: f => this.fileUrl(f),
          headers: () => this.headers(),
          compat: this.compat,
          // Games made for Ruby 1.8 / 1.9 (the server checks their DLLs) need mkxp-z's syntax transform ("2" =
          // compatibility mode); mkxp-z turns it off by itself for any game with an mkxp.json.
          coreOptions: this.list.oldRuby ? { "mkxp-z_syntaxTransform": "2" } : {},
          save: save.bytes,
          bootList: Array.isArray(bootList) ? bootList : [],
          keeper: this.keeper,
          retroarch: { fastforward_ratio: String(Math.max(2, Math.min(4, Number(this.prefs.ffSpeed) || 2))) + ".0" },
          onStatus: (t, done, total) => this.status("loading", t, done, total),
          onLog: line => {
            if (/\[(ERROR|WARN)\]|mkxp-z stdout/.test(line)) console.debug("[mkxp-z] " + line);
            this.watchGameError(line);
          },
        });
        // Remember what the game read while starting, so next time it is fetched before the start (in parallel).
        const remember = () => { if (this.engine) store("set", bootKey, JSON.stringify(this.engine.readSoFar())); };
        for (const ms of [20000, 45000, 95000]) setTimeout(remember, ms);
        window.addEventListener("pagehide", remember);
        this.onStart(save);
        if (this.keeper && this.keepOn) this.fillKeep();
      } catch (e) {
        this.fail(e);
      }
    }

    onStart(save) {
      // The browser's own zip of the save files just put in place = what the server has (see top of file).
      try { this.sync.synced = this.engine.readSave() || save.bytes; } catch (e) { /* keep the server's bytes */ }
      window.addEventListener("beforeunload", e => this.onBeforeUnload(e), true);
      window.addEventListener("pagehide", () => this.onPageHide());
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden" && this.running) this.sync.flush({});
      });
      this.setVolume(typeof this.prefs.volume === "number" ? this.prefs.volume : 0.5);
      this.input = new SC.controls.Input("rpgmaker", this.prefs.controls, (id, v) => this.engine.pad(id, v > 0));
      this.input.attach();
      this.typingReady = this.engine.resetTyping();
      this.typingTimer = setInterval(() => this.pollTyping(), 250);
      this.running = true;
      this.sync.start();
      this.setState("playing");
      if (this.gameError) this.showGameError();
      else this.status("ok", this.sync.base ? "Playing with your server save (version " + this.sync.base + ")." : "Playing. No save on the server yet.");
    }

    /**
     * A Ruby error the game doesn't catch stops it (the core logs "[mkxp-z exception] <message>" then "Game threw an
     * exception; terminating") and the picture stays black. Say so, with the game's own message, instead of nothing.
     */
    watchGameError(line) {
      const m = /\[mkxp-z exception\]\s*(.*)$/.exec(line);
      if (m && this.gameErrorText === undefined) this.gameErrorText = m[1].replace(/\[[0-9;]*m/g, "").trim().slice(0, 300);
      // The engine itself stopping (a WebAssembly trap) would otherwise leave a black screen with no message.
      const fatal = /\] Fatal error: (.*)$/.exec(line);
      if (!this.gameError && (fatal || /Game threw an exception; terminating/.test(line))) {
        this.gameError = this.gameErrorText || (fatal ? "the game engine stopped (" + fatal[1].trim().slice(0, 100) + ")" : "unknown error");
        if (this.state === "playing") this.showGameError();
      }
    }

    showGameError() {
      this.status("error", "The game stopped with an error, so it can't go on: " + this.gameError
        + ". Saves made before this are safe. Reload the page to try again.");
    }

    /** The game's save (zip of its Saves folder), or null if it hasn't saved. */
    readSave() { return this.engine ? this.engine.readSave() : null; }

    /** The server's save chosen in a clash: put it in place and restart the game on it. */
    loadSave(bytes) {
      const eng = this.engine;
      if (!eng) return;
      eng.writeSave(bytes).then(() => {
        try { this.sync.synced = eng.readSave() || bytes; } catch (e) { /* keep the server's bytes */ }
        eng.reset();
      }).catch(e => this.status("error", "Couldn't put the server's save in place: " + e.message));
    }

    // ---------- controls the UI calls ----------
    async pause() {
      if (this.paused || !this.engine) return;
      this.paused = true;
      if (this.input) this.input.setEnabled(false);
      this.engine.setKeys(false);
      if (this.ff) this.fastForward(false);
      let picture = null;
      if (this.running) picture = await this.engine.screenshot(800).catch(() => null);
      if (!this.paused) return;
      this.engine.pause();
      this.pausePicture = picture;
      this.emit("paused", { paused: true, picture });
    }

    resume() {
      if (!this.engine || (this.sync && this.sync.conflict)) return;
      const was = this.paused;
      this.paused = false;
      this.engine.resume();
      if (this.input) this.input.setEnabled(!this.typing);
      this.engine.setKeys(!this.typing);
      if (was) this.emit("paused", { paused: false });
    }

    restart() { if (this.engine) this.engine.reset(); }
    setVolume(v) { if (this.engine) this.engine.setVolume(Math.max(0, Math.min(1, v))); }

    fastForward(on) {
      on = !!on && !this.paused;
      if (this.engine && this.running) this.engine.fastForward(on);
      if (on !== this.ff) { this.ff = on; this.emit("ff", on); }
    }

    setInputEnabled(on) {
      const live = on && !this.paused && !this.typing;
      if (this.input) this.input.setEnabled(live);
      if (this.engine) this.engine.setKeys(live);
    }

    async screenshot() {
      if (!this.running) throw new Error("The game isn't running.");
      if (this.paused) {
        if (this.pausePicture) return this.pausePicture;
        throw new Error("Resume the game first.");
      }
      return this.engine.screenshot(3000);
    }

    /** Types one character into the game (touch keyboard). */
    typeChar(ch) { return this.engine ? this.engine.typeChar(ch) : false; }
    /** A named key (Backspace, Enter) pressed and let go (touch keyboard). */
    tapKey(code) {
      if (!this.engine) return;
      this.engine.key([code, code], true);
      setTimeout(() => this.engine.key([code, code], false), 60);
    }

    // ---------- no snapshots or cheats for RPG Maker (D147) ----------
    get supportsSnapshots() { return false; }
    async launchChoices() { return []; }
    async loadCheats() { this.cheats = null; }
    applyCheats() { return false; }
    async autoSnapshot() { return null; }
    async snapshots() { return { enabled: false, snapshots: [] }; }
    async saveSnapshot() { throw new Error("RPG Maker games have no snapshots. Save in the game."); }
    async loadSnapshot() { throw new Error("RPG Maker games have no snapshots. Save in the game."); }
  }

  SC.Player = RpgPlayer;
  SC.RpgPlayer = RpgPlayer;
})();
