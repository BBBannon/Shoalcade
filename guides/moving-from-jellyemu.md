# Moving from JellyEMU

**Order matters: import saves first, then remove JellyEMU, then a full restart, then scan.**
Your ROM files are never touched. JellyEMU's save files are only read.

## Steps

1. **Back up Jellyfin**, including its `plugins` folder and (if it exists) `<Jellyfin data>/shoalcade/`.
2. **Install Shoalcade** ([Install](install.md), step 1) with **JellyEMU still installed**. Restart Jellyfin.
   - Shoalcade waits: JellyEMU keeps finding the games for now.
3. **Import saves:** **Dashboard → Scheduled Tasks → Shoalcade → Import JellyEMU saves → Run.** Wait until it finishes.
4. **Uninstall JellyEMU:** **Dashboard → Plugins → JellyEmu → Uninstall.**
5. **Do a FULL restart** — stop and start the Jellyfin service or container:
   - Linux: `sudo systemctl restart jellyfin`
   - Docker: `docker restart <jellyfin-container>`
6. **Scan:** **Dashboard → Libraries → Scan All Libraries.** Wait until done.
7. **Check your saves:** open the Shoalcade saves manager. Any save marked **Not in your library** → **Move to another game** (pick the same game).
8. **Optional:** remove any reverse-proxy rules you added for `/jellyemu/`.

**Phone users:** install the Shoalcade app and log in ([Install](install.md), steps 3–4).

## Games that will disappear

Shoalcade shows **GB, GBC, GBA, DS, PS1, PSP and 3DS** only.
**PS1:** only one `.chd` or `.pbp` file per game stays. PS1 games as `.bin/.cue`, `.iso`, archives or disc folders leave the list (files untouched).
Games for **every other console JellyEMU listed** leave the library. **Their files stay on your server.**

| Group | Consoles that disappear |
|---|---|
| Nintendo | NES, SNES, N64, Virtual Boy, GameCube, Wii, Wii U, Switch |
| Sega | Master System, Game Gear, Genesis / Mega Drive, Sega CD, 32X, Saturn, Dreamcast |
| Sony | PlayStation 2, PlayStation 3, PS Vita (and PS1 games that aren't `.chd`/`.pbp`) |
| Atari | 2600, 5200, 7800, Jaguar, Lynx |
| Other | Arcade / MAME 2003, TurboGrafx-16, PC-FX, Neo Geo Pocket, WonderSwan, ColecoVision, 3DO, Commodore 64, Amiga, DOS, PICO-8, Xbox, Xbox 360 |
| Computer apps | Windows, macOS, Linux, Android |

**Need one of these?** Stay on JellyEMU for now. (While JellyEMU is installed, Shoalcade's own scanner stays off.)

## Something went wrong?

- **Games missing after the restart?** Do another **full** restart (step 5), then scan (step 6).
- **Want JellyEMU back?** Stop Jellyfin, copy its plugin folder back from your backup, start Jellyfin, scan. Your Shoalcade saves stay safe.
