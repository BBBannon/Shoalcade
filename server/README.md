# Shoalcade server plugin

A **Jellyfin plugin** that replaces JellyEMU for the Shoalcade app. It gives the app a **secure, logged-in** way to list ROMs, download them and sync saves, and adds a **browser player** for the same library and saves.

- **Status:** **s0.2 dev 2** is the first build called **Shoalcade** (before that: JellyGames, D124): same plugin id, so Jellyfin treats it as an update; settings, saves, snapshots and game edits are copied over on its first start (the old `jellygames/` data folder is kept). The plan is in `docs/server-plugin.md`.
- **Language:** C# / .NET, built against **Jellyfin 12.3** (`targetAbi` 12.3.0.0).
- **Browser pages** (served at `/shoalcade/`): library, player (EmulatorJS, pinned and served by the plugin), saves manager and Settings (incl. the **phone app download**: the APK ships inside the plugin, D134). They share one save per game with the app, DS too (D91; an old separate DS browser list is merged in automatically, every version kept).

## Layout

```
server/
├── README.md                     ← this file
├── build.sh                      ← builds the install zip (--install = test-bed only)
├── emulatorjs.lock               ← pinned EmulatorJS packages + SHA-256
└── Jellyfin.Plugin.Shoalcade/   ← plugin project
    ├── Api/                      ← controllers (games, saves, snapshots, prefs, admin, pages)
    ├── Saves/ States/ Prefs/     ← plain-file stores (D47)
    └── Pages/                    ← browser pages: index/library, play/play-ui/play.js, saves, sc.css, api.js
```

## Installing (manual until v1.0)

The repo is **private**, so Jellyfin can't read a plugin manifest from GitHub. Until v1.0:

1. Build the plugin zip: `server/build.sh` (→ `dist/server/Shoalcade_s<ver>-dev<n>.zip` for a dev build, e.g. `Shoalcade_s0.2-dev2.zip`; `Shoalcade_s<ver>.zip` for a release). Version: `server/Directory.Build.props` (D88).
2. Unzip it into Jellyfin's `plugins/Shoalcade_<version>/` folder.
3. Restart Jellyfin.

After v1.0, switch to a proper plugin repository (a `manifest.json` URL) so updates show in the Jellyfin dashboard (D30).

## Rules (on top of the project `CLAUDE.md`)

- **Every endpoint needs a Jellyfin login**, and users only see their own saves.
- **Never modify or delete ROM files** on the server.
- **Never overwrite a save without keeping the previous version.**
- **Only serve ROM items**, never other media (the JellyEMU hole, D29). One deliberate exception: the phone app APK bundled in the plugin (`/shoalcade/app/Shoalcade.apk`, D134).
- Code reused from JellyEMU (MIT) keeps its credit; record it in `docs/licences.md`.
