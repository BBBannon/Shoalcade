# Install Shoalcade

**Four steps:** install the plugin → make a games library → install the app → log in.

**Moving from JellyEMU?** Read [Moving from JellyEMU](moving-from-jellyemu.md) **first**.

---

## You need

- **Jellyfin 12.3 or newer** (tested on 12.3 and 12.4), and an admin account
- An **HTTPS** address for your server (the app won't use plain HTTP)
- An **Android 11+** phone
- **Optional (new):** a **Windows 10 or 11** PC (64-bit) for the Windows app
- **For PS2 (Windows app only):** a BIOS file copied from **your own** PlayStation 2
- Your own games in a folder Jellyfin can read

---

## 1. Install the plugin

### Option A — Plugin repository (easiest, updates show in Jellyfin)

1. **Dashboard → Plugins → Repositories → +** (the **Catalogue** settings).
2. Name: **Shoalcade**. URL: **`https://raw.githubusercontent.com/BBBannon/Shoalcade/main/manifest.json`**
3. **Dashboard → Plugins → Catalogue → Shoalcade → Install**.
4. **Restart Jellyfin.**
5. Refresh the browser (**Ctrl+F5**).

### Option B — Manual install

1. Download **`Shoalcade_s<version>.zip`** from the [Releases page](https://github.com/BBBannon/Shoalcade/releases).
2. Unzip it into Jellyfin's **`plugins/Shoalcade_<version>/`** folder.
   - Linux (usual): `/var/lib/jellyfin/plugins/`
   - Docker: the `plugins` folder inside your `/config` volume
   - Windows: `C:\ProgramData\Jellyfin\Server\plugins\`
3. Make sure Jellyfin's user can read the files (Linux: `chown -R jellyfin:jellyfin <folder>`).
4. **Restart Jellyfin.**

**Check:** **Dashboard → Plugins** shows **Shoalcade** as **Active**.

---

## 2. Set up a games library

**Lay out your games** — one folder per console works best:

```
/media/games/
├── Game Boy/            .gb
├── Game Boy Color/      .gbc
├── Game Boy Advance/    .gba
├── Nintendo 64/         .z64 .n64 .v64
├── Nintendo DS/         .nds
├── PS1/                 .chd .pbp   (one file per game)
├── PSP/                 .iso .cso .pbp .chd
├── Nintendo 3DS/        .3ds .cci .cxi .3dsx (+ .zcci .zcxi .z3dsx; .cia listed, not playable)
├── GameCube/            .iso .gcm .rvz .wia .gcz .ciso .tgc   (Windows app only)
├── Wii/                 .iso .wbfs .rvz .wia .gcz .ciso .wad   (Windows app only)
├── PS2/                 .iso .chd .cso .zso .gz .ciso   (Windows app only)
└── Xbox 360/            .iso .zar .xcp   (Windows app only)
```

- Short names work too: `gb`, `gbc`, `gba`, `n64`, `nds`, `ps1`, `psx`, `psp`, `3ds`, `gc`, `wii`, `ps2`, `xbox360`, `x360`.
- A **.zip** or **.7z** holding one game is fine (not PS1, GameCube, Wii, PS2 or Xbox 360).
- PSP `.iso` files **must** be in a PSP folder (`.iso` alone doesn't say which console).
- **A file must fit its folder's console.** E.g. a `.gba` file in a `PSP` folder is **not listed** any more. Move it to the right folder.
- **Nothing is ever written into your ROM folder.**
- **Skipped files are logged:** Jellyfin's log names each file the scanner leaves out and why (see [Problems?](#problems)).

**Games on more than one disc** (PS1, PS2, GameCube, Wii, Xbox 360)

- Put the discs in **the game's own folder**, named `… (Disc 1)`, `… (Disc 2)` and so on, **or** list them in a `.m3u` file.
- It shows as **one game with one save**. Every disc must be one of that console's own file types (no `.cue` / `.bin`).
- The phone and Windows apps download every disc. **Windows:** menu → **Game → Change disc**. **Phone** (PS1): side menu → **Discs**.
- The **browser plays disc 1 only**, and says so.
- **Updating from an older version?** Discs that used to be **separate games** are now **one new game**. Their saves on Jellyfin **move across by themselves** after the next library scan (newest one current, the rest in history), as long as the combined game has no save yet. Anything left over: **Saves** → the old game → **Move to another game** (the combined game is offered first), or on the phone **Settings → Saves → Not in your library → Upload to another game…**

**GameCube, Wii and PS2 — the rules** (Windows app only)

| Works | Not supported |
|---|---|
| One file per game, or a game on more than one disc | `.zip` / `.7z` archives |
| In a **GameCube** (`gc`), **Wii** or **PS2** folder (`.iso` is shared by several consoles) | **PS2 `.bin` + `.cue`**: make one `.chd` of the disc instead |
| Wii `.wad` (WiiWare, Virtual Console) and homebrew `.dol` / `.elf` | Split Wii `.wbfs` (`.wbf1`…), **Wii U** |

- The phone shows these games as **Plays on Windows**; the browser as **Plays in the Windows app**.
- **GameCube and Wii:** no BIOS or system files needed.
- **PS2:** needs **your own BIOS** (see [Windows app](#windows-app-optional-new)).

**Xbox 360 — the rules** (Windows app only, new)

| Works | Not supported |
|---|---|
| One `.iso`, `.zar` or `.xcp` file per game, or a game on more than one disc | `.zip` / `.7z` archives |
| In an **Xbox 360** (`xbox360`, `x360`) folder (`.iso` is shared by several consoles) | **Extracted games** (a folder with a `.xex`): keep the game as one file. The original **Xbox** |

- The phone shows these games as **Plays on Windows**; the browser as **Plays in the Windows app**.
- **No BIOS, keys or system files needed**, and none are included.
- **Needs a PC with AVX and a Vulkan graphics card** (about RTX 2060 class for full speed). There is no speed check.
- **No snapshots, rewind, cheats or fast-forward.** The game saves itself.
- **Saves** sync like the others: the game's saved games and the profile that made them. Title updates and DLC stay on the PC.

**PS1 games — the rules**

| Works | Not yet |
|---|---|
| **One `.chd` or `.pbp` file per game** | `.bin` + `.cue`, `.iso` |
| In a **PS1** folder (or `ps1`, `psx`, `PlayStation`) | `.zip` / `.7z` archives |
| Or a file named with **(PS1)**, e.g. `Game (Europe) (PS1).chd` | |
| Games on more than one disc (see above) | |
| | Your own BIOS file, analogue sticks |

- **No BIOS needed.** A built-in stand-in is used. Most games run; **some may need a real BIOS**, which isn't supported yet.
- `.chd` and `.pbp` files outside a PS1 folder are treated as **PSP**, unless the name says PS1 (a PS1 `.pbp` is also recognised by its contents).
- **Big discs** (hundreds of MB) play best on the phone. In the browser they need a lot of memory; a note says so before **Play**.
- **The save is one 128 KB memory card**, shared by the phone and the browser. A second card is turned off.

**Create the library:**

1. **Dashboard → Plugins → Shoalcade** (the settings page).
2. Under **Games library → Add a games library**:
   - **Name** — e.g. `Games`
   - **ROM folder on the server** — the folder as the server sees it
3. Press **Create games library**.
4. Press **Scan for new games**. Wait for the scan to finish.

**What you'll see:**
- Games get official names and **box art** (from libretro — see [Privacy](privacy.md)).
- Games stay **off Jellyfin's home page** by default. Open them with the **Shoalcade** link in Jellyfin's menu.
- Change this under **Settings → Keep games off Jellyfin's home page**.

---

## 3. Install the app

1. Get the app, either way:
   - **From your Jellyfin:** open Shoalcade in a browser (`http://<your-jellyfin>/shoalcade/`), then **Settings → Phone app**. Scan the QR code with the phone (the code works for 10 minutes), or tap **Download the phone app**. Nothing is downloaded from anywhere else. If it says your Jellyfin admin has turned this off, use GitHub instead.
   - **From GitHub:** [`Shoalcade.apk`](https://github.com/BBBannon/Shoalcade/releases/latest/download/Shoalcade.apk) is always the newest. Each [release](https://github.com/BBBannon/Shoalcade/releases) also has `Shoalcade-<version>.apk`.
2. When asked, allow **Install unknown apps** for your browser.
3. Open the APK and tap **Install**.

**Updating:** install the new APK over the old one. Your login, games and saves stay.

---

## 4. First login

1. Open **Shoalcade**.
2. Type your server: **Server (e.g. jellyfin.example.com)**.
3. Pick a way in:
   - **Log in with Quick Connect** — the app shows **Your Quick Connect code**. Approve it in Jellyfin on any signed-in device (**Settings → Quick Connect**).
     - The admin must turn Quick Connect on first: **Dashboard → General → Quick Connect**.
   - **Use username and password instead** — your normal Jellyfin login.
4. Your games list appears. Tap a game → **Download** → **Play**.

---

## Windows app (optional, new)

**First Windows release.** It isn't yet widely tested on real PCs: please report problems.

1. **Update the plugin to 0.10.0.0 or newer first.** Older plugins don't accept saves from Windows (the save then waits on the PC).
2. Download **`ShoalcadeApp-win-Setup.exe`** from the [latest release](https://github.com/BBBannon/Shoalcade/releases/latest).
3. Run it. It isn't **signed** yet, so Windows says **"Windows protected your PC"**:
   - Click **More info** → **Run anyway**.
4. It installs for **your Windows account only** (no admin needed) and adds Desktop and Start menu shortcuts.
5. Open **Shoalcade**, type your server's **HTTPS** address, then **Log in** (or **Use Quick Connect instead**).
6. Click **Download** on a game → **Play**.

- **Updating:** run the new Setup file over the old one. Login, games and saves stay.
- **Uninstalling** (Windows **Settings → Apps**) never deletes your saves: they stay in `%LOCALAPPDATA%\Shoalcade\`.
- **Link play:** the first time the PC hosts, allow Shoalcade on **private networks** when Windows Firewall asks.

**Settings** (⚙) opens as a panel on the right, with tabs: **Account**, **Saves**, **Display**, **PS2**, **Link play**, **3DS check**, **About**.

**PS2 games: pick your BIOS once per PC**

1. Copy the BIOS file from **your own** PlayStation 2. Shoalcade never includes, downloads or uploads one.
2. **⚙ Settings → PS2 → PS2 BIOS file…** → pick the file.
3. The app checks it and shows what it found (e.g. "Found: PS2 BIOS, Europe, v2.00"). It keeps a copy **on this PC only**: never on your Jellyfin.

- **PS2 emulator** (same tab): **LRPS2** (default) or **PCEE2 (newer PCSX2)**. Try the other if a game doesn't work. One memory card works in both; snapshots only load in the emulator that made them.
- **GameCube and Wii check** (**3DS check** tab): tells you how well this PC runs GameCube and Wii games. Advice only.
- GameCube, Wii and PS2 need a **fast PC**. Real-game speed **hasn't been tested yet**.

---

## Browser player

- Open the **Shoalcade** link in Jellyfin's menu, or go to **`https://<your-server>/shoalcade/`**.
- It uses your Jellyfin web login. No extra sign-in.
- Plays **GB, GBC, GBA, N64, DS and PS1**. PSP and 3DS are in the phone and Windows apps only; GameCube, Wii, PS2 and Xbox 360 in the Windows app only.
- Each game page has **Play** and **Saves** buttons.
- Saves are sent to Jellyfin by themselves, a few seconds after the game saves.
  - Leaving too soon? It asks: **Your save hasn't reached the server yet** → **Stay** or **Leave anyway**.
- **Quick snapshot** (F2) and **Load snapshot** (F4) are on the toolbar.
- **⚙ Settings** (top of the library): **Manage saves**, **Phone app** and each console's **Browser player** settings.

**Admins** can turn the browser player off: **Settings → Browser player and library pages**.

---

## Save sync basics

**The rules**
- **Only in-game saves sync.** Snapshots stay where you made them (phone or browser).
- **Shared saves**: the phone, the Windows app and the browser use the same saves, with older versions kept.
- **Nothing is overwritten without a copy.** Every older version stays in the **list**.

**On the phone**

| You want to… | Do this |
|---|---|
| Send your save after playing | **Close Game (and Upload)** |
| Send it later | Game → **Upload save to Jellyfin** |
| Get the latest save from Jellyfin | Game → **Get save from Jellyfin** |
| Sync every game at once | **Push all** / **Pull all** |
| See older versions | Game → **Saves on Jellyfin…** → **Use this save** |
| Save a phone save whose game left the library | **Settings → Saves → Not in your library** |

**PS1 controller tip:** buttons match **by position**, so a controller's **A** is **○**. Prefer ✕? Change it in **Settings → Controls**.

**If both sides changed**, the app asks:
- **Keep the phone's**
- **Keep Jellyfin's**
- **Decide later**

Both copies are kept either way.

**In the browser** (saves manager)
- **Use this save** — make an older version current. The save you were using stays in the list.
- **Pin** — keep a version for good.
- **Name** — label a version.
- **Upload a save file** / **Download** — move saves to and from your computer.
- **Move to another game** — e.g. after a game was renamed.

**Offline?** The app says **You're offline**. Keep playing. Saves go up by themselves when you're back online (**Settings → Saves → Upload saves automatically**, on by default).

**Before uninstalling the app:** upload every save (**Push all**). Uninstalling deletes everything on the phone.

---

## Problems?

| Problem | Try |
|---|---|
| No games after a scan, or one game missing | Check the folder layout above — a file whose type doesn't fit its folder's console is skipped. **Dashboard → Logs** → open the newest log and search for **`Scanner skipped`**: each line names the file and why |
| PS2: "This PS2 game needs a BIOS file" | **⚙ Settings → PS2 → PS2 BIOS file…** in the Windows app |
| App shows a red banner | The plugin is too old for the app. Update the plugin |
| Login fails | Use the **HTTPS** address. Try **Log in with Quick Connect** |
| No box art | Wait for the next scan. Some games have no libretro art |
| Windows: "Windows protected your PC" | The installer isn't signed yet. **More info → Run anyway** |
| Windows: saves won't upload | Update the plugin to **0.10.0.0 or newer**, then fully restart Jellyfin |

**Please report bugs on [Discord](https://discord.gg/8jZQXTRCEj)** — not to the Jellyfin team.
