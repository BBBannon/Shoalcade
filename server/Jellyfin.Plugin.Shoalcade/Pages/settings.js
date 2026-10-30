/*
 * Shoalcade Settings page (/shoalcade/settings, D134). Three parts:
 * - Saves: a short summary and the existing saves manager (saves-manager), reused as it is.
 * - Phone app: the APK bundled in the plugin. Version beside the button, install steps and a QR code. The QR code
 *   and the button use a short-lived download ticket from POST /shoalcade/app/link, so the Jellyfin sign-in never
 *   goes in a URL and a phone with no sign-in can still download it. Off in the plugin settings: the server says 404.
 * - Browser player: each console's Snapshots switch and its changed controls, with the same reset as the player's
 *   Settings panel. Same prefs/{console} documents, read-modify-write of the whole document so unknown keys survive.
 * Everything is built with DOM calls and textContent (never innerHTML).
 */
(function () {
  "use strict";

  const api = window.SC.api;
  const $ = id => document.getElementById(id);
  const NEW_CODE_MS = 9 * 60 * 1000; // a fresh code before the 10-minute ticket runs out

  const S = { info: null, prefs: {}, app: null, link: null, codeTimer: 0 };

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

  function size(n) {
    n = Number(n) || 0;
    if (n < 1024 * 1024) return Math.max(1, Math.round(n / 1024)) + " KB";
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1).replace(/\.0$/, "") + " MB";
    return (n / 1024 / 1024 / 1024).toFixed(1).replace(/\.0$/, "") + " GB";
  }

  let toastTimer = 0;
  function toast(msg) {
    const t = $("toast");
    t.textContent = msg;
    t.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("on"), 4000);
  }

  function message(e) {
    return e && e.message ? e.message : String(e);
  }

  function show(which) {
    for (const id of ["loading", "signed-out", "failed", "settings"]) $(id).hidden = id !== which;
  }

  /** The library's view (?c=&q=) is kept, so "Back to library" and the saves manager return to it. */
  function withView(page) {
    const p = new URLSearchParams(location.search);
    const s = new URLSearchParams();
    for (const k of ["c", "q"]) if (p.get(k)) s.set(k, p.get(k));
    const t = s.toString();
    return page + (t ? "?" + t : "");
  }

  // ---------- load ----------
  async function load() {
    show("loading");
    if (!api.hasLogin()) { show("signed-out"); return; }
    try {
      const [info, saves, prefs, app] = await Promise.all([
        api.info(),
        api.saveList(),
        api.request("/shoalcade/prefs").then(r => r.json()),
        appInfo(),
      ]);
      S.info = info;
      S.prefs = prefs && typeof prefs === "object" ? prefs : {};
      S.app = app;
      api.showVersion(info);
      $("who").textContent = info.user && info.user.name ? "Signed in as " + info.user.name : "";
      renderSaves(saves);
      renderPlayer();
      show("settings");
      renderApp();
      // Only our own section ids, never the hash as a selector (a crafted link like settings#[ used to throw, I3).
      const want = location.hash.slice(1);
      if (["saves-s", "app-s", "player-s", "patch-s"].includes(want)) $(want).scrollIntoView();
    } catch (e) {
      if (e && e.status === 401) {
        $("signed-out-text").textContent = e.message;
        show("signed-out");
      } else {
        $("failed-text").textContent = message(e);
        show("failed");
      }
    }
  }

  /** { off } when the admin turned the download off (404 app_off), else the server's app info. */
  async function appInfo() {
    const res = await api.request("/shoalcade/app/info", { allow: [404] });
    let body = null;
    try { body = await res.json(); } catch (e) { /* not JSON */ }
    if (res.status === 404) return { off: !body || body.error === "app_off" };
    return body || { available: false };
  }

  // ---------- saves ----------
  function renderSaves(list) {
    const saves = (list && list.saves) || [];
    const used = (list && list.usedBytes) || 0;
    const quota = (list && list.quotaBytes) || 0;
    const games = saves.length === 1 ? "1 game" : saves.length + " games";
    $("saves-sum").textContent = saves.length
      ? "You have saves for " + games + ". They use " + size(used) + (quota ? " of the " + size(quota) + " allowed." : ".")
      : "No saves yet. Play a game and its save appears here.";
    $("saves-open").href = withView("saves-manager");
  }

  // ---------- phone app ----------
  function renderApp() {
    const a = S.app || {};
    $("app-off").hidden = !a.off;
    $("app-none").hidden = !!a.off || !!a.available;
    $("app-box").hidden = !!a.off || !a.available;
    if (a.off || !a.available) return;
    $("app-ver").textContent = "Version " + a.version + " · " + size(a.size);
    $("app-addr").textContent = location.origin + api.BASE;
    newCode();
  }

  /** A fresh download link (ticket) from the server: absolute URL, for the QR code and the button. */
  async function getLink() {
    const res = await api.request("/shoalcade/app/link", { method: "POST" });
    const body = await res.json();
    return location.origin + api.BASE + body.url;
  }

  async function newCode() {
    clearTimeout(S.codeTimer);
    $("app-err").hidden = true;
    try {
      S.link = await getLink();
      drawQr(S.link);
      $("app-qr-text").textContent = "Scan with your phone's camera. This code works for 10 minutes.";
      S.codeTimer = setTimeout(newCode, NEW_CODE_MS);
    } catch (e) {
      appError(e);
    }
  }

  function appError(e) {
    if (e && e.status === 404 && e.code === "app_off") { S.app = { off: true }; renderApp(); return; }
    if (e && e.status === 404 && e.code === "no_app") { S.app = { available: false }; renderApp(); return; }
    $("app-err").textContent = "Couldn't get a download link: " + message(e);
    $("app-err").hidden = false;
  }

  function drawQr(text) {
    const code = window.SC.qr.encode(text);
    const NS = "http://www.w3.org/2000/svg";
    const q = 4; // the light border scanners need
    const n = code.size + q * 2;
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 " + n + " " + n);
    svg.setAttribute("shape-rendering", "crispEdges");
    svg.setAttribute("aria-hidden", "true");
    const bg = document.createElementNS(NS, "rect");
    bg.setAttribute("width", String(n));
    bg.setAttribute("height", String(n));
    bg.setAttribute("fill", "#fff");
    let d = "";
    for (let y = 0; y < code.size; y++) {
      for (let x = 0; x < code.size; x++) if (code.dark(x, y)) d += "M" + (x + q) + " " + (y + q) + "h1v1h-1z";
    }
    const path = document.createElementNS(NS, "path");
    path.setAttribute("d", d);
    path.setAttribute("fill", "#000");
    svg.append(bg, path);
    $("app-qr").replaceChildren(svg);
    $("app-qr").dataset.link = text; // for the test-bed browser check (a short-lived ticket, never the sign-in)
  }

  /** The button: a fresh ticket link, so the browser downloads it itself (progress, no sign-in in the URL). */
  async function download() {
    const b = $("app-dl");
    b.disabled = true;
    try {
      const url = await getLink();
      const a = h("a", { href: url, download: "Shoalcade.apk", class: "sr" });
      document.body.append(a);
      a.click();
      a.remove();
      toast("Downloading the phone app (" + size(S.app.size) + ").");
    } catch (e) {
      appError(e);
    } finally {
      b.disabled = false;
    }
  }

  // ---------- browser player, per console ----------
  /** What a console's settings have changed from the defaults (same keys as the player's Settings panel). */
  function changed(p) {
    const c = (p && p.controls) || {};
    const pads = c.gamepads && typeof c.gamepads === "object" ? Object.keys(c.gamepads).length : 0;
    const out = [];
    if (c.keyboard && Object.keys(c.keyboard).length) out.push({ key: "keys", label: "Keys" });
    if (c.hotkeys && Object.keys(c.hotkeys).length) out.push({ key: "hot", label: "Shortcuts" });
    if (pads) out.push({ key: "pad", label: pads === 1 ? "Controller buttons (1 controller)" : "Controller buttons (" + pads + " controllers)" });
    if (p && p.touch) out.push({ key: "touch", label: "Touch buttons" });
    return out;
  }

  function renderPlayer() {
    const cons = ((S.info && S.info.consoles) || []).filter(c => c.browserCore);
    $("player-off").hidden = !!(S.info && S.info.browserPlayer !== false);
    $("consoles").replaceChildren(...cons.map(consoleBox));
  }

  function consoleBox(c) {
    const p = S.prefs[c.key] || {};
    const box = h("div", { class: "set-con", id: "con-" + c.key });
    const sw = h("input", { type: "checkbox", role: "switch", id: "snap-" + c.key });
    sw.checked = p.snapshots !== false;
    sw.addEventListener("change", () => setSnapshots(c, sw));
    const list = changed(p);
    box.append(
      h("h3", { class: "set-h3", text: c.name }),
      h("label", { class: "tog" }, [h("span", { text: "Snapshots" }), sw, h("span", { class: "tog-ui", "aria-hidden": "true" })]),
      h("p", { class: "fine set-sub", text: "Controls" }),
      list.length
        ? h("ul", { class: "set-changed" }, list.map(x => {
          const b = h("button", { type: "button", class: "mini", text: "Reset to defaults", "aria-label": "Reset " + c.name + " " + x.label.toLowerCase() + " to defaults" });
          b.addEventListener("click", () => resetPart(c, x, b));
          return h("li", null, [h("span", { text: x.label + ": changed" }), b]);
        }))
        : h("p", { class: "set-none", text: "All controls are the defaults." }));
    return box;
  }

  /** Read-modify-write of one console's whole settings document (keys this page doesn't know are kept). */
  async function change(c, fn) {
    const fresh = await api.prefs(c.key);
    const p = fresh && typeof fresh === "object" ? fresh : {};
    fn(p);
    await api.putPrefs(c.key, p);
    S.prefs[c.key] = p;
    return p;
  }

  async function setSnapshots(c, sw) {
    const on = sw.checked;
    sw.disabled = true;
    try {
      await change(c, p => { p.snapshots = on; });
      toast("Snapshots " + (on ? "on" : "off") + " for " + c.name + ".");
    } catch (e) {
      sw.checked = !on;
      toast("Couldn't save your settings: " + message(e));
    } finally {
      sw.disabled = false;
    }
  }

  async function resetPart(c, part, b) {
    b.disabled = true;
    try {
      await change(c, p => {
        const controls = p.controls = Object.assign({}, p.controls);
        if (part.key === "keys") delete controls.keyboard;
        else if (part.key === "hot") delete controls.hotkeys;
        else if (part.key === "pad") delete controls.gamepads;
        else delete p.touch;
      });
      toast(c.name + " " + part.label.replace(/ \(.*\)$/, "").toLowerCase() + " reset to defaults.");
      $("con-" + c.key).replaceWith(consoleBox(c));
    } catch (e) {
      b.disabled = false;
      toast("Couldn't save your settings: " + message(e));
    }
  }

  // ---------- wiring ----------
  function wire() {
    $("signin").href = api.signInUrl;
    $("jf-home").href = api.homeUrl;
    $("back").href = withView("./");
    $("home").href = withView("./");
    $("retry").addEventListener("click", load);
    $("app-dl").addEventListener("click", download);
    $("app-qr-new").addEventListener("click", newCode);
    load();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
  else wire();
})();
