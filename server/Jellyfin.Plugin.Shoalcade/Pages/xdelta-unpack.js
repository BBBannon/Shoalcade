/*
 * Shoalcade patcher: reads xdelta3 patches that use LZMA "secondary compression" (xdelta3's default since 3.0).
 *
 * RomPatcher.js reads plain VCDIFF (xdelta) only. xdelta3 normally squeezes each window's three sections (data,
 * instructions, addresses) with LZMA2 inside an .xz stream: one continuous stream per section type for the whole
 * patch, flushed at the end of every window. XdeltaUnpack.unpack() decodes those sections and writes the same patch
 * back out uncompressed, which RomPatcher then applies as usual. Plain patches are returned untouched.
 *
 * The LZMA/LZMA2 decoder follows the public-domain LZMA specification (Igor Pavlov, LzmaSpec) and xz-embedded
 * (Lasse Collin, public domain). Written for Shoalcade; runs only in the page, on the files the user picked.
 */
"use strict";

var XdeltaUnpack = (function () {
  var VCD_DECOMPRESS = 0x01, VCD_CODETABLE = 0x02, VCD_APPHEADER = 0x04;
  var VCD_SOURCE = 0x01, VCD_TARGET = 0x02, VCD_ADLER32 = 0x04;
  var LZMA_ID = 2;
  var MAX_SECTION = 512 * 1024 * 1024; // a section bigger than this is not a real patch

  function fail(code) {
    var e = new Error(code);
    e.code = code;
    throw e;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Range decoder (one per LZMA chunk) and the LZMA decoder state that lives across chunks
  // ---------------------------------------------------------------------------------------------------------------

  function Range(buf, pos, end) {
    if (end - pos < 5 || buf[pos] !== 0) fail("xz_bad");
    this.buf = buf; this.pos = pos + 1; this.end = end;
    this.range = 0xFFFFFFFF;
    this.code = ((buf[pos + 1] << 24) >>> 0) + (buf[pos + 2] << 16) + (buf[pos + 3] << 8) + buf[pos + 4];
    this.pos = pos + 5;
    if (this.code === this.range) fail("xz_bad");
  }
  Range.prototype.normalize = function () {
    if (this.range < 0x1000000) {
      if (this.pos >= this.end) fail("xz_bad");
      this.range = this.range * 256;
      this.code = this.code * 256 + this.buf[this.pos++];
    }
  };
  Range.prototype.bit = function (probs, i) {
    var p = probs[i], bound = (this.range >>> 11) * p, b;
    if (this.code < bound) { this.range = bound; probs[i] = p + ((2048 - p) >>> 5); b = 0; }
    else { this.range -= bound; this.code -= bound; probs[i] = p - (p >>> 5); b = 1; }
    this.normalize();
    return b;
  };
  Range.prototype.direct = function (n) {
    var r = 0;
    while (n--) {
      this.range = Math.floor(this.range / 2);
      if (this.code >= this.range) { this.code -= this.range; r = r * 2 + 1; } else { r = r * 2; }
      this.normalize();
    }
    return r;
  };
  Range.prototype.tree = function (probs, base, bits) {
    var m = 1;
    for (var i = 0; i < bits; i++) m = (m << 1) | this.bit(probs, base + m);
    return m - (1 << bits);
  };
  Range.prototype.reverse = function (probs, base, bits) {
    var m = 1, s = 0;
    for (var i = 0; i < bits; i++) { var b = this.bit(probs, base + m); m = (m << 1) | b; s |= b << i; }
    return s;
  };

  function newLen() {
    return { choice: new Uint16Array(2), low: new Uint16Array(16 << 3), mid: new Uint16Array(16 << 3), high: new Uint16Array(256) };
  }
  function resetLen(l) { l.choice.fill(1024); l.low.fill(1024); l.mid.fill(1024); l.high.fill(1024); }
  function readLen(rc, l, posState) {
    if (rc.bit(l.choice, 0) === 0) return rc.tree(l.low, posState << 3, 3);
    if (rc.bit(l.choice, 1) === 0) return 8 + rc.tree(l.mid, posState << 3, 3);
    return 16 + rc.tree(l.high, 0, 8);
  }

  /** One LZMA2 stream's decoder: dictionary (window) + LZMA state, kept between chunks and between windows. */
  function Lzma2(dictSize) {
    this.size = Math.max(dictSize, 4096);
    this.dict = new Uint8Array(this.size);
    this.dpos = 0; this.full = 0; this.total = 0; // circular write position, bytes held, bytes since dict reset
    this.needDictReset = true; this.needProps = true;
    this.isMatch = new Uint16Array(12 << 4); this.isRep = new Uint16Array(12); this.isRepG0 = new Uint16Array(12);
    this.isRepG1 = new Uint16Array(12); this.isRepG2 = new Uint16Array(12); this.isRep0Long = new Uint16Array(12 << 4);
    this.posSlot = new Uint16Array(4 << 6); this.posSpec = new Uint16Array(115); this.align = new Uint16Array(16);
    this.len = newLen(); this.repLen = newLen();
    this.lit = null;
  }
  Lzma2.prototype.props = function (d) {
    if (d > 224) fail("xz_bad");
    this.lc = d % 9; d = (d / 9) | 0; this.lp = d % 5; this.pb = (d / 5) | 0;
    if (this.lc + this.lp > 4) fail("xz_bad");
    this.lit = new Uint16Array(0x300 << (this.lc + this.lp));
    this.needProps = false;
  };
  Lzma2.prototype.resetState = function () {
    if (!this.lit) fail("xz_bad");
    [this.isMatch, this.isRep, this.isRepG0, this.isRepG1, this.isRepG2, this.isRep0Long, this.posSlot, this.posSpec,
      this.align, this.lit].forEach(function (a) { a.fill(1024); });
    resetLen(this.len); resetLen(this.repLen);
    this.state = 0; this.rep0 = this.rep1 = this.rep2 = this.rep3 = 0;
  };
  Lzma2.prototype.resetDict = function () { this.dpos = 0; this.full = 0; this.total = 0; this.needDictReset = false; };
  Lzma2.prototype.put = function (b, out) {
    this.dict[this.dpos++] = b;
    if (this.dpos === this.size) this.dpos = 0;
    if (this.full < this.size) this.full++;
    this.total++;
    out.push(b);
  };
  Lzma2.prototype.back = function (dist) { // byte `dist` back (0 = the last byte written)
    var i = this.dpos - dist - 1;
    return this.dict[i < 0 ? i + this.size : i];
  };

  /** Decodes one LZMA chunk: exactly `unpacked` bytes from buf[pos, end). */
  Lzma2.prototype.chunk = function (buf, pos, end, unpacked, out) {
    var rc = new Range(buf, pos, end);
    var pbMask = (1 << this.pb) - 1, lpMask = (1 << this.lp) - 1, lc = this.lc;
    var goal = out.length + unpacked;
    while (out.length < goal) {
      var posState = this.total & pbMask, st = this.state;
      if (rc.bit(this.isMatch, (st << 4) + posState) === 0) {
        var prev = this.full ? this.back(0) : 0;
        var base = 0x300 * (((this.total & lpMask) << lc) + (prev >>> (8 - lc)));
        var sym = 1;
        if (st >= 7) {
          var match = this.back(this.rep0);
          do {
            var mb = (match >>> 7) & 1; match = (match << 1) & 0xFF;
            var b = rc.bit(this.lit, base + ((1 + mb) << 8) + sym);
            sym = (sym << 1) | b;
            if (mb !== b) break;
          } while (sym < 0x100);
        }
        while (sym < 0x100) sym = (sym << 1) | rc.bit(this.lit, base + sym);
        this.put(sym & 0xFF, out);
        this.state = st < 4 ? 0 : st < 10 ? st - 3 : st - 6;
        continue;
      }
      var len;
      if (rc.bit(this.isRep, st) === 0) {
        len = readLen(rc, this.len, posState);
        this.state = st < 7 ? 7 : 10;
        var dist = this.distance(rc, len);
        this.rep3 = this.rep2; this.rep2 = this.rep1; this.rep1 = this.rep0; this.rep0 = dist;
      } else {
        if (this.full === 0) fail("xz_bad");
        if (rc.bit(this.isRepG0, st) === 0) {
          if (rc.bit(this.isRep0Long, (st << 4) + posState) === 0) { // one byte at rep0
            this.state = st < 7 ? 9 : 11;
            this.put(this.back(this.rep0), out);
            continue;
          }
        } else {
          var d;
          if (rc.bit(this.isRepG1, st) === 0) { d = this.rep1; }
          else {
            if (rc.bit(this.isRepG2, st) === 0) { d = this.rep2; } else { d = this.rep3; this.rep3 = this.rep2; }
            this.rep2 = this.rep1;
          }
          this.rep1 = this.rep0; this.rep0 = d;
        }
        len = readLen(rc, this.repLen, posState);
        this.state = st < 7 ? 8 : 11;
      }
      len += 2;
      if (this.rep0 >= this.full || out.length + len > goal) fail("xz_bad");
      for (var k = 0; k < len; k++) this.put(this.back(this.rep0), out);
    }
    if (rc.pos !== end) fail("xz_bad");
  };
  Lzma2.prototype.distance = function (rc, len) {
    var lenState = len < 3 ? len : 3;
    var slot = rc.tree(this.posSlot, lenState << 6, 6);
    if (slot < 4) return slot;
    var bits = (slot >>> 1) - 1, dist = (2 | (slot & 1)) << bits;
    if (slot < 14) return dist + rc.reverse(this.posSpec, dist - slot - 1, bits);
    dist += rc.direct(bits - 4) * 16;
    return dist + rc.reverse(this.align, 0, 4);
  };

  // ---------------------------------------------------------------------------------------------------------------
  // .xz stream (as xdelta3 writes it: one block, LZMA2, no check, flushed per window, never finished)
  // ---------------------------------------------------------------------------------------------------------------

  var XZ_MAGIC = [0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00];

  function XzStream() { this.stage = 0; this.lz = null; this.ended = false; }

  /** Decodes one section's compressed bytes buf[pos, end) into exactly `size` bytes. */
  XzStream.prototype.section = function (buf, pos, end, size) {
    var out = [];
    if (this.stage === 0) { // stream header: magic, 2 flag bytes, CRC32
      if (end - pos < 12) fail("xz_bad");
      for (var i = 0; i < 6; i++) if (buf[pos + i] !== XZ_MAGIC[i]) fail("xz_bad");
      pos += 12; this.stage = 1;
    }
    if (this.stage === 1) { // block header
      if (pos >= end || buf[pos] === 0) fail("xz_bad");
      var hsize = (buf[pos] + 1) * 4, h = pos + 1, flags = buf[h++];
      if (end - pos < hsize || (flags & 3) !== 0) fail("xz_bad"); // one filter only (LZMA2)
      if (flags & 0x40) h = skipVli(buf, h);
      if (flags & 0x80) h = skipVli(buf, h);
      if (buf[h++] !== 0x21 || buf[h++] !== 1) fail("xz_bad"); // filter id LZMA2, 1 property byte
      var p = buf[h] & 0x3F;
      if (p > 40) fail("xz_bad");
      var dictSize = p === 40 ? 0xFFFFFFFF : (2 | (p & 1)) * Math.pow(2, (p >>> 1) + 11);
      this.lz = new Lzma2(Math.min(dictSize, 256 * 1024 * 1024));
      pos += hsize; this.stage = 2;
    }
    var lz = this.lz;
    while (pos < end && !this.ended) {
      var c = buf[pos++];
      if (c === 0x00) { this.ended = true; break; }
      if (c === 0x01 || c === 0x02) { // stored chunk
        if (end - pos < 2) fail("xz_bad");
        var n = ((buf[pos] << 8) | buf[pos + 1]) + 1; pos += 2;
        if (c === 0x01) lz.resetDict(); else if (lz.needDictReset) fail("xz_bad");
        if (end - pos < n) fail("xz_bad");
        for (var j = 0; j < n; j++) lz.put(buf[pos + j], out);
        pos += n;
        continue;
      }
      if (c < 0x80 || end - pos < 4) fail("xz_bad");
      var unpacked = ((c & 0x1F) << 16) + (buf[pos] << 8) + buf[pos + 1] + 1;
      var packed = (buf[pos + 2] << 8) + buf[pos + 3] + 1;
      pos += 4;
      var mode = (c >>> 5) & 3;
      if (mode === 3) lz.resetDict(); else if (lz.needDictReset) fail("xz_bad");
      if (mode >= 2) { if (pos >= end) fail("xz_bad"); lz.props(buf[pos++]); } else if (lz.needProps) fail("xz_bad");
      if (mode >= 1) lz.resetState();
      if (end - pos < packed) fail("xz_bad");
      lz.chunk(buf, pos, pos + packed, unpacked, out);
      pos += packed;
    }
    if (pos !== end || out.length !== size) fail("xz_bad");
    return Uint8Array.from(out);
  };

  function skipVli(buf, h) {
    for (var i = 0; i < 9; i++) if ((buf[h++] & 0x80) === 0) return h;
    fail("xz_bad");
    return h;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // VCDIFF rewrite
  // ---------------------------------------------------------------------------------------------------------------

  function Reader(u8, pos) { this.u8 = u8; this.pos = pos; }
  Reader.prototype.byte = function () {
    if (this.pos >= this.u8.length) fail("xdelta_bad");
    return this.u8[this.pos++];
  };
  Reader.prototype.size = function () { // VCDIFF integer: base 128, most significant first
    var n = 0, b;
    do {
      b = this.byte();
      n = n * 128 + (b & 0x7F);
      if (n > Number.MAX_SAFE_INTEGER / 256) fail("xdelta_bad");
    } while (b & 0x80);
    return n;
  };

  function sizeBytes(n) {
    var a = [n % 128];
    n = Math.floor(n / 128);
    while (n > 0) { a.unshift((n % 128) | 0x80); n = Math.floor(n / 128); }
    return a;
  }

  /** "lzma" if the patch needs unpacking, "other" for DJW/FGK compression we can't read, or null for a plain patch. */
  function kind(u8) {
    if (u8.length < 6 || u8[0] !== 0xD6 || u8[1] !== 0xC3 || u8[2] !== 0xC4) return null;
    if (!(u8[4] & VCD_DECOMPRESS)) return null;
    return u8[5] === LZMA_ID ? "lzma" : "other";
  }

  /** Returns the patch with every compressed section unpacked (a plain VCDIFF), or the same bytes if it had none. */
  function unpack(u8) {
    var k = kind(u8);
    if (k === null) return u8;
    if (k === "other") fail("xdelta_other");
    var r = new Reader(u8, 4), out = [0xD6, 0xC3, 0xC4, u8[3]];
    var ind = r.byte();
    out.push(ind & ~VCD_DECOMPRESS);
    r.byte(); // secondary compressor id (LZMA, checked above)
    if (ind & VCD_CODETABLE) fail("xdelta_other");
    if (ind & VCD_APPHEADER) {
      var appLen = r.size();
      if (r.pos + appLen > u8.length) fail("xdelta_bad");
      Array.prototype.push.apply(out, sizeBytes(appLen));
      for (var a = 0; a < appLen; a++) out.push(u8[r.pos + a]);
      r.pos += appLen;
    }
    var streams = [new XzStream(), new XzStream(), new XzStream()]; // data, instructions, addresses
    var chunks = [Uint8Array.from(out)];
    while (r.pos < u8.length) {
      var head = [], win = r.byte();
      head.push(win);
      if (win & (VCD_SOURCE | VCD_TARGET)) {
        Array.prototype.push.apply(head, sizeBytes(r.size()));
        Array.prototype.push.apply(head, sizeBytes(r.size()));
      }
      r.size(); // encoding length: rewritten below
      var target = r.size(), del = r.byte();
      var lens = [r.size(), r.size(), r.size()];
      var adler = null;
      if (win & VCD_ADLER32) { adler = u8.subarray(r.pos, r.pos + 4); r.pos += 4; }
      var parts = [];
      for (var s = 0; s < 3; s++) {
        var start = r.pos, stop = start + lens[s];
        if (stop > u8.length) fail("xdelta_bad");
        if (del & (1 << s)) {
          var sr = new Reader(u8, start), plain = sr.size();
          if (plain === 0 || plain > MAX_SECTION) fail("xdelta_bad");
          parts.push(streams[s].section(u8, sr.pos, stop, plain));
        } else {
          parts.push(u8.subarray(start, stop));
        }
        r.pos = stop;
      }
      var tail = sizeBytes(target).concat([0], sizeBytes(parts[0].length), sizeBytes(parts[1].length), sizeBytes(parts[2].length));
      var encLen = tail.length + parts[0].length + parts[1].length + parts[2].length + (adler ? 4 : 0);
      chunks.push(Uint8Array.from(head.concat(sizeBytes(encLen), tail)));
      if (adler) chunks.push(Uint8Array.from(adler));
      chunks.push(parts[0], parts[1], parts[2]);
    }
    var total = chunks.reduce(function (n, c) { return n + c.length; }, 0), res = new Uint8Array(total), at = 0;
    chunks.forEach(function (c) { res.set(c, at); at += c.length; });
    return res;
  }

  /**
   * The checksum xdelta3 stores for each window of the result: [{ length, adler }] in order (adler null when that
   * window has none). Reads a plain (unpacked) patch. Lets the page check the patched file itself; RomPatcher's own
   * check is unusable (it calls an adler32 function the library doesn't have).
   */
  function checksums(u8) {
    var r = new Reader(u8, 4), list = [];
    var ind = r.byte();
    if (ind & VCD_DECOMPRESS) r.byte();
    var n;
    if (ind & VCD_CODETABLE) { n = r.size(); r.pos += n; } // size() moves pos, so read it before adding
    if (ind & VCD_APPHEADER) { n = r.size(); r.pos += n; }
    while (r.pos < u8.length) {
      var win = r.byte();
      if (win & (VCD_SOURCE | VCD_TARGET)) { r.size(); r.size(); }
      r.size();
      var target = r.size();
      r.byte();
      var skip = r.size() + r.size() + r.size(), adler = null;
      if (win & VCD_ADLER32) {
        if (r.pos + 4 > u8.length) fail("xdelta_bad");
        adler = ((u8[r.pos] << 24) >>> 0) + (u8[r.pos + 1] << 16) + (u8[r.pos + 2] << 8) + u8[r.pos + 3];
        r.pos += 4;
      }
      r.pos += skip;
      list.push({ length: target, adler: adler });
    }
    return list;
  }

  /** Adler-32 of u8[start, start + len), as xdelta3 computes it (starting value 1). */
  function adler32(u8, start, len) {
    var a = 1, b = 0, end = start + len;
    while (start < end) {
      var stop = Math.min(end, start + 3800); // keep the sums small before taking the remainder
      for (; start < stop; start++) { a += u8[start]; b += a; }
      a %= 65521; b %= 65521;
    }
    return ((b << 16) >>> 0) + a;
  }

  /** True if every window of the patched file `out` matches the checksum the patch carries. */
  function verify(plainPatch, out) {
    var at = 0, list = checksums(plainPatch);
    for (var i = 0; i < list.length; i++) {
      var w = list[i];
      if (at + w.length > out.length) return false;
      if (w.adler !== null && adler32(out, at, w.length) !== w.adler) return false;
      at += w.length;
    }
    return at === out.length;
  }

  return { kind: kind, unpack: unpack, checksums: checksums, verify: verify };
}());

if (typeof module !== "undefined" && module.exports) {
  module.exports = XdeltaUnpack;
}
