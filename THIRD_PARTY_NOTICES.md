# Third-party notices

**Shoalcade** is free software under the **GPL-3.0** ([LICENSE](LICENSE)).
It is built on the work of the projects below. Thank you to all of them.

- **Full detail** (exact versions, hashes, checks): [docs/licences.md](docs/licences.md)
- **No games, BIOS, firmware or keys** are included in anything we ship.

---

## Android app (APK)

| Component | Used for | Licence | Source |
|---|---|---|---|
| **LibretroDroid** 0.14.0 | Emulator front-end | GPL-3.0 | https://github.com/Swordfish90/LibretroDroid/tree/0.14.0 |
| **mGBA** `0.11-212-7a12d6d` | Game Boy, Game Boy Color, Game Boy Advance | MPL-2.0 | https://github.com/mgba-emu/mgba/tree/7a12d6d |
| **melonDS DS** 1.4.0 | Nintendo DS | GPL-3.0 | https://github.com/JesseTG/melonds-ds/tree/v1.4.0 |
| **gpSP** `v1.1.0-5819380` | Game Boy Advance link play | GPL-2.0-or-later | https://github.com/libretro/gpsp/tree/5819380 |
| **PPSSPP** (commit `902e5cc`) | PSP | GPL-2.0-or-later | https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c |
| **PCSX ReARMed** `r26l-119-gc881679` (commit `c8816799`) | PlayStation (PS1) | GPL-2.0-or-later | https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a |
| **PPSSPP support files** | PSP system fonts, UI fonts, compatibility list | See below | Same as PPSSPP |
| **AndroidX**, **Jetpack Compose**, **Kotlin**, **kotlinx.coroutines** | App framework | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support · https://github.com/JetBrains/kotlin · https://github.com/Kotlin/kotlinx.coroutines |

### PPSSPP support files

| Part | Licence |
|---|---|
| PPSSPP assets (language files, compatibility list, UI pictures, sounds) | GPL-2.0-or-later, plus the **PSPSDK BSD notice** (in `PPSSPP/LICENSE.TXT` inside the app) |
| PSP system fonts (`flash0/font/*.pgf`) | **Free replacement fonts made for PPSSPP** by nassau-tk (not Sony's). The Japanese font is based on **Ume-font** (free licence) |
| Roboto Condensed | Apache-2.0 |
| Inconsolata | SIL Open Font Licence 1.1 |
| SDL_GameControllerDB (`gamecontrollerdb.txt`) | zlib |

---

## Jellyfin plugin (server zip)

| Component | Used for | Licence | Source |
|---|---|---|---|
| **EmulatorJS** 4.2.3 | Browser player front-end | GPL-3.0 | https://github.com/EmulatorJS/EmulatorJS/tree/v4.2.3 |
| **mGBA** (EmulatorJS build 4.2.3) | Browser: GB, GBC, GBA | MPL-2.0 | https://github.com/EmulatorJS/mgba |
| **melonDS** (EmulatorJS build 4.2.3) | Browser: DS | GPL-3.0 | https://github.com/EmulatorJS/melonDS |
| **PCSX ReARMed** (EmulatorJS build 4.2.3) | Browser: PS1 | GPL-2.0-or-later | https://github.com/EmulatorJS/pcsx_rearmed |
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
