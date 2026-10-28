/*
 * Shoalcade player page UI ("Pocket", D55) on top of the engine in play.js.
 *  - Game full-screen; slim toolbar (left rail, top strip on phones) shows on mouse move / tap, hides after 2.5 s.
 *  - Settings panel (bottom sheet on phones); the game area shrinks so the panel never covers the game.
 *  - Display: DS screen layout (melonDS core option), fast-forward speed, volume, toolbar pin.
 *  - Controls per console: press-to-rebind keyboard keys and shortcut keys, per-controller-model pad maps,
 *    touch buttons (touch screens only). All saved to the server: prefs/{console} (D33, D61).
 *  - Snapshots (D37/D38): 3 slots + automatic, on the server.
 * Built with DOM calls + textContent only (D66): game names are metadata.
 */
(function () {
  "use strict";

  const api = window.SC.api;
  const C = window.SC.controls;
  const $ = id => document.getElementById(id);
  const HIDE_MS = 2500;

  /** Shortcut keys (prefs[console].controls.hotkeys). */
  const HOTKEYS = [
    ["pause", "Pause / resume", ["KeyP"]],
    ["ff", "Fast-forward (hold)", ["Space"]],
    ["quickSave", "Quick snapshot (slot 1)", ["F2"]],
    ["quickLoad", "Load snapshot (slot 1)", ["F4"]],
    ["screenshot", "Screenshot", ["F9"]],
    ["fullscreen", "Full screen", ["KeyF"]],
  ];
  const HOTKEY_NAMES = HOTKEYS.map(x => x[0]);
  const DEFAULT_HOTKEYS = Object.fromEntries(HOTKEYS.map(x => [x[0], x[2]]));

  /** DS layouts -> melonDS "melonds_screen_layout" values [normal, swapped]. */
  const DS_LAYOUT = {
    side: ["Left/Right", "Right/Left"],
    stack: ["Top/Bottom", "Bottom/Top"],
    big: ["Hybrid Top", "Hybrid Bottom"],
  };

  const SHORT = { gb: "GB", gbc: "GBC", gba: "GBA", nds: "DS", psx: "PS1" };
  const BUTTON_LABEL = { Up: "Up", Down: "Down", Left: "Left", Right: "Right", A: "A", B: "B", X: "X", Y: "Y", L: "L", R: "R", L2: "L2", R2: "R2", Start: "Start", Select: "Select" };
  /** PS1 names (D137, docs/wording.md "PS1 buttons"): face buttons by position, as the app: △ = X, ○ = A, ✕ = B, □ = Y. */
  const PSX_LABEL = { X: "△", A: "○", B: "✕", Y: "□", L: "L1", R: "R1", L2: "L2", R2: "R2" };
  /** What a button is called on a console's controller. */
  function buttonLabel(consoleKey, b) {
    return (consoleKey === "psx" && PSX_LABEL[b]) || BUTTON_LABEL[b] || b;
  }
  const KEY_NAMES = {
    ArrowUp: "↑", ArrowDown: "↓", ArrowLeft: "←", ArrowRight: "→", Space: "Space", Enter: "Enter", NumpadEnter: "Num Enter",
    Backspace: "Backspace", Tab: "Tab", Escape: "Esc", ShiftLeft: "Left Shift", ShiftRight: "Right Shift",
    ControlLeft: "Left Ctrl", ControlRight: "Right Ctrl", AltLeft: "Left Alt", AltRight: "Right Alt",
    Semicolon: ";", Quote: "'", Comma: ",", Period: ".", Slash: "/", Backslash: "\\", BracketLeft: "[", BracketRight: "]",
    Minus: "-", Equal: "=", Backquote: "`",
  };
  const PAD_NAMES = {
    south: "Bottom button", east: "Right button", west: "Left button", north: "Top button",
    l1: "L / LB", r1: "R / RB", l2: "ZL / LT", r2: "ZR / RT", select: "Select / View", start: "Start / Menu",
    l3: "Left stick press", r3: "Right stick press", up: "D-pad up", down: "D-pad down", left: "D-pad left", right: "D-pad right", home: "Home",
  };
  const AXIS_NAMES = { "a0-": "Left stick left", "a0+": "Left stick right", "a1-": "Left stick up", "a1+": "Left stick down",
    "a2-": "Right stick left", "a2+": "Right stick right", "a3-": "Right stick up", "a3+": "Right stick down" };

  function keyName(code) {
    if (KEY_NAMES[code]) return KEY_NAMES[code];
    let m = /^Key([A-Z])$/.exec(code);
    if (m) return m[1];
    m = /^Digit(\d)$/.exec(code);
    if (m) return m[1];
    m = /^Numpad(.+)$/.exec(code);
    if (m) return "Num " + m[1];
    return code;
  }

  function padName(bind) {
    if (PAD_NAMES[bind]) return PAD_NAMES[bind];
    if (AXIS_NAMES[bind]) return AXIS_NAMES[bind];
    let m = /^b(\d+)$/.exec(bind);
    if (m) return "Button " + m[1];
    m = /^a(\d+)([+-])$/.exec(bind);
    if (m) return "Axis " + m[1] + " " + m[2];
    return bind;
  }

  // ---------------------------------------------------------------------------------------------
  // Small DOM helpers (textContent only)
  // ---------------------------------------------------------------------------------------------
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v === undefined || v === null || v === false) continue;
      if (k === "text") el.textContent = v;
      else if (k === "class") el.className = v;
      else if (k === "on") for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
      else if (k === "data") for (const [dk, dv] of Object.entries(v)) el.dataset[dk] = dv;
      else el.setAttribute(k, v === true ? "" : String(v));
    }
    for (const kid of kids.flat()) if (kid !== null && kid !== undefined && kid !== false) el.append(kid);
    return el;
  }

  const ICONS = {
    load: ["M12 4v11M7 10l5 5 5-5", "M5 20h14"],
    save: ["M5 4h11l3 3v13H5z", "M8 4v5h7V4M8 20v-6h8v6"],
    trash: ["M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"],
    pad: ["M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6L14 14h-4l-1 1.6A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4z", "M7 10.5v3M5.5 12h3"],
  };
  function icon(name) {
    const NS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("class", "ico");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    for (const d of ICONS[name]) {
      const p = document.createElementNS(NS, "path");
      p.setAttribute("d", d);
      svg.append(p);
    }
    return svg;
  }

  let toastTimer = 0;
  function toast(msg) {
    const t = $("toast");
    t.textContent = msg;
    t.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("on"), 2800);
  }

  function when(iso) {
    const d = new Date(iso);
    if (isNaN(d)) return "";
    const today = new Date();
    const time = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
    if (d.toDateString() === today.toDateString()) return "Today, " + time;
    const y = new Date(today); y.setDate(today.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return "Yesterday, " + time;
    return d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" }) + ", " + time;
  }

  function size(n) { return n >= 1048576 ? (n / 1048576).toFixed(1) + " MB" : Math.max(1, Math.round(n / 1024)) + " KB"; }

  /** Two-step confirm on a button: first press arms it for 3 s, second press runs. */
  function sure(btn, label, fn) {
    if (btn.classList.contains("sure")) {
      btn.classList.remove("sure");
      clearTimeout(btn._sureT);
      fn();
      return;
    }
    btn.classList.add("sure");
    toast(label);
    btn._sureT = setTimeout(() => btn.classList.remove("sure"), 3000);
  }

  // ---------------------------------------------------------------------------------------------
  // Page
  // ---------------------------------------------------------------------------------------------
  const player = new window.SC.Player($("sc-game"));
  window.SC.player = player;
  const playerEl = $("player");
  const body = document.body;
  const params = new URLSearchParams(location.search);
  const itemId = (params.get("id") || "").replace(/-/g, "").toLowerCase();
  const isTouch = window.matchMedia("(pointer: coarse)").matches;

  const S = {
    prefsBy: {},          // console key -> prefs object (the playing console's is player.prefs)
    saveTimers: {},
    pinned: false,
    tab: "display",
    ctlConsole: null,
    ctlTab: isTouch ? "touch" : "keys",
    listening: null,
    leaving: false,
    snapUrls: [],
    status: { kind: "loading", text: "Loading…" },
  };

  function consoleName(key) {
    const c = player.info && (player.info.consoles || []).find(x => x.key === key);
    return c ? c.name : (SHORT[key] || key);
  }

  function playing() { return player.state === "playing"; }
  // Open = between openPanel and closePanel, not "still visible while it slides away": during the 220 ms close the
  // Settings button opens it again and game keys work (B9: the open/close race).
  let panelState = { open: false, gen: 0 };
  function panelOpen() { return panelState.open; }
  function conflictOpen() { return !$("sc-conflict").hidden; }

  /** Address of the library view the person came from (?c=&q= kept, the game's sheet open). */
  function libraryHref() {
    const from = new URLSearchParams(params.get("from") || "");
    const keep = new URLSearchParams();
    for (const k of ["c", "q"]) if (from.get(k)) keep.set(k, from.get(k));
    const s = keep.toString();
    return "./" + (s ? "?" + s : "") + (/^[0-9a-f]{32}$/.test(itemId) ? "#game=" + itemId : "");
  }

  /**
   * Sends the save (and the automatic snapshot) first, then goes. If the save didn't reach the server, asks first
   * (Stay / Leave anyway) instead of going anyway (M6).
   */
  async function leaveTo(href, useHistory) {
    if (S.leaving) return;
    S.leaving = true;
    if (playing()) {
      toast("Saving before you go…");
      if (!(await player.leave()) && !(await askLeave())) { S.leaving = false; return; }
    }
    if (useHistory && params.has("from") && history.length > 1) history.back();
    else location.href = href;
    setTimeout(() => { S.leaving = false; }, 1500);
  }

  /** "Your save hasn't reached the server yet": true = Leave anyway, false = Stay (the game carries on). */
  function askLeave() {
    const card = $("sc-leave");
    if (panelOpen()) closePanel();
    card.hidden = false;
    wake();
    $("sc-leave-stay").focus();
    return new Promise(resolve => {
      const done = go => {
        card.hidden = true;
        $("sc-leave-stay").removeEventListener("click", stay);
        $("sc-leave-go").removeEventListener("click", leave);
        if (go) player.leavingAnyway = true; // the person already said yes: no second browser prompt
        resolve(go);
      };
      const stay = () => done(false);
      const leave = () => done(true);
      $("sc-leave-stay").addEventListener("click", stay);
      $("sc-leave-go").addEventListener("click", leave);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Settings (prefs) storage: read-modify-write of the whole console document, so keys this page
  // doesn't know about (e.g. ones the app writes) are kept.
  // ---------------------------------------------------------------------------------------------
  const PREFS_NOT_LOADED = "Your settings couldn't be loaded from the server, so changes aren't saved (they last for this visit only). Shoalcade keeps trying.";

  async function prefsFor(scope) {
    if (player.game && scope === player.game.console) {
      // Controls are only edited on top of the real server copy, never on stand-in defaults (H1).
      if (!(await player.reloadPrefs())) { toast(PREFS_NOT_LOADED); throw new Error("settings not loaded"); }
      return player.prefs;
    }
    if (!S.prefsBy[scope]) {
      let p = {};
      try { p = await api.prefs(scope); } catch (e) { toast("Couldn't load " + consoleName(scope) + " settings: " + e.message); throw e; }
      S.prefsBy[scope] = p && typeof p === "object" ? p : {};
    }
    return S.prefsBy[scope];
  }

  function savePrefs(scope, now) {
    clearTimeout(S.saveTimers[scope]);
    const run = async () => {
      const mine = player.game && scope === player.game.console;
      // A failed load is never written back: that would replace the server copy with defaults (H1).
      if (mine && !(await player.reloadPrefs())) {
        S.prefsPending = true;
        toast(PREFS_NOT_LOADED);
        throw new Error("settings not loaded");
      }
      const p = mine ? player.prefs : S.prefsBy[scope];
      try {
        await api.putPrefs(scope, p);
      } catch (e) {
        toast("Couldn't save your settings: " + e.message);
        throw e;
      }
    };
    if (now) return run();
    S.saveTimers[scope] = setTimeout(() => { run().catch(() => {}); }, 400);
    return Promise.resolve();
  }

  // ---------------------------------------------------------------------------------------------
  // Toolbar: shows on mouse move / tap, hides after 2.5 s
  // ---------------------------------------------------------------------------------------------
  let hideTimer = 0;
  function wake() {
    playerEl.classList.add("chrome-on");
    clearTimeout(hideTimer);
    hideTimer = setTimeout(maybeHide, HIDE_MS);
  }
  function maybeHide() {
    const keep = S.pinned || panelOpen() || !playing() || conflictOpen() || $("toolbar").matches(":hover, :focus-within");
    if (keep) { hideTimer = setTimeout(maybeHide, HIDE_MS); return; }
    playerEl.classList.remove("chrome-on");
  }
  document.addEventListener("pointermove", e => { if (e.pointerType === "mouse") wake(); });
  $("stage").addEventListener("pointerdown", wake);
  $("toolbar").addEventListener("focusin", wake);

  /** The game area changed size: tell EmulatorJS (it listens for window resizes). */
  function relayout() {
    setTimeout(() => window.dispatchEvent(new Event("resize")), 260);
  }

  function setPinned(on) {
    S.pinned = !!on;
    playerEl.classList.toggle("pinned", S.pinned);
    $("tb-pin").checked = S.pinned;
    wake();
    relayout();
  }

  // ---------------------------------------------------------------------------------------------
  // Actions (toolbar, panel, shortcut keys)
  // ---------------------------------------------------------------------------------------------
  async function quickSave() {
    try {
      const info = await player.saveSnapshot("1");
      toast(info && info.pictureFailed ? "Snapshot saved to slot 1, without a picture." : "Snapshot saved to slot 1.");
      if (panelOpen() && S.tab === "snaps") refreshSnaps();
    } catch (e) { toast("Couldn't save the snapshot: " + e.message); }
  }

  async function quickLoad() {
    try {
      await player.loadSnapshot("1");
      toast("Loaded slot 1.");
    } catch (e) { toast(e.status === 404 ? "Slot 1 is empty." : "Couldn't load the snapshot: " + e.message); }
  }

  async function screenshot() {
    try {
      const png = await player.screenshot();
      const url = URL.createObjectURL(new Blob([png], { type: "image/png" }));
      const stamp = new Date().toISOString().slice(0, 19).replace(/[T:]/g, "-");
      const name = (player.game ? player.game.name : "game").replace(/[\\/:*?"<>|]+/g, "").trim().slice(0, 80) || "game";
      const a = h("a", { href: url, download: name + " " + stamp + ".png", hidden: true });
      body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
      toast("Screenshot saved to your downloads.");
    } catch (e) { toast("Couldn't take a screenshot: " + e.message); }
  }

  function toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else document.documentElement.requestFullscreen({ navigationUI: "hide" }).catch(e => toast("Full screen isn't allowed here: " + e.message));
  }

  function act(name, btn, fromKey) {
    switch (name) {
      case "pause": player.togglePause(); break;
      case "restart":
        sure(btn, "Press Restart again to restart the game.", () => { player.restart(); toast("Game restarted."); });
        break;
      case "ff": player.fastForward(!player.ff); break;
      case "qsave": quickSave(); break;
      case "qload":
        // Two presses on the key too (D74): the toolbar button shows it's waiting either way.
        sure(btn, "Press " + (fromKey ? keyName(fromKey) : "Load snapshot") + " again to load slot 1. Play since then is lost unless the game saved.", quickLoad);
        break;
      case "shot": screenshot(); break;
      case "full": toggleFullscreen(); break;
      case "settings": if (panelOpen()) closePanel(); else openPanel(S.tab); break;
    }
  }

  document.addEventListener("click", e => {
    const b = e.target.closest("[data-act]");
    if (!b || b.disabled) return;
    act(b.dataset.act, b, false);
    // A mouse click leaves focus on the button, which would stop game keys reaching the game.
    if (e.detail > 0 && b.closest("#toolbar") && b.dataset.act !== "settings") b.blur();
  });

  for (const id of ["tb-back", "sc-error-back"]) {
    $(id).addEventListener("click", e => {
      if (e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) return;
      e.preventDefault();
      leaveTo(libraryHref(), true);
    });
  }
  // The pixel Jellyfin logo: same path as Back to library, so the save is sent before leaving.
  $("tb-jf").addEventListener("click", e => {
    if (e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    leaveTo(api.homeUrl, false);
  });
  $("saves-link").addEventListener("click", e => {
    if (e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    leaveTo($("saves-link").getAttribute("href"), false);
  });

  /** Shortcut keys. Registered before the engine's game keys, so a shortcut never reaches the game. */
  function hotkeyFor(code) {
    const hk = Object.assign({}, DEFAULT_HOTKEYS, (player.prefs && player.prefs.controls && player.prefs.controls.hotkeys) || {});
    return HOTKEY_NAMES.find(n => Array.isArray(hk[n]) && hk[n].includes(code)) || null;
  }

  function onKey(e) {
    if (S.listening) { captureKey(e); return; }
    if (e.key === "Escape" && e.type === "keydown" && panelOpen()) { e.preventDefault(); closePanel(); return; }
    if (!playing() || panelOpen() || conflictOpen() || e.ctrlKey || e.metaKey || e.altKey) return;
    const t = e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
    if (t && /^(BUTTON|A)$/.test(t.tagName) && (e.code === "Space" || e.code === "Enter")) return;
    const name = hotkeyFor(e.code);
    if (!name) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (name === "ff") { if (!e.repeat) player.fastForward(e.type === "keydown"); return; }
    if (e.type !== "keydown" || e.repeat) return;
    if (name === "quickLoad") { act("qload", $("toolbar").querySelector("[data-act=qload]"), e.code); return; }
    ({ pause: () => player.togglePause(), quickSave, screenshot, fullscreen: toggleFullscreen })[name]();
  }
  window.addEventListener("keydown", onKey, true);
  window.addEventListener("keyup", onKey, true);

  // ---------------------------------------------------------------------------------------------
  // Settings panel
  // ---------------------------------------------------------------------------------------------
  function openPanel(tab) {
    const p = $("panel");
    const gen = ++panelState.gen;
    panelState.open = true;
    p.hidden = false;
    requestAnimationFrame(() => { if (gen === panelState.gen) p.classList.add("open"); });
    body.classList.add("panel-open");
    $("toolbar").querySelector("[data-act=settings]").setAttribute("aria-expanded", "true");
    player.setInputEnabled(false);
    wake();
    selectTab(tab || S.tab, true);
    relayout();
  }

  function closePanel() {
    stopListening();
    const p = $("panel");
    const gen = ++panelState.gen;
    panelState.open = false;
    p.classList.remove("open");
    body.classList.remove("panel-open");
    $("toolbar").querySelector("[data-act=settings]").setAttribute("aria-expanded", "false");
    // Only the latest open/close counts: a slide-in frame or hide timer from an earlier one does nothing.
    setTimeout(() => { if (gen === panelState.gen) p.hidden = true; }, 220);
    if (playing() && !conflictOpen()) player.setInputEnabled(true);
    wake();
    relayout();
  }

  $("panel-x").addEventListener("click", closePanel);

  function selectTab(tab, focus) {
    S.tab = tab;
    for (const b of document.querySelectorAll("[role=tab]")) {
      const on = b.dataset.tab === tab;
      b.setAttribute("aria-selected", String(on));
      b.tabIndex = on ? 0 : -1;
      if (on && focus) b.focus();
    }
    for (const t of ["display", "saves", "snaps", "cheats", "controls"]) $("pt-" + t).hidden = t !== tab;
    stopListening();
    if (tab === "saves") renderSaves();
    if (tab === "snaps") refreshSnaps();
    if (tab === "cheats") renderCheats();
    if (tab === "controls") renderControls();
  }

  const tabs = Array.from(document.querySelectorAll("[role=tab]"));
  for (const b of tabs) {
    b.addEventListener("click", () => selectTab(b.dataset.tab));
    b.addEventListener("keydown", e => {
      const i = tabs.indexOf(b);
      const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
      if (!step) return;
      e.preventDefault();
      selectTab(tabs[(i + step + tabs.length) % tabs.length].dataset.tab, true);
    });
  }

  // ----- Display tab -----
  function dsSettings() {
    const ds = (player.prefs && player.prefs.ds) || {};
    const wide = window.innerWidth > window.innerHeight;
    return { layout: DS_LAYOUT[ds.layout] ? ds.layout : (wide ? "side" : "stack"), swap: !!ds.swap };
  }

  function applyDs() {
    if (!player.game || player.game.console !== "nds") return;
    const ds = dsSettings();
    player.setCoreOption("melonds_screen_layout", DS_LAYOUT[ds.layout][ds.swap ? 1 : 0]);
    // "One big, one small": the small screen shows whichever one isn't big.
    player.setCoreOption("melonds_hybrid_small_screen", ds.swap ? "Top" : "Bottom");
    for (const b of document.querySelectorAll("[data-layout]")) b.setAttribute("aria-pressed", String(b.dataset.layout === ds.layout));
    $("ds-swap").checked = ds.swap;
    relayout();
  }

  for (const b of document.querySelectorAll("[data-layout]")) {
    b.addEventListener("click", () => {
      player.prefs.ds = Object.assign({}, player.prefs.ds, { layout: b.dataset.layout });
      applyDs();
      savePrefs(player.game.console);
    });
  }
  $("ds-swap").addEventListener("change", () => {
    // Before a good settings load, only the changed key is kept: a guessed layout would replace the server's (N1).
    player.prefs.ds = Object.assign({}, player.prefsLoadFailed ? {} : dsSettings(), player.prefs.ds, { swap: $("ds-swap").checked });
    applyDs();
    savePrefs(player.game.console);
  });

  function applyFf(n) {
    player.setFastForwardSpeed(n);
    for (const b of document.querySelectorAll("[data-ff]")) b.setAttribute("aria-pressed", String(+b.dataset.ff === player.ffSpeed));
    $("tb-ff-lbl").textContent = player.ffSpeed + "×";
    $("toolbar").querySelector("[data-act=ff]").title = "Fast-forward " + player.ffSpeed + "× (hold " + keyName((hotkeysOf(player.prefs).ff || [])[0] || "") + ")";
  }
  for (const b of document.querySelectorAll("[data-ff]")) {
    b.addEventListener("click", () => {
      player.prefs.ffSpeed = +b.dataset.ff;
      applyFf(player.prefs.ffSpeed);
      savePrefs(player.game.console);
    });
  }

  function applyVolume(v, from) {
    v = Math.max(0, Math.min(100, Math.round(v)));
    for (const id of ["vol", "tb-vol"]) if ($(id) !== from) $(id).value = String(v);
    player.setVolume(v / 100);
  }
  for (const id of ["vol", "tb-vol"]) {
    $(id).addEventListener("input", e => {
      applyVolume(+e.target.value, e.target);
      if (player.prefs) { player.prefs.volume = +e.target.value / 100; savePrefs(player.game.console); }
    });
  }

  $("tb-pin").addEventListener("change", () => {
    setPinned($("tb-pin").checked);
    player.prefs.toolbarPinned = S.pinned;
    savePrefs(player.game.console);
  });

  // ----- Saves tab -----
  function renderSaves() {
    const st = $("save-state");
    st.textContent = S.status.text;
    st.dataset.kind = S.status.kind;
    const sync = player.sync;
    $("save-detail").textContent = !sync ? "Start the game to sync its save."
      : sync.base ? "Version " + sync.base + " is the save in use on the server." : "No save on the server yet.";
  }

  $("sc-save").addEventListener("click", () => player.saveNow());

  // ----- Snapshots tab -----
  function snapsOn() { return !(player.prefs && player.prefs.snapshots === false); }

  function applySnapsSwitch() {
    const on = snapsOn() && (!playing() || player.supportsSnapshots);
    body.classList.toggle("snaps-off", !on);
    $("snap-on").checked = snapsOn();
    if (player.game) $("snap-on-lbl").textContent = "Snapshots for " + consoleName(player.game.console) + " games";
  }

  $("snap-on").addEventListener("change", async () => {
    player.prefs.snapshots = $("snap-on").checked;
    applySnapsSwitch();
    try {
      await savePrefs(player.game.console, true);
      toast("Snapshots " + (player.prefs.snapshots ? "on" : "off") + " for " + consoleName(player.game.console) + ".");
    } catch (e) { /* toast shown */ }
    refreshSnaps();
  });

  let snapSeq = 0;
  async function refreshSnaps() {
    const seq = ++snapSeq;
    const list = $("snaps");
    const msg = $("snap-msg");
    const say = text => { msg.textContent = text; msg.hidden = !text; };
    if (!player.itemId || !player.info) { say("Loading…"); list.replaceChildren(); return; }
    if (!snapsOn()) { say("Snapshots are off for this console. Your normal save still works."); list.replaceChildren(); return; }
    if (playing() && !player.supportsSnapshots) { say("This emulator can't make snapshots of this game."); list.replaceChildren(); return; }
    let data;
    try {
      data = await player.snapshots();
    } catch (e) {
      if (seq === snapSeq) { say("Couldn't load your snapshots: " + e.message); list.replaceChildren(); }
      return;
    }
    if (seq !== snapSeq) return;
    if (!data.enabled) { say("Snapshots are off for this console. Your normal save still works."); list.replaceChildren(); return; }
    // D81: Game Boy snapshots from the old gambatte core can never load in mGBA, so their slots show as empty, with a note.
    const all = data.snapshots || [];
    const old = all.filter(isOldGambatte);
    const oldNote = old.length
      ? (old.length === 1 ? "1 old snapshot" : old.length + " old snapshots") + " from before the browser player changed its Game Boy emulator can't be loaded, so " +
        (old.length === 1 ? "it is" : "they are") + " hidden. Saving into " + (old.length === 1 ? "that slot replaces it" : "those slots replaces them") + "."
      : "";
    say([playing() ? "" : "Start the game to save or load snapshots.", oldNote].filter(Boolean).join(" "));
    S.snapUrls.forEach(u => URL.revokeObjectURL(u));
    S.snapUrls = [];
    const bySlot = new Map(all.filter(s => !isOldGambatte(s)).map(s => [s.slot, s]));
    const rows = ["auto", "1", "2", "3"].map(slot => snapRow(slot, bySlot.get(slot) || null));
    list.replaceChildren(...rows);
  }

  /** A snapshot made by the gambatte core, dropped in D81 (GB/GBC now play on mGBA). */
  function isOldGambatte(s) {
    return !!(s && typeof s.core === "string" && /^gambatte/i.test(s.core));
  }

  function snapRow(slot, info) {
    const label = slot === "auto" ? "Automatic" : "Slot " + slot;
    const damaged = !!(info && info.damaged);
    const pic = h("div", { class: "snap-pic" }, damaged ? "Damaged" : info ? null : "Empty");
    if (info && info.hasImage && !damaged) {
      player.snapshotImage(slot).then(blob => {
        const url = URL.createObjectURL(blob);
        S.snapUrls.push(url);
        pic.replaceChildren(h("img", { src: url, alt: "" }));
      }).catch(() => {});
    }
    const otherCore = info && info.core && info.core !== player.snapshotCore;
    const meta = !info ? (slot === "auto" ? "Taken when you leave the game or switch to another tab" : "Empty")
      : damaged ? "Damaged on the server, so it can't be loaded. Save over it or delete it."
      : when(info.created) + ", " + size(info.size) + (info.cheats && info.cheats.length ? ", " + (info.cheats.length === 1 ? "1 cheat" : info.cheats.length + " cheats") + " on" : "")
        + (otherCore ? ". Made by a different emulator version, so it can't be loaded" : "");
    const acts = h("div", { class: "sv-acts" });
    const run = async (fn, done) => {
      try { const r = await fn(); toast(r && r.pictureFailed ? done.replace(/\.$/, ", without a picture.") : done); } catch (e) { toast(e.message); }
      refreshSnaps();
    };
    if (info && playing() && !otherCore && !damaged) {
      const b = h("button", { type: "button", class: "mini", title: "Go back to this moment" }, icon("load"), "Load");
      b.addEventListener("click", () => sure(b, "Press Load again. Play since then is lost unless the game saved.", () => run(() => player.loadSnapshot(slot), "Loaded " + label.toLowerCase() + ".")));
      acts.append(b);
    }
    if (slot !== "auto" && playing()) {
      const b = h("button", { type: "button", class: "mini" }, icon("save"), info ? "Save over" : "Save here");
      b.addEventListener("click", () => {
        const go = () => run(() => player.saveSnapshot(slot), "Saved to " + label.toLowerCase() + ".");
        if (info) sure(b, "Press Save over again to replace " + label.toLowerCase() + ".", go); else go();
      });
      acts.append(b);
    }
    if (info) {
      const b = h("button", { type: "button", class: "mini danger" }, icon("trash"), "Delete");
      b.addEventListener("click", () => sure(b, "Press Delete again to delete " + label.toLowerCase() + ".", () => run(() => player.deleteSnapshot(slot), "Deleted " + label.toLowerCase() + ".")));
      acts.append(b);
    }
    return h("li", { class: "snap" }, pic,
      h("div", { class: "snap-txt" }, h("span", { class: "sv-when", text: label }), h("span", { class: "sv-meta", text: meta })),
      acts.childElementCount ? acts : null);
  }

  // ----- Cheats tab (D143) -----
  // Which code types each console's emulator reads (checked on the test-bed: tools/testbed/cheat_cores.py).
  const CHEAT_HINTS = {
    gb: "Game Boy: GameShark codes like 01FF16D0, or Game Genie codes like 00A-17B-C49.",
    gbc: "Game Boy Color: GameShark codes like 01FF16D0, or Game Genie codes like 00A-17B-C49.",
    gba: "Game Boy Advance: CodeBreaker, GameShark or Action Replay codes, like 82025CE4 03E7. Put each line of a long code on its own line.",
    nds: "DS: Action Replay codes, like 02000000 0000270F. Put each line on its own line.",
    psx: "PlayStation: GameShark codes, like 800A1234 03E7. Put each line on its own line.",
  };
  const CODE_OK = /^[A-Za-z0-9 +\-:_.,\n]+$/;
  const cheatForm = { editing: null };

  /** Plain-English problem with a name and code, or "" if they're fine (same rules as the server). */
  function cheatProblem(name, code) {
    const n = name.trim().replace(/\s+/g, " ");
    const lines = code.replace(/\r/g, "").split("\n").map(l => l.trim().replace(/\s+/g, " ")).filter(Boolean);
    if (!n) return "Give the cheat a name.";
    if (n.length > 60) return "Keep the name to 60 characters or fewer.";
    if (!lines.length) return "Type or paste the cheat code.";
    if (!CODE_OK.test(lines.join("\n"))) return "That doesn't look like a cheat code. Codes use letters, numbers, spaces and + - : _ . , only.";
    if (lines.length > 64 || lines.join("\n").length > 4000) return "That code is too long.";
    return "";
  }

  function resetCheatForm() {
    cheatForm.editing = null;
    $("cheat-name").value = "";
    $("cheat-code").value = "";
    $("cheat-err").hidden = true;
    $("cheat-form-h").textContent = "Add a cheat";
    $("cheat-add").textContent = "Add and turn on";
    $("cheat-cancel").hidden = true;
  }

  function cheatSaid(stuck, done) {
    toast(stuck ? done + " A DS cheat you switched off stays on until you leave the game and start it again." : done);
  }

  function renderCheats() {
    const list = $("cheats");
    const msg = $("cheat-msg");
    const say = text => { msg.textContent = text; msg.hidden = !text; };
    $("cheat-hint").textContent = CHEAT_HINTS[player.game && player.game.console] || "";
    $("cheat-ds").hidden = !(player.game && player.game.console === "nds");
    const ok = !!player.cheats;
    for (const id of ["cheat-name", "cheat-code", "cheat-add"]) $(id).disabled = !ok;
    if (!ok) { say(player.cheatsNote || "Loading…"); list.replaceChildren(); return; }
    const doc = player.cheats;
    const on = new Set(doc.active);
    say(!doc.cheats.length ? "No cheats yet. Add one below." : (on.size ? on.size + " on." : "None on.") + (playing() ? "" : " They go on when the game starts."));
    list.replaceChildren(...doc.cheats.map(c => {
      const sw = h("input", { type: "checkbox", role: "switch", id: "cheat-" + c.id });
      sw.checked = on.has(c.id);
      sw.addEventListener("change", async () => {
        sw.disabled = true;
        const ids = doc.cheats.filter(x => x.id === c.id ? sw.checked : on.has(x.id)).map(x => x.id);
        try {
          cheatSaid(await player.setActiveCheats(ids), (sw.checked ? "Turned on " : "Turned off ") + c.name + ".");
        } catch (e) {
          sw.checked = !sw.checked;
          toast("Couldn't change it: " + e.message);
        }
        renderCheats();
      });
      const edit = h("button", { type: "button", class: "mini" }, "Edit");
      edit.addEventListener("click", () => {
        cheatForm.editing = c.id;
        $("cheat-name").value = c.name;
        $("cheat-code").value = c.code;
        $("cheat-form-h").textContent = "Change " + c.name;
        $("cheat-add").textContent = "Save changes";
        $("cheat-cancel").hidden = false;
        $("cheat-err").hidden = true;
        $("cheat-name").focus();
      });
      const del = h("button", { type: "button", class: "mini danger" }, icon("trash"), "Delete");
      del.addEventListener("click", () => sure(del, "Press Delete again to delete " + c.name + ".", async () => {
        try {
          cheatSaid(await player.deleteCheat(c.id), "Deleted " + c.name + ".");
          if (cheatForm.editing === c.id) resetCheatForm();
        } catch (e) { toast("Couldn't delete it: " + e.message); }
        renderCheats();
      }));
      return h("li", { class: "cheat" },
        h("label", { class: "tog", for: "cheat-" + c.id }, h("span", { text: c.name }), sw, h("span", { class: "tog-ui", "aria-hidden": "true" })),
        h("code", { class: "cheat-code", text: c.code }),
        h("div", { class: "sv-acts" }, edit, del));
    }));
  }

  $("cheat-add").addEventListener("click", async () => {
    const name = $("cheat-name").value, code = $("cheat-code").value;
    const err = $("cheat-err");
    const problem = cheatProblem(name, code);
    if (problem) { err.textContent = problem; err.hidden = false; return; }
    err.hidden = true;
    const b = $("cheat-add");
    b.disabled = true;
    try {
      if (cheatForm.editing) {
        cheatSaid(await player.editCheat(cheatForm.editing, { name, code }), "Saved " + name.trim() + ".");
      } else {
        const r = await player.addCheat(name, code, true);
        cheatSaid(r.stuck, "Added " + r.cheat.name + (playing() ? " and turned it on." : ". It goes on when the game starts."));
      }
      resetCheatForm();
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    } finally {
      b.disabled = false;
    }
    renderCheats();
  });
  $("cheat-cancel").addEventListener("click", resetCheatForm);
  player.on("cheats", () => { if (panelOpen() && S.tab === "cheats") renderCheats(); });

  // ----- Controls tab -----
  function hotkeysOf(prefs) {
    const saved = (prefs && prefs.controls && prefs.controls.hotkeys) || {};
    return C.buildMap(HOTKEY_NAMES, DEFAULT_HOTKEYS, saved);
  }

  function firstPad() {
    const pads = navigator.getGamepads ? navigator.getGamepads() : [];
    for (const p of pads) if (p && p.connected) return p;
    return null;
  }

  function keyButton(text, onClick, title) {
    const b = h("button", { type: "button", class: "key", text, title });
    b.addEventListener("click", () => onClick(b));
    return b;
  }

  async function renderControls() {
    const playable = ((player.info && player.info.consoles) || []).filter(c => c.browserCore && C.BUTTONS[c.key]);
    if (!S.ctlConsole) S.ctlConsole = player.game ? player.game.console : (playable[0] && playable[0].key);
    const cc = $("cc");
    cc.replaceChildren(...playable.map(c => {
      const b = h("button", { type: "button", text: SHORT[c.key] || c.key, title: c.name, "aria-pressed": String(c.key === S.ctlConsole) });
      b.addEventListener("click", () => { S.ctlConsole = c.key; renderControls(); });
      return b;
    }));
    for (const b of document.querySelectorAll("[data-ctab]")) b.setAttribute("aria-pressed", String(b.dataset.ctab === S.ctlTab));
    const scope = S.ctlConsole;
    const out = $("ctl-body");
    if (!scope) { out.replaceChildren(); return; }
    let prefs;
    try { prefs = await prefsFor(scope); } catch (e) { out.replaceChildren(h("p", { class: "note", text: "Couldn't load these settings." })); return; }
    if (scope !== S.ctlConsole) return;
    const controls = prefs.controls || {};
    const buttons = C.BUTTONS[scope];
    const name = consoleName(scope);
    const reset = $("ctl-reset");

    if (S.ctlTab === "keys") {
      const map = C.buildMap(buttons, C.DEFAULT_KEYS, controls.keyboard);
      out.replaceChildren(h("ul", { class: "binds" }, buttons.map(b => h("li", {},
        h("span", { text: buttonLabel(scope, b) }),
        keyButton(map[b].map(keyName).join(" / ") || "Not set", el => startListen({ kind: "key", scope, name: b, el }), "Change the key for " + buttonLabel(scope, b))))),
      h("p", { class: "fine", text: "Click a key, then press the new key. Esc cancels." }));
      reset.textContent = "Reset " + name + " keys to defaults";
    } else if (S.ctlTab === "hot") {
      const map = hotkeysOf(prefs);
      out.replaceChildren(h("ul", { class: "binds" }, HOTKEYS.map(([k, label]) => h("li", {},
        h("span", { text: label }),
        keyButton(map[k].map(keyName).join(" / ") || "Not set", el => startListen({ kind: "hot", scope, name: k, el }), "Change the key for " + label.toLowerCase())))),
      h("p", { class: "fine", text: "Shortcuts work while the game is running and the settings are closed." }));
      reset.textContent = "Reset " + name + " shortcuts to defaults";
    } else if (S.ctlTab === "pad") {
      const pad = firstPad();
      if (!pad) {
        out.replaceChildren(h("p", { class: "device" }, icon("pad"), h("span", {},
          h("strong", { text: "No controller found" }), h("br"),
          h("span", { class: "sv-meta", text: "Connect a controller and press any button on it. Each controller model gets its own buttons." }))));
        reset.textContent = "Reset controller to defaults";
        reset.disabled = true;
        return;
      }
      reset.disabled = false;
      const saved = controls.gamepads && controls.gamepads[pad.id];
      const map = C.buildMap(buttons, C.DEFAULT_PAD, saved);
      out.replaceChildren(
        h("p", { class: "device" }, icon("pad"), h("span", {},
          h("strong", { text: pad.id }), h("br"),
          h("span", { class: "sv-meta", text: (pad.mapping === "standard" ? "Buttons saved by name" : "Buttons saved by number") + ", just for this controller model." }))),
        h("ul", { class: "binds" }, buttons.map(b => h("li", {},
          h("span", { text: buttonLabel(scope, b) }),
          keyButton(map[b].map(padName).join(" / ") || "Not set", el => startListen({ kind: "pad", scope, name: b, el, padIndex: pad.index, padId: pad.id }), "Change the controller button for " + buttonLabel(scope, b))))),
        h("p", { class: "fine", text: "Player 1 only. Click a button here, then press the controller button. Esc cancels." }));
      reset.textContent = "Reset this controller to defaults";
    } else {
      const t = touchSettings(prefs);
      const show = h("input", { type: "checkbox", role: "switch", id: "touch-show" });
      show.checked = t.show;
      const sz = h("input", { type: "range", class: "range", id: "touch-size", min: "60", max: "150", step: "10", value: String(t.size) });
      const op = h("input", { type: "range", class: "range", id: "touch-see", min: "0", max: "85", step: "5", value: String(Math.round(100 - t.opacity * 100)) });
      const changed = () => {
        prefs.touch = { show: show.checked, size: +sz.value, opacity: Math.round(100 - +op.value) / 100 };
        if (scope === (player.game && player.game.console)) buildTouch();
        savePrefs(scope);
      };
      show.addEventListener("change", changed);
      sz.addEventListener("input", changed);
      op.addEventListener("input", changed);
      out.replaceChildren(
        isTouch ? null : h("p", { class: "note", text: "This screen isn't a touch screen, so touch buttons stay hidden here. These settings are used on phones and tablets." }),
        h("label", { class: "tog" }, h("span", { text: "Show touch buttons" }), show, h("span", { class: "tog-ui", "aria-hidden": "true" })),
        h("h4", {}, h("label", { for: "touch-size", text: "Button size" })), sz,
        h("h4", {}, h("label", { for: "touch-see", text: "See-through" })), op,
        h("p", { class: "fine", text: "Buttons shown: D-pad, " + buttons.filter(b => !/^(Up|Down|Left|Right)$/.test(b)).join(", ") + "." }));
      reset.textContent = "Reset " + name + " touch buttons";
    }
    reset.disabled = false;
  }

  for (const b of document.querySelectorAll("[data-ctab]")) {
    b.addEventListener("click", () => { S.ctlTab = b.dataset.ctab; stopListening(); renderControls(); });
  }
  window.addEventListener("gamepadconnected", () => { if (panelOpen() && S.tab === "controls" && S.ctlTab === "pad") renderControls(); });
  window.addEventListener("gamepaddisconnected", () => { if (panelOpen() && S.tab === "controls" && S.ctlTab === "pad") renderControls(); });

  $("ctl-reset").addEventListener("click", async () => {
    const scope = S.ctlConsole;
    const prefs = await prefsFor(scope);
    const controls = prefs.controls = Object.assign({}, prefs.controls);
    let what;
    if (S.ctlTab === "keys") { delete controls.keyboard; what = "keys"; }
    else if (S.ctlTab === "hot") { delete controls.hotkeys; what = "shortcuts"; }
    else if (S.ctlTab === "pad") {
      const pad = firstPad();
      if (!pad || !controls.gamepads) return;
      controls.gamepads = Object.assign({}, controls.gamepads);
      delete controls.gamepads[pad.id];
      what = "controller buttons";
    } else { delete prefs.touch; what = "touch buttons"; }
    afterControlsChange(scope, prefs);
    try { await savePrefs(scope, true); toast(consoleName(scope) + " " + what + " reset to defaults."); } catch (e) { /* toast shown */ }
    renderControls();
  });

  function afterControlsChange(scope, prefs) {
    if (player.game && scope === player.game.console) {
      player.setControls(prefs.controls);
      player.setInputEnabled(false); // still in the panel
      buildTouch();
      applyFf(player.ffSpeed);
    }
  }

  // ----- press-to-rebind -----
  let padRaf = 0;
  let listenTimer = 0;

  function startListen(L) {
    const again = S.listening && S.listening.el === L.el;
    stopListening();
    if (again) return; // second click on the same key cancels
    S.listening = L;
    L.el.classList.add("listen");
    L.el.textContent = L.kind === "pad" ? "Press a button…" : "Press a key…";
    listenTimer = setTimeout(() => { stopListening(); renderControls(); }, 10000);
    if (L.kind === "pad") {
      const pad = navigator.getGamepads()[L.padIndex];
      let held = new Set(pad ? padInputs(pad) : []);
      const poll = () => {
        const p = navigator.getGamepads()[L.padIndex];
        if (!S.listening || S.listening !== L) return;
        if (p) {
          const now = padInputs(p);
          const fresh = now.find(x => !held.has(x));
          if (fresh) { stopListening(); bindPad(L, fresh); return; }
          held = new Set(now.filter(x => held.has(x)));
        }
        padRaf = requestAnimationFrame(poll);
      };
      padRaf = requestAnimationFrame(poll);
    }
  }

  function stopListening() {
    clearTimeout(listenTimer);
    cancelAnimationFrame(padRaf);
    if (S.listening) S.listening.el.classList.remove("listen");
    S.listening = null;
  }

  /** What is being pressed on a pad, by the names the engine understands (D61). */
  function padInputs(pad) {
    const out = [];
    const std = pad.mapping === "standard";
    pad.buttons.forEach((b, i) => { if (b && (b.pressed || b.value > 0.5)) out.push(std && C.STANDARD[i] ? C.STANDARD[i] : "b" + i); });
    pad.axes.forEach((v, i) => { if (v > 0.6) out.push("a" + i + "+"); else if (v < -0.6) out.push("a" + i + "-"); });
    return out;
  }

  function captureKey(e) {
    if (S.listening.kind === "pad") {
      if (e.key === "Escape") { e.preventDefault(); e.stopImmediatePropagation(); stopListening(); renderControls(); }
      return;
    }
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.type !== "keydown" || e.repeat) return;
    const L = S.listening;
    stopListening();
    if (e.key === "Escape" || !e.code) { renderControls(); return; }
    // F12 is left to the browser (developer tools), so it can't be bound (D73).
    if (e.code === "F12") { toast("F12 belongs to the browser. Choose another key."); renderControls(); return; }
    bindKey(L, e.code);
  }

  async function bindKey(L, code) {
    const prefs = await prefsFor(L.scope);
    const controls = prefs.controls = Object.assign({}, prefs.controls);
    const keys = C.buildMap(C.BUTTONS[L.scope], C.DEFAULT_KEYS, controls.keyboard);
    const hot = hotkeysOf(prefs);
    const lost = [];
    // One key does one thing: take it off anything else first.
    for (const [map, labelOf] of [[keys, b => buttonLabel(L.scope, b)], [hot, k => HOTKEYS.find(x => x[0] === k)[1].toLowerCase()]]) {
      for (const k of Object.keys(map)) {
        if ((L.kind === "key" ? map === keys : map === hot) && k === L.name) continue;
        if (map[k].includes(code)) {
          map[k] = map[k].filter(c => c !== code);
          lost.push(labelOf(k) + (map[k].length ? "" : " (now has no key)"));
        }
      }
    }
    if (L.kind === "key") keys[L.name] = [code]; else hot[L.name] = [code];
    controls.keyboard = keys;
    controls.hotkeys = hot;
    afterControlsChange(L.scope, prefs);
    const what = L.kind === "key" ? buttonLabel(L.scope, L.name) : HOTKEYS.find(x => x[0] === L.name)[1];
    try {
      await savePrefs(L.scope, true);
      toast(what + " is now " + keyName(code) + "." + (lost.length ? " Taken from " + lost.join(", ") + "." : " Saved."));
    } catch (e) { /* toast shown */ }
    renderControls();
  }

  async function bindPad(L, bind) {
    const prefs = await prefsFor(L.scope);
    const controls = prefs.controls = Object.assign({}, prefs.controls);
    controls.gamepads = Object.assign({}, controls.gamepads);
    const map = C.buildMap(C.BUTTONS[L.scope], C.DEFAULT_PAD, controls.gamepads[L.padId]);
    for (const k of Object.keys(map)) if (k !== L.name) map[k] = map[k].filter(x => x !== bind);
    // A direction keeps its stick when given a new button (and the other way round).
    const isAxis = x => /^a\d+[+-]$/.test(x);
    const keep = /^(Up|Down|Left|Right)$/.test(L.name) ? map[L.name].filter(x => isAxis(x) !== isAxis(bind)) : [];
    map[L.name] = [bind].concat(keep);
    controls.gamepads[L.padId] = map;
    afterControlsChange(L.scope, prefs);
    try {
      await savePrefs(L.scope, true);
      toast(buttonLabel(L.scope, L.name) + " is now " + padName(bind) + " on this controller.");
    } catch (e) { /* toast shown */ }
    renderControls();
  }

  // ---------------------------------------------------------------------------------------------
  // Touch buttons (touch screens only)
  // ---------------------------------------------------------------------------------------------
  function touchSettings(prefs) {
    const t = (prefs && prefs.touch) || {};
    return {
      show: t.show !== false,
      size: typeof t.size === "number" ? Math.max(60, Math.min(150, t.size)) : 100,
      opacity: typeof t.opacity === "number" ? Math.max(0.15, Math.min(1, t.opacity)) : 0.55,
    };
  }

  function buildTouch() {
    const pad = $("touchpad");
    const t = touchSettings(player.prefs);
    const on = isTouch && t.show && playing() && !!player.game;
    pad.hidden = !on;
    const was = body.classList.contains("touch-on");
    body.classList.toggle("touch-on", on);
    if (was !== on) relayout();
    if (!on) { pad.replaceChildren(); return; }
    pad.style.setProperty("--ts", String(t.size / 100));
    pad.style.setProperty("--to", String(t.opacity));
    body.style.setProperty("--ts", String(t.size / 100)); // play.css sizes the DS game's side room from it
    if (pad.dataset.console === player.game.console && pad.childElementCount) return;
    pad.dataset.console = player.game.console;
    const has = C.BUTTONS[player.game.console] || C.BUTTONS.gba;
    const btn = (b, cls) => h("div", { class: "tp-b " + cls, data: { b }, text: buttonLabel(player.game.console, b) });
    pad.classList.toggle("ps", player.game.console === "psx"); // PS1: four shoulder buttons, L1 L2 left and R2 R1 right

    const dpad = h("div", { class: "tp-dpad" }, ["Up", "Down", "Left", "Right"].map(d => h("span", { data: { d } })));
    const faces = ["X", "Y", "A", "B"].filter(b => has.includes(b));
    const face = h("div", { class: "tp-face" + (faces.length <= 2 ? " two" : "") }, faces.map(b => btn(b, "")));
    const kids = [dpad, face];
    for (const b of ["L", "L2", "R2", "R"]) if (has.includes(b)) kids.push(btn(b, "tp-sh"));
    kids.push(btn("Select", "tp-sm"), btn("Start", "tp-sm"));
    pad.replaceChildren(...kids);

    // Buttons: each finger holds what it pressed until it lifts.
    for (const el of pad.querySelectorAll(".tp-b")) {
      const b = el.dataset.b;
      const down = new Set();
      const up = e => {
        if (!down.delete(e.pointerId)) return;
        if (down.size === 0) { el.classList.remove("on"); player.touch(b, false); }
      };
      el.addEventListener("pointerdown", e => {
        e.preventDefault();
        try { el.setPointerCapture(e.pointerId); } catch (x) { /* fine */ }
        down.add(e.pointerId);
        el.classList.add("on");
        player.touch(b, true);
      });
      for (const ev of ["pointerup", "pointercancel", "lostpointercapture"]) el.addEventListener(ev, up);
    }
    // D-pad: direction from where the finger is (diagonals allowed), updated as it slides.
    const held = new Map(); // pointerId -> Set of directions
    const union = () => {
      const u = new Set();
      for (const set of held.values()) for (const d of set) u.add(d);
      return u;
    };
    const setDirs = (id, dirs) => {
      const before = union();
      if (dirs) held.set(id, dirs); else held.delete(id);
      const after = union();
      for (const d of ["Up", "Down", "Left", "Right"]) {
        if (before.has(d) === after.has(d)) continue;
        player.touch(d, after.has(d));
        dpad.querySelector("[data-d=" + d + "]").classList.toggle("on", after.has(d));
      }
    };
    const dirsAt = e => {
      const r = dpad.getBoundingClientRect();
      const dx = (e.clientX - r.left) / r.width - 0.5;
      const dy = (e.clientY - r.top) / r.height - 0.5;
      const s = new Set();
      if (Math.hypot(dx, dy) < 0.1) return s;
      if (dx < -0.15) s.add("Left"); else if (dx > 0.15) s.add("Right");
      if (dy < -0.15) s.add("Up"); else if (dy > 0.15) s.add("Down");
      return s;
    };
    dpad.addEventListener("pointerdown", e => {
      e.preventDefault();
      try { dpad.setPointerCapture(e.pointerId); } catch (x) { /* fine */ }
      setDirs(e.pointerId, dirsAt(e));
    });
    dpad.addEventListener("pointermove", e => { if (held.has(e.pointerId)) setDirs(e.pointerId, dirsAt(e)); });
    for (const ev of ["pointerup", "pointercancel", "lostpointercapture"]) {
      dpad.addEventListener(ev, e => { if (held.has(e.pointerId)) setDirs(e.pointerId, null); });
    }
    pad.addEventListener("contextmenu", e => e.preventDefault());
  }

  // ---------------------------------------------------------------------------------------------
  // Engine events
  // ---------------------------------------------------------------------------------------------
  player.on("game", g => {
    document.title = g.name + " - Shoalcade";
    $("intro-title").textContent = g.name;
    $("tb-title").textContent = g.name;
    $("panel-title").textContent = g.name;
  });

  // Settings loaded late (after a failed first load): save what was changed meanwhile.
  player.on("prefsLoaded", () => {
    if (!S.prefsPending) return;
    S.prefsPending = false;
    savePrefs(player.game.console);
    toast("Your settings are back and saved.");
  });

  player.on("prefs", p => {
    const g = player.game;
    $("intro-console").textContent = consoleName(g.console);
    $("panel-sub").textContent = consoleName(g.console);
    $("ds-sec").hidden = g.console !== "nds";
    body.classList.toggle("ds", g.console === "nds");
    body.classList.toggle("psx", g.console === "psx"); // four face buttons: sideways, the touch columns are as wide as DS's
    S.ctlConsole = g.console;
    setPinned(!!p.toolbarPinned);
    applyFf(typeof p.ffSpeed === "number" ? p.ffSpeed : 2);
    applyVolume(typeof p.volume === "number" ? p.volume * 100 : 50);
    applyDs();
    applySnapsSwitch();
    if (!isTouch) {
      const keys = C.buildMap(C.BUTTONS[g.console] || C.BUTTONS.gba, C.DEFAULT_KEYS, p.controls && p.controls.keyboard);
      const hk = hotkeysOf(p);
      const k = b => (keys[b] || []).map(keyName)[0] || "?";
      const n = b => buttonLabel(g.console, b);
      $("intro-keys").textContent = "Keys: arrows (or your own) to move, " + k("A") + " = " + n("A") + ", " + k("B") + " = " + n("B") + ", " + k("Start") + " = Start. "
        + keyName(hk.pause[0] || "") + " pauses, hold " + keyName(hk.ff[0] || "") + " to fast-forward. Change them in Settings, Controls.";
      $("intro-keys").hidden = false;
    }
  });

  // ----- Launch choice (D90): "Continue from save" (default) or one of the game's snapshots -----
  function snapLabel(slot) { return slot === "auto" ? "Automatic" : "Slot " + slot; }

  function renderLaunch() {
    const snaps = player.launchSnapshots || [];
    $("sc-start-lbl").textContent = snaps.length ? "Continue from save" : "Play";
    $("launch").hidden = snaps.length === 0;
    $("launch-snaps").replaceChildren(...snaps.map(info => {
      const pic = h("span", { class: "snap-pic", "aria-hidden": "true" }, info.hasImage ? null : "No picture");
      if (info.hasImage) {
        player.snapshotImage(info.slot).then(blob => {
          const url = URL.createObjectURL(blob);
          S.snapUrls.push(url);
          pic.replaceChildren(h("img", { src: url, alt: "" }));
        }).catch(() => { pic.textContent = "No picture"; });
      }
      const label = snapLabel(info.slot);
      const time = when(info.created);
      const b = h("button", { type: "button", class: "launch-snap", "aria-label": "Load snapshot: " + label + ", " + time },
        pic, h("span", { class: "snap-txt" }, h("span", { class: "sv-when", text: label }), h("span", { class: "sv-meta", text: time })));
      b.addEventListener("click", () => launch(info.slot));
      return h("li", null, b);
    }));
  }

  function launch(slot) {
    for (const b of document.querySelectorAll("#sc-start, .launch-snap")) b.disabled = true;
    player.start(slot || null);
  }

  player.on("state", s => {
    body.dataset.state = s.state;
    $("sc-start").hidden = s.state !== "ready";
    if (s.state === "ready") { renderLaunch(); $("sc-start").focus(); }
    else $("launch").hidden = true;
    const live = s.state === "playing";
    for (const b of document.querySelectorAll("[data-act]")) if (!["full", "settings"].includes(b.dataset.act)) b.disabled = !live;
    $("sc-save").disabled = !live;
    if (live) {
      applySnapsSwitch();
      buildTouch();
      wake();
      if (panelOpen()) selectTab(S.tab);
    }
    if (s.state === "error") {
      $("sc-error-text").textContent = s.message;
      $("sc-signin").hidden = !s.signIn;
      $("sc-error").hidden = false;
      $("intro").hidden = true;
      if (panelOpen()) closePanel();
      $("toolbar").querySelector("[data-act=settings]").disabled = true;
      $("tb-vol").disabled = true;
      buildTouch();
    }
  });

  player.on("status", s => {
    S.status = s;
    const st = $("sc-status");
    st.textContent = s.text;
    st.dataset.kind = s.kind;
    if (panelOpen() && S.tab === "saves") renderSaves();
    if (playing() && (s.kind === "saved" || s.kind === "error")) toast(s.text);
    if (conflictOpen() && s.kind === "error") {
      $("sc-conflict-err").textContent = s.text;
      $("sc-conflict-err").hidden = false;
      for (const id of ["sc-keep-browser", "sc-keep-server"]) $(id).disabled = false;
    }
  });

  player.on("paused", p => {
    playerEl.classList.toggle("is-paused", p.paused);
    const pause = $("toolbar").querySelector("[data-act=pause]");
    pause.querySelector(".lbl").textContent = p.paused ? "Resume" : "Pause";
    pause.title = (p.paused ? "Resume" : "Pause") + " (" + keyName(hotkeysOf(player.prefs).pause[0] || "") + ")";
    const img = $("paused-pic");
    if (img.src) { URL.revokeObjectURL(img.src); img.removeAttribute("src"); }
    if (p.paused && p.picture) {
      img.src = URL.createObjectURL(new Blob([p.picture], { type: "image/png" }));
      img.hidden = false;
    } else {
      img.hidden = true;
    }
    $("paused").hidden = !p.paused || conflictOpen();
    wake();
  });

  player.on("ff", on => { $("toolbar").querySelector("[data-act=ff]").setAttribute("aria-pressed", String(on)); });

  player.on("conflict", c => {
    const s = c.server;
    $("sc-conflict-server").textContent = s
      ? "Version " + s.version + ", saved " + when(s.created) + (s.device ? " on " + s.device : "") + "."
      : "No current save (it was cleared).";
    $("sc-conflict-err").hidden = true;
    for (const id of ["sc-keep-browser", "sc-keep-server"]) $(id).disabled = false;
    if (panelOpen()) closePanel();
    $("paused").hidden = true;
    $("sc-conflict").hidden = false;
    wake();
    $("sc-keep-browser").focus();
  });

  player.on("resolved", () => {
    $("sc-conflict").hidden = true;
    toast(S.status.text);
  });

  for (const [id, choice] of [["sc-keep-browser", "browser"], ["sc-keep-server", "server"]]) {
    $(id).addEventListener("click", () => {
      for (const b of ["sc-keep-browser", "sc-keep-server"]) $(b).disabled = true;
      $("sc-conflict-err").hidden = true;
      player.resolveConflict(choice);
    });
  }

  $("sc-start").addEventListener("click", () => launch(null));
  $("sc-retry").addEventListener("click", () => location.reload());
  document.addEventListener("fullscreenchange", relayout);
  window.addEventListener("orientationchange", relayout);

  // ---------------------------------------------------------------------------------------------
  // Go
  // ---------------------------------------------------------------------------------------------
  $("sc-signin").href = api.signInUrl;
  $("tb-back").href = libraryHref();
  $("tb-jf").href = api.homeUrl;
  $("sc-error-back").href = libraryHref();
  $("saves-link").href = "saves-manager?id=" + encodeURIComponent(itemId);
  body.classList.toggle("no-fullscreen", !document.fullscreenEnabled);
  wake();
  player.prepare(itemId);
})();
