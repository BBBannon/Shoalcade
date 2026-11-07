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
5. **Keep licence texts in the builds:** `LICENSE` in the APK (shown under **About and licences**), `LICENSE.txt`, `THIRD_PARTY_NOTICES.md` and `ejs/LICENSE.txt` in the plugin zip. **Since 01/11/2026 (D224):** `THIRD_PARTY_LICENCES.txt` (repo root) holds every bundled part's own licence file(s) at the shipped commit plus the full GPL-3.0/GPL-2.0/LGPL-2.1/LGPL-3.0/MPL-2.0/Apache-2.0 texts. It goes into the APK (`assets/`, **About → Every part's licence text**; the build fails if it's missing) and into the plugin zip next to `LICENSE.txt` (`server/build.sh`). Remake it with `python3 -I tools/make_third_party_licences.py` whenever a part or its commit changes (`--check` says if it's out of date). Still not swept: notices of libraries compiled inside a core beyond the files listed there (e.g. melonDS DS's and mGBA's bundled libraries).
6. **Ship nothing else:** no ROMs, BIOS, firmware or keys.

### Embedded emulator cores — exact sources

| Core | Where | Licence | Exact source |
|---|---|---|---|
| mGBA `0.11-212-7a12d6d` | App (GB, GBC, GBA) | MPL-2.0 | https://github.com/libretro/mgba/tree/7a12d6d |
| melonDS DS `1.4.0` | App (DS) | GPL-3.0 | https://github.com/JesseTG/melonds-ds/tree/v1.4.0 |
| gpSP `v1.1.0-5819380` | App (GBA link play) | GPL-2.0-or-later | https://github.com/libretro/gpsp/tree/5819380 |
| PPSSPP (libretro nightly) | App (PSP) | GPL-2.0-or-later | https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c |
| PCSX ReARMed `r26l-119-gc881679` (libretro nightly, built 25/10/2026) | App (PS1, D137) | GPL-2.0-or-later (bundled parts below) | https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a |
| Mupen64Plus-Next GLES3 `3.0-GLES3 15bada8` (libretro nightly, arm64 built 27/10/2026, x86_64 debug-only 28/10/2026) | App (N64, plan-n64) | GPL-3.0 as combined (parts: sweep below) | https://github.com/libretro/mupen64plus-libretro-nx/tree/15bada85906593376e8fd697f1b0b3af3a20f512 |
| mGBA (EmulatorJS build 4.2.3) | Plugin (browser GB, GBC, GBA) | MPL-2.0 | https://github.com/EmulatorJS/mgba (npm `@emulatorjs/core-mgba` 4.2.3, hash in `server/emulatorjs.lock`) |
| melonDS legacy (EmulatorJS build 4.2.3) | Plugin (browser DS) | GPL-3.0 | https://github.com/EmulatorJS/melonDS (npm `@emulatorjs/core-melonds` 4.2.3, hash in `server/emulatorjs.lock`) |
| RomPatcher.js 3.2.1 core | Plugin (patcher page, `Pages/rompatcher.js`) | MIT (licence kept at the top of the file) | https://github.com/marcrobledo/RomPatcher.js/tree/v3.2.1 (archive SHA-256 `95d4ac04…128275f`, checked by `tools/vendor_rompatcher.py`) |
| PCSX ReARMed (EmulatorJS build 4.2.3) | Plugin (browser PS1, D137) | GPL-2.0-or-later | https://github.com/EmulatorJS/pcsx_rearmed (npm `@emulatorjs/core-pcsx_rearmed` 4.2.3, hash in `server/emulatorjs.lock`) |
| Mupen64Plus-Next (EmulatorJS build 4.2.3, fork commit `c35e55e`) | Plugin (browser N64, D146) | GPL-2.0-or-later | https://github.com/EmulatorJS/mupen64plus-libretro-nx/tree/c35e55e87cbaa4cfc8355d8f91ceaa2beb212e9d (npm `@emulatorjs/core-mupen64plus_next` 4.2.3, hash in `server/emulatorjs.lock`) |

- ✅ `About.kt` shows the PPSSPP row as commit `902e5cc` (checked 28/10/2026).
- The EmulatorJS core builds don't name an exact upstream commit; the npm package + SHA-256 is the pin. Their `reports/*.json` give the build date.

## Before sharing the APK (checklist)
1. **Publish the source** at the address shown in the app (`Licences.APP_SOURCE` = `https://github.com/BBBannon/Shoalcade`). The source must match the APK you share: push it and tag that commit (first public release: tag `v0.3.0` = app 0.6.0 + plugin 0.3.0.0). ⚠️ The repo must be **public before** the APK or plugin zip is shared.
2. If a core is updated, update its version + source link in **both** this file and `About.kt` (`Licences.parts`).
3. Ship nothing else: no ROMs, BIOS, firmware or keys.
4. ✅ **PPSSPP commit found** (25/10/2026, re-checked 01/11/2026): `902e5cc` is embedded in the bundled `libppsspp_libretro_android.so` (arm64 SHA-256 `e1d3a12c…095b22ae`) and in the debug-only x86_64 copy (`e5aa26fb…6db4031f`); it is the only PPSSPP version string in either file. Full hash above, same commit as `windows/cores/cores.lock`. If the core is ever replaced, re-check the string and update `About.kt` and `tools/fetch_ppsspp_assets.sh`.
5. ✅ **PPSSPP fonts checked** (25/10/2026): they are PPSSPP's own community replacements, not Sony's. **Where they come from, proven 01/11/2026:** every file in `PPSSPP.zip` is byte-identical to PPSSPP's `assets/` folder at commit `902e5cc` (plus its root `LICENSE.TXT`; `assets/debugger` is a submodule, left empty), so the fonts are `assets/flash0/font/*.pgf` at that commit. Details below.
6. ✅ **Mupen64Plus-Next licence sweep** (28/10/2026, commit `15bada8`). See "Mupen64Plus-Next (N64) sweep" below. GLideN64 wording decided (D146).

### Mupen64Plus-Next (N64) sweep — 28/10/2026

- **What gets built:** the buildbot uses `libretro/jni/Android.mk` (`.gitlab-ci.yml`).
  - **arm64** (`Android.mk:38-43`): `LLE`, `HAVE_PARALLEL_RSP`, `HAVE_PARALLEL_RDP` and `HAVE_THR_AL` are all on. So it also has cxd4 (**CC0**), parallel-rsp (**MIT**, or LGPL-3) + GNU lightning (**LGPL-3.0-or-later**), parallel-rdp (**MIT**) and angrylion (**MIT** upstream; the tree has no licence text for it).
  - **x86_64** (debug only): those four parts are left out.
- **Both builds:**
  - mupen64plus-core, rsp-hle, the libretro glue and `custom/` are **GPL-2.0-or-later** (headers: "either version 2 … or any later version").
  - GLideN64 + GLideNHQ, libretro-common (**MIT**), libco (public domain), xxHash (**BSD-2**), zlib, libpng, minizip and md5 (zlib / BSD style).
- **No GPL-2.0-only file headers.** No non-commercial or MAME-only licences.
- **GLideN64 — decided 28/10/2026 (Ben: "u pick"), D146.** Its `LICENSE` says "GNU General Public License version 2" with no "or later", and almost none of its files carry a header. GLideN64 upstream itself already builds in GPL-3.0-or-later code (`GraphicBuffer.cpp`), GPL-3.0 xBRZ (`TextureFilters_xbrz.cpp`) and LGPL-3.0 gles2n64 parts, so its own authors distribute it combined with GPL-3 code. We follow upstream: listed as **"GLideN64: GPL-2.0 (combined upstream with GPL-3.0 code)"**, shipped as part of the GPL-3.0 app with full source at the tag. **If the GLideN64 authors ever say it is GPL-2.0-only and object,** the fix is a core build without GLideN64 (parallel-RDP / angrylion only), not a change to the app's licence.
- **Result:** the `.so` is **GPL-3.0 as combined**, which fits the app's GPL-3.0.

| Component | Use | Licence |
|---|---|---|
| **LibretroDroid** 0.14.0 | Emulator front-end library | GPL-3.0 |
| **mGBA** libretro core `0.11-212-7a12d6d` | GB, GBC and GBA | MPL-2.0 — source https://github.com/libretro/mgba/tree/7a12d6d |
| **melonDS DS** libretro core `1.4.0` | DS | GPL-3.0 — source https://github.com/JesseTG/melonds-ds/tree/v1.4.0 |
| **gpSP** libretro core `v1.1.0-5819380` | GBA link play only (Pokémon link cable / wireless adapter) | GPL-2.0-or-later — source https://github.com/libretro/gpsp/tree/5819380 |
| **PPSSPP** libretro core (nightly built 22/10/2026; commit `902e5cc` embedded in the arm64 `.so` and reported by the PC test build) | PSP | GPL-2.0-or-later (headers: *"version 2.0 or later versions"*) — source https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c |
| **PPSSPP support files** (`assets/PPSSPP.zip`: buildbot `assets/system/PPSSPP.zip` of 22/10/2026 = PPSSPP's `assets/` + `LICENSE.TXT` at commit `902e5cc`; rebuilt from that commit by `tools/fetch_ppsspp_assets.sh`) | PSP fonts, compatibility list, UI atlas. Unpacked to `files/system/PPSSPP` | GPL-2.0 + PSPSDK BSD notice (`PPSSPP/LICENSE.TXT` inside) |
| **PCSX ReARMed** libretro core `r26l-119-gc881679` (commit `c8816799`, 02/10/2026) | PS1 (D137) | GPL-2.0-or-later, no GPL-2.0-only files (sweep below) — source https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a |
| **Mupen64Plus-Next** libretro core, GLES3 build `3.0-GLES3 15bada8` (commit `15bada85`, 22/10/2026). Bundled as `libmupen64plusnext_libretro_android.so`: arm64 SHA-256 `4335b6b1…6bc3c7`, x86_64 (debug APK only) `ead53e23…3ce9e` | N64 (plan-n64) | GPL-3.0 as combined, no GPL-3-incompatible code (sweep below) — source https://github.com/libretro/mupen64plus-libretro-nx/tree/15bada85906593376e8fd697f1b0b3af3a20f512 |
| **Azahar** libretro core `2126.2` (commit `219d743`), **built by us** with `tools/build_azahar_core.sh`: `ENABLE_BUILTIN_KEYBLOB=OFF` (no keys inside, checked in `build.ninja`). **Modified by us:** patches in `tools/azahar-patches/` (D154), so the source we must publish is Azahar 2126.2 plus those patches. `libazahar_libretro_android.so` arm64 SHA-256 `1f7adadf…360895` (`src/main/jniLibs`, in the app from v0.8.0). The patches and build script are published in Shoalcade's repo (`tools/azahar-patches/`, `tools/build_azahar_core.sh`) | 3DS (D153) | GPL-3.0-or-later (some GPL-2.0-or-later files) — source https://github.com/azahar-emu/azahar/tree/2126.2 |
| **LibretroDroid 3DS copy** (`android/lr3ds`): Argosy's Vulkan fork of LibretroDroid, commit `68f1368`, renamed to `libretrodroid3ds` by `tools/vendor_lr3ds.py` (published). In the app from v0.8.0 | 3DS only (Vulkan); other consoles stay on LibretroDroid 0.14.0 | GPL-3.0 — source https://github.com/rommapp/argosy-launcher/tree/68f1368b61707f8c4ad001b89eb095a705a46143/libretrodroid. Bundled inside it: libadrenotools `8fae8ce` (BSD-2-Clause), rcheevos `40d916d` (MIT), Oboe (Apache-2.0), SoundTouch (LGPL-2.1) |
| **3DS check scene** `assets/n3ds/check.3dsx` (built by `tools/build_n3ds_check.sh` from `tools/n3ds-check`) | The 3DS check's test scene (D159) | Shoalcade's code GPL-3.0-or-later, based on devkitPro 3ds-examples `graphics/gpu/fragment_light` (**Public Domain Mark**, README). Linked: **libctru** (zlib, licence text in its README: github.com/devkitPro/libctru) and **citro3d** (zlib, github.com/devkitPro/citro3d/blob/master/LICENSE). Confirmed 30/10/2026. zlib asks only that the notice isn't removed from source copies; no attribution needed in the binary, but it's listed in About anyway |
| Shoalcade link shim (`cpp/linkshim.c`, `cpp/linkstate.c`) | Gives cores link support | GPL-3.0 (ours) |
| Shoalcade clock shim (`cpp/clockshim.c`) | Saves the Game Boy cartridge clock with the game | GPL-3.0 (ours) |
| AndroidX / Jetpack Compose / Kotlin | App framework | Apache-2.0 |
| Shoalcade QR code maker (`server/.../Pages/qr.js`) | Browser Settings page: QR code for the phone app download (D134) | GPL-3.0 (ours). Written for Shoalcade from the QR code standard (ISO/IEC 18004); no third-party code, no network |
| **Phone app inside the plugin zip** (`app/Shoalcade.apk`, D134) | The plugin now also hands out the APK, cores and all | Same duties as sharing the APK (see "GPL obligations"): each plugin release that bundles an APK must have that APK's source (the repo at its tag) published, like a GitHub release. **The plugin zip therefore carries the APK's GPL duties too** — including every app core (mGBA, melonDS DS, gpSP, PPSSPP, PCSX ReARMed, Mupen64Plus-Next) and their notices |
| JellyEMU (server plugin, not bundled) | ROM + save API | MIT |
| **JellyEMU 0.9.2.4 code reused** (console, region and disc detection, name cleaning, .cue/.j3u reading: `PlatformResolver`, `NameCleaner`, `RetroGamesCore`), ported into `server/.../Scanner/ConsoleDetector.cs` and `RomScanner.cs` | Server scanner (phase 3, D42) | MIT — Copyright (c) 2026 Jellyfin GP — https://github.com/Jellyfin-PG/JellyEmu. The MIT notice must stay with the code. Full MIT text: `THIRD_PARTY_NOTICES.md`. Source headers in both files credit it and point at the MIT text, which ships in the plugin zip (`THIRD_PARTY_NOTICES.md`) |
| **libretro-database** `.dat` files (`metadat/no-intro`, `redump`, `developer`, `publisher`, `releaseyear`, `genre`) — **downloaded at run time, not bundled** (s0.2, D48) | Server: official game names, year, developer, publisher, genre by ROM MD5. Fetched by the plugin from `https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/` into `shoalcade/gamedb/`, only for consoles that have games, checked weekly | **CC-BY-SA-4.0** — © libretro and contributors, https://github.com/libretro/libretro-database. Not redistributed by us (each server downloads its own copy). If we ever ship the files or a database built from them: credit libretro-database, link the licence, and share that data under CC-BY-SA-4.0 |
| **libretro-thumbnails** (online service, `https://thumbnails.libretro.com/`) | Server: box art for games with no local picture; Jellyfin downloads and stores each picture once (not hotlinked) | Box art belongs to the games' publishers; fetched one game at a time for each server's own library. Not bundled, not hotlinked. Credited in `THIRD_PARTY_NOTICES.md`; the privacy note (`guides/privacy.md`) says game names are sent. Admins can untick the source per library |
| *Future (not built)*: Hasheous | Game descriptions | Service (AGPL-3.0) |

## PPSSPP support files (`assets/PPSSPP.zip`) — checked 25/10/2026

**Exact source (01/11/2026):** the zip in the app (and in the v0.10.0 APK) is the libretro buildbot's `assets/system/PPSSPP.zip`, packed 22/10/2026 23:06. Each of its 176 entries was compared with the PPSSPP git tree at **`902e5ccd8c1400b4387b73942491cded655e5f9c`** (the commit in the PSP core): all 165 files under `PPSSPP/` match `assets/` byte for byte (git blob hashes), `PPSSPP/LICENSE.TXT` matches the root `LICENSE.TXT`, and `PPSSPP/debugger/` is empty (a submodule in the tree). Nothing else is in the zip.

- **Public builds:** `PPSSPP.zip` is not in git. `tools/fetch_ppsspp_assets.sh` fetches those files at that commit and packs them. It checks the result against a **content SHA-256** `9bdf58e1…a665ff55` (SHA-256 of the sorted list of every entry's name + SHA-256 of its bytes), which the shipped zip also gives; a changed or extra file fails. The zip container is not byte-identical to the buildbot's (that was packed by another zip tool with its own deflate; Python's zlib can't reproduce its streams), so the check is on what the app unpacks (D220).

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
| **Mupen64Plus-Next** (EmulatorJS build) | `@emulatorjs/core-mupen64plus_next` 4.2.3 (core built 14/06/2025, version string `2.8-GLES3 c35e55e`) | `2b8952aa…09724417` (npm sha512 `HnnEXbOE…5e3hw==` also checked) | **N64** in the browser (same core family as the app, one save block, D146). No BIOS/PIF ROM needed or bundled | GPL-2.0-or-later — https://github.com/EmulatorJS/mupen64plus-libretro-nx/tree/c35e55e87cbaa4cfc8355d8f91ceaa2beb212e9d (fork of https://github.com/libretro/mupen64plus-libretro-nx) |

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
- **Play! (PS2), Shoalcade Dev only** (D249): `play_libretro_android.so` arm64 + x86_64 nightlies from the same buildbot, 05/11/2026, in `src/debug/jniLibs` only. **BSD-2-Clause** (github.com/jpd002/Play-), fine with GPL-3.0. Not in any release APK. Credit it in `THIRD_PARTY_NOTICES.md` if it ever ships.
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


## Celio Link interop (app) — added 31/10/2026

- **Our own code** (`android/app/src/main/java/uk/benmarks/shoalcade/Celio.kt`, GPL-3.0 like the rest of the app), written from the protocol. No Celio or gen1recomp code is copied in.
- **Projects that informed it** (read for the protocol only):

| Project | Licence | What we took from it |
|---|---|---|
| Celio-Link/Celio-Server (`src/session.ts`, `client.ts`, `index.ts`, `messages/gameboy.ts`, `messages/validation.ts`) | GPL-3.0 | Event names and payloads, LinkStatus/CommandType values, status → command rules, ack/retry and 5 s reconnect behaviour, ping timings |
| Celio-Link/Celio-mGBA-Link (`celio_device.lua`) | GPL-3.0 | The device side: handshake words, 4 frames per packet, the "no key held" filter, 0x5FFF / 0xCAFE 0x0017 session ends |
| Celio-Link/Celio-Client (`linkExchangeSession.ts`, `commandEmitter.socketIO.ts`) | GPL-3.0 | One packet per `deviceData` emit, reordering by sequence, uuid dedup |
| koutaitatu mGBA Celio Edition (`src/platform/qt/CelioNet.cpp`) | MPL-2.0 | Cross-check of the PC side we link with |
| mleo2003/gen1recomp-celio (`lib/sio.lua`, `lib/celio.lua`, `lib/gbalink.lua`, `tests/celio_test.lua`) | MIT | Socket.IO v5 framing as a client, the emulator-leads role (AwaitModeEmulator), linger before leaving, test shape |
| libretro/gpsp (`serial_proto.c`) | GPL-2.0-or-later | MPK1 packet format and the mul_poke master behaviour (ported into a JUnit fake only, not into the app) |

- **New library in the APK:** **OkHttp 4.12.0** (+ Okio, Kotlin stdlib already present), **Apache-2.0**, for the WebSocket only. Listed in About and THIRD_PARTY_NOTICES.md. Android has no JDK WebSocket client (`java.net.http` is missing), and a hand-made TLS WebSocket would mean doing hostname checks ourselves.

## Shoalcade for Windows (installer) — added 31/10/2026

- **What ships:** `ShoalcadeApp-win-Setup.exe` (Dev: `ShoalcadeDevApp-…`), built on Linux by `tools/build_windows_release.sh` (plan-windows §8, D199–D202). Report: `docs/windows/phase8-installer.md`.
- **Licence texts in the install folder:** `LICENSE.txt` (GPL-3.0) and `licences\` (every part's own text plus a `README.txt` index), written by `windows/installer/licences.sh`. Java's notices stay in `runtime\legal\`.
- **Corresponding Source (GPLv3 §6(d)):** `SOURCE.txt` in the install folder names the exact Shoalcade commit, each core's repo + commit + DLL SHA-256 (from `windows/cores/cores.lock`), each core's submodule commits, the patch SHA-256s, and every other part's pinned source. The player is built from the committed source only (`git archive HEAD`). A build with uncommitted changes says **TEST BUILD — do not publish** in `SOURCE.txt`.
- **About screen:** `windows/app/.../desktop/WinLicences.kt` lists every part, its licence and exact source link; `WinLicencesTest` fails if a Windows core commit in `cores.lock`, the Java runtime, SDL or Velopack version stops matching.
- **Publishing:** `tools/build_windows_release.sh` is un-ignored in `.gitignore`: it controls how the shipped files are built and installed, so it is part of the Corresponding Source.

| Part (Windows only) | Licence | Exact source |
|---|---|---|
| Cores: same commits as the phone, built by us with MinGW-w64 (D190) | as the phone's (table above); PCSX ReARMed x64 also includes lightrec (LGPL-2.1+) and GNU lightning (LGPL-3.0+); PPSSPP bundles FFmpeg (LGPL-2.1+) | `windows/cores/cores.lock`; mGBA https://github.com/libretro/mgba/tree/7a12d6d; melonDS DS https://github.com/JesseTG/melonds-ds/tree/f394adbacb5722ee97c1b37c8064da9a25818310 + melonDS https://github.com/JesseTG/melonDS/tree/16f127dbb587a73371827ce79689c5d237c4b59b; patches `tools/ppsspp-patches`, `tools/azahar-patches` |
| **Dolphin** libretro core (GameCube + Wii, Windows only, D218/D222), built natively on Windows with MSYS2's MinGW-w64 GCC 16, every library from its own `Externals` linked statically (`USE_SYSTEM_LIBS=OFF`), + `tools/dolphin-patches` (cheats through `retro_cheat_set`); reports `2609.0.441+b93001ed50` | GPL-2.0-or-later; bundled libraries (mbedtls Apache-2.0, zstd/lz4/xxhash BSD, zlib-ng/minizip-ng zlib, fmt MIT, curl, SPIRV-Cross Apache-2.0, glslang BSD/Apache, FreeSurround and LZO GPL-2, Bochs disasm LGPL and others) per its `Externals/licenses.md`, shipped as `licences\cores\Dolphin-Externals-licenses.md` | https://github.com/libretro/dolphin/tree/b93001ed50c8b34e6ead4eed713c2005a80386e4 (+ its submodules at the commits that tree records, listed in `SOURCE.txt`) |
| **Dolphin `Sys` folder** (`app\system\dolphin-emu\Sys`, copied to the data folder's `system\dolphin-emu\Sys` by `Shoalcade.exe`, stamp `dolphin-emu.stamp`); the whole folder (D-9) | GPL-2.0-or-later (Dolphin's own files: GameSettings, `codehandler.bin` built from Dolphin's `docs/codehandler.s`, Dolphin's free DSP ROM replacements); GameCube fonts made from Droid Sans: Apache-2.0 (`GC/font-licenses.txt`). **Nothing from Nintendo** | `Data/Sys` at the Dolphin commit above |
| **LRPS2** libretro core (`libretro/parallel-ps2`, PS2, Windows only), built natively as Dolphin, + `tools/lrps2-patches` (GCC arch flags instead of MSVC's `/arch:AVX`; version string from LRPS2's own commit; cheats through `retro_cheat_set`); reports `v2.0.0-083de59f52` | GPL-3.0 (PCSX2 parts GPL-3.0+/LGPL-3.0+); vendored glslang (BSD/Apache-2.0), xbyak (BSD-3), vixl (BSD-3), Vulkan headers (Apache-2.0). **No BIOS** shipped: each PC's owner picks their own | https://github.com/libretro/parallel-ps2/tree/083de59f52159ce4dd2f63a63f74fa9f0e936ee3 |
| **PCEE2** libretro core (`WizzardSK/pcee2-libretro` branch `libretro`, PS2 second choice: the user picks LRPS2 or PCEE2, D222 D-3; Windows only), built natively as Dolphin, + `tools/pcee2-patches` (the game's own memory card `<save dir>\<game>.ps2`, same file and format as LRPS2; cheats through `retro_cheat_set`). Its libraries (SDL3, FreeType, plutovg/plutosvg, rapidyaml, shaderc + glslang, zlib, libpng, libjpeg-turbo, zstd, lz4, libwebp, DirectX-Headers) are built from source at the revisions in its `pcee2-libretro/scripts/deps.versions` and linked statically. Its `bin/resources` (game database, fonts, shaders) ship to `app\system\pcsx2\resources` | GPL-3.0-or-later (PCSX2); the libraries' own licences per its `THIRD_PARTY_LICENSES.html`, shipped as `licences\cores\PCEE2-THIRD_PARTY_LICENSES.html`. **No BIOS** shipped | https://github.com/WizzardSK/pcee2-libretro/tree/5719627731beb90e379ff1be1223391b7f563b3b |
| **Xenia Edge** libretro core (`WizzardSK/xenia-libretro` branch `libretro`, port of `has207/xenia-edge` `8dc77b0`; Xbox 360, Windows only, plan-windows-x360.md), built natively on Windows with **MSVC** (Visual Studio 2022 Build Tools 17.14, cl 19.44, CMake + Ninja, Release + LTO, as its own Windows CI), every library from its own `third_party` submodules linked statically, + `tools/xenia-patches` (static C runtime `/MT`, so no Visual C++ runtime is needed). No `dxil.dll` inside (Vulkan only) and no game-patches database (that repo has no licence). Reports `8dc77b0 (libretro core 8fd1709)`. Build tool only, not shipped: Slang 2026.8 (`slangc`) | BSD-3-Clause (Xenia, Ben Vanik); compiled-in libraries: FFmpeg `libavcodec/libavutil/libavformat` (XMA audio) LGPL-2.1-or-later, libmspack LGPL-2.1, libusb LGPL-2.1-or-later, Mesa `spirv_to_dxil` (D3D12 shader backend) MIT, glslang BSD/Apache-2.0, SPIRV-Tools Apache-2.0, fmt MIT, zstd/xxhash/snappy/capstone/xbyak BSD, zlib-ng zlib, imgui/pugixml/VulkanMemoryAllocator/zarchive MIT-style, boost.context BSL-1.0 and others. The LGPL parts are fine inside Shoalcade (GPL-3.0), whose Corresponding Source covers them. **No BIOS, keys or firmware** (none needed) | https://github.com/WizzardSK/xenia-libretro/tree/8fd1709218e98767d4468a482656adc64f45a8ef (+ its submodules at the commits that tree records, except macOS-only MoltenVK and SPIRV-Cross) |
| SDL 3.2.24 (`SDL3.dll`) | zlib | https://github.com/libsdl-org/SDL/tree/release-3.2.24 (MinGW kit SHA-256 `07fba995…aeebd376f0`) |
| zstd 1.5.7 (MSYS2 `mingw-w64-x86_64-zstd` 1.5.7-2), linked statically into `ShoalcadePlayer.exe` for the GameCube / Wii / PS2 snapshots (plan P8) | BSD-3-Clause (text in `licences\zstd-LICENSE.txt`, vendored in `windows/installer/licences/`) | https://github.com/facebook/zstd/tree/v1.5.7 |
| GameCube / Wii check scenes `gc-check.dol`, `wii-check.dol` (built by `tools/build_gc_check.sh` from `tools/gc-check`, D222 D-7) | GPL-3.0-or-later (our own scene: procedural textures, no third-party art); libogc linked in: zlib-style (`licences\libogc-LICENSE.txt`) | `tools/gc-check` at the release commit; https://github.com/devkitPro/libogc |
| Dear ImGui 1.92.4, nlohmann/json 3.11.3, stb, libretro.h, Atkinson Hyperlegible 1.006 | MIT / MIT / MIT or public domain / MIT / OFL-1.1 | Vendored in `windows/player/third_party/` (README there lists each pin) |
| MinGW-w64 runtime (GCC 14.2, mingw-w64 12.0), linked statically | GPL-3.0 + GCC Runtime Library Exception; mingw-w64 ZPL/MIT-style | https://sources.debian.org/src/gcc-mingw-w64/, https://sources.debian.org/src/mingw-w64/ |
| Eclipse Temurin 17.0.20.1+1 (jlinked `runtime\`) | GPL-2.0 with Classpath Exception | https://github.com/adoptium/jdk17u/tree/jdk-17.0.20.1+1 |
| Compose Multiplatform 1.7.3, Skiko 0.8.18 (Skia BSD-3), Kotlin 2.0.21, kotlinx, AndroidX, OkHttp 4.12.0/Okio, JNA 5.15.0 (Apache-2.0 chosen), JmDNS 3.5.9 (link play), JetBrains annotations; SLF4J API 2.0.7 (MIT, used by JmDNS) | Apache-2.0 (SLF4J: MIT, text in `licences\java\`) | Maven Central (`-sources.jar` beside each); exact list in `licences\README.txt` |
| JSON-java 20240303 | Public domain | https://github.com/stleary/JSON-java/tree/20240303 |
| Velopack 1.2.161 (`Setup.exe`, `Update.exe`) | MIT | https://github.com/velopack/velopack/tree/1.2.161 |

- **No BIOS, firmware, keys or games** are in the installer (filename sweep of the built image 31/10/2026: only hits were Java's `legal/java.base/aes.md` notice and PPSSPP's `shaders/smiley_16x16_rgba.bin` test texture; `flash0/font` holds PPSSPP's community fonts, see "PPSSPP support files").
- ⚠️ This branch's `THIRD_PARTY_NOTICES.md` (copied into `licences\`) and the mGBA row above still say `mgba-emu/mgba`; the fix (`libretro/mgba`, commit abb8071 on the main line) must be merged before a public Windows build.

## Windows app: link play (D193–D198)

- **New library in the Windows app (not the APK):** **JmDNS 3.5.9** (`org.jmdns:jmdns`, Apache-2.0) + **SLF4J API 2.0.7** (MIT), to find and show link hosts on the home network with the phone's DNS-SD service. Listed in the About screen (`WinLicences.kt`) and the installer's `licences\README.txt`.
- The Windows player's `link.cpp` is our own port of our `linkshim.c` (GPL-3.0-or-later); no outside code.
