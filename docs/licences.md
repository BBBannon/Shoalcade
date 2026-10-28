# Licences — Shoalcade

The app links GPL code, so **Shoalcade is GPL-3.0** (`LICENSE` in the repo root, also bundled in the APK and shown under **About and licences**).
**Every bundled part is now GPL-3.0-compatible** (23/10/2026, D25), so the APK can be shared.
A short public summary is in **`THIRD_PARTY_NOTICES.md`** (repo root). This file is the detailed record.

## Release status (checked 25/10/2026)

| Item | Status |
|---|---|
| JellyEMU MIT notice for the ported scanner code | ✅ **Done (s0.2 dev 2).** `ConsoleDetector.cs` and `RomScanner.cs` both credit JellyEMU (MIT, © 2026 Jellyfin GP) and point at the full MIT text in `THIRD_PARTY_NOTICES.md`, which `server/build.sh` now ships in the plugin zip next to `LICENSE.txt` |
| libretro-database CC-BY-SA credit | ✅ In `THIRD_PARTY_NOTICES.md` and `guides/privacy.md`. Not redistributed (each server downloads its own copy) |
| libretro-thumbnails | ✅ Credited. Fetched per game, not bundled. Box art belongs to the games' publishers |
| GPL source published + tagged | ❌ **Not yet** (repo private). See "GPL obligations" below |
| PPSSPP core: exact source | ✅ **Found.** The bundled `.so` contains the string `902e5cc` = PPSSPP commit **`902e5ccd8c1400b4387b73942491cded655e5f9c`** (22/10/2026, "Merge pull request #22738 … android-tv-output"). Matches the PC test build. SHA-256 of the `.so`: `e1d3a12c…095b22ae` |
| PPSSPP fonts (`flash0/font/*.pgf`) | ✅ **Not Sony's.** Community-made replacements, see "PPSSPP support files" below. One small gap (base font of the Latin/Korean files not documented) |
| Release APK shrinking + own signing key (D125) | ✅ **No change to licence duties.** R8 only removes and renames code inside the APK; the GPL "corresponding source" is still the repo at the release tag, which includes the build rules (`proguard-rules.pro`), so anyone can rebuild it. `assets/LICENSE.txt` survives shrinking: checked 25/10/2026 on the **release** APK, where **About and licences** lists every core with its source link and **Full licence** shows the GPL v3 text. LibretroDroid (GPL-3.0) is kept whole, not renamed. The signing key is not "Installation Information" (GPLv3 §6): Android lets anyone install their own build, signed with their own key, as a separate app or after uninstalling ours. The mapping file is a debugging aid, not source |
| EmulatorJS extras in the plugin zip | ✅ **Done (s0.2 dev 2).** `ejs/compression/libunrar.js` + `.wasm` (never used; unRAR licence not GPL-compatible) are left out of the zip by `server/build.sh` |

## GPL obligations (each public release)

1. **Make the repo public** (or change `Licences.APP_SOURCE` in `About.kt` to wherever the source lives).
2. **Tag every release** in git (e.g. `app-v0.6.0`, `plugin-s0.2`). The source at the tag must build the exact APK / zip shared.
3. **Attach or link the source** on each GitHub Release (GitHub's automatic source zip at the tag is enough).
4. **List every embedded core** with its licence and exact source (tables below). If a core changes, update **both** this file and `About.kt` (`Licences.parts`).
5. **Keep licence texts in the builds:** `LICENSE` in the APK (shown under **About and licences**), `LICENSE.txt`, `THIRD_PARTY_NOTICES.md` and `ejs/LICENSE.txt` in the plugin zip.
6. **Ship nothing else:** no ROMs, BIOS, firmware or keys.

### Embedded emulator cores — exact sources

| Core | Where | Licence | Exact source |
|---|---|---|---|
| mGBA `0.11-212-7a12d6d` | App (GB, GBC, GBA) | MPL-2.0 | https://github.com/mgba-emu/mgba/tree/7a12d6d |
| melonDS DS `1.4.0` | App (DS) | GPL-3.0 | https://github.com/JesseTG/melonds-ds/tree/v1.4.0 |
| gpSP `v1.1.0-5819380` | App (GBA link play) | GPL-2.0-or-later | https://github.com/libretro/gpsp/tree/5819380 |
| PPSSPP (libretro nightly) | App (PSP) | GPL-2.0-or-later | https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c |
| PCSX ReARMed `r26l-119-gc881679` (libretro nightly, built 25/10/2026) | App (PS1, D137) | GPL-2.0-or-later (bundled parts below) | https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a |
| mGBA (EmulatorJS build 4.2.3) | Plugin (browser GB, GBC, GBA) | MPL-2.0 | https://github.com/EmulatorJS/mgba (npm `@emulatorjs/core-mgba` 4.2.3, hash in `server/emulatorjs.lock`) |
| melonDS legacy (EmulatorJS build 4.2.3) | Plugin (browser DS) | GPL-3.0 | https://github.com/EmulatorJS/melonDS (npm `@emulatorjs/core-melonds` 4.2.3, hash in `server/emulatorjs.lock`) |
| PCSX ReARMed (EmulatorJS build 4.2.3) | Plugin (browser PS1, D137) | GPL-2.0-or-later | https://github.com/EmulatorJS/pcsx_rearmed (npm `@emulatorjs/core-pcsx_rearmed` 4.2.3, hash in `server/emulatorjs.lock`) |

- ✅ `About.kt` shows the PPSSPP row as commit `902e5cc` (checked 28/10/2026).
- The EmulatorJS core builds don't name an exact upstream commit; the npm package + SHA-256 is the pin. Their `reports/*.json` give the build date.

## Before sharing the APK (checklist)
1. **Publish the source** at the address shown in the app (`Licences.APP_SOURCE` = `https://github.com/BBBannon/Shoalcade`). The source must match the APK you share: push it and tag that commit (first public release: tag `v0.3.0` = app 0.6.0 + plugin 0.3.0.0). ⚠️ The repo must be **public before** the APK or plugin zip is shared.
2. If a core is updated, update its version + source link in **both** this file and `About.kt` (`Licences.parts`).
3. Ship nothing else: no ROMs, BIOS, firmware or keys.
4. ✅ **PPSSPP commit found** (25/10/2026): `902e5cc` is embedded in the bundled `libppsspp_libretro_android.so`. Full hash above. If the core is ever replaced, re-check the string and update `About.kt`.
5. ✅ **PPSSPP fonts checked** (25/10/2026): they are PPSSPP's own community replacements, not Sony's. Details below.

| Component | Use | Licence |
|---|---|---|
| **LibretroDroid** 0.14.0 | Emulator front-end library | GPL-3.0 |
| **mGBA** libretro core `0.11-212-7a12d6d` | GB, GBC and GBA | MPL-2.0 — source https://github.com/mgba-emu/mgba/tree/7a12d6d |
| **melonDS DS** libretro core `1.4.0` | DS | GPL-3.0 — source https://github.com/JesseTG/melonds-ds/tree/v1.4.0 |
| **gpSP** libretro core `v1.1.0-5819380` | GBA link play only (Pokémon link cable / wireless adapter) | GPL-2.0-or-later — source https://github.com/libretro/gpsp/tree/5819380 |
| **PPSSPP** libretro core (nightly built 22/10/2026; commit `902e5cc` embedded in the arm64 `.so` and reported by the PC test build) | PSP | GPL-2.0-or-later (headers: *"version 2.0 or later versions"*) — source https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c |
| **PPSSPP support files** (`assets/PPSSPP.zip`, from buildbot `assets/system/PPSSPP.zip`) | PSP fonts, compatibility list, UI atlas. Unpacked to `files/system/PPSSPP` | GPL-2.0 + PSPSDK BSD notice (`PPSSPP/LICENSE.TXT` inside) |
| **PCSX ReARMed** libretro core `r26l-119-gc881679` (commit `c8816799`, 02/10/2026) | PS1 (D137) | GPL-2.0-or-later, no GPL-2.0-only files (sweep below) — source https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a |
| Shoalcade link shim (`cpp/linkshim.c`, `cpp/linkstate.c`) | Gives cores link support | GPL-3.0 (ours) |
| Shoalcade clock shim (`cpp/clockshim.c`) | Saves the Game Boy cartridge clock with the game | GPL-3.0 (ours) |
| AndroidX / Jetpack Compose / Kotlin | App framework | Apache-2.0 |
| Shoalcade QR code maker (`server/.../Pages/qr.js`) | Browser Settings page: QR code for the phone app download (D134) | GPL-3.0 (ours). Written for Shoalcade from the QR code standard (ISO/IEC 18004); no third-party code, no network |
| **Phone app inside the plugin zip** (`app/Shoalcade.apk`, D134) | The plugin now also hands out the APK, cores and all | Same duties as sharing the APK (see "GPL obligations"): each plugin release that bundles an APK must have that APK's source (the repo at its tag) published, like a GitHub release. **The plugin zip therefore carries the APK's GPL duties too** — including every app core (mGBA, melonDS DS, gpSP, PPSSPP, PCSX ReARMed) and their notices |
| JellyEMU (server plugin, not bundled) | ROM + save API | MIT |
| **JellyEMU 0.9.2.4 code reused** (console, region and disc detection, name cleaning, .cue/.j3u reading: `PlatformResolver`, `NameCleaner`, `RetroGamesCore`), ported into `server/.../Scanner/ConsoleDetector.cs` and `RomScanner.cs` | Server scanner (phase 3, D42) | MIT — Copyright (c) 2026 Jellyfin GP — https://github.com/Jellyfin-PG/JellyEmu. The MIT notice must stay with the code. Full MIT text: `THIRD_PARTY_NOTICES.md`. Source headers in both files credit it and point at the MIT text, which ships in the plugin zip (`THIRD_PARTY_NOTICES.md`) |
| **libretro-database** `.dat` files (`metadat/no-intro`, `redump`, `developer`, `publisher`, `releaseyear`, `genre`) — **downloaded at run time, not bundled** (s0.2, D48) | Server: official game names, year, developer, publisher, genre by ROM MD5. Fetched by the plugin from `https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/` into `shoalcade/gamedb/`, only for consoles that have games, checked weekly | **CC-BY-SA-4.0** — © libretro and contributors, https://github.com/libretro/libretro-database. Not redistributed by us (each server downloads its own copy). If we ever ship the files or a database built from them: credit libretro-database, link the licence, and share that data under CC-BY-SA-4.0 |
| **libretro-thumbnails** (online service, `https://thumbnails.libretro.com/`) | Server: box art for games with no local picture; Jellyfin downloads and stores each picture once (not hotlinked) | Box art belongs to the games' publishers; fetched one game at a time for each server's own library. Not bundled, not hotlinked. Credited in `THIRD_PARTY_NOTICES.md`; the privacy note (`guides/privacy.md`) says game names are sent. Admins can untick the source per library |
| *Future (not built)*: Hasheous | Game descriptions | Service (AGPL-3.0) |

## PPSSPP support files (`assets/PPSSPP.zip`) — checked 25/10/2026

What's inside (176 files) and the licence of each part:

| Part | Licence / origin | OK to ship? |
|---|---|---|
| `LICENSE.TXT` | PPSSPP's GPL-2.0 + PSPSDK BSD notice | ✅ Keep it in the zip (it is) |
| `flash0/font/jpn0.pgf` | Community replacement by **nassau-tk** (PPSSPP PRs #13522, #13588, 2020–2023). Made from **Ume-font**, whose licence says *"Unlimited permission is granted to use, copy, and distribute it, with or without modification"* (PPSSPP issue #13510) | ✅ |
| `flash0/font/ltn0–15.pgf`, `kr0.pgf` | Community replacements by **nassau-tk** (PPSSPP commit `0c243ee139` "Nassau's new fonts", 08/12/2020, and later fixes). Made with `pgftool` from TTF fonts. The internal names (`FTT-NewRodin`, `FTT-Matisse`, `AsiaKNHH`) copy Sony's on purpose so games accept them (commit `dc34bea8d4` "camouflage the Font name") — **they are not Sony's files** | ✅ Shipped by PPSSPP itself in its GPL repo and every PPSSPP release. ⚠️ Small gap: the base TTF for the Latin/Korean files isn't documented. PPSSPP's lead says bitmap renderings of a font are fine to distribute (issue #13510). Low risk |
| `Roboto_Condensed-*.ttf` | Roboto: Apache-2.0 (Google) | ✅ |
| `Inconsolata-Regular.ttf` | SIL Open Font Licence 1.1 | ✅ |
| `gamecontrollerdb.txt` | SDL_GameControllerDB: zlib licence | ✅ |
| `lang/*.ini`, `compat.ini`, `*.zim` atlases, `shaders/`, `ui_images/`, `*.wav`, `cheats.json`, `knownfuncs.ini`, `*.json` | Part of PPSSPP (GPL-2.0-or-later) | ✅ |
| `redump.csv` | Disc ID list from redump.org data, shipped by PPSSPP | ✅ as part of PPSSPP's assets. Not separately licensed |

- The zip holds **no Sony BIOS or firmware**. Real PSP fonts can still be added by users themselves; we don't ship or point to them.
- PPSSPP font history: https://github.com/hrydgard/ppsspp/commits/master/assets/flash0/font

## Server plugin bundle (browser player, `Shoalcade_s*.zip`)

The plugin is GPL-3.0 too (`LICENSE.txt` in the zip). It bundles EmulatorJS and three cores (mGBA, melonDS and, since dev 3, PCSX ReARMed), **pinned in `server/emulatorjs.lock`** and **checked by SHA-256 on every build** (`server/build.sh` step 1; it refuses to build on a mismatch). Source: the npm tarballs `https://registry.npmjs.org/@emulatorjs/<name>/-/<name>-4.2.3.tgz`.

| Component | npm package + version | SHA-256 of the tarball | Use | Licence + source |
|---|---|---|---|---|
| **EmulatorJS** | `@emulatorjs/emulatorjs` 4.2.3 | `8033a18a…5b589639` | Browser front-end (unpacked `data/` → `ejs/`; its `LICENSE` → `ejs/LICENSE.txt`) | GPL-3.0 — https://github.com/EmulatorJS/EmulatorJS (tag v4.2.3) |
| **mGBA** (EmulatorJS build) | `@emulatorjs/core-mgba` 4.2.3 (core built 14/06/2025, `reports/mgba.json`) | `9ea664b4…42cad529` | **GB, GBC and GBA** in the browser (same core family as the app, D25) | MPL-2.0 — https://github.com/EmulatorJS/mgba (fork of https://github.com/mgba-emu/mgba) |
| **melonDS** legacy (EmulatorJS build) | `@emulatorjs/core-melonds` 4.2.3 (core built 14/06/2025, `reports/melonds.json`) | `6cbd17e0…e08d0045` | DS in the browser | GPL-3.0 — https://github.com/EmulatorJS/melonDS (fork of https://github.com/melonDS-emu/melonDS) |
| **PCSX ReARMed** (EmulatorJS build) | `@emulatorjs/core-pcsx_rearmed` 4.2.3 (core built June 2025) | `d641b90c…ff165d923` (npm sha512 `mMbl/Nsz…fgQCog==` also checked) | **PS1** in the browser (same core family as the app, one memory card, D137). Run with its HLE BIOS stand-in; no BIOS bundled | GPL-2.0-or-later — https://github.com/EmulatorJS/pcsx_rearmed (fork of https://github.com/libretro/pcsx_rearmed) |

- Full hashes are in `server/emulatorjs.lock`. **Not yet done (security M3):** cross-check them once against npm's published `dist.integrity`, and diff-review EmulatorJS on any version bump.
- Each core `.data` file is a 7-Zip archive built by EmulatorJS; the licence above is the upstream core's. (Not unpacked here to read any licence file inside: no 7-Zip on the build PC.)
- ✅ **gambatte removed from the plugin too** (24/10/2026): it is GPL-2.0-only (see Notes), the same reason D25 removed it from the app. The browser's GB/GBC core is now **mGBA**, matching the app, so GB/GBC save files keep one format. `core-gambatte` is gone from `emulatorjs.lock`, so it can't be bundled by mistake. Side effect: browser snapshots made with gambatte before this change show as "made by another core" and won't load; saves are not affected.
- **Not bundled:** PPSSPP for the browser (planned last, D34), BIOS, firmware, keys, ROMs.
- **Other files EmulatorJS brings** (checked in `Shoalcade_s0.2-dev2.zip`, 25/10/2026):

| File | Licence | Note |
|---|---|---|
| `ejs/src/socket.io.min.js` | MIT (Socket.IO 4.8.1, header kept) | EmulatorJS netplay. Not used by us |
| `ejs/src/nipplejs.js` | MIT (nipplejs) | Touch joystick |
| `ejs/compression/extractzip.js`, `extract7z.js` | Emscripten builds shipped by EmulatorJS (zip / 7-Zip code) | Used to unpack the cores and zipped games. No licence header in the minified files; covered by EmulatorJS's GPL-3.0 bundle |
| `ejs/compression/libunrar.js` + `libunrar.wasm` | **unRAR licence** (free to share, but forbids making a RAR compressor; **not GPL-compatible**) | ✅ **Not shipped** since s0.2 dev 2 (`server/build.sh` removes them; `play.js` only allows zip and 7z) |
| `ejs/localization/*.json` | EmulatorJS, GPL-3.0 | |

- **Jellyfin.Controller / Jellyfin.Model 12.3.0** (NuGet): GPL-3.0-only. Compile-time only (`ExcludeAssets=runtime`), **not in the zip**. GPL-3.0 plugin: compatible.

## Notes
- Cores are libretro **nightly** builds from `buildbot.libretro.com` (arm64-v8a), downloaded 22/10/2026, renamed to `lib<name>_libretro_android.so`.
- gpSP arm64 + x86_64 test cores (and x86_64 copies of the others, debug builds only, for the PC test emulators) downloaded from the same buildbot on 23/10/2026.
- **No BIOS, firmware or keys are bundled.** melonDS DS uses its built-in free firmware; gpSP is forced to its built-in BIOS (`gpsp_bios=builtin`).
- ✅ **gambatte removed** (23/10/2026, D25): GB/GBC now run on mGBA. Proof that saves still match JellyEMU's browser (which uses gambatte): `android/coretest/run.sh` — 6/6 pass, byte-identical both ways on Gen 1 and Gen 2 Pokémon cartridge types.
- (History) **gambatte is GPL-2.0-only** (checked 23/10/2026). Headers in `libgambatte/src/cpu.cpp` and `gambatte.cpp` say *"version 2 as published by the Free Software Foundation"* with **no "or later"**. GPL-2.0-only can't be combined with GPL-3.0 code (LibretroDroid, melonDS DS). The FSF treats dlopen'd plugins sharing data as one programme (GPL FAQ "GPLPlugins"). **Fine for personal use** (no distribution). **Before any public release:** play GB/GBC with **mGBA** instead (MPL-2.0, already bundled, plays `.gb`/`.gbc`) or SameBoy (MIT). Test Gen 2 Pokémon clock (RTC) saves carry over before switching.
- ✅ **gpSP is GPL-2.0-or-later** (`main.c`: *"either version 2 of the License, or (at your option) any later version"*), so it is fine under GPL-3.0.
- **Test ROMs (test-bed only, never shipped):** every test ROM is made by `tools/testbed/makeroms.py` from our own code, no third-party code or data. That includes **"JG DS Touch Test (USA).nds"** (24/10/2026, s0.2 item 3): a tiny DS homebrew written there with a built-in mini ARM assembler. It colours the screens and draws where the touch screen is touched, for `tools/testbed/browser_ds_touch.mjs`. No Nintendo logo, BIOS or libnds inside. Same licence as the repo.

## PCSX ReARMed (PS1 core) — checked 25/10/2026

- **Files:** buildbot `nightly/android/latest/{arm64-v8a,x86_64}/pcsx_rearmed_libretro_android.so.zip` (zip dated 25/10/2026 06:44), renamed `libpcsxrearmed_libretro_android.so`.
  - arm64 (`src/main/jniLibs/arm64-v8a`, shipped): SHA-256 `c3cd9654b893c08ded126ea62444c39035ca51cbafbee1c95504ec5e39dc10bb`
  - x86_64 (`src/debug/jniLibs/x86_64`, debug/emulator only): SHA-256 `33dcc110994966d95171c7a7a54c81b59836f7aae15a6f768dd97a0d110e132f`
  - Both contain the version string `r26l-119-gc881679` = commit **`c8816799b50388e61cfe237fe2cdbb7d8175f20a`**, the same commit as the Step 0 Windows test build.
- **Sweep result: no GPL-2.0-only code.** Every source file's licence header at that commit was sorted by script; all "version 2 without later" hits were false positives (lightrec's `LGPL-2.1-or-later` tags, zstd's BSD/GPLv2 choice, `VERSION 2` defines). Files with no header fall under the top-level `COPYING` (GPLv2 with no version picked, so any version may be chosen, GPLv2 §9). The only "GPL-2.0" tag is `miyoo/pkg.cfg` (packaging for another handheld, not code, not built for Android).

| Part | Licence | In our build? |
|---|---|---|
| `libpcsxcore/` (incl. `new_dynarec`), `frontend/`, `plugins/dfsound`, `plugins/gpu_neon` | GPL-2.0-or-later (a few files GPL-2+/LGPL-2.1+ choice; `gte_divider.c` BSD-3, `memmap.h` MIT) | Yes (both ABIs) |
| `plugins/gpulib`, `plugins/cdrcimg` | GPL-2+ or LGPL-2.1+ (choice) | Yes |
| `deps/libchdr` (rtissera, `9ccd3a7`) | BSD-3-Clause; inside it LZMA (public domain), zstd 1.5.7 (BSD or GPLv2, we take BSD), FLAC (BSD-3 / public domain) | Yes |
| `deps/libretro-common` (`f0173cc`) | MIT | Yes |
| zlib | Android's own system zlib (`-lz`), not bundled | — |
| `deps/lightrec` (pcercuei, `a8c0f3e`) + `tlsf` | LGPL-2.1-or-later + BSD | **x86_64 debug only** |
| `deps/lightning` (GNU lightning, `a6bb2b5`) | LGPL-3.0-or-later (file headers; not GPL-3+ as first thought) | **x86_64 debug only** |
| `plugins/gpu_unai`, `dfxvideo`, `deps/mman`, `deps/arm-mem`, `frontend/libpicofe` | various | Not built for our ABIs |

All of these combine cleanly under GPL-3.0. No BIOS is bundled: the core runs with its built-in HLE BIOS stand-in (`pcsx_rearmed_bios=HLE`).

