/*
 * RPG Maker extras on the player page (D147), after play-ui.js: "Keep in this browser" (Saves tab) and the touch
 * keyboard for typing names. DOM calls + textContent only (D66).
 */
(function () {
  "use strict";
  const player = window.SC.player;
  const $ = id => document.getElementById(id);
  const MB = 1024 * 1024;
  const mb = n => (n >= 1024 * MB ? (n / 1024 / MB).toFixed(1) + " GB" : Math.round(n / MB) + " MB");

  // ----- Keep in this browser -----
  const sec = $("keep-sec"), sw = $("keep-on"), line = $("keep-state");
  let busy = false;
  function render(k) {
    k = k || player.keepState();
    sec.hidden = !k.total || k.why === "small";
    sw.checked = k.on;
    sw.disabled = !k.can || busy;
    let text;
    if (k.why === "https") text = "This browser can only keep games when Jellyfin is opened over HTTPS (or on this computer).";
    else if (k.why === "browser") text = "This browser can't keep games.";
    else if (!k.on) text = "Not kept. The game is " + mb(k.total) + ".";
    else if (k.error) text = "Couldn't keep the game: " + k.error;
    else if (k.kept >= k.total) text = "Kept in this browser (" + mb(k.total) + ").";
    else if (k.busy) text = "Keeping… " + mb(k.kept) + " of " + mb(k.total) + (typeof k.progress === "number" ? " (" + Math.round(k.progress * 100) + "% of what was missing)" : "") + ".";
    else text = "Kept: " + mb(k.kept) + " of " + mb(k.total) + "." + (k.failed ? " " + k.failed + " files couldn't be kept; they are tried again next time." : "");
    line.textContent = text;
  }
  sw.addEventListener("change", async () => {
    busy = true;
    render();
    try {
      await player.setKeep(sw.checked);
    } catch (e) {
      line.textContent = e.message;
    }
    busy = false;
    render();
  });
  player.on("keep", render);
  player.on("state", s => { if (s.state === "ready" || s.state === "playing") render(); });

  // ----- Touch keyboard: when the game wants text (typing bridge) the phone keyboard opens by itself; the Keyboard
  // button opens it by hand. Typed text goes to the game's typing file. -----
  const kb = $("tb-kb"), field = $("kb-in");
  let open = false;
  function setOpen(on) {
    open = on;
    kb.setAttribute("aria-pressed", String(on));
    if (on) { field.value = " "; field.focus(); } else field.blur(); // one space, so a delete key still sends an input event
  }
  kb.addEventListener("click", () => setOpen(!open));
  field.addEventListener("blur", () => { if (open) setOpen(false); });
  // While the game wants text it goes to the typing file; otherwise (Keyboard button) as key presses for games that
  // read keys themselves.
  const send = s => {
    if (player.typing) { player.typeText(s); return; }
    if (s === "\b") player.tapKey("Backspace");
    else if (s === "\n") player.tapKey("Enter");
    else for (const ch of s) player.typeChar(ch);
  };
  field.addEventListener("input", e => {
    if (e.inputType && e.inputType.startsWith("delete")) send("\b");
    else if (e.inputType === "insertLineBreak") send("\n");
    else if (e.data) send(e.data);
    field.value = " ";
  });
  field.addEventListener("keydown", e => {
    if (e.key === "Enter") { e.preventDefault(); send("\n"); }
    else if (e.key === "Backspace") { e.preventDefault(); send("\b"); }
    else if (e.key === "Escape" && player.typing) { e.preventDefault(); player.typeText("\x1b"); }
  });
  // The game asks for text: open the keyboard on touch screens (a PC types on its own keyboard); close it after.
  player.on("typing", on => {
    if (document.body.classList.contains("touch-on")) setOpen(on);
  });
  player.on("state", s => { kb.disabled = s.state !== "playing"; if (s.state !== "playing" && open) setOpen(false); });
})();
