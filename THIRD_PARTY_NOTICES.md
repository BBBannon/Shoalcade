# Third-party notices

**Shoalcade** is free software under the **GPL-3.0** ([LICENSE](LICENSE)).
It is built on the work of the projects below. Thank you to all of them.

- **Full detail** (exact versions, hashes, checks): [docs/licences.md](docs/licences.md)
- **No games, BIOS, firmware or keys** are included in anything we ship.
- **Every part's own licence text:** [THIRD_PARTY_LICENCES.txt](THIRD_PARTY_LICENCES.txt), also inside the APK (**About → Every part's licence text**) and the plugin zip.

---

## Android app (APK)

| Component | Used for | Licence | Source |
|---|---|---|---|
| **LibretroDroid** 0.14.0 | Emulator front-end | GPL-3.0 | https://github.com/Swordfish90/LibretroDroid/tree/0.14.0 |
| **mGBA** `0.11-212-7a12d6d` | Game Boy, Game Boy Color, Game Boy Advance | MPL-2.0 | https://github.com/libretro/mgba/tree/7a12d6d |
| **melonDS DS** 1.4.0 | Nintendo DS | GPL-3.0 | https://github.com/JesseTG/melonds-ds/tree/v1.4.0 |
| **gpSP** `v1.1.0-5819380` | Game Boy Advance link play | GPL-2.0-or-later | https://github.com/libretro/gpsp/tree/5819380 |
| **PPSSPP** (commit `902e5cc`) | PSP | GPL-2.0-or-later | https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c |
| **PCSX ReARMed** `r26l-119-gc881679` (commit `c8816799`) | PlayStation (PS1) | GPL-2.0-or-later | https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a |
| **Mupen64Plus-Next** `3.0-GLES3 15bada8` | Nintendo 64 | GPL-3.0 as combined (parts GPL-2.0-or-later, MIT, CC0, LGPL-3.0-or-later) | https://github.com/libretro/mupen64plus-libretro-nx/tree/15bada85906593376e8fd697f1b0b3af3a20f512 |
| **Azahar** 2126.2 (commit `219d743`) + Shoalcade patches | Nintendo 3DS | GPL-3.0-or-later (some parts GPL-2.0-or-later) | https://github.com/azahar-emu/azahar/tree/219d743a1e698101fe1ab8bef43e6563126c3c27 (patches: `tools/azahar-patches`) |
| **LibretroDroid, Argosy's Vulkan version** (commit `68f1368`) | 3DS emulator front-end | GPL-3.0 (bundles libadrenotools BSD-2-Clause, rcheevos MIT, Oboe Apache-2.0, SoundTouch LGPL-2.1) | https://github.com/rommapp/argosy-launcher/tree/68f1368b61707f8c4ad001b89eb095a705a46143/libretrodroid |
| **3DS check scene** | The 3DS check's test scene | GPL-3.0-or-later; based on devkitPro 3ds-examples (public domain); libctru and citro3d (zlib) | `tools/n3ds-check` in this repository |
| **PPSSPP support files** | PSP system fonts, UI fonts, compatibility list | See below | Same as PPSSPP |
| **AndroidX**, **Jetpack Compose**, **Kotlin**, **kotlinx.coroutines** | App framework | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support · https://github.com/JetBrains/kotlin · https://github.com/Kotlin/kotlinx.coroutines |
| **OkHttp** 4.12.0 + **Okio** | Celio Link connection (WebSocket) | Apache-2.0 | https://github.com/square/okhttp/tree/parent-4.12.0 · https://github.com/square/okio |

### PPSSPP support files

| Part | Licence |
|---|---|
| PPSSPP assets (language files, compatibility list, UI pictures, sounds) | GPL-2.0-or-later, plus the **PSPSDK BSD notice** (in `PPSSPP/LICENSE.TXT` inside the app) |
| PSP system fonts (`flash0/font/*.pgf`) | **Free replacement fonts made for PPSSPP** by nassau-tk (not Sony's). The Japanese font is based on **Ume-font** (free licence) |
| Roboto Condensed | Apache-2.0 |
| Inconsolata | SIL Open Font Licence 1.1 |
| SDL_GameControllerDB (`gamecontrollerdb.txt`) | zlib |

---

## Windows app: GameCube, Wii, PS2 and Xbox 360 cores (Windows only)

Built by us with MinGW-w64 (`tools/build_windows_cores.sh`), exact commits and SHA-256 in `windows/cores/cores.lock`. Not in the phone app.

| Component | Used for | Licence | Source |
|---|---|---|---|
| **Dolphin** libretro (`2609.0.441`, commit `b93001ed50`) + our cheats patch (`tools/dolphin-patches`) | GameCube, Wii | GPL-2.0-or-later; its bundled libraries (mbedtls, zstd, lz4, zlib-ng, minizip-ng, fmt, curl, SPIRV-Cross, glslang, FreeSurround, LZO and others) as listed in its `Externals/licenses.md` | https://github.com/libretro/dolphin/tree/b93001ed50c8b34e6ead4eed713c2005a80386e4 |
| **Dolphin system files** (`system\dolphin-emu\Sys`) | Game fixes, cheat code handler, free DSP ROM replacements, names | GPL-2.0-or-later (Dolphin's own files); GameCube fonts made from **Droid Sans**: Apache-2.0. **Nothing from Nintendo** | Same as Dolphin (`Data/Sys`) |
| **LRPS2** (`libretro/parallel-ps2`, commit `083de59f52`) + our build fixes and cheats patch (`tools/lrps2-patches`) | PlayStation 2 | GPL-3.0 (PCSX2 parts GPL-3.0+/LGPL-3.0+); bundles glslang (BSD/Apache-2.0), xbyak (BSD-3), vixl (BSD-3), Vulkan headers (Apache-2.0) | https://github.com/libretro/parallel-ps2/tree/083de59f52159ce4dd2f63a63f74fa9f0e936ee3 |
| **PCEE2** (`WizzardSK/pcee2-libretro`, commit `5719627731`) + our memory card and cheats patches (`tools/pcee2-patches`), and its resources (`system\pcsx2\resources`) | PlayStation 2 (second choice) | GPL-3.0-or-later (PCSX2); compiled-in libraries (SDL3 zlib, FreeType FTL, shaderc/glslang Apache-2.0/BSD, zstd/lz4/libwebp BSD, libpng, libjpeg-turbo, rapidyaml/plutovg MIT and others) as listed in its `THIRD_PARTY_LICENSES.html` | https://github.com/WizzardSK/pcee2-libretro/tree/5719627731beb90e379ff1be1223391b7f563b3b |
| **Xenia Edge** libretro (`WizzardSK/xenia-libretro`, commit `8fd1709218`, a port of `has207/xenia-edge`) | Xbox 360 | BSD-3-Clause (Xenia); its bundled libraries under their own licences | https://github.com/WizzardSK/xenia-libretro/tree/8fd1709218e98767d4468a482656adc64f45a8ef |
| **GameCube / Wii check scenes** (`gc-check.dol`, `wii-check.dol`) | The GameCube / Wii speed check's test scene | GPL-3.0-or-later (our own scene); libogc inside it: zlib-style licence | `tools/gc-check` in this repository; https://github.com/devkitPro/libogc |
| **zstd** 1.5.7 (inside `ShoalcadePlayer.exe`) | Packing the big GameCube / Wii / PS2 snapshots | BSD-3-Clause | https://github.com/facebook/zstd/tree/v1.5.7 |

- **No PS2 BIOS** is included: each PC's owner picks their own (D218).
- **Xbox 360:** no BIOS, keys or firmware are included or needed.

---

## Jellyfin plugin (server zip)

| Component | Used for | Licence | Source |
|---|---|---|---|
| **EmulatorJS** 4.2.3 | Browser player front-end | GPL-3.0 | https://github.com/EmulatorJS/EmulatorJS/tree/v4.2.3 |
| **mGBA** (EmulatorJS build 4.2.3) | Browser: GB, GBC, GBA | MPL-2.0 | https://github.com/EmulatorJS/mgba |
| **melonDS** (EmulatorJS build 4.2.3) | Browser: DS | GPL-3.0 | https://github.com/EmulatorJS/melonDS |
| **PCSX ReARMed** (EmulatorJS build 4.2.3) | Browser: PS1 | GPL-2.0-or-later | https://github.com/EmulatorJS/pcsx_rearmed |
| **Mupen64Plus-Next** (EmulatorJS build 4.2.3) | Browser: Nintendo 64 | GPL-2.0-or-later | https://github.com/EmulatorJS/mupen64plus-libretro-nx |
| **Shoalcade phone app** (`app/Shoalcade.apk`) | Phone app download from your Jellyfin | GPL-3.0, with every part in the **Android app** table above | This repository, at the release's tag |
| **nipplejs** (inside EmulatorJS) | Touch joystick | MIT | https://github.com/yoannmoinet/nipplejs |
| **Socket.IO** client 4.8.1 (inside EmulatorJS) | Not used by Shoalcade | MIT | https://github.com/socketio/socket.io |
| **zip / 7-Zip unpackers** (inside EmulatorJS, `ejs/compression/`) | Unpacking the cores and zipped games | Shipped as part of EmulatorJS | https://github.com/EmulatorJS/EmulatorJS/tree/v4.2.3/data/compression |
| **unRAR** (`libunrar`, inside EmulatorJS) | **Not used** (RAR games are refused) — removed from the zip (D124) | unRAR licence (freeware, source available) | https://www.rarlab.com/rar_add.htm |
| **RomPatcher.js** 3.2.1 core (`Pages/rompatcher.js`, joined unchanged by `tools/vendor_rompatcher.py`) | Patcher page: IPS, UPS, BPS, PPF, xdelta, APS, RUP, PMSR patches | MIT | https://github.com/marcrobledo/RomPatcher.js/tree/v3.2.1 |
| **JellyEMU** 0.9.2.4 (code ported into our scanner) | Console, region and disc detection; name cleaning | MIT | https://github.com/Jellyfin-PG/JellyEmu |
| **Jellyfin.Controller / Jellyfin.Model** 12.3.0 | Plugin API (build only, not shipped) | GPL-3.0-only | https://github.com/jellyfin/jellyfin |

The EmulatorJS packages are pinned by SHA-256 in `server/emulatorjs.lock`.

---

## Online data (downloaded by your server, not shipped)

| Source | Used for | Licence / rights |
|---|---|---|
| **libretro-database** (`metadat/` lists) | Official game names, year, developer, publisher, genre | **CC-BY-SA-4.0** — © libretro and contributors — https://github.com/libretro/libretro-database |
| **libretro-thumbnails** (https://thumbnails.libretro.com/) | Box art for games with no picture | Thumbnails collected by the libretro community — https://github.com/libretro-thumbnails/libretro-thumbnails. Box art belongs to the games' publishers |

- Each server downloads its **own** copy of the libretro-database lists. We don't redistribute them.
- If you share those lists, or a database built from them, credit libretro-database and share it under **CC-BY-SA-4.0**.
- What these downloads send is explained in [guides/privacy.md](guides/privacy.md).

---

## JellyEMU — MIT licence

Parts of `server/Jellyfin.Plugin.Shoalcade/Scanner/ConsoleDetector.cs` and `RomScanner.cs` are ported from JellyEMU 0.9.2.4 (`PlatformResolver`, `NameCleaner`, `RetroGamesCore`).

```
MIT License

Copyright (c) 2026 Jellyfin GP

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Trademarks

- **Jellyfin** is a trademark of Jellyfin, Inc. Shoalcade is **not an official Jellyfin project**.
- Nintendo, Game Boy, Nintendo DS, Nintendo 3DS, Sony, PlayStation and PSP are trademarks of their owners. Shoalcade is not affiliated with or endorsed by them.
