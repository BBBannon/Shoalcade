/*
 * Shoalcade RPG Maker engine for the browser player (D147, docs/plan-rpgmaker.md).
 * RPG Maker XP / VX / VX Ace games run in mkxp-z (libretro core, emscripten build = RetroArch web + the core in one
 * module, served by the plugin at /shoalcade/ejs/mkxp-z/). The same core commit as the phone app.
 *
 * A game is a folder, not a file. Nothing is downloaded up front:
 *  - every file in the game's list (GET /shoalcade/games/{id}/files) becomes a file in the emulator's file system with
 *    its real size but no bytes; the bytes are fetched the first time the game reads them (GameFiles below);
 *  - small files come whole, big ones (music) in 1 MB pieces with Range requests, so a song starts at once;
 *  - the core runs on the page's own thread and reads files without waiting, so a read that isn't here yet is a
 *    synchronous request (the only kind a read can wait for). To keep those rare, the files the game read while starting
 *    last time (remembered per game in this browser) plus Data/, Fonts/ and the settings files are fetched in
 *    parallel before the game starts;
 *  - bytes held in memory are capped (MEMORY_BUDGET): the least recently used pieces are dropped and fetched again
 *    if needed (the browser's own cache usually has them, or "Keep in browser" does: see rpgmaker-sw.js).
 *
 * Every start also writes shoalcade_compat.rb (one source file: android/app/src/main/assets/rpgmaker/, served by the
 * plugin) into <system>/mkxp-z/Scripts/Preload/ and turns it on with the core option in COMPAT_OPTION.
 *
 * Saves: the core writes everything the game saves to <saves>/mkxp-z/Saves/<title>/. Shoalcade's save for the game is
 * a zip of that Saves folder (entries like "Pokemon Reborn-/Game.rxdata"), *.log and errorlog.txt left out, entries
 * sorted, fixed times (same rule as the phone), so an unchanged save always zips to the same bytes.
 *
 * Input: Shoalcade's own input layer (play.js Input: keyboard map, controllers, touch pad) presses RetroPad buttons
 * through keypad keys RetroArch is told to read as the pad (PROXY). Every real key is also passed to the game's own
 * keyboard (raw keys), so typing a name works.
 */
(function () {
  "use strict";
  const SC = window.SC = window.SC || {};

  const UD = "/home/web_user/retroarch/userdata";
  const CFG = UD + "/retroarch.cfg";
  const CORE_CFG = UD + "/retroarch-core-options.cfg";
  const SYSTEM = UD + "/system";
  const SAVES = UD + "/saves";
  const SHOTS = UD + "/screenshots";
  /** Where the core keeps every game's save folder. */
  const SAVE_DIR = SAVES + "/mkxp-z/Saves";
  const GAME_DIR = "/game";
  const PRELOAD_DIR = SYSTEM + "/mkxp-z/Scripts/Preload";
  const COMPAT_NAME = "shoalcade_compat.rb";
  /** mkxp-z_preload-<hex of the script's file name> = enabled turns a preload script on. */
  const preloadOption = name => "mkxp-z_preload-" + Array.from(new TextEncoder().encode(name), b => b.toString(16).padStart(2, "0")).join("");
  const COMPAT_OPTION = preloadOption(COMPAT_NAME);

  const CHUNK = 1 << 20;              // pieces of big files
  const WHOLE_MAX = 4 << 20;          // files up to this size are fetched whole
  const PREFETCH_MAX = 8 << 20;       // largest file fetched before the start
  const MEMORY_BUDGET = 256 << 20;    // most game bytes kept in memory at once (starting holds 50-150 MB)
  const PREFETCH_PARALLEL = 16;
  const BOOT_LEARN_MS = 90000;        // files read this long after the start count as "needed to start"
  const BOOT_LIST_MAX = 3000;
  /**
   * Always fetched before the start: the game's scripts, data, fonts and settings. Scripts/ holds loose script files
   * some games load one by one as they start (Rejuvenation: 258 files; fetched one at a time while the game waits,
   * that alone took 33 s).
   */
  const ALWAYS = p => /^(Data|Fonts)\//i.test(p) || /^Scripts\/.+\.rb$/i.test(p) || /^[^/]+\.(ini|json|rxdata|rvdata2?|rgss[23]?a|ttf|otf)$/i.test(p);

  // ---------------------------------------------------------------------------------------------
  // Game files, fetched when read
  // ---------------------------------------------------------------------------------------------
  class GameFiles {
    /**
     * fs: Emscripten FS. list: [{ path, size, sha256 }]. url(entry) -> address of one file.
     * headers() -> request headers (login). onFetch(kind, entry, bytes, ms) for statistics.
     */
    constructor(FS, list, url, headers, onFetch, keeper) {
      this.FS = FS;
      this.keeper = keeper || null; // "Keep in browser": files already in this browser are read from there
      this.url = url;
      this.headers = headers;
      this.onFetch = onFetch || (() => {});
      this.entries = new Map();   // FS node -> entry
      this.byPath = new Map();    // path -> entry
      this.lru = new Map();       // key -> bytes (oldest first)
      this.held = 0;              // bytes in memory
      this.readLog = null;        // paths read (boot learning), or null when not learning
      this.stats = { syncRequests: 0, syncBytes: 0, syncMs: 0, asyncRequests: 0, asyncBytes: 0, keptReads: 0, evicted: 0, errors: 0 };
      this.makeNodes(list);
    }

    makeNodes(list) {
      const FS = this.FS;
      const self = this;
      FS.mkdirTree(GAME_DIR);
      const dirs = new Set([GAME_DIR]);
      let ops = null;
      for (const f of list) {
        if (typeof f.path !== "string" || !/^[^/\\][^\\]*$/.test(f.path) || f.path.split("/").some(s => s === ".." || s === "." || s === "")) continue;
        const full = GAME_DIR + "/" + f.path;
        const dir = full.slice(0, full.lastIndexOf("/"));
        if (!dirs.has(dir)) { FS.mkdirTree(dir); dirs.add(dir); }
        const node = FS.create(full, 0o444);
        if (!ops) {
          const base = node.stream_ops;
          ops = Object.assign({}, base, {
            read(stream, buffer, offset, length, position) { return self.read(stream.node, buffer, offset, length, position); },
            write() { throw new FS.ErrnoError(63); /* EROFS: the game folder is read-only */ },
            mmap(stream, length, position, prot, flags) {
              // MEMFS copies from node.contents, so lend it the fetched bytes for this call only
              stream.node.contents = self.whole(self.entries.get(stream.node));
              try { return base.mmap(stream, length, position, prot, flags); } finally { stream.node.contents = null; }
            },
          });
        }
        node.stream_ops = ops;
        node.usedBytes = f.size;
        const e = { path: f.path, size: f.size, sha256: f.sha256, node, data: null, chunks: null };
        this.entries.set(node, e);
        this.byPath.set(f.path.toLowerCase(), e);
      }
    }

    read(node, buffer, offset, length, position) {
      const e = this.entries.get(node);
      if (!e) throw new this.FS.ErrnoError(5);
      if (this.readLog) this.readLog.add(e.path);
      if (position >= e.size || length <= 0) return 0;
      const n = Math.min(length, e.size - position);
      try {
        if (e.data || e.size <= WHOLE_MAX) {
          const d = this.whole(e);
          buffer.set(d.subarray(position, position + n), offset);
          return n;
        }
        const first = Math.floor(position / CHUNK), last = Math.floor((position + n - 1) / CHUNK);
        this.ensureChunks(e, first, last);
        let done = 0;
        for (let c = first; c <= last; c++) {
          const piece = e.chunks.get(c);
          const from = Math.max(position + done, c * CHUNK) - c * CHUNK;
          const take = Math.min(piece.length - from, n - done);
          buffer.set(piece.subarray(from, from + take), offset + done);
          done += take;
          this.touch(e.path + "#" + c);
        }
        return n;
      } catch (err) {
        this.stats.errors++;
        console.error("Shoalcade: couldn't read " + e.path + ": " + (err && err.message));
        throw new this.FS.ErrnoError(29); // EIO
      }
    }

    /** The whole file's bytes (fetched now if they aren't here). */
    whole(e) {
      if (e.data) { this.touch(e.path); return e.data; }
      const t = performance.now();
      const d = this.syncGet(e, null);
      if (d.length !== e.size) throw new Error("got " + d.length + " bytes, expected " + e.size);
      this.keep(e, d, performance.now() - t, "sync");
      return d;
    }

    keep(e, d, ms, kind) {
      if (e.data) return;
      e.data = d;
      e.chunks = null;
      this.held += d.length;
      this.lru.set(e.path, { e, c: -1, n: d.length });
      this.onFetch(kind, e, d.length, ms);
      this.evict(e.path);
    }

    ensureChunks(e, first, last) {
      if (!e.chunks) e.chunks = new Map();
      let c = first;
      while (c <= last) {
        if (e.chunks.has(c)) { c++; continue; }
        let end = c;
        while (end + 1 <= last && !e.chunks.has(end + 1)) end++;
        const from = c * CHUNK, to = Math.min(e.size, (end + 1) * CHUNK) - 1;
        const t = performance.now();
        const d = this.syncGet(e, [from, to]);
        if (d.length !== to - from + 1) throw new Error("got " + d.length + " bytes of a piece, expected " + (to - from + 1));
        for (let k = c; k <= end; k++) {
          const piece = d.subarray((k - c) * CHUNK, Math.min(d.length, (k - c + 1) * CHUNK));
          e.chunks.set(k, c === end ? d : piece.slice()); // own bytes per piece, so one can be dropped on its own
          this.held += piece.length;
          this.lru.set(e.path + "#" + k, { e, c: k, n: piece.length });
        }
        this.onFetch("range", e, d.length, performance.now() - t);
        this.evict(e.path);
        c = end + 1;
      }
    }

    touch(key) {
      const v = this.lru.get(key);
      if (v === undefined) return;
      this.lru.delete(key);
      this.lru.set(key, v);
    }

    /** Drops the least recently used bytes while over the budget (never the file being read now). */
    evict(current) {
      for (const [key, v] of this.lru) {
        if (this.held <= MEMORY_BUDGET) break;
        if (v.e.path === current) continue;
        this.lru.delete(key);
        this.held -= v.n;
        this.stats.evicted += v.n;
        if (v.c < 0) v.e.data = null; else if (v.e.chunks) v.e.chunks.delete(v.c);
      }
    }

    /** Synchronous request (the core is waiting for these bytes). range = [from, to] or null for the whole file. */
    syncGet(e, range) {
      const t0 = performance.now();
      if (this.keeper && this.keeper.has(e.sha256)) {
        const d = this.keeper.readSync(e.sha256, range ? range[0] : 0, range ? range[1] + 1 : e.size);
        if (d) { this.stats.keptReads++; return d; }
      }
      const x = new XMLHttpRequest();
      x.open("GET", this.url(e), false);
      // Binary over a synchronous request: each byte arrives as one character (the page's thread can't use arraybuffer).
      x.overrideMimeType("text/plain; charset=x-user-defined");
      const h = this.headers();
      for (const k in h) x.setRequestHeader(k, h[k]);
      if (range) x.setRequestHeader("Range", "bytes=" + range[0] + "-" + range[1]);
      x.send(null);
      this.stats.syncRequests++;
      this.stats.syncMs += Math.round(performance.now() - t0);
      let s = x.responseText;
      if (range && x.status === 200) s = s.slice(range[0], range[1] + 1); // the server sent the whole file
      else if (x.status !== (range ? 206 : 200)) throw new Error("the server said " + x.status);
      const out = new Uint8Array(s.length);
      for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xff;
      this.stats.syncBytes += out.length;
      return out;
    }

    /** Fetches files in parallel before the start (whole files only). progress(doneBytes, totalBytes). */
    async prefetch(paths, progress) {
      const t0 = performance.now();
      const list = [...new Set(paths.map(p => this.byPath.get(String(p).toLowerCase())))].filter(e => e && !e.data && e.size <= PREFETCH_MAX);
      const total = list.reduce((s, e) => s + e.size, 0);
      let done = 0, i = 0;
      const one = async () => {
        while (i < list.length) {
          const e = list[i++];
          const t = performance.now();
          try {
            let d = this.keeper && this.keeper.has(e.sha256) ? await this.keeper.read(e.sha256) : null;
            if (!d) {
              const res = await fetch(this.url(e), { headers: this.headers(), credentials: "omit" });
              if (!res.ok) throw new Error("the server said " + res.status);
              d = new Uint8Array(await res.arrayBuffer());
            }
            if (d.length !== e.size) throw new Error("wrong size");
            this.stats.asyncRequests++;
            this.stats.asyncBytes += d.length;
            this.keep(e, d, performance.now() - t, "prefetch");
          } catch (err) {
            this.stats.errors++;
            console.warn("Shoalcade: couldn't fetch " + e.path + " early (" + (err && err.message) + "); it will be fetched when the game reads it.");
          }
          done += e.size;
          if (progress) progress(done, total);
        }
      };
      await Promise.all(Array.from({ length: PREFETCH_PARALLEL }, one));
      return { files: list.length, bytes: total, ms: Math.round(performance.now() - t0) };
    }
  }

  // ---------------------------------------------------------------------------------------------
  // "Keep in browser" (big games): the game's files in this browser's Cache Storage, one entry per file keyed by its
  // SHA-256 (so a changed file is fetched again and an unchanged one never is). The core reads files without waiting,
  // and the Cache API only answers later, so a small worker reads the cache and the page waits for its answer on a
  // shared buffer (needs a cross-origin isolated page, which the player is, on HTTPS or localhost).
  // ---------------------------------------------------------------------------------------------
  const BRIDGE_BYTES = 8 << 20;
  const BRIDGE_WAIT_MS = 5000;
  const BRIDGE_SRC = [
    '"use strict";',
    "let ctl = null, data = null, cache = null;",
    "onmessage = async e => {",
    "  const m = e.data;",
    "  if (m.sab) { ctl = new Int32Array(m.sab, 0, 4); data = new Uint8Array(m.sab, 16); postMessage('ready'); return; }",
    "  try {",
    "    cache = cache || await caches.open(m.name);",
    "    const r = await cache.match(m.key);",
    "    if (!r) { Atomics.store(ctl, 0, 2); return; }",
    "    const b = await r.blob();",
    "    const buf = new Uint8Array(await b.slice(m.start, m.end).arrayBuffer());",
    "    if (buf.length > data.length) throw new Error('too big');",
    "    data.set(buf);",
    "    Atomics.store(ctl, 1, buf.length);",
    "    Atomics.store(ctl, 0, 1);",
    "  } catch (err) {",
    "    Atomics.store(ctl, 0, 3);",
    "  }",
    "};",
  ].join("\n");

  class Keeper {
    /** True if this browser can keep games (Cache Storage + a shared buffer: HTTPS or localhost, isolated page). */
    static supported() {
      return typeof caches !== "undefined" && typeof SharedArrayBuffer !== "undefined" && !!window.crossOriginIsolated && typeof Worker !== "undefined";
    }
    static cacheName(userId, itemId) { return "shoalcade-rpgmaker-" + String(userId || "me").replace(/[^A-Za-z0-9]/g, "") + "-" + itemId; }

    constructor(name) {
      this.name = name;
      this.cache = null;
      this.kept = new Set();
      this.worker = null;
    }

    keyFor(name) { return location.origin + "/shoalcade/rpgmaker-cache/" + name; }
    has(sha) { return !!sha && this.kept.has(sha); }

    /**
     * Opens the cache and learns which files it has from its index entry (listing a cache of thousands of files with
     * cache.keys() takes minutes in Chrome). A file the index names but the browser dropped is found on its first read
     * and fetched from the server from then on.
     */
    async open() {
      this.cache = await caches.open(this.name);
      const r = await this.cache.match(this.keyFor("index.json"));
      let list = [];
      try { list = r ? await r.json() : []; } catch (e) { list = []; }
      this.kept = new Set(Array.isArray(list) ? list.filter(x => typeof x === "string") : []);
      await this.startBridge();
      return this;
    }

    /**
     * Starts the worker that reads the cache for readSync() and waits until it runs: a worker can't start while the
     * page is busy waiting for it, so it must be up before the game's first read.
     */
    startBridge() {
      if (this.worker) return Promise.resolve(this.bridgeReady);
      this.bridgeReady = false;
      this.sab = new SharedArrayBuffer(16 + BRIDGE_BYTES);
      this.ctl = new Int32Array(this.sab, 0, 4);
      this.data = new Uint8Array(this.sab, 16);
      const url = URL.createObjectURL(new Blob([BRIDGE_SRC], { type: "text/javascript" }));
      this.worker = new Worker(url);
      return new Promise(resolve => {
        const done = ok => { this.bridgeReady = ok; URL.revokeObjectURL(url); resolve(ok); };
        const t = setTimeout(() => done(false), 10000);
        this.worker.onmessage = e => { if (e.data === "ready") { clearTimeout(t); done(true); } };
        this.worker.onerror = () => { clearTimeout(t); done(false); };
        this.worker.postMessage({ sab: this.sab });
      });
    }

    async writeIndex() {
      if (!this.cache) return;
      await this.cache.put(this.keyFor("index.json"), new Response(JSON.stringify(Array.from(this.kept)), { headers: { "Content-Type": "application/json" } }));
    }

    /** Bytes kept (of the files in list). */
    keptBytes(list) { return list.reduce((s, f) => s + (this.has(f.sha256) ? f.size : 0), 0); }

    /** A kept file's bytes (async), or null. */
    async read(sha) {
      if (!this.cache) return null;
      const r = await this.cache.match(this.keyFor(sha));
      return r ? new Uint8Array(await r.arrayBuffer()) : null;
    }

    /** A kept file's bytes [start, end) right now (the core is waiting), or null if they can't be read. */
    readSync(sha, start, end) {
      if (!this.worker || !this.bridgeReady) return null; // fetched from the server instead
      const out = new Uint8Array(end - start);
      for (let at = start; at < end; at += BRIDGE_BYTES) {
        const to = Math.min(end, at + BRIDGE_BYTES);
        Atomics.store(this.ctl, 0, 0);
        this.worker.postMessage({ name: this.name, key: this.keyFor(sha), start: at, end: to });
        const until = performance.now() + BRIDGE_WAIT_MS;
        let st = 0;
        // The page has to wait right here (the core is mid-read); the worker answers within milliseconds.
        while ((st = Atomics.load(this.ctl, 0)) === 0) { if (performance.now() > until) break; }
        if (st !== 1) {
          if (st === 2) this.kept.delete(sha); // gone (the browser cleared it): fetched from the server from now on
          if (st === 0) { this.worker.terminate(); this.bridgeReady = false; } // no answer in time: server only from now on
          return null;
        }
        const n = Atomics.load(this.ctl, 1);
        if (n !== to - at) return null;
        out.set(this.data.subarray(0, n), at - start);
      }
      return out;
    }

    /**
     * Downloads every file not kept yet (each checked against its SHA-256 before it is kept).
     * progress(doneBytes, totalBytes); stops early when signal is aborted. Returns { files, bytes, failed }.
     */
    async download(list, url, headers, progress, signal) {
      if (!this.cache) await this.open();
      const todo = list.filter(f => f.sha256 && !this.has(f.sha256));
      const total = todo.reduce((s, f) => s + f.size, 0);
      let done = 0, i = 0, failed = 0;
      const one = async () => {
        while (i < todo.length && !(signal && signal.aborted)) {
          const f = todo[i++];
          try {
            const res = await fetch(url(f), { headers: headers(), credentials: "omit", signal });
            if (!res.ok) throw new Error("the server said " + res.status);
            const buf = await res.arrayBuffer();
            const sha = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", buf)), b => b.toString(16).padStart(2, "0")).join("");
            if (buf.byteLength !== f.size || sha !== f.sha256.toLowerCase()) throw new Error("the file didn't match its list entry");
            if (!this.cache) break; // cleared meanwhile
            await this.cache.put(this.keyFor(f.sha256), new Response(buf, { headers: { "Content-Type": "application/octet-stream" } }));
            this.kept.add(f.sha256);
            if (++this.sinceIndex >= 500) { this.sinceIndex = 0; await this.writeIndex(); }
          } catch (err) {
            if (signal && signal.aborted) break;
            failed++;
            console.warn("Shoalcade: couldn't keep " + f.path + ": " + (err && err.message));
          }
          done += f.size;
          if (progress) progress(done, total);
        }
      };
      this.sinceIndex = 0;
      await Promise.all([one(), one(), one(), one()]);
      await this.writeIndex().catch(() => {});
      return { files: todo.length, bytes: total, failed };
    }

    /** Removes this game's kept files. */
    async clear() {
      if (this.worker) { this.worker.terminate(); this.worker = null; this.bridgeReady = false; }
      this.kept.clear();
      this.cache = null;
      return caches.delete(this.name);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Save zip (stored, sorted, fixed times) and unzip (stored + deflate)
  // ---------------------------------------------------------------------------------------------
  const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
    return t;
  })();
  function crc32(d) {
    let c = 0xffffffff;
    for (let i = 0; i < d.length; i++) c = CRC_TABLE[(c ^ d[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }
  // 01/01/1980 00:00 in DOS time/date (the phone's ZIP_TIME, the earliest a zip can say)
  const DOS_TIME = 0, DOS_DATE = (0 << 9) | (1 << 5) | 1;

  /** Zips [[name, bytes]] (names sorted here) with no compression. Null if there are no files. */
  function zipFiles(files) {
    files = files.slice().sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
    if (files.length === 0) return null;
    const enc = new TextEncoder();
    const parts = [], central = [];
    let off = 0;
    for (const [name, data] of files) {
      const nb = enc.encode(name), crc = crc32(data);
      const h = new DataView(new ArrayBuffer(30));
      h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true); h.setUint16(8, 0, true);
      h.setUint16(10, DOS_TIME, true); h.setUint16(12, DOS_DATE, true); h.setUint32(14, crc, true);
      h.setUint32(18, data.length, true); h.setUint32(22, data.length, true); h.setUint16(26, nb.length, true); h.setUint16(28, 0, true);
      const c = new DataView(new ArrayBuffer(46));
      c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true);
      c.setUint16(10, 0, true); c.setUint16(12, DOS_TIME, true); c.setUint16(14, DOS_DATE, true); c.setUint32(16, crc, true);
      c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, nb.length, true);
      c.setUint32(42, off, true);
      parts.push(new Uint8Array(h.buffer), nb, data);
      central.push(new Uint8Array(c.buffer), nb);
      off += 30 + nb.length + data.length;
    }
    const cdSize = central.reduce((s, p) => s + p.length, 0);
    const end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true);
    end.setUint32(12, cdSize, true); end.setUint32(16, off, true);
    const all = parts.concat(central, [new Uint8Array(end.buffer)]);
    const out = new Uint8Array(all.reduce((s, p) => s + p.length, 0));
    let o = 0;
    for (const p of all) { out.set(p, o); o += p.length; }
    return out;
  }

  async function inflateRaw(data) {
    const s = new Blob([data]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
    return new Uint8Array(await new Response(s).arrayBuffer());
  }

  /** Unzips (stored or deflated entries; read from the central directory). Returns [[name, bytes]] files only. */
  async function unzip(bytes) {
    const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let e = -1;
    for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) if (v.getUint32(i, true) === 0x06054b50) { e = i; break; }
    if (e < 0) throw new Error("The save isn't a zip.");
    const count = v.getUint16(e + 10, true);
    let p = v.getUint32(e + 16, true);
    const dec = new TextDecoder();
    const out = [];
    for (let k = 0; k < count; k++) {
      if (v.getUint32(p, true) !== 0x02014b50) throw new Error("The save zip is damaged.");
      const method = v.getUint16(p + 10, true), csize = v.getUint32(p + 20, true), usize = v.getUint32(p + 24, true);
      const nlen = v.getUint16(p + 28, true), xlen = v.getUint16(p + 30, true), clen = v.getUint16(p + 32, true);
      const local = v.getUint32(p + 42, true);
      const name = dec.decode(bytes.subarray(p + 46, p + 46 + nlen)).replace(/\\/g, "/");
      p += 46 + nlen + xlen + clen;
      if (name.endsWith("/")) continue;
      const parts = name.split("/");
      if (name.startsWith("/") || parts.some(s => s === ".." || s === "." || s === "")) throw new Error("The save contains a bad file name.");
      const lstart = local + 30 + v.getUint16(local + 26, true) + v.getUint16(local + 28, true);
      const raw = bytes.subarray(lstart, lstart + csize);
      let data;
      if (method === 0) data = raw.slice();
      else if (method === 8) data = await inflateRaw(raw);
      else throw new Error("The save zip uses a kind of packing Shoalcade can't read.");
      if (data.length !== usize) throw new Error("The save zip is damaged.");
      out.push([name, data]);
    }
    return out;
  }

  /**
   * Left out of the save zip (game logs, never progress). Every dot file or dot folder too, as on the phone: the typing
   * bridge's .shoalcade-typing and .shoalcade-keyboard live in the save folder and must never be synced.
   */
  function notSave(name) {
    const parts = name.split("/");
    const base = parts[parts.length - 1].toLowerCase();
    return base.endsWith(".log") || base === "errorlog.txt" || parts.some(p => p.startsWith("."));
  }

  /** Typing bridge files (shoalcade_compat.rb, module Typing): in the game's own save folder. */
  const TYPED = ".shoalcade-typing";
  const WANTED = ".shoalcade-keyboard";

  // ---------------------------------------------------------------------------------------------
  // Keys the core understands (mkxp-z's own keyboard map, src/input/input-retro.cpp)
  // ---------------------------------------------------------------------------------------------
  /**
   * RetroPad id -> the key RetroArch is told means that button (RetroArch config name, KeyboardEvent code).
   * RetroArch only turns keys into pad buttons (the core's own keyboard map only feeds raw keys, e.g. for typing a
   * name), so Shoalcade's input layer (keyboard map, controllers, touch pad: play.js Input) presses these keypad keys.
   * mkxp-z's pad map: A = C, B = B, X = A, Y = X, L3 = Y, R3 = Z, L/R, L2 = Ctrl, R2 = Shift, Start = Alt.
   */
  const PROXY = {
    0: ["kp_period", "NumpadDecimal"], 1: ["keypad3", "Numpad3"], 2: ["f15", "F15"], 3: ["kp_equals", "NumpadEqual"],
    4: ["keypad8", "Numpad8"], 5: ["keypad2", "Numpad2"], 6: ["keypad4", "Numpad4"], 7: ["keypad6", "Numpad6"],
    8: ["keypad5", "Numpad5"], 9: ["keypad1", "Numpad1"], 10: ["kp_minus", "NumpadSubtract"], 11: ["kp_plus", "NumpadAdd"],
    12: ["kp_divide", "NumpadDivide"], 13: ["kp_multiply", "NumpadMultiply"], 14: ["keypad7", "Numpad7"], 15: ["keypad9", "Numpad9"],
  };
  /** Held = fast-forward (RetroArch hotkey). */
  const FF_KEY = ["f14", "F14"];
  const PAD_NAMES = ["b", "y", "select", "start", "up", "down", "left", "right", "a", "x", "l", "r", "l2", "r2", "l3", "r3"];

  // ---------------------------------------------------------------------------------------------
  // The emulator
  // ---------------------------------------------------------------------------------------------
  function cfgText(obj) {
    return Object.keys(obj).map(k => k + ' = "' + String(obj[k]).replace(/"/g, "") + '"').join("\n") + "\n";
  }

  /** RetroArch settings: no menus, no hotkeys, no keyboard-to-pad mapping (the core reads the keyboard itself). */
  function retroarchConfig(extra) {
    const c = {
      savefile_directory: SAVES, savestate_directory: UD + "/states", system_directory: SYSTEM, screenshot_directory: SHOTS,
      sort_savefiles_enable: "false", sort_savefiles_by_content_enable: "false", sort_savestates_enable: "false",
      sort_savestates_by_content_enable: "false", sort_screenshots_by_content_enable: "false",
      savestate_auto_save: "false", savestate_auto_load: "false", savestate_thumbnail_enable: "false",
      config_save_on_exit: "false", core_options_path: CORE_CFG, game_specific_options: "false", auto_overrides_enable: "false",
      auto_remaps_enable: "false", global_core_options: "true",
      pause_nonactive: "false", video_font_enable: "false", menu_enable_widgets: "false", notification_show_autoconfig: "false",
      notification_show_config_override_load: "false", notification_show_remap_load: "false", notification_show_fast_forward: "false",
      notification_show_screenshot: "false", menu_show_load_content_animation: "false",
      input_autodetect_enable: "false", input_auto_game_focus: "0", input_max_users: "1",
      video_vsync: "true", video_smooth: "false", video_scale_integer: "false", aspect_ratio_index: "22" /* core provided */,
      fastforward_ratio: "2.0", audio_enable: "true", audio_mute_enable: "false", audio_latency: "96",
      log_verbosity: "true", libretro_log_level: "1", frontend_log_level: "1",
    };
    // Every keyboard binding off: RetroArch must never react to a key (Esc would quit, F1 open its menu, P pause...).
    const hotkeys = ["menu_toggle", "exit_emulator", "toggle_fast_forward", "hold_fast_forward", "toggle_slowmotion", "hold_slowmotion",
      "rewind", "pause_toggle", "frame_advance", "reset", "shader_next", "shader_prev", "shader_toggle", "cheat_index_plus", "cheat_index_minus",
      "cheat_toggle", "screenshot", "audio_mute", "osk_toggle", "netplay_game_watch", "netplay_player_chat", "netplay_host_toggle",
      "netplay_ping_toggle", "netplay_fade_chat_toggle", "enable_hotkey", "save_state", "load_state", "state_slot_increase",
      "state_slot_decrease", "toggle_fullscreen", "volume_up", "volume_down", "movie_record_toggle", "grab_mouse_toggle",
      "game_focus_toggle", "desktop_menu_toggle", "fps_toggle", "statistics_toggle", "disk_eject_toggle", "disk_next", "disk_prev",
      "ai_service", "recording_toggle", "streaming_toggle", "runahead_toggle", "preempt_toggle", "toggle_vrr_runloop", "overlay_next",
      "close_content", "replay_slot_increase", "replay_slot_decrease", "play_replay", "record_replay", "halt_replay",
      "send_debug_info", "turbo_fire_toggle", "toggle_statistics"];
    const off = k => { c[k] = c[k + "_btn"] = c[k + "_axis"] = "nul"; };
    for (const h of hotkeys) off("input_" + h);
    const pad = ["a", "b", "x", "y", "start", "select", "up", "down", "left", "right", "l", "r", "l2", "r2", "l3", "r3",
      "l_x_plus", "l_x_minus", "l_y_plus", "l_y_minus", "r_x_plus", "r_x_minus", "r_y_plus", "r_y_minus", "turbo"];
    for (const b of pad) off("input_player1_" + b);
    PAD_NAMES.forEach((b, id) => { c["input_player1_" + b] = PROXY[id][0]; });
    c.input_hold_fast_forward = FF_KEY[0];
    return Object.assign(c, extra || {});
  }

  /**
   * Starts a game. o = {
   *   canvas, coreUrl (…/mkxp-z_libretro.js), files ([{path,size,sha256}]), fileUrl(entry), headers() -> {},
   *   compat (text of shoalcade_compat.rb), save (zip bytes or null), bootList ([paths] read while starting last time),
   *   coreOptions {}, onStatus(text, done, total), onLog(line), onFetch(kind, entry, bytes, ms) }
   * Resolves with the running Engine once the core has been started.
   */
  async function boot(o) {
    const status = o.onStatus || (() => {});
    const log = o.onLog || (() => {});
    status("Starting the emulator…");
    const times = {};
    let tm = performance.now();
    const lap = k => { const n = performance.now(); times[k] = Math.round(n - tm); tm = n; };
    const factory = (await import(o.coreUrl)).default;
    const dir = o.coreUrl.slice(0, o.coreUrl.lastIndexOf("/") + 1);
    const lines = [];
    const keepLine = s => { lines.push(s); if (lines.length > 400) lines.shift(); log(s); };
    // The engine (~54 MB of WebAssembly) is fetched here, not by the core, so the loading bar can show how far it got.
    let wasmFailed;
    const wasmFail = new Promise((_, reject) => { wasmFailed = reject; });
    const Module = await Promise.race([wasmFail, factory({
      canvas: o.canvas,
      instantiateWasm: (imports, done) => {
        loadWasm(dir + "mkxp-z_libretro.wasm", imports, status).then(r => done(r.instance, r.module), wasmFailed);
        return {};
      },
      noInitialRun: true,
      noExitRuntime: true,
      locateFile: name => dir + name,
      print: (...a) => keepLine(a.join(" ")),
      printErr: (...a) => keepLine(a.join(" ")),
    })]);
    lap("core");
    const FS = Module.FS;
    for (const d of [UD, SYSTEM, SAVES, SAVE_DIR, SHOTS, UD + "/states", PRELOAD_DIR]) FS.mkdirTree(d);
    const files = new GameFiles(FS, o.files, o.fileUrl, o.headers, o.onFetch, o.keeper);
    const ini = files.byPath.get("game.ini");
    if (!ini) throw new Error("This game has no Game.ini.");

    // shoalcade_compat.rb (stand-ins + no network, see the file) runs before every game.
    FS.writeFile(PRELOAD_DIR + "/" + COMPAT_NAME, o.compat);
    FS.writeFile(CFG, cfgText(retroarchConfig(o.retroarch)));
    const opts = Object.assign({}, o.coreOptions || {});
    opts[COMPAT_OPTION] = "enabled";
    for (const name of Object.keys(o.preloads || {})) { // test scripts (browser_rpgmaker.mjs)
      if (!/^[A-Za-z0-9_]+\.rb$/.test(name)) continue;
      FS.writeFile(PRELOAD_DIR + "/" + name, o.preloads[name]);
      opts[preloadOption(name)] = "enabled";
    }
    FS.writeFile(CORE_CFG, cfgText(opts));

    // The save, before the game starts.
    if (o.save) putSave(FS, await unzip(o.save));

    // Files the game needs to start, fetched in parallel now.
    const want = [ini.path];
    for (const e of files.byPath.values()) if (ALWAYS(e.path)) want.push(e.path);
    for (const p of o.bootList || []) want.push(p);
    const pre = await files.prefetch(want, (d, t) => status("Loading game files (" + mbOf(d, t) + ")", d, t));
    lap("prefetch");

    files.readLog = new Set();
    status("Starting the game…");
    const engine = new Engine(Module, files, lines);
    engine.prefetched = pre;
    // The core is pointed at Game.ini (its folder is the game).
    Module.callMain([GAME_DIR + "/" + ini.path, "-c", CFG]);
    lap("callMain");
    engine.forwardKeys();
    engine.times = times;
    setTimeout(() => { engine.bootList = engine.readSoFar(); files.readLog = null; }, BOOT_LEARN_MS);
    return engine;
  }

  function mbOf(d, t) { return window.SC.mbOf ? window.SC.mbOf(d, t) : Math.round(100 * d / (t || 1)) + "%"; }

  /** Fetches and compiles the engine, saying how much has arrived ("Downloading the engine (12 of 54 MB)"). */
  async function loadWasm(url, imports, status) {
    status("Downloading the engine…");
    // Its size: Jellyfin compresses the download (no length then), but answers a one-byte range with the full size.
    const size = fetch(url, { credentials: "same-origin", headers: { Range: "bytes=0-0" } })
      .then(r => Number((/\/(\d+)$/.exec(r.headers.get("Content-Range") || "") || [])[1]) || 0).catch(() => 0);
    const res = await fetch(url, { credentials: "same-origin" });
    if (!res.ok) throw new Error("The game engine is missing (" + res.status + "). Reinstall the plugin.");
    const total = (res.headers.get("Content-Encoding") ? 0 : Number(res.headers.get("Content-Length")) || 0) || await size;
    let got = 0, last = 0;
    const counted = res.body.pipeThrough(new TransformStream({
      transform(chunk, ctl) {
        got += chunk.length;
        if (performance.now() - last > 200) {
          last = performance.now();
          status(total ? "Downloading the engine (" + mbOf(Math.min(got, total), total) + ")" : "Downloading the engine (" + Math.round(got / 1048576) + " MB)",
            total ? Math.min(got, total) : undefined, total || undefined);
        }
        ctl.enqueue(chunk);
      },
      flush() { status("Starting the engine…"); },
    }));
    return WebAssembly.instantiateStreaming(new Response(counted, { headers: { "Content-Type": "application/wasm" } }), imports);
  }

  /** A folder's entries (no "." or ".."), or [] if it isn't there. */
  function names(FS, dir) {
    try { return FS.readdir(dir).filter(n => n !== "." && n !== ".."); } catch (e) { return []; }
  }

  /** Writes unzipped save files ([[name, bytes]]) into the Saves folder. */
  function putSave(FS, entries) {
    for (const [name, data] of entries) {
      const p = SAVE_DIR + "/" + name;
      FS.mkdirTree(p.slice(0, p.lastIndexOf("/")));
      FS.writeFile(p, data);
    }
  }

  class Engine {
    constructor(Module, files, lines) {
      this.Module = Module;
      this.FS = Module.FS;
      this.files = files;
      this.lines = lines;
      this.paused = false;
      this.held = new Set();    // RetroPad ids pressed
      this.rawDown = new Set(); // physical keys passed on to the game and not let go yet
      this.bootList = null;
    }

    /**
     * RetroArch listens for keys on the game's canvas only. Keys pressed anywhere else on the page (nothing focused,
     * the toolbar) are passed on to it, except while typing in a field or when another handler already used the key.
     */
    forwardKeys() {
      const canvas = this.Module.canvas;
      this.onKey = e => {
        if (e.__shoalcade || e.target === canvas) return;
        if (e.type === "keydown" && !this.keysOn) return;
        if (e.type === "keyup" && !this.rawDown.has(e.code)) return;
        const t = e.target;
        if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
        if (t && /^(BUTTON|A)$/.test(t.tagName) && (e.code === "Space" || e.code === "Enter")) return;
        if (e.ctrlKey || e.metaKey) return;
        if (e.type === "keydown") this.rawDown.add(e.code); else this.rawDown.delete(e.code);
        this.key([e.code, e.key], e.type === "keydown", e.repeat);
        if (/^(Arrow|Space$|Tab$)/.test(e.code)) e.preventDefault(); // no page scrolling
      };
      this.keysOn = true;
      window.addEventListener("keydown", this.onKey);
      window.addEventListener("keyup", this.onKey);
    }

    /** Game keys on or off (off while a panel is open or the game is paused). */
    setKeys(on) { this.keysOn = !!on; if (!on) this.releaseAll(); }

    /** The paths read so far while starting (for next time). */
    readSoFar() { return this.bootList || (this.files.readLog ? Array.from(this.files.readLog).slice(0, BOOT_LIST_MAX) : []); }

    /** Every file in the core's Saves folder as [[name, bytes]] (names relative to it), save files only. */
    saveFiles() {
      const FS = this.FS, out = [];
      const walk = (dir, rel) => {
        for (const n of names(FS, dir)) {
          const p = dir + "/" + n, r = rel ? rel + "/" + n : n;
          const st = FS.stat(p);
          if (FS.isDir(st.mode)) walk(p, r);
          else if (FS.isFile(st.mode) && !notSave(r)) out.push([r, FS.readFile(p)]);
        }
      };
      walk(SAVE_DIR, "");
      return out;
    }

    // ----- typing (names): the core has no text input, so shoalcade_compat.rb reads typed text from a file -----
    /** The game's own save folder (the core's /Save): the newest folder under the Saves folder, or null. */
    typingFolder() {
      const FS = this.FS;
      let best = null, t = -1;
      for (const n of names(FS, SAVE_DIR)) {
        const st = FS.stat(SAVE_DIR + "/" + n);
        if (FS.isDir(st.mode) && st.mtime.getTime() >= t) { best = SAVE_DIR + "/" + n; t = st.mtime.getTime(); }
      }
      return best;
    }

    /** A new session: nothing typed yet, keyboard not wanted. False if the game's save folder isn't there yet. */
    resetTyping() {
      const dir = this.typingFolder();
      if (!dir) return false;
      this.FS.writeFile(dir + "/" + TYPED, "");
      this.FS.writeFile(dir + "/" + WANTED, "0");
      return true;
    }

    /** True while the game wants typed text (it wrote "1" to .shoalcade-keyboard). */
    typingWanted() {
      const dir = this.typingFolder();
      if (!dir) return false;
      try { return this.FS.readFile(dir + "/" + WANTED, { encoding: "utf8" }).trim() === "1"; } catch (e) { return false; }
    }

    /** Adds to the typing file: text as it is, Backspace "\b", Enter "\n", Escape "\x1b" (UTF-8). */
    type(text) {
      const dir = this.typingFolder();
      if (!dir || !text) return false;
      const bytes = new TextEncoder().encode(String(text).replace(/\r/g, ""));
      const s = this.FS.open(dir + "/" + TYPED, "a");
      try { this.FS.write(s, bytes, 0, bytes.length); } finally { this.FS.close(s); }
      return true;
    }

    /** The game's save as Shoalcade keeps it (zip), or null if the game hasn't saved anything. */
    readSave() { return zipFiles(this.saveFiles()); }

    /** Replaces the save folder with the zip's files (nothing written if the zip can't be read). */
    async writeSave(zip) {
      const entries = zip ? await unzip(zip) : [];
      const FS = this.FS;
      const clear = dir => {
        for (const n of names(FS, dir)) {
          const p = dir + "/" + n;
          if (FS.isDir(FS.stat(p).mode)) { clear(p); FS.rmdir(p); } else FS.unlink(p);
        }
      };
      clear(SAVE_DIR);
      putSave(FS, entries);
    }

    pause() { if (!this.paused) { this.releaseAll(); this.Module._cmd_pause(); this.paused = true; } }
    resume() { if (this.paused) { this.Module._cmd_unpause(); this.paused = false; } }
    reset() { this.releaseAll(); this.Module._cmd_reset(); }
    fastForward(on) { this.ffOn = !!on; this.key([FF_KEY[1], FF_KEY[1]], !!on); }
    /** Volume 0-1 (RetroArch takes decibels). */
    setVolume(v) {
      const db = v <= 0 ? -80 : Math.max(-80, 20 * Math.log10(v));
      try { this.Module._cmd_set_volume(db); } catch (e) { /* older builds: no volume */ }
    }

    /** RetroPad button id (0-15) down or up, from Shoalcade's input layer. */
    pad(id, down) {
      const p = PROXY[id];
      if (!p) return;
      if (down) this.held.add(id); else this.held.delete(id);
      this.key([p[1], p[1], 0], !!down);
    }

    key(k, down, repeat) {
      const ev = new KeyboardEvent(down ? "keydown" : "keyup", { code: k[0], key: k[1], repeat: !!repeat, bubbles: true, cancelable: true });
      ev.__shoalcade = true;
      this.Module.canvas.dispatchEvent(ev);
    }

    /** Types a character (on-screen keyboard): a key press with the matching code. */
    typeChar(ch) {
      const up = ch.toUpperCase();
      let code = null;
      if (/^[A-Z]$/.test(up)) code = "Key" + up;
      else if (/^[0-9]$/.test(ch)) code = "Digit" + ch;
      else code = { " ": "Space", "-": "Minus", ".": "Period", ",": "Comma", "'": "Quote", "/": "Slash", "=": "Equal", ";": "Semicolon" }[ch];
      if (!code) return false;
      const k = [code, ch, up.charCodeAt(0)];
      const shift = ch !== ch.toLowerCase();
      const sh = ["ShiftLeft", "Shift"];
      if (shift) this.key(sh, true);
      this.key(k, true);
      setTimeout(() => { this.key(k, false); if (shift) this.key(sh, false); }, 60);
      return true;
    }

    releaseAll() {
      for (const id of Array.from(this.held)) this.pad(id, false);
      for (const code of Array.from(this.rawDown)) this.key([code, code], false);
      if (this.ffOn) this.fastForward(false);
      this.rawDown.clear();
    }

    /** A PNG of the game picture from RetroArch's own screenshot (waits at most ms). */
    screenshot(ms) {
      const FS = this.FS;
      const list = () => names(FS, SHOTS).filter(n => n.endsWith(".png"));
      for (const n of list()) try { FS.unlink(SHOTS + "/" + n); } catch (e) { /* gone */ }
      this.Module._cmd_take_screenshot();
      const until = Date.now() + (ms || 3000);
      return new Promise((resolve, reject) => {
        let last = -1;
        const check = () => {
          const n = list()[0];
          if (n) {
            const size = FS.stat(SHOTS + "/" + n).size;
            if (size > 0 && size === last) { const d = FS.readFile(SHOTS + "/" + n); FS.unlink(SHOTS + "/" + n); resolve(d); return; }
            last = size;
          }
          if (Date.now() > until) { reject(new Error("The emulator didn't make a picture.")); return; }
          setTimeout(check, 60);
        };
        setTimeout(check, 60);
      });
    }
  }

  /** mkxp.json is JSON with comments and trailing commas allowed. Returns {} if it can't be read. */
  function parseMkxpJson(text) {
    try {
      let s = String(text).replace(/^﻿/, "");
      s = s.replace(/("(?:[^"\\]|\\.)*")|\/\/[^\n]*|\/\*[\s\S]*?\*\//g, (m, str) => str || "");
      s = s.replace(/("(?:[^"\\]|\\.)*")|,(\s*[}\]])/g, (m, str, end) => str || end);
      const v = JSON.parse(s);
      return v && typeof v === "object" ? v : {};
    } catch (e) {
      return {};
    }
  }

  SC.rpgmaker = { boot, GameFiles, Engine, Keeper, zipFiles, unzip, crc32, notSave, parseMkxpJson, PROXY, COMPAT_OPTION, SAVE_DIR, GAME_DIR };
})();
