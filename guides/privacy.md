# Privacy

**In short:** your games, saves and login stay between **your phone** and **your Jellyfin server**.
The only third party contacted is **libretro**, by your **server**, for game names and box art.
**No analytics. No tracking. No ads. No accounts with us.**

---

## What leaves your server

| Where to | What is sent | When | Why |
|---|---|---|---|
| **GitHub** (`raw.githubusercontent.com`, libretro-database) | Your server's IP address. The **console list file names** for consoles you have games for (e.g. "Nintendo - Game Boy Advance") | First scan, then a weekly "has it changed?" check | Official game names, year, developer, publisher, genre |
| **libretro thumbnails** (`thumbnails.libretro.com`) | Your server's IP address. The **console and official name of each game that has no picture** | During scans, one game at a time. A miss isn't asked again for 30 days | Box art |

- Every request says it comes from **Shoalcade** (in the User-Agent).
- **Not sent:** your ROM files, file hashes, saves, user names or Jellyfin login.
- Game matching happens **on your server**, using its own downloaded copy of the list.
- Box art is downloaded **once** and stored by Jellyfin. Players' browsers and phones never contact libretro.
- **Nothing is sent while JellyEMU is still installed** (Shoalcade's scanner is off then).

**Turn off box-art lookups:** **Dashboard → Libraries →** your games library **→ Manage library →** Book image fetchers **→ untick "Shoalcade box art (libretro)"**.

**Advanced (no outside contact at all):** set the environment variables `SHOALCADE_LIBRETRO_DATABASE_URL` and `SHOALCADE_LIBRETRO_THUMBNAILS_URL` to a mirror you host. Without a mirror, games keep their file names and have no box art.

---

## What leaves your phone

| Where to | What | When |
|---|---|---|
| **Your Jellyfin server only** (HTTPS) | Login, game list, game downloads, save uploads and downloads, your cheat codes, the app's version number | When you use the app online |
| **Other phones on the same Wi-Fi** | The name **"Shoalcade" + your phone model**, a check that you typed the host's **4-digit PIN** (the PIN itself is never sent), then game link data, phone to phone | Only during **Link play (same Wi-Fi)** |

- The app contacts **no other server**. Not us, not libretro, not Google.
- Box art comes from **your** server.
- The browser player and library pages can only talk to **your** server (their security policy blocks anything else). EmulatorJS's update check is switched off.
- **Patch a game** runs entirely in your browser: the game file and patch you pick are **never uploaded**, not even to your own server.
- **RPG Maker games can't go online**, on the phone or in the browser. A game's own web requests or updater fail as if you were offline.
- **Keep in browser** (big RPG Maker games) stores that game's files in **this browser only**, until you switch it off.

---

## Where your login is kept

| Place | How |
|---|---|
| **Phone app** | Your Jellyfin token is stored in **encrypted preferences** (key held in the **Android Keystore**). No password is stored. Android backup is off for the app |
| **Browser player** | Uses the login **Jellyfin web already saved** in that browser. Shoalcade stores no login of its own |
| **Both** | The token only travels in the request header, **never in a web address**, and is **never logged** |

**Log out** removes the token from the phone, and also ends the login on your Jellyfin server when the phone can reach it.
If the phone was offline when you logged out, remove it in **Dashboard → Devices** to cut it off.

### Phone app download (⚙ Settings → Phone app)

- The app file comes **from your own Jellyfin** (it's built into the plugin). Nothing is fetched from anywhere else.
- The **QR code** and the **Download** button use a **short-lived link**: random, valid **10 minutes**, **5 downloads** at most, kept only in the server's memory (a restart clears them).
- The link holds **no login, user name or account data**. Your Jellyfin token never goes in it. Anyone who got hold of it could only download the public app file.
- Jellyfin itself may write a request's web address (with that short-lived link) to its own log if something goes wrong. Shoalcade never logs it.
- An admin can turn the download off: **Offer the phone app download**.

---

## What an uninstall removes

### Uninstalling the phone app

**Removed:**
- Downloaded games, box art cache
- **Saves on the phone**, their backup copies, **snapshots** and cheat lists
- Your login

**Kept on the server:** every save you uploaded, with all its versions.

⚠️ **Upload first** (**Push all**). A save that never reached Jellyfin is lost.

### Uninstalling the server plugin

**Removed:** the plugin, the **Shoalcade** menu link, the **Play**/**Saves** buttons, the browser player.

**Kept** (on purpose, so a reinstall picks up where you left off):
- **Your ROM files** — never touched
- **Saves, snapshots, cheat codes and player settings** in Jellyfin's data folder: `<Jellyfin data>/shoalcade/` (**include it in your backups**)
- The downloaded game lists (`<Jellyfin data>/shoalcade/gamedb/`)
- The games library and box art Jellyfin has stored
- JellyEMU's old save files, if any (only ever read)

**To remove everything:** delete `<Jellyfin data>/shoalcade/` after uninstalling. ⚠️ **This deletes every user's saves.** Back it up first.

**Tip:** turn off **Keep games off Jellyfin's home page** *before* uninstalling. Otherwise each person re-ticks **Display in other home screen sections** in their own **Settings → Home screen**.

---

## Who can see what

- Every Shoalcade request needs a **Jellyfin login**.
- People see **only their own saves**, snapshots and cheat codes.
- Admins get a **storage view** (sizes, counts and names only). They can't open, download or delete other people's saves from it.
- Only games are served. Your other Jellyfin media is never exposed through Shoalcade.
