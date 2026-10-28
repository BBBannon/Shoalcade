/*
 * Shoalcade QR code maker (Settings → Phone app, D134). Written for Shoalcade from the QR code standard
 * (ISO/IEC 18004); no outside code, no network. Small on purpose: byte mode only, error correction level M,
 * versions 1-20 (up to 666 bytes, far more than a download link needs).
 *
 * window.SC.qr.encode(text) -> { size, dark(x, y) }   (throws if the text is too long)
 */
(function () {
  "use strict";

  // Error correction level M, versions 1-20: EC codewords per block and number of blocks.
  const ECC_PER_BLOCK = [10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26];
  const BLOCKS = [1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16];
  const MAX_VERSION = 20;
  const ECL_BITS = 0; // M = 00 in the format bits

  // ---------- GF(256) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1 ----------
  function gfMul(x, y) {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11d);
      z ^= ((y >>> i) & 1) * x;
    }
    return z & 0xff;
  }

  /** Reed-Solomon generator polynomial of the given degree (highest term left out, as usual). */
  function rsDivisor(degree) {
    const r = new Array(degree).fill(0);
    r[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
      for (let j = 0; j < r.length; j++) {
        r[j] = gfMul(r[j], root);
        if (j + 1 < r.length) r[j] ^= r[j + 1];
      }
      root = gfMul(root, 0x02);
    }
    return r;
  }

  function rsRemainder(data, divisor) {
    const r = new Array(divisor.length).fill(0);
    for (const b of data) {
      const factor = b ^ r.shift();
      r.push(0);
      for (let i = 0; i < divisor.length; i++) r[i] ^= gfMul(divisor[i], factor);
    }
    return r;
  }

  // ---------- sizes ----------
  /** Modules left for data + EC once the fixed patterns are drawn (whole bits). */
  function rawModules(ver) {
    let n = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      const a = Math.floor(ver / 7) + 2;
      n -= (25 * a - 10) * a - 55;
      if (ver >= 7) n -= 36;
    }
    return n;
  }

  function dataCodewords(ver) {
    return Math.floor(rawModules(ver) / 8) - ECC_PER_BLOCK[ver - 1] * BLOCKS[ver - 1];
  }

  function alignPositions(ver) {
    if (ver === 1) return [];
    const count = Math.floor(ver / 7) + 2;
    const size = ver * 4 + 17;
    const step = Math.ceil((ver * 4 + 4) / (count * 2 - 2)) * 2;
    const out = [6];
    for (let pos = size - 7; out.length < count; pos -= step) out.splice(1, 0, pos);
    return out;
  }

  // ---------- encoding ----------
  function encode(text) {
    const bytes = Array.from(new TextEncoder().encode(String(text)));
    let ver = 1;
    for (; ver <= MAX_VERSION; ver++) {
      const countBits = ver <= 9 ? 8 : 16;
      if (4 + countBits + bytes.length * 8 <= dataCodewords(ver) * 8) break;
    }
    if (ver > MAX_VERSION) throw new Error("Too long for a QR code.");

    // Bit stream: mode 0100 (byte), count, data, terminator, padding.
    const bits = [];
    const put = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
    put(4, 4);
    put(bytes.length, ver <= 9 ? 8 : 16);
    for (const b of bytes) put(b, 8);
    const capacity = dataCodewords(ver) * 8;
    put(0, Math.min(4, capacity - bits.length));
    put(0, (8 - bits.length % 8) % 8);
    for (let pad = 0xec; bits.length < capacity; pad ^= 0xec ^ 0x11) put(pad, 8);
    const data = [];
    for (let i = 0; i < bits.length; i += 8) {
      let b = 0;
      for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
      data.push(b);
    }

    // Split into blocks, add EC, interleave.
    const numBlocks = BLOCKS[ver - 1];
    const eccLen = ECC_PER_BLOCK[ver - 1];
    const raw = Math.floor(rawModules(ver) / 8);
    const shortBlocks = numBlocks - raw % numBlocks;
    const shortLen = Math.floor(raw / numBlocks);
    const divisor = rsDivisor(eccLen);
    const blocks = [];
    for (let i = 0, k = 0; i < numBlocks; i++) {
      const dat = data.slice(k, k + shortLen - eccLen + (i < shortBlocks ? 0 : 1));
      k += dat.length;
      const ecc = rsRemainder(dat, divisor);
      if (i < shortBlocks) dat.push(-1); // placeholder so every block has the same length while interleaving
      blocks.push(dat.concat(ecc));
    }
    const codewords = [];
    for (let i = 0; i < blocks[0].length; i++) {
      for (let j = 0; j < blocks.length; j++) {
        if (i !== shortLen - eccLen || j >= shortBlocks) codewords.push(blocks[j][i]);
      }
    }

    return draw(ver, codewords);
  }

  // ---------- drawing ----------
  function draw(ver, codewords) {
    const size = ver * 4 + 17;
    const mod = [];
    const fixed = [];
    for (let y = 0; y < size; y++) { mod.push(new Array(size).fill(false)); fixed.push(new Array(size).fill(false)); }
    const set = (x, y, dark) => { mod[y][x] = dark; fixed[y][x] = true; };

    // Timing lines
    for (let i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    // Finders (with their light border)
    for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) {
      for (let dy = -4; dy <= 4; dy++) {
        for (let dx = -4; dx <= 4; dx++) {
          const x = cx + dx, y = cy + dy;
          if (x < 0 || x >= size || y < 0 || y >= size) continue;
          const d = Math.max(Math.abs(dx), Math.abs(dy));
          set(x, y, d !== 2 && d !== 4);
        }
      }
    }
    // Alignment patterns (not over the finders)
    const al = alignPositions(ver);
    for (let i = 0; i < al.length; i++) {
      for (let j = 0; j < al.length; j++) {
        if ((i === 0 && j === 0) || (i === 0 && j === al.length - 1) || (i === al.length - 1 && j === 0)) continue;
        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) set(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
    }
    // Reserve the format areas (real bits drawn after masking) and draw the version info.
    drawFormat(mod, set, size, 0);
    if (ver >= 7) {
      let rem = ver;
      for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      const v = (ver << 12) | rem;
      for (let i = 0; i < 18; i++) {
        const bit = ((v >>> i) & 1) === 1;
        const a = size - 11 + i % 3, b = Math.floor(i / 3);
        set(a, b, bit);
        set(b, a, bit);
      }
    }

    // Data, in the zigzag order (right to left in pairs of columns, skipping the timing column).
    let i = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? size - 1 - vert : vert;
          if (!fixed[y][x] && i < codewords.length * 8) {
            mod[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) === 1;
            i++;
          }
        }
      }
    }

    // Try all 8 masks, keep the one with the lowest penalty.
    let best = null, bestScore = Infinity;
    for (let m = 0; m < 8; m++) {
      const g = mod.map(r => r.slice());
      applyMask(g, fixed, m);
      drawFormat(g, (x, y, d) => { g[y][x] = d; }, size, m);
      const score = penalty(g);
      if (score < bestScore) { best = g; bestScore = score; }
    }
    return { size, dark: (x, y) => x >= 0 && y >= 0 && x < size && y < size && best[y][x] };
  }

  function drawFormat(g, set, size, mask) {
    const data = (ECL_BITS << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412;
    const bit = i => ((bits >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6));
    set(8, 8, bit(7));
    set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
    set(8, size - 8, true); // the dark module
  }

  function applyMask(g, fixed, m) {
    const size = g.length;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        if (fixed[y][x]) continue;
        let flip;
        switch (m) {
          case 0: flip = (x + y) % 2 === 0; break;
          case 1: flip = y % 2 === 0; break;
          case 2: flip = x % 3 === 0; break;
          case 3: flip = (x + y) % 3 === 0; break;
          case 4: flip = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
          case 5: flip = (x * y) % 2 + (x * y) % 3 === 0; break;
          case 6: flip = ((x * y) % 2 + (x * y) % 3) % 2 === 0; break;
          default: flip = ((x + y) % 2 + (x * y) % 3) % 2 === 0; break;
        }
        if (flip) g[y][x] = !g[y][x];
      }
    }
  }

  /** The standard's mask penalty (runs, 2x2 blocks, finder look-alikes, dark balance). Only affects how easy the code is to scan. */
  function penalty(g) {
    const size = g.length;
    let score = 0, dark = 0;
    const lines = [];
    for (let y = 0; y < size; y++) lines.push(g[y]);
    for (let x = 0; x < size; x++) lines.push(g.map(r => r[x]));
    const look = [true, false, true, true, true, false, true];
    for (const line of lines) {
      let run = 1;
      for (let i = 1; i <= size; i++) {
        if (i < size && line[i] === line[i - 1]) { run++; continue; }
        if (run >= 5) score += 3 + run - 5;
        run = 1;
      }
      for (let i = 0; i + 7 <= size; i++) {
        if (!look.every((v, k) => line[i + k] === v)) continue;
        const lightBefore = i >= 4 && [1, 2, 3, 4].every(k => !line[i - k]);
        const lightAfter = i + 11 <= size && [0, 1, 2, 3].every(k => !line[i + 7 + k]);
        if (lightBefore || lightAfter) score += 40;
      }
    }
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        if (g[y][x]) dark++;
        if (x + 1 < size && y + 1 < size && g[y][x] === g[y][x + 1] && g[y][x] === g[y + 1][x] && g[y][x] === g[y + 1][x + 1]) score += 3;
      }
    }
    const total = size * size;
    score += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
    return score;
  }

  window.SC = window.SC || {};
  window.SC.qr = { encode };
})();
