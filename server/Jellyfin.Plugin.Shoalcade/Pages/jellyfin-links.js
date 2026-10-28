/*
 * Shoalcade links inside Jellyfin's own web client (D67). Our plugin adds this one file to Jellyfin web's page.
 * - The "Shoalcade" link in Jellyfin's menu: shown only to users who can see at least one game. Jellyfin draws it from
 *   its config.json; if the copy Jellyfin loaded lacks it (old cached file, a proxy, Jellyfin's built-in fallback),
 *   we add it ourselves in the same places (top bar, side menu), never twice.
 * - "Play" and "Saves" buttons on a game's page (only ROMs in our games libraries; never films, books etc.).
 * Uses Jellyfin web's own login: the token goes only in the Authorization header, never in a URL.
 * If Jellyfin's page ever changes shape, this quietly does nothing: it must never break Jellyfin.
 */
(function () {
  "use strict";
  if (window.__shoalcadeLinks) return;
  window.__shoalcadeLinks = true;

  // "/jellyfin/web/index.html" -> "/jellyfin"
  const BASE = (function () {
    const p = location.pathname;
    const i = p.lastIndexOf("/web/");
    return i > 0 ? p.slice(0, i) : "";
  })();

  let user = null; // user the answers below belong to
  let hasGames = false;
  let items = {}; // item id -> { isGame, playable } (null while asking)
  let queued = false;
  let failedAt = {}; // item id -> time its last ask failed (no retry for RETRY_MS)
  const RETRY_MS = 30000;
  let warned = false;

  // Must match JellyfinWebLinksMiddleware.MenuUrl / MenuName / MenuIcon (checked by the plugin's tests).
  const MENU_URL = "../shoalcade/";
  const MENU_NAME = "Shoalcade";
  const OLD_MENU_NAME = "Games"; // the link's name before s0.2: an old cached config.json may still say it
  const MENU_ICON = "sports_esports";
  const LINK = 'a[href="' + MENU_URL + '"]';
  const MARK = "data-shoalcade-link"; // on links we added ourselves (fallback)
  // Jellyfin menus that each hold at most one Shoalcade link: classic side menu; new layout's top bar and side menu.
  const MENUS = ".customMenuOptions, .MuiToolbar-root, .MuiDrawer-root";

  // One console line per page load, so a lasting failure is visible without flooding the log.
  function warnOnce(what, e) {
    if (warned) return;
    warned = true;
    try { console.warn("Shoalcade: " + what + " (links hidden)", e && e.message ? e.message : e); } catch (x) { /* never break Jellyfin */ }
  }

  function client() {
    const c = window.ApiClient;
    return c && typeof c.getCurrentUserId === "function" && typeof c.getJSON === "function" ? c : null;
  }

  function ask(c, path) {
    return Promise.resolve(c.getJSON(c.getUrl(path)));
  }

  function icon(extra, name) {
    const s = document.createElement("span");
    s.className = "material-icons " + extra + " " + name;
    s.setAttribute("aria-hidden", "true");
    return s;
  }

  // The Shoalcade link itself is drawn by Jellyfin web (our plugin adds it to Jellyfin's own menu links list).
  // Here we only hide it from users with no games, open it in this tab instead of a new one, and (upgrade from
  // before s0.2) rename a link of ours still labelled with the old name, so the menu never shows "Games".
  // Only a style, an attribute and that one label change: Jellyfin's own elements are never moved or removed.
  function menu() {
    document.querySelectorAll(LINK).forEach(function (a) {
      const item = a.parentElement && a.parentElement.tagName === "LI" ? a.parentElement : a;
      const show = hasGames ? "" : "none";
      if (item.style.display !== show) item.style.display = show;
      if (a.hasAttribute("target")) a.removeAttribute("target");
      relabel(a);
    });
    fallback();
  }

  // Our link's label text (not the icon ligature) from the old name to the new one. Nothing else is touched.
  function relabel(a) {
    const walk = document.createTreeWalker(a, NodeFilter.SHOW_TEXT);
    for (let n = walk.nextNode(); n; n = walk.nextNode()) {
      if (n.parentElement && n.parentElement.classList.contains("material-icons")) continue;
      if (n.nodeValue.trim() === OLD_MENU_NAME) n.nodeValue = n.nodeValue.replace(OLD_MENU_NAME, MENU_NAME);
    }
  }

  // Fallback: if Jellyfin's loaded config.json had no Shoalcade link, add one ourselves where Jellyfin would have drawn it.
  // One per menu (classic side menu; new layout's top bar and side menu), only for users with games, and ours goes
  // again as soon as Jellyfin's own link is there. Ours are marked, and are the only elements we ever remove.
  function fallback() {
    document.querySelectorAll("[" + MARK + "]").forEach(function (el) {
      const menuEl = el.parentElement && el.parentElement.closest(MENUS);
      const keep = hasGames && menuEl
        && menuEl.querySelector("[" + MARK + "]") === el // the first of ours in this menu
        && !Array.prototype.some.call(menuEl.querySelectorAll(LINK), function (a) { return !a.closest("[" + MARK + "]"); }); // Jellyfin's own isn't there
      if (!keep) el.remove();
    });
    if (!hasGames) return;

    // Classic layout: Jellyfin's own list of menu links in the side menu.
    document.querySelectorAll(".mainDrawer .customMenuOptions").forEach(function (list) {
      if (list.querySelector(LINK)) return;
      const a = document.createElement("a");
      a.className = "emby-button navMenuOption lnkMediaFolder";
      a.setAttribute("href", MENU_URL);
      a.setAttribute(MARK, "");
      a.appendChild(icon("navMenuOptionIcon", MENU_ICON));
      const t = document.createElement("span");
      t.className = "navMenuOptionText";
      t.textContent = MENU_NAME;
      a.appendChild(t);
      list.appendChild(a);
    });

    // New layout: copy Jellyfin's own Favourites link (same look) and put ours next to it.
    document.querySelectorAll('.MuiToolbar-root a.MuiButton-root[href="#/home?tab=1"], .MuiDrawer-root a.MuiListItemButton-root[href="#/home?tab=1"]').forEach(function (fav) {
      const menuEl = fav.closest(MENUS);
      if (!menuEl || menuEl.querySelector(LINK)) return;
      const inBar = fav.classList.contains("MuiButton-root");
      // Copy Favourites, or (while Favourites is the highlighted one) a library button after it, so ours never looks selected.
      let from = fav;
      if (inBar && !fav.classList.contains("MuiButton-colorInherit")) {
        for (let n = fav.nextElementSibling; n; n = n.nextElementSibling) {
          if (n.matches("a.MuiButton-colorInherit") && n.querySelector(".MuiButton-startIcon")) { from = n; break; }
        }
      }
      const at = inBar ? fav : fav.parentElement.tagName === "LI" ? fav.parentElement : fav;
      const item = (inBar ? from : at).cloneNode(true);
      const a = item.tagName === "A" ? item : item.querySelector("a");
      if (!a) return;
      a.setAttribute("href", MENU_URL);
      a.removeAttribute("target");
      a.classList.remove("Mui-selected", "Mui-focusVisible");
      item.setAttribute(MARK, "");
      // Icon: Jellyfin's own icon font, as Jellyfin uses for menu links.
      const box = a.querySelector(".MuiButton-startIcon, .MuiListItemIcon-root");
      if (box) {
        const s = document.createElement("span");
        s.className = "material-icons notranslate MuiIcon-root MuiIcon-fontSizeMedium";
        s.setAttribute("aria-hidden", "true");
        s.textContent = MENU_ICON;
        box.replaceChildren(s);
      }
      // Label: the copied link's text becomes "Shoalcade".
      const walk = document.createTreeWalker(a, NodeFilter.SHOW_TEXT);
      let named = false;
      for (let n = walk.nextNode(); n; n = walk.nextNode()) {
        if (!n.nodeValue.trim() || (n.parentElement && n.parentElement.classList.contains("material-icons"))) continue;
        n.nodeValue = named ? "" : MENU_NAME;
        named = true;
      }
      if (!named) return;
      at.parentNode.insertBefore(item, at.nextSibling);
    });
  }

  function itemId() {
    const h = location.hash || "";
    if (h.indexOf("#/details") !== 0) return null;
    const m = /[?&]id=([0-9a-f]{32})(?:&|$)/i.exec(h);
    return m ? m[1].toLowerCase() : null;
  }

  function button(id, kind, href, iconName, label) {
    const a = document.createElement("a");
    a.className = "button-flat detailButton emby-button shoalcade-btn shoalcade-" + kind;
    a.href = href;
    a.title = label;
    a.setAttribute("aria-label", label);
    a.dataset.id = id;
    const d = document.createElement("div");
    d.className = "detailButton-content";
    d.appendChild(icon("detailButton-icon", iconName));
    a.appendChild(d);
    return a;
  }

  function buttons(c) {
    const id = itemId();
    const page = document.querySelector(".itemDetailPage:not(.hide)");
    const row = page && page.querySelector(".mainDetailButtons");
    document.querySelectorAll(".shoalcade-btn").forEach(function (b) {
      if (!row || !row.contains(b) || b.dataset.id !== id) b.remove();
    });
    if (!id || !row) return;
    if (!(id in items)) {
      if (failedAt[id] && Date.now() - failedAt[id] < RETRY_MS) return; // wait before asking again
      items[id] = null;
      const u = user;
      ask(c, "shoalcade/links/" + id).then(function (r) {
        if (user === u) { delete failedAt[id]; items[id] = r || {}; schedule(); }
      }, function (e) {
        warnOnce("couldn't check this item", e);
        if (user === u) { delete items[id]; failedAt[id] = Date.now(); } // try again after RETRY_MS
      });
      return;
    }
    const info = items[id];
    if (!info || !info.isGame || row.querySelector(".shoalcade-btn")) return;
    const q = "?id=" + encodeURIComponent(id);
    const first = row.firstChild;
    if (info.playable) row.insertBefore(button(id, "play", BASE + "/shoalcade/play" + q, "sports_esports", "Play in browser"), first);
    row.insertBefore(button(id, "saves", BASE + "/shoalcade/saves-manager" + q, "save", "Game saves"), first);
  }

  /** Does this user have games? Hidden until the answer; a failed ask is tried again after RETRY_MS (L10). */
  function checkGames(c, u) {
    ask(c, "shoalcade/links").then(function (r) {
      if (user === u) { hasGames = !!(r && r.hasGames); schedule(); }
    }, function (e) {
      warnOnce("couldn't check for games", e); // stay hidden for now
      setTimeout(function () {
        try { const c2 = client(); if (user === u && c2) checkGames(c2, u); } catch (e2) { warnOnce("couldn't check for games", e2); }
      }, RETRY_MS);
    });
  }

  function run() {
    queued = false;
    try {
      const c = client();
      const u = c ? c.getCurrentUserId() || null : null;
      if (u !== user) {
        user = u;
        hasGames = false;
        items = {};
        failedAt = {};
        if (u) checkGames(c, u);
      }
      menu();
      if (u) buttons(c);
    } catch (e) {
      warnOnce("page check failed", e); // never break Jellyfin's page
    }
  }

  function schedule() {
    if (queued) return;
    queued = true;
    setTimeout(run, 60);
  }

  try {
    new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    window.addEventListener("hashchange", schedule);
    document.addEventListener("viewshow", schedule);
    schedule();
  } catch (e) {
    // Never break Jellyfin's page.
  }
})();
