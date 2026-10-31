/*
 * Shoalcade library page (/shoalcade/), "Pocket" look (D55).
 * - Game list from GET /shoalcade/games: the server only returns ROMs this user's Jellyfin account can see,
 *   so hidden libraries never reach the page.
 * - Covers are Jellyfin's Primary image, loaded by plain URL (Jellyfin serves images without a login, so no
 *   token is ever put in a URL). No cover, or it fails to load: a plain tile with the console and name.
 * - "Played lately" only appears when there is real data: Jellyfin's last-played date or a save on the server.
 * - Everything is built with DOM calls and textContent (never innerHTML), because game names are metadata.
 * - Console filter and search are kept in the address (?c=&q=) so Back from the player returns to the same view;
 *   the open game is #game=<id>, so Back closes it. ?c= is one console (gba) or a maker's group (nintendo, sony).
 * - Console bar: All, then one menu button per maker (Nintendo ▾, Sony ▾, from the server's consoles[].maker) and a
 *   plain button for a console with no maker. A maker with only one console that has games is a plain
 *   button for that console.
 * - Sort (D123): Name / Year / Platform / Last played, the same words and order rules as the app's GameSort.kt.
 *   Remembered per user in the server's settings ("global" scope, key librarySort), with this browser's
 *   localStorage as the fallback. Last played = the later of Jellyfin's last played and the current save's time.
 * - Admins get "Edit game" on the game sheet (D122): name, cover upload, find a cover, reset to automatic.
 *   The server checks admin again on every call.
 */
(function () {
  "use strict";

  const api = window.SC.api;
  const $ = id => document.getElementById(id);
  const SHORT = { gb: "GB", gbc: "GBC", gba: "GBA", n64: "N64", nds: "DS", "3ds": "3DS", psx: "PS1", psp: "PSP" };
  const RECENT_MAX = 6;
  /** The "★ Starred" filter (a pseudo console in ?c=, so Back from the player returns to it). */
  const STARRED = "starred";
  const SORTS = ["name", "year", "platform", "played"];
  const DEFAULT_SORT = "played";
  /** Platform order (same as the app): the Game Boy family oldest first, then N64, DS, 3DS, PS1, PSP. Unknown consoles last. */
  const PLATFORM_ORDER = ["gb", "gbc", "gba", "n64", "nds", "3ds", "psx", "psp"];
  const collator = new Intl.Collator("en-GB", { sensitivity: "base" });

  const S = { info: null, games: [], byId: new Map(), cards: new Map(), con: "all", q: "", sort: DEFAULT_SORT, openedHere: false, edit: null,
    groupOf: new Map(), menuBtn: null };

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

  const fold = s => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

  function hue(id) {
    let n = 0;
    for (let i = 0; i < id.length; i++) n = (n * 31 + id.charCodeAt(i)) % 360;
    return n;
  }

  function consoleName(key) {
    const c = S.info && (S.info.consoles || []).find(x => x.key === key);
    return c ? c.name : (SHORT[key] || key);
  }

  const rtf = new Intl.RelativeTimeFormat("en-GB", { numeric: "auto" });
  function ago(date) {
    const d = new Date(date);
    const s = (d.getTime() - Date.now()) / 1000;
    const a = Math.abs(s);
    if (a < 60) return "just now";
    if (a < 3600) return rtf.format(Math.round(s / 60), "minute");
    if (a < 86400) return rtf.format(Math.round(s / 3600), "hour");
    if (a < 7 * 86400) return rtf.format(Math.round(s / 86400), "day");
    return "on " + d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: d.getFullYear() === new Date().getFullYear() ? undefined : "numeric" });
  }

  /** Most recent real activity: Jellyfin's last played, or the newest save on the server. Null if none. */
  function lastActivity(g) {
    let best = null;
    const consider = (when, kind) => {
      if (!when) return;
      const t = new Date(when).getTime();
      if (!isNaN(t) && (!best || t > best.t)) best = { t, when, kind };
    };
    consider(g.lastPlayed, "Played");
    consider(g.save && g.save.created, "Saved");
    return best;
  }

  /** Why Play is not offered for a game, or null if it can be played here. */
  function cantPlay(g) {
    if (!S.info.browserPlayer) return "The browser player is switched off on this server.";
    if (!g.browserPlayable) return consoleName(g.console) + " games can't be played in the browser. Use the Shoalcade app.";
    if (!S.info.user.canDownload) return "Your Jellyfin account isn't allowed to download, so games can't be played here. Ask the server admin.";
    return null;
  }

  // ---------- sort (D123) ----------
  const byName = (a, b) => collator.compare(a.name, b.name) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

  /** The list in the chosen order. Unknowns (no year, never played, unknown console) always last, then by name. */
  function sortGames(list, sort) {
    const out = list.slice();
    if (sort === "name") return out.sort(byName);
    if (sort === "year") {
      return out.sort((a, b) => (a.year == null) - (b.year == null) || (b.year || 0) - (a.year || 0) || byName(a, b));
    }
    if (sort === "platform") {
      const rank = g => { const i = PLATFORM_ORDER.indexOf(g.console); return i < 0 ? PLATFORM_ORDER.length : i; };
      return out.sort((a, b) => rank(a) - rank(b) || byName(a, b));
    }
    const t = new Map(out.map(g => { const a = lastActivity(g); return [g.id, a ? a.t : null]; }));
    return out.sort((a, b) => (t.get(a.id) == null) - (t.get(b.id) == null) || (t.get(b.id) || 0) - (t.get(a.id) || 0) || byName(a, b));
  }

  const sortKey = () => "sc.librarySort." + (S.info && S.info.user ? S.info.user.id : "");

  function localSort() {
    try { return localStorage.getItem(sortKey()); } catch (e) { return null; }
  }

  // G6 L1: set while a sort change hasn't reached the server; the next visit sends it before trusting the server copy.
  const unsentKey = () => "sc.sortUnsent." + (S.info && S.info.user ? S.info.user.id : "");
  function unsent(v) {
    try { if (v) localStorage.setItem(unsentKey(), v); else localStorage.removeItem(unsentKey()); } catch (e) { /* private window */ }
  }

  /** The remembered sort: an unsent change from this browser first, then the server's settings, then this browser's copy, then Last played. */
  async function loadSort() {
    let pending = null;
    try { pending = localStorage.getItem(unsentKey()); } catch (e) { /* private window */ }
    if (SORTS.includes(pending)) {
      saveSort(pending); // tried once more now; still kept here if it fails again
      return pending;
    }
    let v = null;
    try {
      const p = await api.prefs("global");
      if (p && SORTS.includes(p.librarySort)) v = p.librarySort;
    } catch (e) { /* fall back to this browser's copy */ }
    if (!v) { const l = localSort(); if (SORTS.includes(l)) v = l; }
    return v || DEFAULT_SORT;
  }

  let sortSaving = Promise.resolve();
  function saveSort(v) {
    try { localStorage.setItem(sortKey(), v); } catch (e) { /* private window: the server copy still works */ }
    unsent(v);
    // Read-modify-write of the whole "global" document, one at a time, so other keys are kept.
    sortSaving = sortSaving.then(async () => {
      try {
        const p = await api.prefs("global");
        const obj = p && typeof p === "object" && !Array.isArray(p) ? p : {};
        if (obj.librarySort !== v) {
          obj.librarySort = v;
          await api.putPrefs("global", obj);
        }
        unsent(null);
      } catch (e) { /* kept in this browser and marked unsent: the next visit sends it again (loadSort) */ }
    });
    return sortSaving;
  }

  // ---------- tiles ----------
  function art(g, width) {
    const box = h("div", { class: "art" }, [
      h("span", { class: "art-t", text: g.name }),
      h("span", { class: "art-c", text: SHORT[g.console] || g.console }),
    ]);
    box.style.setProperty("--h", String(hue(g.id)));
    if (g.imageTag) {
      const img = h("img", { src: api.coverUrl(g.id, g.imageTag, width), alt: "", loading: "lazy", decoding: "async" });
      img.addEventListener("error", () => { img.remove(); box.classList.remove("has-cover"); }, { once: true });
      box.classList.add("has-cover");
      box.prepend(img);
    }
    return box;
  }

  function cardMeta(g) {
    const a = lastActivity(g);
    return a ? a.kind + " " + ago(a.when) : consoleName(g.console);
  }

  function card(g) {
    return h("button", { type: "button", class: "card", "data-open": g.id, "aria-label": g.name + ", " + consoleName(g.console) + (g.favourite ? ", starred" : "") }, [
      art(g, 400),
      h("span", { class: "card-t", text: (g.favourite ? "★ " : "") + g.name }),
      h("span", { class: "card-m", text: cardMeta(g) }),
    ]);
  }

  // ---------- library ----------
  function readAddress() {
    const p = new URLSearchParams(location.search);
    S.con = p.get("c") || "all";
    S.q = p.get("q") || "";
  }

  function writeAddress() {
    const p = new URLSearchParams();
    if (S.con !== "all") p.set("c", S.con);
    if (S.q) p.set("q", S.q);
    const s = p.toString();
    history.replaceState(history.state, "", location.pathname + (s ? "?" + s : "") + location.hash);
    $("settings-btn").href = settingsLink();
  }

  /** Settings page link carrying this view (?c=&q=), so its "Back to library" returns here (D134). */
  function settingsLink() {
    return savesLink(null).replace(/^saves-manager/, "settings");
  }

  /** Saves manager link carrying this view (?c=&q=), so its "Back to library" returns here. */
  function savesLink(id) {
    const p = new URLSearchParams();
    if (id) p.set("id", id);
    if (S.con !== "all") p.set("c", S.con);
    if (S.q) p.set("q", S.q);
    const s = p.toString();
    return "saves-manager" + (s ? "?" + s : "");
  }

  function renderFilters() {
    const counts = new Map();
    for (const g of S.games) counts.set(g.console, (counts.get(g.console) || 0) + 1);
    // Consoles in platform order (the server lists PS1 after PSP for its own reasons), only those with games.
    const keys = (S.info.consoles || []).map(c => c.key).filter(k => counts.has(k));
    for (const k of counts.keys()) if (!keys.includes(k)) keys.push(k);
    const rank = k => { const i = PLATFORM_ORDER.indexOf(k); return i < 0 ? PLATFORM_ORDER.length : i; };
    keys.sort((a, b) => rank(a) - rank(b));

    // Bar items in platform order: a maker's consoles share one item, placed where its first console is.
    const groups = new Map(), items = [];
    S.groupOf = new Map();
    for (const k of keys) {
      const c = (S.info.consoles || []).find(x => x.key === k);
      if (!c || !c.maker) { items.push({ keys: [k] }); continue; }
      const gk = c.maker.toLowerCase();
      if (!groups.has(gk)) { const grp = { gk, name: c.maker, keys: [] }; groups.set(gk, grp); items.push(grp); }
      groups.get(gk).keys.push(k);
      S.groupOf.set(k, gk);
    }
    // A group with one console is a plain button for it, so a ?c=sony link then means that console.
    if (groups.has(S.con) && groups.get(S.con).keys.length === 1) S.con = groups.get(S.con).keys[0];
    const starred = S.games.filter(g => g.favourite).length;
    if (S.con === STARRED && !starred) S.con = "all";
    if (S.con !== "all" && S.con !== STARRED && !counts.has(S.con) && !groups.has(S.con)) S.con = "all";

    S.menuBtn = null;
    const chip = (k, name, n) => h("button", { type: "button", class: "chip", "data-con": k, "aria-pressed": String(S.con === k) }, [
      h("span", { class: "chip-n", text: name }),
      h("span", { class: "chip-k", text: String(n) }),
    ]);
    const f = $("filters");
    f.replaceChildren(chip("all", "All games", S.games.length), ...(starred ? [chip(STARRED, "★ Starred", starred)] : []), ...items.map(it => it.keys.length > 1
      ? groupMenu(it, counts)
      : chip(it.keys[0], consoleName(it.keys[0]), counts.get(it.keys[0]))));
    f.hidden = keys.length < 2 && !starred; // one console and no stars: the strip would only say "All"
  }

  /** "Nintendo ▾" (or "Nintendo: GBA ▾" while one of its consoles is picked) and its menu: All Nintendo, then each console. */
  function groupMenu(grp, counts) {
    const id = "menu-" + grp.gk;
    const total = grp.keys.reduce((t, k) => t + counts.get(k), 0);
    const cur = grp.keys.includes(S.con) ? S.con : null;
    const item = (k, name, n) => h("li", { role: "none" }, [
      h("button", { type: "button", role: "menuitemradio", tabindex: "-1", "data-con": k, "aria-checked": String(S.con === k) }, [
        h("span", { text: name }),
        h("span", { class: "chip-k", text: String(n) }),
      ]),
    ]);
    return h("div", { class: "con-grp" }, [
      h("button", { type: "button", class: "chip", "data-grp": grp.gk, "data-on": (cur || S.con === grp.gk) ? "true" : null,
        "aria-haspopup": "menu", "aria-expanded": "false", "aria-controls": id }, [
        h("span", { class: "chip-n", text: grp.name + (cur ? ": " + (SHORT[cur] || consoleName(cur)) : "") }),
        h("span", { class: "chip-k", text: String(cur ? counts.get(cur) : total) }),
        h("span", { class: "chip-v", "aria-hidden": "true", text: "▾" }),
      ]),
      h("ul", { class: "con-menu", role: "menu", id, "aria-label": grp.name + " consoles", hidden: true },
        [item(grp.gk, "All " + grp.name, total)].concat(grp.keys.map(k => item(k, consoleName(k), counts.get(k))))),
    ]);
  }

  function menuItems(btn) { return [...$(btn.getAttribute("aria-controls")).querySelectorAll("[role=menuitemradio]")]; }

  function openMenu(btn, last) {
    closeMenu(false);
    const m = $(btn.getAttribute("aria-controls"));
    m.hidden = false;
    // Fixed, so the bar's sideways scroll never clips it; kept on screen at phone width.
    const r = btn.getBoundingClientRect();
    m.style.left = Math.max(8, Math.min(r.left, innerWidth - m.offsetWidth - 8)) + "px";
    m.style.top = (r.bottom + 4) + "px";
    btn.setAttribute("aria-expanded", "true");
    S.menuBtn = btn;
    const list = menuItems(btn);
    (last ? list[list.length - 1] : list.find(b => b.getAttribute("aria-checked") === "true") || list[0]).focus({ preventScroll: true });
  }

  function closeMenu(refocus) {
    const btn = S.menuBtn;
    if (!btn) return;
    S.menuBtn = null;
    $(btn.getAttribute("aria-controls")).hidden = true;
    btn.setAttribute("aria-expanded", "false");
    if (refocus) btn.focus();
  }

  /** Arrow keys open the menu from its button and move between its items; Escape closes it (focus back on the button). */
  function menuKeys(e) {
    const t = e.target;
    if (t.dataset.grp && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
      e.preventDefault();
      openMenu(t, e.key === "ArrowUp");
      return;
    }
    if (t.getAttribute("role") !== "menuitemradio" || !S.menuBtn) return;
    const list = menuItems(S.menuBtn), i = list.indexOf(t);
    const to = { ArrowDown: i + 1, ArrowUp: i - 1, Home: 0, End: list.length - 1 }[e.key];
    if (to !== undefined) {
      e.preventDefault();
      list[(to + list.length) % list.length].focus({ preventScroll: true });
    } else if (e.key === "Escape") {
      e.preventDefault();
      closeMenu(true);
    } else if (e.key === "Tab") {
      closeMenu(false);
    }
  }

  function pickConsole(t) {
    const fromMenu = t.getAttribute("role") === "menuitemradio" && S.menuBtn ? S.menuBtn.dataset.grp : null;
    S.con = t.dataset.con;
    closeMenu(false);
    renderFilters();
    writeAddress();
    renderGrid();
    // The bar is rebuilt: focus goes back to the button just used (the group button after a menu pick).
    const again = $("filters").querySelector(fromMenu ? '[data-grp="' + fromMenu + '"]' : '[data-con="' + S.con + '"]');
    if (again) again.focus({ preventScroll: true });
  }

  function renderGrid() {
    const q = fold(S.q.trim());
    const list = sortGames(S.games, S.sort).filter(g => (S.con === "all" || (S.con === STARRED ? g.favourite : g.console === S.con || S.groupOf.get(g.console) === S.con)) && (!q || fold(g.name).includes(q) || fold(g.sortName).includes(q)));
    const grid = $("grid");
    const kids = list.map(g => S.cards.get(g.id));
    if (!list.length) {
      kids.push(h("p", { class: "empty", text: !S.games.length
        ? "No games yet. Ask the server admin to add a games library you can see."
        : q ? "No games match “" + S.q.trim() + "”. Clear the search or pick another console."
        : S.con === STARRED ? "No starred games. Open a game and choose Star." : "No games for this console." }));
    }
    grid.replaceChildren(...kids);
    $("count").textContent = list.length + (list.length === 1 ? " game" : " games");
  }

  function renderRecent() {
    const list = S.games.map(g => ({ g, a: lastActivity(g) })).filter(x => x.a)
      .sort((x, y) => y.a.t - x.a.t).slice(0, RECENT_MAX);
    $("recent").hidden = !list.length;
    $("cols").classList.toggle("solo", !list.length);
    $("recent-list").replaceChildren(...list.map(({ g, a }) =>
      h("button", { type: "button", class: "cont", "data-open": g.id }, [
        art(g, 120),
        h("span", { class: "cont-txt" }, [
          h("span", { class: "card-t", text: g.name }),
          h("span", { class: "card-m", text: consoleName(g.console) + ", " + a.kind.toLowerCase() + " " + ago(a.when) }),
        ]),
      ])));
  }

  // ---------- game sheet ----------
  function saveText(v) {
    if (!v) return null;
    return "Version " + v.version + ", saved " + ago(v.created) + (v.device ? " from " + v.device : v.source ? " from " + v.source : "");
  }

  function fillSheet(g) {
    $("sheet-art").replaceChildren(art(g, 400));
    $("sheet-title").textContent = g.name;
    const a = lastActivity(g);
    $("sheet-sub").textContent = consoleName(g.console) + (a ? ", " + a.kind.toLowerCase() + " " + ago(a.when) : ", not played yet");

    const play = $("sheet-play");
    const why = cantPlay(g);
    if (why) {
      play.removeAttribute("href");
      play.setAttribute("aria-disabled", "true");
    } else {
      // "from" = this view (?c=&q=), so the player's Back returns here.
      play.href = "play?id=" + encodeURIComponent(g.id) + "&from=" + encodeURIComponent(location.search.replace(/^\?/, ""));
      play.removeAttribute("aria-disabled");
    }
    $("sheet-why").textContent = why || "";
    $("sheet-why").hidden = !why;
    $("sheet-saves").href = savesLink(g.id);
    $("sheet-edit").hidden = !(S.info.user && S.info.user.isAdmin);
    const star = $("sheet-star");
    star.hidden = !(S.info.features || []).includes("favourites");
    star.setAttribute("aria-pressed", String(!!g.favourite));
    $("sheet-star-t").textContent = g.favourite ? "Starred" : "Star";

    const facts = [
      ["Console", consoleName(g.console)],
      ["Region", g.region],
      ["Year", g.year],
      ["Series", g.series],
      ["Last played", g.lastPlayed ? ago(g.lastPlayed) : null],
      ["Save", saveText(g.save) || "None on the server yet"],
    ].filter(([, v]) => v !== null && v !== undefined && v !== "");
    $("sheet-facts").replaceChildren(...facts.flatMap(([k, v]) => [h("dt", { text: k }), h("dd", { text: String(v) })]));
  }

  /** Stars or unstars the open game; the card, the Starred chip and the grid follow. */
  async function toggleStar() {
    const g = hashGame();
    if (!g || S.starBusy) return;
    S.starBusy = true;
    try {
      await api.setFavourite(g.id, !g.favourite);
      g.favourite = !g.favourite;
      S.cards.set(g.id, card(g));
      fillSheet(g);
      renderFilters();
      renderGrid();
    } catch (e) {
      $("sheet-why").textContent = "Couldn't change the star: " + (e && e.message ? e.message : String(e));
      $("sheet-why").hidden = false;
    } finally {
      S.starBusy = false;
    }
  }

  function hashGame() {
    const m = /^#game=([0-9a-f]{32})$/i.exec(location.hash);
    return m ? S.byId.get(m[1].toLowerCase()) || null : null;
  }

  function syncSheet() {
    const d = $("sheet");
    const g = S.info ? hashGame() : null;
    if (g) {
      if (S.edit && S.edit.id !== g.id) closeEditor();
      fillSheet(g);
      if (!d.open) d.showModal();
    } else if (d.open) {
      d.close();
    }
  }

  // ---------- admin game editor (D122) ----------
  function edStatus(text, isError) {
    const p = $("ed-status");
    p.textContent = text || "";
    p.hidden = !text;
    p.classList.toggle("sg-err", !!isError);
  }

  function edBusy(on) {
    for (const el of $("editor").querySelectorAll("button, input")) {
      if (el.id === "ed-done") continue;
      el.disabled = on;
    }
    $("editor").setAttribute("aria-busy", String(on));
  }

  function clearResults() {
    if (S.edit && S.edit.abort) S.edit.abort.abort();
    for (const u of (S.edit && S.edit.urls) || []) URL.revokeObjectURL(u);
    if (S.edit) { S.edit.urls = []; S.edit.abort = null; }
    $("ed-results").replaceChildren();
  }

  function closeEditor() {
    clearResults();
    S.edit = null;
    $("editor").hidden = true;
    $("sheet-edit").setAttribute("aria-expanded", "false");
    edStatus("");
  }

  /** What the server says about the game: whether finding covers works, and what Reset would undo. */
  function showEditState(st) {
    S.edit.state = st;
    const find = !!st.canFindCovers;
    $("ed-find").disabled = !find;
    $("ed-find-form").querySelector("button[type=submit]").disabled = !find;
    $("ed-find-help").textContent = find
      ? "Searches the libretro game list for this console. Type the main title, e.g. “HeartGold”."
      : "Finding a cover isn't available for this game right now (Shoalcade must be the one finding your games, and libretro must have covers for the console). You can still upload a picture.";
    const bits = [];
    if (st.nameEdited) bits.push("name set by an admin");
    if (st.coverSource === "upload") bits.push("cover uploaded");
    else if (st.coverSource === "libretro") bits.push("cover picked" + (st.coverFrom ? " (" + st.coverFrom + ")" : ""));
    $("ed-now").textContent = bits.length ? "Changed: " + bits.join(", ") + "." : "Name and cover are automatic.";
    $("ed-reset").disabled = !bits.length;
  }

  async function openEditor() {
    const g = hashGame();
    if (!g) return;
    S.edit = { id: g.id, urls: [], abort: null, state: null };
    $("editor").hidden = false;
    $("sheet-edit").setAttribute("aria-expanded", "true");
    $("ed-name").value = g.name;
    $("ed-find").value = g.name;
    $("ed-now").textContent = "";
    clearResults();
    edStatus("Loading…");
    edBusy(true);
    let ok = false;
    try {
      showEditState(await api.editGame(g.id));
      edStatus("");
      ok = true;
    } catch (e) {
      edStatus(e.status === 403 ? "Only Jellyfin admins can edit games." : "Couldn't open the editor: " + e.message, true);
    } finally {
      edBusy(false);
      if (ok && S.edit) showEditState(S.edit.state);
    }
    $("editor").scrollIntoView({ block: "nearest" });
    $("ed-name").focus();
  }

  /** Reloads one game from the server and redraws its card, the lists and the sheet. */
  async function refreshGame(id) {
    const fresh = await api.game(id);
    const i = S.games.findIndex(x => x.id === id);
    if (i >= 0) S.games[i] = fresh; else S.games.push(fresh);
    S.byId.set(id, fresh);
    S.cards.set(id, card(fresh));
    renderGrid();
    renderRecent();
    const open = hashGame();
    if (open && open.id === id) fillSheet(fresh);
    return fresh;
  }

  /** Runs one editor action: busy while it runs, then the game is reloaded and the result shown. */
  async function edRun(action, done) {
    const id = S.edit && S.edit.id;
    if (!id) return false;
    edBusy(true);
    edStatus("Working…");
    let st = null;
    try {
      st = await action(id);
      const text = typeof done === "function" ? done(st) : done;
      // G6 L3: the change is saved by now; a failed list refresh must not look like a failed edit.
      try {
        await refreshGame(id);
      } catch (e) {
        edStatus("Saved, but the list couldn't refresh: " + e.message + " Reload the page to see it.", true);
        return true;
      }
      edStatus(text);
      return true;
    } catch (e) {
      edStatus(e.status === 403 ? "Only Jellyfin admins can edit games." : e.message, true);
      return false;
    } finally {
      edBusy(false);
      if (S.edit && (st || S.edit.state)) showEditState(st || S.edit.state);
    }
  }

  function saveName(e) {
    e.preventDefault();
    const name = $("ed-name").value.trim();
    if (!name) { edStatus("Type a name.", true); return; }
    edRun(id => api.renameGame(id, name), "Name saved. It stays after library scans.");
  }

  function uploadPicked() {
    const f = $("ed-file").files && $("ed-file").files[0];
    $("ed-file").value = "";
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) { edStatus("That picture is too big (10 MB at most).", true); return; }
    edRun(id => api.uploadCover(id, f), "Cover saved. It stays after library scans.");
  }

  async function findCovers(e) {
    e.preventDefault();
    const text = $("ed-find").value.trim();
    const id = S.edit && S.edit.id;
    if (!text || !id) return;
    clearResults();
    const abort = new AbortController();
    S.edit.abort = abort;
    edStatus("Searching…");
    let names;
    try {
      names = (await api.findCovers(id, text)).names || [];
    } catch (err) {
      if (!abort.signal.aborted) edStatus("Couldn't search: " + err.message, true);
      return;
    }
    if (abort.signal.aborted) return;
    if (!names.length) {
      edStatus("No games called “" + text + "” in the libretro list for this console. Try fewer words, like the main title.");
      return;
    }
    edStatus(names.length + (names.length === 1 ? " match. " : " matches. ") + "Pick a cover to use it.");
    const tiles = names.map(name => {
      const b = h("button", { type: "button", class: "ed-pick", "data-pick": name, disabled: true, "aria-label": "Use the cover of " + name }, [
        h("span", { class: "ed-pic" }, [h("span", { class: "ed-wait", text: "Loading…" })]),
        h("span", { class: "ed-cap", text: name }),
      ]);
      return { name, b };
    });
    $("ed-results").replaceChildren(...tiles.map(x => x.b));
    // One preview at a time (the server asks libretro one at a time anyway).
    for (const { name, b } of tiles) {
      if (abort.signal.aborted) return;
      const pic = b.querySelector(".ed-pic");
      try {
        const blob = await api.coverPreview(id, name, abort.signal);
        if (abort.signal.aborted) return;
        if (!blob) { pic.replaceChildren(h("span", { class: "ed-wait", text: "No cover" })); continue; }
        const url = URL.createObjectURL(blob);
        S.edit.urls.push(url);
        pic.replaceChildren(h("img", { src: url, alt: "" }));
        b.disabled = false;
      } catch (err) {
        if (abort.signal.aborted) return;
        pic.replaceChildren(h("span", { class: "ed-wait", text: "Couldn't load" }));
      }
    }
  }

  function pickCover(e) {
    const b = e.target.closest("[data-pick]");
    if (!b || b.disabled) return;
    const name = b.dataset.pick;
    edRun(id => api.pickCover(id, name), "Cover saved (" + name + "). It stays after library scans.");
  }

  async function resetGame() {
    const g = hashGame();
    if (!g) return;
    if (!window.confirm("Put “" + g.name + "” back to its automatic name and cover?")) return;
    clearResults();
    // G6 L2b: a name locked in Jellyfin's own metadata editor isn't ours to unlock, so say it stays.
    const done = st => st && st.nameLockedInJellyfin
      ? "Cover back to automatic. The name stays: it is locked in Jellyfin's own metadata editor (unlock it there)."
      : "Back to automatic.";
    if (await edRun(id => api.resetGame(id), done)) {
      const fresh = hashGame();
      if (fresh) { $("ed-name").value = fresh.name; $("ed-find").value = fresh.name; }
    }
  }

  function openGame(id) {
    if (location.hash !== "#game=" + id) {
      S.openedHere = true;
      location.hash = "game=" + id; // -> hashchange -> syncSheet
    } else {
      syncSheet();
    }
  }

  function onSheetClosed() {
    closeEditor();
    if (!hashGame()) return;
    // Closed with Esc or the X: drop the #game from the address too.
    if (S.openedHere) { S.openedHere = false; history.back(); }
    else history.replaceState(history.state, "", location.pathname + location.search);
  }

  // ---------- states ----------
  function show(which) {
    for (const id of ["loading", "signed-out", "failed", "library"]) $(id).hidden = id !== which;
    $("search-box").hidden = which !== "library";
  }

  async function load() {
    show("loading");
    if (!api.hasLogin()) { show("signed-out"); return; }
    try {
      const [info, games] = await Promise.all([api.info(), api.games()]);
      S.info = info;
      S.sort = await loadSort();
      $("sort").value = S.sort;
      api.showVersion(info);
      S.games = Array.isArray(games) ? games : [];
      S.byId = new Map(S.games.map(g => [g.id, g]));
      S.cards = new Map(S.games.map(g => [g.id, card(g)]));
      $("who").textContent = info.user && info.user.name ? "Signed in as " + info.user.name : "";
      $("search").value = S.q;
      $("settings-btn").href = settingsLink();
      renderFilters();
      renderGrid();
      renderRecent();
      show("library");
      syncSheet();
    } catch (e) {
      if (e && e.status === 401) {
        $("signed-out-text").textContent = e.message;
        show("signed-out");
      } else {
        $("failed-text").textContent = e && e.message ? e.message : String(e);
        show("failed");
      }
    }
  }

  function wire() {
    $("signin").href = api.signInUrl;
    $("jf-home").href = api.homeUrl;
    readAddress();

    document.addEventListener("click", e => {
      const t = e.target.closest("[data-open],[data-con],[data-grp]");
      // A tap anywhere outside the open menu (and its button) closes it.
      if (S.menuBtn && !S.menuBtn.parentNode.contains(e.target)) closeMenu(false);
      if (!t) return;
      if (t.dataset.grp) {
        if (S.menuBtn === t) closeMenu(true);
        else openMenu(t, false);
      } else if (t.dataset.con) {
        pickConsole(t);
      } else {
        openGame(t.dataset.open);
      }
    });
    $("filters").addEventListener("keydown", menuKeys);
    // The menu is placed by its button's position: scrolling or resizing closes it rather than leave it behind.
    document.addEventListener("scroll", e => { if (S.menuBtn && !S.menuBtn.parentNode.contains(e.target)) closeMenu(false); }, true);
    window.addEventListener("resize", () => closeMenu(false));
    $("search").addEventListener("input", e => { S.q = e.target.value; writeAddress(); renderGrid(); });
    $("search").addEventListener("keydown", e => {
      if (e.key === "Escape" && e.target.value) { e.target.value = ""; S.q = ""; writeAddress(); renderGrid(); }
    });
    $("retry").addEventListener("click", load);
    $("sort").addEventListener("change", e => {
      S.sort = SORTS.includes(e.target.value) ? e.target.value : DEFAULT_SORT;
      renderGrid();
      saveSort(S.sort);
    });
    $("sheet-edit").addEventListener("click", () => (S.edit ? closeEditor() : openEditor()));
    $("sheet-star").addEventListener("click", toggleStar);
    $("ed-done").addEventListener("click", closeEditor);
    $("ed-name-form").addEventListener("submit", saveName);
    $("ed-upload").addEventListener("click", () => $("ed-file").click());
    $("ed-file").addEventListener("change", uploadPicked);
    $("ed-find-form").addEventListener("submit", findCovers);
    $("ed-results").addEventListener("click", pickCover);
    $("ed-reset").addEventListener("click", resetGame);
    $("sheet-x").addEventListener("click", () => $("sheet").close());
    $("sheet").addEventListener("close", onSheetClosed);
    // Tap outside the sheet closes it.
    $("sheet").addEventListener("click", e => { if (e.target === e.currentTarget) e.currentTarget.close(); });
    window.addEventListener("hashchange", syncSheet);

    load();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
  else wire();
})();
