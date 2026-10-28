/*
 * Shoalcade patcher page (/shoalcade/patcher). Applies a ROM patch to a game file the user picks, entirely in this
 * browser: nothing is uploaded and nothing on the server changes. The patched file is handed to the browser as a
 * download. Formats come from RomPatcher.js (rompatcher.js, MIT); xdelta3's default LZMA compression is unpacked
 * first by xdelta-unpack.js. Built with DOM calls and textContent only (never innerHTML).
 */
(function () {
  "use strict";

  const api = window.SC.api;
  const $ = id => document.getElementById(id);
  const BIG = 300 * 1024 * 1024;

  const S = { rom: null, patch: null, busy: false };

  const NAMES = {
    IPS: "IPS", UPS: "UPS", BPS: "BPS", PPF: "PPF", VCDIFF: "xdelta", APS: "APS", APSGBA: "APS",
    RUP: "RUP", BDF: "BDF", PMSR: "PMSR",
  };

  function size(n) {
    if (n < 1024 * 1024) return Math.max(1, Math.round(n / 1024)) + " KB";
    return (n / 1024 / 1024).toFixed(1).replace(/\.0$/, "") + " MB";
  }

  function show(which) {
    for (const id of ["loading", "signed-out", "failed", "patcher"]) $(id).hidden = id !== which;
  }

  function status(text) { $("status").textContent = text; }

  /** Lets the "Working…" line paint before a long synchronous patch. */
  const breathe = () => new Promise(r => setTimeout(r, 30));

  async function load() {
    show("loading");
    if (!api.hasLogin()) { show("signed-out"); return; }
    try {
      const info = await api.info();
      api.showVersion(info);
      $("who").textContent = info.user && info.user.name ? "Signed in as " + info.user.name : "";
      show("patcher");
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

  function reset() {
    $("mismatch").hidden = true;
    $("anyway").hidden = true;
    status("");
    $("go").disabled = !(S.rom && S.patch) || S.busy;
    $("big").hidden = !(S.rom && S.rom.size > BIG);
  }

  function pickRom() {
    const f = $("rom").files[0] || null;
    S.rom = f;
    $("rom-info").textContent = f ? f.name + " · " + size(f.size) : "";
    reset();
  }

  async function pickPatch() {
    const f = $("patch").files[0] || null;
    S.patch = null;
    $("patch-info").textContent = "";
    reset();
    if (!f) return;
    try {
      const parsed = await readPatch(f);
      S.patch = f;
      const kind = NAMES[parsed.patch.constructor.name] || "patch";
      $("patch-info").textContent = kind + " patch · " + size(f.size) + " · " + (checks(parsed)
        ? "it checks that you picked the right game file"
        : "this kind of patch can't check the game file, so make sure it's the one the patch was made for");
    } catch (e) {
      $("patch-info").textContent = explain(e);
    }
    reset();
  }

  /** Reads a patch file into RomPatcher's patch object (xdelta3's LZMA compression unpacked first). */
  async function readPatch(file) {
    let bytes = new Uint8Array(await file.arrayBuffer());
    if (XdeltaUnpack.kind(bytes)) bytes = XdeltaUnpack.unpack(bytes);
    const bin = new BinFile(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    bin.fileName = file.name;
    const patch = RomPatcher.parsePatchFile(bin);
    if (!patch) throw new Error("unknown_patch");
    // xdelta: the per-window checksums of the result, which the page checks itself after patching.
    const sums = patch.constructor.name === "VCDIFF" ? XdeltaUnpack.checksums(bytes) : null;
    return { patch, bytes, sums };
  }

  /** True if this kind of patch can tell whether the game file is the right one. */
  function checks(parsed) {
    if (parsed.sums) return parsed.sums.some(w => w.adler !== null);
    return typeof parsed.patch.validateSource === "function";
  }

  function explain(e) {
    const code = e && (e.code || e.message);
    if (code === "unknown_patch") return "This isn't a patch file this page can read. It works with IPS, UPS, BPS, PPF, xdelta, APS, RUP and PMSR patches.";
    if (code === "xdelta_other") return "This xdelta patch uses a kind of compression this page can't read. Ask for a version made with xdelta3's normal settings, or use a patcher on your computer.";
    if (code === "xdelta_bad" || code === "xz_bad") return "This xdelta patch looks damaged or incomplete. Try downloading it again.";
    if (code === "Target ROM checksum mismatch") return "The patched game didn't match what the patch expects, so it wasn't saved. Check you picked the game file the patch was made for (the same version and region).";
    if (e instanceof RangeError || (e && e.name === "RangeError")) return "Your browser ran out of memory. Close other tabs and try again, or use a computer with more memory.";
    return "Couldn't patch the game: " + (e && e.message ? e.message : String(e));
  }

  async function run(force) {
    if (!S.rom || !S.patch || S.busy) return;
    S.busy = true;
    $("go").disabled = true;
    $("anyway").hidden = true;
    $("mismatch").hidden = true;
    status("Reading your files…");
    await breathe();
    try {
      const { patch, bytes, sums } = await readPatch(S.patch);
      const rom = new BinFile(await S.rom.arrayBuffer());
      rom.fileName = S.rom.name;
      if (!force && !RomPatcher.validateRom(rom, patch)) {
        $("mismatch").textContent = "This doesn't look like the game file the patch was made for: its checksum is different. The patched game probably won't work. Check the version and region, or patch it anyway.";
        $("mismatch").hidden = false;
        $("anyway").hidden = false;
        status("");
        return;
      }
      status("Patching…");
      await breathe();
      let out;
      if (patch.constructor.name === "VCDIFF") {
        // xdelta: check each part of the result against the patch's own checksums (unless "Patch anyway").
        out = patch.apply(rom, false);
        if (!force && sums.some(w => w.adler !== null) && !XdeltaUnpack.verify(bytes, out._u8array)) throw new Error("Target ROM checksum mismatch");
        out.fileName = rom.fileName.replace(/\.([^.]*?)$/, " (patched).$1");
      } else {
        out = RomPatcher.applyPatch(rom, patch, { requireValidation: false, outputSuffix: true });
      }
      download(out);
      status("Done: " + out.fileName + " (" + size(out.fileSize) + ") is in your downloads.");
    } catch (e) {
      status("");
      $("mismatch").textContent = explain(e);
      $("mismatch").hidden = false;
      if (e && e.message === "Target ROM checksum mismatch") $("anyway").hidden = false;
    } finally {
      S.busy = false;
      $("go").disabled = !(S.rom && S.patch);
    }
  }

  function download(bin) {
    const blob = new Blob([bin._u8array], { type: "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = bin.fileName;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  function wire() {
    $("signin").href = api.signInUrl;
    $("jf-home").href = api.homeUrl;
    $("retry").addEventListener("click", load);
    $("rom").addEventListener("change", pickRom);
    $("patch").addEventListener("change", pickPatch);
    $("go").addEventListener("click", () => run(false));
    $("anyway").addEventListener("click", () => run(true));
    load();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
  else wire();
})();
