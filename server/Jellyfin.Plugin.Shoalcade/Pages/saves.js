/*
 * Shoalcade saves manager (/shoalcade/saves-manager), "Pocket" look (D55).
 * - ?id=<item>  : one game's save history (current save marked) with Use this save, download, pin, name, delete,
 *                 upload, start fresh and move. One list per game, DS too (D91): versions that were in the old
 *                 separate DS browser list say so.
 * - no id       : every game the user has a save for, including games no longer in the library (D53).
 * - The server does every check (login, own saves only, hidden games, conflicts); this page only asks and shows.
 * - Saves are precious: nothing here ever overwrites a save without the old one going into history, every
 *   destructive step asks first in an in-page dialog, and an upload uses the normal version check (409 -> ask).
 * - Everything is built with DOM calls and textContent (never innerHTML), because game names are metadata.
 * - Address: ?id= plus the library's view (?c=&q=), so "Back to library" returns to the same view.
 */
(function () {
  "use strict";

  const api = window.SC.api;
  const $ = id => document.getElementById(id);
  const SHORT = { gb: "GB", gbc: "GBC", gba: "GBA", nds: "DS", "3ds": "3DS", psx: "PS1", psp: "PSP" };
  const SOURCE = {
    browser: "the browser player",
    app: "the phone app",
    upload: "an uploaded file",
    restore: "an older version",
    jellyemu: "JellyEMU (imported)",
    moved: "another game (moved here)",
    recovered: "a recovered file",
  };
  const ICON = {
    undo: ["M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3"],
    dl: ["M12 4v11M7 10l5 5 5-5M5 20h14"],
    pin: ["M9 4h6l-1 6 4 3H6l4-3z", "M12 13v7"],
    tag: ["M3 12V4h8l10 10-8 8z", "M7.5 6a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3"],
    trash: ["M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"],
  };

  const S = {
    info: null, games: [], byId: new Map(), list: null,
    id: null, c: "", q: "",
    game: null, entries: [], console: null, removed: false,
    hist: null, snaps: [], busy: false,
  };

  // ---------- helpers ----------
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

  function ico(name) {
    const NS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("class", "ico");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    for (const d of ICON[name]) {
      const p = document.createElementNS(NS, "path");
      p.setAttribute("d", d);
      svg.append(p);
    }
    return svg;
  }

  function hue(id) {
    let n = 0;
    for (let i = 0; i < id.length; i++) n = (n * 31 + id.charCodeAt(i)) % 360;
    return n;
  }

  function consoleInfo(key) {
    return (S.info && S.info.consoles || []).find(x => x.key === key) || null;
  }

  function consoleName(key) {
    const c = consoleInfo(key);
    return c ? c.name : (SHORT[key] || key || "Unknown console");
  }

  function size(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + (n === 1 ? " byte" : " bytes");
    if (n < 1024 * 1024) return (n / 1024).toFixed(n < 10 * 1024 ? 1 : 0).replace(/\.0$/, "") + " KB";
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1).replace(/\.0$/, "") + " MB";
    return (n / 1024 / 1024 / 1024).toFixed(1).replace(/\.0$/, "") + " GB";
  }

  /** "Today, 18:42", "Yesterday, 21:10", "Mon 20 Oct, 20:02" (year added if not this year). */
  function when(iso) {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "Unknown time";
    const now = new Date();
    const t = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
    const day = x => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const diff = Math.round((day(now) - day(d)) / 86400000);
    if (diff === 0) return "Today, " + t;
    if (diff === 1) return "Yesterday, " + t;
    return d.toLocaleDateString("en-GB", {
      weekday: "short", day: "numeric", month: "short", year: d.getFullYear() === now.getFullYear() ? undefined : "numeric",
    }) + ", " + t;
  }

  function fullTime(iso) {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? "" : d.toLocaleString("en-GB", { dateStyle: "full", timeStyle: "medium" });
  }

  /** This page's own address, keeping the library's view (c, q). */
  function addr(p) {
    const s = new URLSearchParams();
    if (p.id) s.set("id", p.id);
    if (S.c) s.set("c", S.c);
    if (S.q) s.set("q", S.q);
    const t = s.toString();
    return "saves-manager" + (t ? "?" + t : "");
  }

  function libraryLink(gameId) {
    const s = new URLSearchParams();
    if (S.c) s.set("c", S.c);
    if (S.q) s.set("q", S.q);
    const t = s.toString();
    return "./" + (t ? "?" + t : "") + (gameId ? "#game=" + gameId : "");
  }

  function art(id, name, consoleKey, imageTag, width) {
    const box = h("div", { class: "art" }, [
      h("span", { class: "art-t", text: name }),
      h("span", { class: "art-c", text: SHORT[consoleKey] || consoleKey || "?" }),
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
  function toast(msg) {
    const t = $("toast");
    t.textContent = msg;
    t.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("on"), 4000);
  }

  // A message that must survive a page change (the move jumps to the target game). Per-tab, cleared once shown.
  const FLASH_KEY = "sc.saves.flash";
  function setFlash(id, msg) {
    try { sessionStorage.setItem(FLASH_KEY, JSON.stringify({ id, msg })); } catch { /* storage blocked: no message */ }
  }

  function showFlash() {
    let f = null;
    try {
      f = JSON.parse(sessionStorage.getItem(FLASH_KEY) || "null");
      sessionStorage.removeItem(FLASH_KEY);
    } catch { /* storage blocked or bad value */ }
    if (f && f.msg && f.id === S.id) toast(f.msg);
  }

  function showError(msg) {
    $("err").textContent = msg || "";
    $("err").hidden = !msg;
    if (msg) $("err").scrollIntoView({ block: "nearest" });
  }

  function message(e) {
    return e && e.message ? e.message : String(e);
  }

  // ---------- in-page dialog (never window.confirm) ----------
  let askDone = null;

  /**
   * Asks the person. o: { title, text: [paragraphs], input: { label, value, max }, ok, okKind: "primary"|"danger",
   * more: [{ v, label, kind }] }. Resolves to the chosen value ("ok" or a "more" value), or null for Cancel/Esc.
   */
  function ask(o) {
    const d = $("ask");
    $("ask-title").textContent = o.title;
    $("ask-text").replaceChildren(...(o.text || []).filter(Boolean).map(t => h("p", { text: t })));
    const field = $("ask-field");
    const input = $("ask-input");
    field.hidden = !o.input;
    if (o.input) {
      $("ask-field-l").textContent = o.input.label;
      input.value = o.input.value || "";
      input.maxLength = o.input.max || 60;
    }
    const cancel = h("button", { type: "button", class: "btn", "data-ask": "" , text: "Cancel" });
    const choices = (o.more || []).map(m => h("button", { type: "button", class: "btn" + (m.kind ? " " + m.kind : ""), "data-ask": m.v, text: m.label }));
    const ok = o.ok ? h("button", { type: "button", class: "btn " + (o.okKind || "primary"), "data-ask": "ok", text: o.ok }) : null;
    $("ask-b").replaceChildren(cancel, ...choices, ok);
    d.returnValue = "";
    return new Promise(resolve => {
      askDone = resolve;
      d.showModal();
      // Destructive questions start on Cancel, so Enter never deletes by accident.
      if (o.input) { input.focus(); input.select(); } else cancel.focus();
    });
  }

  function onAskClosed() {
    const done = askDone;
    askDone = null;
    if (done) done($("ask").returnValue || null);
  }

  // ---------- states ----------
  function show(which) {
    for (const id of ["loading", "signed-out", "failed", "missing", "list-view", "game-view"]) $(id).hidden = id !== which;
  }

  function readAddress() {
    const p = new URLSearchParams(location.search);
    const id = (p.get("id") || "").toLowerCase();
    S.id = id || null;
    S.c = p.get("c") || "";
    S.q = p.get("q") || "";
  }

  async function load() {
    show("loading");
    if (!api.hasLogin()) { show("signed-out"); return; }
    try {
      if (S.id && !/^[0-9a-f]{32}$/.test(S.id)) { show("missing"); return; }
      const [info, games, list] = await Promise.all([api.info(), api.games(), api.saveList()]);
      S.info = info;
      api.showVersion(info);
      S.games = Array.isArray(games) ? games : [];
      S.byId = new Map(S.games.map(g => [g.id, g]));
      S.list = list;
      $("who").textContent = info.user && info.user.name ? "Signed in as " + info.user.name : "";
      if (!S.id) {
        renderList();
        show("list-view");
        return;
      }
      if (!pickGame()) { show("missing"); return; }
      await loadHistory();
      renderGame();
      show("game-view");
      showFlash();
    } catch (e) {
      failed(e);
    }
  }

  function failed(e) {
    if (e && e.status === 401) {
      $("signed-out-text").textContent = e.message;
      show("signed-out");
    } else {
      $("failed-text").textContent = message(e);
      show("failed");
    }
  }

  // ---------- list of every save ----------
  function renderList() {
    document.title = "Saves · Shoalcade";
    $("back").href = libraryLink(null);
    const saves = S.list.saves || [];
    const used = S.list.usedBytes || 0;
    const quota = S.list.quotaBytes || 0;
    $("storage").textContent = "Your saves use " + size(used) + (quota ? " of the " + size(quota) + " allowed." : ".");
    $("list-count").textContent = saves.length + (saves.length === 1 ? " game" : " games");

    const rows = [];
    for (const e of saves) {
      const id = e.itemId;
      const g = S.byId.get(id);
      const removed = e.status === "removed";
      const con = g ? g.console : e.console;
      const name = (g && g.name) || e.gameName || "Unknown game";
      const what = e.current ? "In use: saved " + when(e.current.created) : "No save in use";
      const sn = e.snapshotCount || 0;
      const snaps = sn ? sn + (sn === 1 ? " snapshot" : " snapshots") : "";
      const n = e.versionCount ? e.versionCount + (e.versionCount === 1 ? " version" : " versions") + ", " + size(e.totalBytes) + (snaps && removed ? ". " + snaps : "")
        : snaps;
      rows.push(h("li", null, [
        h("a", { class: "sg-row", href: addr({ id }) }, [
          art(id, name, con, g ? g.imageTag : e.imageTag, 120),
          h("span", { class: "sg-row-txt" }, [
            h("span", { class: "card-t" }, [
              document.createTextNode(name),
              removed ? h("span", { class: "badge pin sg-gone", text: "Not in your library" }) : null,
            ]),
            h("span", { class: "card-m", text: consoleName(con) }),
            h("span", { class: "card-m", text: what + ". " + n }),
          ]),
        ]),
      ]));
    }
    if (!rows.length) rows.push(h("li", null, [h("p", { class: "empty", text: "No saves yet. Play a game and its save appears here." })]));
    $("games").replaceChildren(...rows);
  }

  // ---------- one game ----------
  /** Works out the game and its console. False if there is nothing to show. */
  function pickGame() {
    S.game = S.byId.get(S.id) || null;
    S.entries = (S.list.saves || []).filter(e => e.itemId === S.id);
    if (!S.game && !S.entries.length) return false;
    S.removed = !S.game;
    S.console = S.game ? S.game.console : S.entries[0].console;
    return true;
  }

  async function loadHistory() {
    S.hist = await api.history(S.id);
    await loadSnaps();
  }

  /** F8: a game no longer in the library shows its browser snapshots here (in-library games have them in the player). */
  async function loadSnaps() {
    S.snaps = S.removed ? ((await api.snapshots(S.id)).snapshots || []) : [];
  }

  /** Reloads everything from the server after a change, so the page always shows what the server holds. */
  async function refresh() {
    const [list, hist] = await Promise.all([api.saveList(), api.history(S.id).catch(e => e)]);
    S.list = list;
    if (!pickGame()) { show("missing"); return; }
    if (hist instanceof Error) {
      // e.g. the last version moved away from a removed game: its list no longer exists.
      if (hist.status === 404) { await loadHistory(); } else throw hist;
    } else { S.hist = hist; await loadSnaps(); }
    renderGame();
  }

  function playable() {
    const g = S.game;
    return !!(g && S.info.browserPlayer && g.browserPlayable && S.info.user && S.info.user.canDownload);
  }

  function renderGame() {
    const g = S.game;
    const e0 = S.entries[0];
    const name = (g && g.name) || (e0 && e0.gameName) || "Unknown game";
    document.title = name + " saves · Shoalcade";
    $("g-art").replaceChildren(art(S.id, name, S.console, g ? g.imageTag : e0 && e0.imageTag, 400));
    $("g-title").textContent = name;
    $("g-sub").textContent = consoleName(S.console) + (S.removed ? ", no longer in your library" : "");
    $("back").href = libraryLink(g ? S.id : null);
    $("g-all").href = addr({});
    $("missing-all").href = addr({});
    const play = $("g-play");
    play.hidden = !playable();
    if (playable()) play.href = "play?id=" + encodeURIComponent(S.id);
    $("removed-note").hidden = !S.removed;

    const hist = S.hist;
    const vs = hist.versions || [];
    const acts = $("acts");
    acts.querySelector("[data-act=upload]").hidden = S.removed;
    acts.querySelector("[data-act=fresh]").hidden = S.removed || !hist.current;
    const snaps = S.snaps;
    acts.querySelector("[data-act=move]").hidden = !vs.length && !snaps.length;
    acts.hidden = S.removed && !vs.length && !snaps.length;
    if (!vs.length && !snaps.length) closeMove();

    const nc = $("no-current");
    nc.hidden = !(vs.length && !hist.current);
    nc.textContent = S.removed
      ? "There's no save in use. Move these versions to the game's new entry to carry on with them."
      : "No save in use: the game starts new next time you play. Choose “Use this save” on a version below to carry on from it.";

    $("versions").replaceChildren(...vs.map(row));
    const empty = $("empty");
    empty.hidden = !!vs.length;
    empty.textContent = S.removed ? "No save versions for this game, only browser snapshots."
      : "No saves yet for this game. Play the game, or upload a save file.";
    $("snaps").hidden = !snaps.length;
    $("snap-list").replaceChildren(...snaps.map(snapRow));

    // The real rule from the server only (D68): never a made-up stand-in.
    const r = S.info.saveRules;
    $("keep").textContent = !r ? "Every change keeps a copy here first. (The server didn't say how many copies it keeps.)"
      : "Every change keeps a copy here first. Kept: the last " + r.keepRecent + " saves" +
        (r.keepDailyDays > 0 ? ", plus one a day for " + r.keepDailyDays + " days" : "") +
        ". Pinned saves are never cleared out (up to " + r.maxPinned + " per game).";
  }

  function row(v) {
    const removed = S.removed;
    const from = v.source === "restore" && v.restoredFrom ? "Copy of version " + v.restoredFrom
      : "From " + (SOURCE[v.source] || v.source || "unknown");
    // D91: versions merged in from the old separate DS browser list say so. D90: so do saves made after loading a snapshot.
    const meta = ["Version " + v.version, from + (v.device ? " (" + v.device + ")" : ""),
      v.origin === "browser" ? "Was a browser save (merged in)" : null,
      v.afterSnapshot ? "Saved after loading a snapshot" : null, size(v.size)].filter(Boolean).join(" · ");
    const btn = (act, icon, label, title, extra) => h("button", Object.assign({ type: "button", class: "mini", "data-act": act, "data-v": v.version, title }, extra || {}), [ico(icon), h("span", { text: label })]);
    return h("li", { class: "sv" + (v.isCurrent ? " cur" : "") }, [
      h("div", { class: "sv-main" }, [
        h("span", { class: "sv-when" }, [
          h("time", { datetime: v.created, title: fullTime(v.created), text: when(v.created) }),
          v.isCurrent ? h("span", { class: "badge", text: "In use" }) : null,
          v.pinned ? h("span", { class: "badge pin", text: "Pinned" }) : null,
        ]),
        v.label ? h("span", { class: "sv-label", text: v.label }) : null,
        h("span", { class: "sv-meta", text: meta }),
      ]),
      h("div", { class: "sv-acts" }, [
        v.isCurrent || removed ? null : btn("restore", "undo", "Use this save", "Make this the save in use (the one in use now stays in the list)"),
        btn("download", "dl", "Download", "Download this version as a file"),
        btn("pin", "pin", v.pinned ? "Unpin" : "Pin", v.pinned ? "Unpin: may be cleared out by the keep rule" : "Pin: never cleared out", { "aria-pressed": String(!!v.pinned) }),
        btn("label", "tag", "Name", "Name this save"),
        v.isCurrent || v.pinned ? null : Object.assign(btn("delete", "trash", "Delete", "Delete this version for good"), { className: "mini danger" }),
      ]),
    ]);
  }

  /** One browser snapshot of a removed game (F8): download or delete. */
  function snapRow(st) {
    const name = st.slot === "auto" ? "Automatic snapshot" : "Snapshot slot " + st.slot;
    const btn = (act, icon, label, title) => h("button", { type: "button", class: "mini", "data-act": act, "data-slot": st.slot, title }, [ico(icon), h("span", { text: label })]);
    return h("li", { class: "sv" }, [
      h("div", { class: "sv-main" }, [
        h("span", { class: "sv-when" }, [
          h("time", { datetime: st.created, title: fullTime(st.created), text: when(st.created) }),
          st.damaged ? h("span", { class: "badge pin", text: "Damaged" }) : null,
        ]),
        h("span", { class: "sv-meta", text: [name, st.core ? "Made with " + st.core : null, size(st.size)].filter(Boolean).join(" · ") }),
      ]),
      h("div", { class: "sv-acts" }, [
        st.damaged ? null : btn("snap-download", "dl", "Download", "Download this snapshot as a file"),
        Object.assign(btn("snap-delete", "trash", "Delete", "Delete this snapshot"), { className: "mini danger" }),
      ]),
    ]);
  }

  function snapshot(slot) {
    return S.snaps.find(st => st.slot === slot) || null;
  }

  async function doSnapDownload(st) {
    const blob = await api.snapshotFile(S.id, st.slot);
    const url = URL.createObjectURL(blob);
    const name = (S.entries[0] && S.entries[0].gameName) || "game";
    const a = h("a", { href: url, download: name + " snapshot " + st.slot + ".state", hidden: true });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    toast("Downloading the snapshot");
  }

  async function doSnapDelete(st) {
    const r = await ask({
      title: "Delete this snapshot?",
      text: [(st.slot === "auto" ? "The automatic snapshot" : "Snapshot slot " + st.slot) + " from " + when(st.created) + " will be deleted.",
        "Download it first if you might want it later."],
      ok: "Delete", okKind: "danger",
    });
    if (r !== "ok") return;
    await api.deleteSnapshot(S.id, st.slot);
    await refresh();
    toast("Snapshot deleted");
  }

  function version(n) {
    return (S.hist.versions || []).find(v => v.version === n) || null;
  }

  // ---------- actions ----------
  async function run(fn) {
    if (S.busy) return;
    S.busy = true;
    document.body.setAttribute("aria-busy", "true");
    showError("");
    try {
      await fn();
    } catch (e) {
      if (e && e.status === 401) { failed(e); return; }
      showError(message(e));
      // The page may be out of date (another device saved): show what the server has now.
      if (e && e.code === "conflict") { try { await refresh(); showError(message(e)); } catch (e2) { /* keep first error */ } }
    } finally {
      S.busy = false;
      document.body.removeAttribute("aria-busy");
    }
  }

  async function doRestore(v) {
    const r = await api.restoreSave(S.id, v.version, { base: S.hist.current });
    await refresh();
    toast("Version " + v.version + " is now in use (as version " + (r.current ? r.current.version : "?") + "). The save you were using stays in the list.");
  }

  async function doDownload(v) {
    const f = await api.saveFile(S.id, v.version);
    const url = URL.createObjectURL(f.blob);
    const a = h("a", { href: url, download: f.name || "save v" + v.version + ".sav", hidden: true });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    toast("Downloading version " + v.version);
  }

  async function doPin(v) {
    await api.updateSaveVersion(S.id, v.version, { pinned: !v.pinned });
    await refresh();
    toast(v.pinned ? "Unpinned version " + v.version : "Pinned. Version " + v.version + " won't be cleared out.");
  }

  async function doLabel(v) {
    const max = (S.info.saveRules && S.info.saveRules.maxLabelLength) || 60;
    const r = await ask({
      title: "Name this save", text: ["Version " + v.version + ", " + when(v.created) + ". Leave it empty to remove the name."],
      input: { label: "Name", value: v.label || "", max }, ok: "Save name",
    });
    if (r !== "ok") return;
    const label = $("ask-input").value.trim();
    await api.updateSaveVersion(S.id, v.version, { label });
    await refresh();
    toast(label ? "Named version " + v.version : "Name removed");
  }

  async function doDelete(v) {
    const r = await ask({
      title: "Delete this save?",
      text: ["Version " + v.version + " from " + when(v.created) + (v.label ? " (“" + v.label + "”)" : "") + " will be deleted for good. This can't be undone.",
        "Download it first if you might want it later."],
      ok: "Delete for good", okKind: "danger",
    });
    if (r !== "ok") return;
    await api.deleteSaveVersion(S.id, v.version);
    await refresh();
    toast("Version " + v.version + " deleted");
  }

  async function doFresh() {
    const cur = S.hist.currentVersion;
    if (!cur) return;
    const r = await ask({
      title: "Start fresh?",
      text: ["Next time you play, the game starts from the beginning.",
        "Nothing is deleted: the save in use now (version " + cur.version + ", " + when(cur.created) + ") stays in the list, so you can pick it again any time with “Use this save”.",
        "This save is shared with the phone app."],
      ok: "Start fresh", okKind: "danger",
    });
    if (r !== "ok") return;
    await api.clearSave(S.id, { base: S.hist.current });
    await refresh();
    toast("Started fresh. Version " + cur.version + " is kept in the list.");
  }

  async function doUpload(file) {
    const max = 64 * 1024 * 1024;
    if (file.size > max) throw new Error("That file is far too big to be a save (" + size(file.size) + ").");
    const cur = S.hist.currentVersion;
    const r = await ask({
      title: "Upload this save?",
      text: ["“" + file.name + "” (" + size(file.size) + ") becomes the save in use for " + $("g-title").textContent + ".",
        cur ? "The save in use now (version " + cur.version + ", " + when(cur.created) + ") stays in the list." : "",
        "This save is shared with the phone app."],
      ok: "Upload",
    });
    if (r !== "ok") return;
    const bytes = new Uint8Array(await file.arrayBuffer());
    await sendUpload(bytes, { base: S.hist.current }, false);
  }

  /** Sends an upload and handles the server's answers: conflict and blank file both ask the person. */
  async function sendUpload(bytes, o, allowBlank) {
    let res;
    try {
      res = await api.uploadSave(S.id, bytes, Object.assign({}, o, { allowBlank }));
    } catch (e) {
      if (e && e.code === "blank" && !allowBlank) {
        const r = await ask({
          title: "This file looks blank",
          text: [message(e), "A blank save usually means the game hasn't saved anything yet. Upload it anyway? The save in use stays in the list."],
          ok: "Upload anyway",
        });
        if (r === "ok") await sendUpload(bytes, o, true);
        return;
      }
      throw e;
    }
    if (res.status === 409) {
      const cur = res.body && res.body.current;
      const r = await ask({
        title: "The save changed",
        text: ["Another device saved this game while you had the page open" +
          (cur ? " (version " + cur.version + ", " + when(cur.created) + " from " + (SOURCE[cur.source] || cur.source) + ")." : "."),
          "Which should be in use? Both are kept in the list either way."],
        ok: "Use my file",
        more: [{ v: "server", label: "Keep Jellyfin's" }],
      });
      if (r === "ok") await sendUpload(bytes, { mode: "force" }, allowBlank);
      else if (r === "server") {
        await sendUpload(bytes, { mode: "history" }, allowBlank);
      } else await refresh();
      return;
    }
    await refresh();
    const b = res.body || {};
    const v = b.version;
    const warn = (b.warnings || []).join(" ");
    if (b.status === "unchanged") toast("That file matches version " + (v ? v.version : "?") + ", so nothing changed.");
    else if (o.mode === "history") toast("Your file is kept in the list as version " + (v ? v.version : "?") + ". Jellyfin's save stays in use.");
    else toast("Uploaded as version " + (v ? v.version : "?") + ". The previous save is kept in the list.");
    if (warn) showError("Uploaded, but check this: " + warn);
  }

  // ---------- move ----------
  /** Save format family from the server: saves move only within one (Game Boy and Game Boy Color share "gb"). */
  function saveFormat(key) {
    const c = consoleInfo(key);
    return c && c.saveFormat ? c.saveFormat : key;
  }

  function moveTargets() {
    const f = saveFormat(S.console);
    return S.games.filter(g => saveFormat(g.console) === f && g.id !== S.id)
      .sort((a, b) => String(a.sortName || a.name).localeCompare(String(b.sortName || b.name), "en-GB"));
  }

  /** "Game Boy or Game Boy Color" for a shared format, else just the console's name. */
  function formatNames(key) {
    const f = saveFormat(key);
    const names = ((S.info && S.info.consoles) || []).filter(c => saveFormat(c.key) === f).map(c => c.name);
    return names.length ? names.join(" or ") : consoleName(key);
  }

  /** Target name, with its console when it differs from this game's (e.g. a Game Boy save going to a Game Boy Color game). */
  function targetLabel(g) {
    return g.console === S.console ? g.name : g.name + " (" + consoleName(g.console) + ")";
  }

  function openMove() {
    const list = moveTargets();
    const sel = $("move-to");
    sel.replaceChildren(...list.map(g => h("option", { value: g.id, text: targetLabel(g) })));
    sel.hidden = !list.length;
    $("movebox").querySelector("[data-act=move-go]").hidden = !list.length;
    $("move-none").hidden = !!list.length;
    $("move-none").textContent = "There are no other " + formatNames(S.console) + " games in your library to move this save to.";
    $("movebox").querySelector("label").textContent = (S.hist.versions || []).length ? "Move all of this save's versions to" : "Move this game's snapshots to";
    $("movebox").hidden = false;
    $("acts").querySelector("[data-act=move]").setAttribute("aria-expanded", "true");
    (list.length ? sel : $("movebox").querySelector("[data-act=move-cancel]")).focus();
  }

  function closeMove() {
    $("movebox").hidden = true;
    $("acts").querySelector("[data-act=move]").setAttribute("aria-expanded", "false");
  }

  async function doMove() {
    const to = $("move-to").value;
    const target = S.byId.get(to);
    if (!target) return;
    const n = (S.hist.versions || []).length;
    const there = (S.list.saves || []).find(e => e.itemId === to);
    // F8: a removed game with only snapshots moves just those; the target's save isn't touched.
    const r = await ask(!n ? {
      title: "Move these snapshots?",
      text: ["Its browser snapshots move to “" + target.name + "”. That game's save isn't changed.",
        "A snapshot slot “" + target.name + "” already uses is never replaced: that snapshot stays here."],
      ok: "Move snapshots",
    } : {
      title: "Move this save?",
      text: ["All " + n + (n === 1 ? " version" : " versions") + " move to “" + target.name + "”. Nothing is deleted, but this game's list will be empty afterwards.",
        there && there.current
          ? "“" + target.name + "” already has a save in use. It stays in use; the moved versions go into its list, so you can pick one there with “Use this save”."
          : "The save in use here becomes the save in use for “" + target.name + "”.",
        "Its browser snapshots move too. A snapshot slot “" + target.name + "” already uses is never replaced: that snapshot stays here.",
        target.console !== S.console
          ? consoleName(S.console) + " and " + consoleName(target.console) + " saves use the same format, so either game can use them."
          : null].filter(Boolean),
      ok: "Move save",
    });
    if (r !== "ok") return;
    const res = await api.moveSave(S.id, to);
    // The page reloads for the target game, so the message is handed over and shown there (see showFlash).
    // The game's browser snapshots move too (B9); the server says which, and any that stayed behind.
    const from = (S.game && S.game.name) || (S.entries[0] && S.entries[0].gameName);
    const notes = (res && Array.isArray(res.warnings)) ? res.warnings.filter(w => typeof w === "string") : [];
    const what = n ? "Save" : "Snapshots";
    setFlash(to, [from ? what + " moved here from “" + from + "”." : what + " moved here."].concat(notes).join(" "));
    location.assign(addr({ id: to }));
  }

  // ---------- wiring ----------
  function onClick(e) {
    const b = e.target.closest("[data-act]");
    if (!b || !$("game-view").contains(b)) return;
    const act = b.dataset.act;
    const v = b.dataset.v ? version(Number(b.dataset.v)) : null;
    if (b.dataset.v && !v) return;
    const st = b.dataset.slot ? snapshot(b.dataset.slot) : null;
    if (b.dataset.slot && !st) return;
    switch (act) {
      case "restore": run(() => doRestore(v)); break;
      case "download": run(() => doDownload(v)); break;
      case "pin": run(() => doPin(v)); break;
      case "label": run(() => doLabel(v)); break;
      case "delete": run(() => doDelete(v)); break;
      case "fresh": run(doFresh); break;
      case "upload": if (!S.busy) { $("file").value = ""; $("file").click(); } break;
      case "move": if ($("movebox").hidden) openMove(); else closeMove(); break;
      case "move-cancel": closeMove(); break;
      case "move-go": run(doMove); break;
      case "snap-download": run(() => doSnapDownload(st)); break;
      case "snap-delete": run(() => doSnapDelete(st)); break;
      default: break;
    }
  }

  function wire() {
    $("signin").href = api.signInUrl;
    $("jf-home").href = api.homeUrl;
    readAddress();
    document.addEventListener("click", onClick);
    $("file").addEventListener("change", () => {
      const f = $("file").files && $("file").files[0];
      if (f) run(() => doUpload(f));
    });
    $("retry").addEventListener("click", load);
    const d = $("ask");
    d.addEventListener("close", onAskClosed);
    $("ask-b").addEventListener("click", e => {
      const b = e.target.closest("[data-ask]");
      if (b) d.close(b.dataset.ask);
    });
    $("ask-input").addEventListener("keydown", e => {
      if (e.key === "Enter") { e.preventDefault(); d.close("ok"); }
    });
    load();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
  else wire();
})();
