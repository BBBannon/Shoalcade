# Install Shoalcade

**Four steps:** install the plugin → make a games library → install the app → log in.

**Moving from JellyEMU?** Read [Moving from JellyEMU](moving-from-jellyemu.md) **first**.

---

## You need

- **Jellyfin 12.3 or newer** (tested on 12.3 and 12.4), and an admin account
- An **HTTPS** address for your server (the app won't use plain HTTP)
- An **Android 11+** phone
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
├── Nintendo DS/         .nds
├── PS1/                 .chd .pbp   (one file per game)
├── PSP/                 .iso .cso .pbp .chd
└── Nintendo 3DS/        .3ds .cci .cia
```

- Short names work too: `gb`, `gbc`, `gba`, `nds`, `ps1`, `psx`, `psp`, `3ds`.
- A **.zip** or **.7z** holding one game is fine (not PS1).
- PSP `.iso` files **must** be in a PSP folder (`.iso` alone doesn't say which console).
- **A file must fit its folder's console.** E.g. a `.gba` file in a `PSP` folder is **not listed** any more. Move it to the right folder.
- **Nothing is ever written into your ROM folder.**

**PS1 games — the rules**

| Works | Not yet |
|---|---|
| **One `.chd` or `.pbp` file per game** | `.bin` + `.cue`, `.iso` |
| In a **PS1** folder (or `ps1`, `psx`, `PlayStation`) | `.zip` / `.7z` archives |
| Or a file named with **(PS1)**, e.g. `Game (Europe) (PS1).chd` | Multi-disc games and disc swap |
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

## Browser player

- Open the **Shoalcade** link in Jellyfin's menu, or go to **`https://<your-server>/shoalcade/`**.
- It uses your Jellyfin web login. No extra sign-in.
- Plays **GB, GBC, GBA, DS and PS1**. PSP and 3DS are phone only.
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
- **Shared saves**: the phone and the browser use the same saves, with older versions kept.
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

**Offline?** The app says **You're offline**. Keep playing. Upload when you're back.

**Before uninstalling the app:** upload every save (**Push all**). Uninstalling deletes everything on the phone.

---

## Problems?

| Problem | Try |
|---|---|
| No games after a scan | Check the folder layout above — a file whose type doesn't fit its folder's console is skipped. Check **Dashboard → Logs** for Shoalcade lines |
| App shows a red banner | The plugin is too old for the app. Update the plugin |
| Login fails | Use the **HTTPS** address. Try **Log in with Quick Connect** |
| No box art | Wait for the next scan. Some games have no libretro art |

**Please report bugs on GitHub** — not to the Jellyfin team.
