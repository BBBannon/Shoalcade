# ShoalcadePlayer control protocol (version 1)

How the Shoalcade shell (`Shoalcade.exe`) and the player (`ShoalcadePlayer.exe`) talk. Plan: `docs/plan-windows.md` §4.2.

---

## Rules

- **One JSON object per line**, UTF-8, `\n` at the end (a `\r` before it is ignored). Every object has a `"type"`.
- **Shell → player:** the player's **stdin**. **Player → shell:** the player's **stdout**.
- **stdout carries only protocol messages.** At start-up the player moves the real stdout to a private descriptor and points descriptor 1 at stderr, so a core that `printf`s can't break the protocol. Logs go to **stderr** (and `--log FILE`).
- **The player never sees the Jellyfin token.** It only works on local files and paths the shell gives it.
- **Paths** are plain strings in the platform's form (Windows: `C:\Users\…`). Any field not listed is ignored.
- **Unknown message** → `error {fatal:false}`; the game carries on.
- **stdin closes** (the shell is gone) → the player writes the save and closes (`closing {reason:"shellGone"}`). Not when started with `--start FILE`.

## Start-up order

```
player: hello
shell:  start {...}
player: saveLoaded | saveRefused      (not for folder-save consoles)
player: ready {...}
player: cheatsApplied                 (if start had cheats)
player: firstFrame                    (ends the loading bar)
player: snapshotLoaded                (if start had a snapshot)
...
player: closing {reason}
player: saveChanged                   (if the save changed since the last write)
player: playSeconds
player: stats
player: exited {code}
```

If `start` fails: `error {fatal:true, message}`, `closing {reason:"error"}`, `exited {code:1}`.

---

## Shell → player

### `start`

| Field | Type | Need | Meaning |
|---|---|---|---|
| `console` | string | **yes** | Server console key: `gb`, `gbc`, `gba`, `nds`, `psx`, `n64`, `psp`, `3ds`; Windows only (D218): `gc`, `wii`, `ps2` (folder saves, 4 players, coarse rewind (P10); PS2 plays only with LRPS2 or PCEE2, any other core is refused). See "GameCube, Wii, PS2" below |
| `core` | string | **yes** | Path of the core library (`mgba_libretro.dll`, …) |
| `rom` | string | **yes** | Path of the game file |
| `saveFile` | string | one-file saves | The in-game save (`<id>.sav`). Missing file = no save yet. Not given = saving off |
| `clockFile` | string | no | GB/GBC clock cartridges: the 48-byte clock beside the save (`<id>.clock`, same as the phone's `GbClock`) |
| `saveFolder` | string | **PSP, 3DS** | Folder the core writes its own save files into (the memory stick). Becomes the core's save directory. 3DS: Azahar keeps everything under `<saveFolder>\Azahar\`; the game's save is `Azahar\sdmc\Nintendo 3DS\` (the shell zips only that, D158) |
| `systemDir` | string | no | Core system folder (PPSSPP assets go in `PPSSPP\`). Default: `<core folder>\system` |
| `coreSaveDir` | string | no | Save directory for one-file-save cores (they rarely write there). Default: `<systemDir>\coresaves` |
| `statesDir` | string | no | Where `snapshot.save {slot}` and the quick-save key write (`slot<N>.state` + `.png`) |
| `screenshotDir` | string | no | Where the screenshot key writes (`<title>_<date>.png`) |
| `title` | string | no | Game name for the window title and screenshot names |
| `options` | object | no | Core options `{key: value}`. Console safety options (table in `src/forced_options.cpp`) can't be changed: each refused key gets a `warning` |
| `controls` | object | no | `{keyboard, gamepad, gamepads, hotkeys}`, same shape and names as the browser's `prefs[console].controls` (see Controls). `gamepads` = `{model: {button: [...]}}`: one controller model's own buttons, as the menu changes them (`controlsChanged`) |
| `controls.pressureCross` | bool | no | P4b (PS2; consoles with analogue buttons): the right trigger also presses Cross with its real level (light presses); R2 moves to the right stick click, R3 has none |
| `controllers` | array | no | Wii only: each port's Wii controller, `["nunchuk", "remote", ...]` (`remote`, `sideways`, `nunchuk`, `classic`; default `remote`). The shell remembers it per game. Unknown: `warning`, the Wii Remote is used |
| `layout` | object | no | DS: `{ds: "side"\|"stack"\|"big", swap: bool}`. 3DS: `{n3ds: "stacked"\|"single"\|"large"\|"side", flip: bool}` (default stacked, no flip; an unknown name keeps the default with a `warning`). See "3DS" below |
| `display` | object | no | `{integerScale: bool, smooth: bool, fullscreen: bool}` |
| `snapshot` | string | no | Start from this state file (loaded right after the first frame) |
| `cheats` | array | no | Same as `cheats.set` (with `id` and `name`, so the menu can show and switch them) |
| `ff` | object | no | `{speed: 1-4}` the fast-forward speed (fast-forward itself starts off) |
| `rewind` | object | no | `{enabled: bool, mb: 4-1024 (default 64), every: frames between states (default 1, PS1 2), maxHitchMs (gc/wii/ps2: default 0 = two frames)}`. GB/GBC/GBA/PS1, and gc/wii/ps2 as coarse rewind (P10: `every` is always 60, at least 256 MB). Without it, rewind starts **off** and the menu's Rewind switch (or `rewind.enable`) turns it on for the session with the same defaults (one rule per console: PS1 every 2nd frame, gc/wii/ps2 every 60, the rest every frame). **The Windows app does not send it** (batch W, 02/11/2026): rewind starts off in every game, as fast-forward does, and the player's defaults already give each console its right rewind |
| `volume` | number | no | 0-1 (default 1) |
| `muted` | bool | no | App mute |
| `saveIntervalSeconds` | number | no | Save write timer, default **30** (the phone's rule). 1-600 |
| `canUpload` | bool | no | Default true. False = no "Close Game (and Upload)" in the menu (a game no longer in the library, phone F9) |
| `snapshotsAllowed` | bool | no | Default true. False = no Snapshots section (link session, or off in the server settings) |
| `speedAllowed` | bool | no | Default true. False = no Speed section (link session) |
| `cheatsAllowed` | bool | no | Default true. False = no Cheats section (link session) |
| `rewindAllowed` | bool | no | Default true. False = no Rewind switch in the menu |
| `slots` | array | no | What the menu shows for the snapshot slots, same as the `slots` message. Without it the player shows what is in `statesDir` |
| `autoSnapshotOnClose` | bool | no | Default false. True = write the **Automatic** snapshot (`slot "auto"`) as the game closes (any reason but `core`), before `closing` |
| `link` | object | no | **Link play** (GBA, DS): `{port, role, token, cable, dsMac, peer, connectTimeoutMs}`. See "Link play" below |
| `check` | object | no | **3DS check** (3DS), **GameCube / Wii check** (D222 D-7): `{warmUp: 0-120 s (default 10), measure: 1-300 s (default 30), targetFps (optional: the scene's own frame rate, echoed in `checkResult`; default the core's)}`. See "3DS" and "GameCube, Wii, PS2" below |

### Link play (`start.link`)

The shell does all the networking (finding the other player, the PIN handshake D133, the internet relay D176, Celio Link D177) and gives the player a ready local stream, as the phone's Kotlin gives its link shim a connected socket.

| Field | Type | Need | Meaning |
|---|---|---|---|
| `port` | number | **yes** | The player connects to `127.0.0.1:<port>` (only ever this PC) before the game loads. Can't connect = fatal `error`, exit 1 |
| `role` | string | **yes** | `"host"` (libretro netpacket client 0, the link master) or `"join"` (client 1) |
| `token` | string | no | Written first as `<token>\n` so the shell knows this is its player. Printable ASCII, no spaces, ≤ 256 |
| `cable` | bool | no | GBA: `gpsp_serial` = `mul_poke` (link cable mode) instead of `auto`. The host decides |
| `dsMac` | string | no | DS: `melonds_mac_address_mode` = this `xx:xx:xx:xx:xx:xx` (the phone's "different DS wireless ID") |
| `peer` | string | no | The other player's name, for the corner line ("Linked to …") |
| `connectTimeoutMs` | number | no | Default 10000 |

After the token line the stream carries exactly what the phone's `linkshim.c` socket carries: every packet, both ways, as **u32 big-endian length (1–65536), then the packet**. The rules are linkshim.c's (and RetroArch's): host `start(0)` then `connected(1)`, joiner `start(1)`; the session starts on the first frame; each frame reads the stream, hands each packet to the core, `poll()`, `retro_run`, then sends what the core queued; a bad length or a closed stream is "lost" (the core is told on the next frame).

GBA link uses **gpSP** (the shell passes its core), with `gpsp_bios` = `builtin` and `gpsp_serial` locked for the session. As on the phone, a link session has **no snapshots, cheats, fast-forward, rewind or pause** (the other side wouldn't follow): those messages, keys and menu sections get a `warning` or are gone, and the menu doesn't pause the game. The top corner shows the link (the phone's words: "Linked to … · n in · n out", "Starting link...", "Link lost", …) and, once, "The link stopped. You can keep playing; save, quit and link again to retry."

### Game control

| Message | Fields | What happens |
|---|---|---|
| `pause` | — | Game stops, sound stops, **save written if changed**, `paused {by:"shell"}` |
| `resume` | — | `resumed {by:"shell"}`. Closes the menu first if it's open |
| `menu` (or `menu.open`) | — | Opens the pause menu (pauses: `paused {by:"menu"}`, then `menuOpened {by:"shell"}`) |
| `menu.close` | — | Closes it: `menuClosed`, then `resumed {by:"menu"}` unless the game was paused before it opened |
| `close` | — | Writes the save and quits: `closing {reason:"shell"}` … `exited` |
| `restart` | — | Restarts the game (`retro_reset`), as the menu's Restart but without asking: the save is written first (`saveChanged {reason:"restart"}` if it changed; a folder save is the core's own, written as it goes), rewind is emptied, then `restarted {by}`. Link play / the 3DS check: a `warning`, nothing happens |
| `ff` | `on: bool`, `speed: 1-4` | Fast-forward on/off, speed (capped by the console, 4 everywhere for now: D183). Sound is muted while fast. Reply `ffChanged {on, speed, by}` |
| `rewind` | `on: bool` | Hold rewind (like holding the key). Needs rewind switched on (`start.rewind.enabled` or `rewind.enable`) |
| `rewind.enable` | `on: bool` | Switch rewind (the state recording) on or off, as the menu's Rewind switch. Reply `rewindChanged {enabled, by}`, or a `warning` where it isn't allowed |
| `mute` | `on: bool` (default true) | App mute. Reply `muteChanged {on, by}` |
| `volume` | `level: 0-1` | Reply `volumeChanged {level, by}` |
| `disc.set` | `index` (0-based) | P12: put that disc in the drive (games on more than one disc: the shell starts them with an `.m3u`). Reply `discChanged`; no such disc: `warning` |
| `controller.set` | `port` (0-3), `controller` (as `controllers`) | Wii only: the controller on a port, as the menu's Wii controller row. Reply `controllerChanged`; anything else: `warning` |
| `layout` | DS: `ds`, `swap`. 3DS: `n3ds`, `flip` (each optional: the other is kept) | Screen layout. Reply `layoutChanged {ds, swap, by}` / `{n3ds, flip, by}`. Unknown name: `error {fatal:false}`, nothing changes |
| `display` | `integerScale`, `smooth`, `fullscreen` (all optional bools) | Picture settings. Reply `displayChanged {fullscreen, integerScale, smooth, by}` |
| `options.set` | `options: {key: value}` | Change core options while running. Reply `optionsChanged {applied: [keys], refused: [keys]}` (refused = locked or not a string) |
| `save.flush` | — | Write the save now if changed. Reply `saveFlushed {enabled}` |
| `snapshot.save` | `slot` (number or string, default 1), `path` (optional) | Writes the state atomically to `path`, or `<statesDir>/slot<slot>.state`, plus a PNG of the last frame beside it. Reply `snapshotSaved` |
| `snapshot.load` | `path`, or `slot` (`"auto"`, 1-3: the slot's file) | Loads a state. Reply `snapshotLoaded {path, by}` or `error`. Before the first frame it's held until then |
| `slots` | `slots: [{slot, when, png, path, loadable, note, empty}]` | What the menu shows for each slot (`"auto"`, `"1"`-`"3"`). `when` = the words for the time (e.g. `"31/10/2026 18:45"`), `png` = picture on this PC, `path` = the state file the menu's **Load** uses, `loadable: false` = made by a different core (shown, never loaded), `note` = extra words, `empty: true` = nothing there. Slots not listed keep what they had |
| `cheats.set` | `cheats: [ "CODE" \| {code, enabled, id, name} ]` | `retro_cheat_reset`, then each enabled code from index 0 (the phone's CheatApply rule). Reply `cheatsApplied {count}`. `id` (default: the list position) and `name` (default: the code) are for the menu |
| `cheat.toggle` | `id`, `on` | Switch one cheat, as the menu's switch. Reply `cheatToggled`, then `cheatsApplied` |
| `screenshot` | `path` (optional) | PNG of the last frame to `path` or `<screenshotDir>`. Reply `screenshot` |
| `link` | — | Link session: replies `linkStatus`. Not a link session: `error {fatal:false}` (links are set up in `start.link`) |
| `link.note` | `text` | Link session: this text replaces the corner line while the link is active (Celio Link: the shell's room status). `""` = back to the player's own line |

### The pause menu by message (the shell and the tests drive it this way)

| Message | Fields | What happens |
|---|---|---|
| `menu.pick` | `id` | Exactly as picking the row with the mouse, keyboard or controller (works with the menu closed too). Ids below. An id not in the menu here → `error {fatal:false}` |
| `menu.nav` | `key`: `up`, `down`, `left`, `right`, `next` (R1), `previous` (L1), `pick`, `back` | One key in the open menu. The first move only shows the highlight (on Resume) |
| `menu.state` | — | Reply `menuState {open, focus, showFocus, armed, drawn, listening, menuButton: {shown, rect}, sections: [{id, title, items: [{id, kind, label, hint, on, value, picture, rect}]}]}`. `kind`: action, choice, toggle, slider, info, bind, heading, note, slot. `rect` = where the row is drawn (window x, y, w, h), only while open and on screen. `listening` = the Controls row waiting for a press (`""` = none). `menuButton` = the ☰ button (shown now, and its place in the window) |

**Menu item ids** (the phone's GameMenu.kt ids where it has them):

| Section | Ids |
|---|---|
| Game | `resume`, `screenshot`, `restart` (needs a second press within 5 s, "Press Restart again to restart the game."; not in link play), `close`, `close_upload` |
| Snapshots | `snap.save.1` … `snap.save.3`, `snap.load.auto`, `snap.load.1` … `snap.load.3` (slot cards `snap.slot.*` can't be picked). **Load** and **Save over an existing one** need a second press within 5 s: the first gives `menuConfirm {id}` |
| Speed | `speed.ff` (on/off), `speed.x2` `speed.x3` `speed.x4`, `speed.rewind` (on/off) |
| Cheats | `cheat.<id>` (on/off) |
| Sound | `sound.mute`, `sound.volume` (slider: `menu.nav` left/right ±10 %) |
| Display | `screen.window`, `screen.full`, `display.integer`, `display.smooth`; DS `ds.side` `ds.stack` `ds.big` `ds.swap`; 3DS `3ds.stacked` `3ds.single` `3ds.large` `3ds.side` `3ds.flip` (labels: the phone's "Top and bottom", "One screen (Flip to swap)", "Big screen, small screen", "Side by side", "Swap the two screens") |
| Controls | `control.<button>` (e.g. `control.A`, `control.StickUp`) and `shortcut.<name>` (e.g. `shortcut.pause`): picked = waiting for the new key or controller button (`controlsListening {id}`; see Controls). `shortcut.menu` (Esc) can't be changed. `controls.reset.keys`, `controls.reset.pad` (only with a controller), `controls.reset.shortcuts` |

### Test hooks (only with `--test-hooks`)

| Message | Fields | Reply |
|---|---|---|
| `debug.pokeSave` | `offset`, `hex` | `debug.result {what, ok}` — writes bytes into the core's save memory |
| `debug.dumpSave` | `path` | `debug.result {what, ok, error}` — writes the core's save memory to a file |
| `debug.readMemory` | `id` (RETRO_MEMORY_*, 2 = system RAM), `offset`, `length` (≤ 4096) | `debug.result {what, ok, hex, size}` |
| `debug.button` | `port`, `button` (Shoalcade name, e.g. `"A"`), `on` | `debug.result {what, ok}` — holds a button |
| `debug.stats` | — | `stats` |
| `debug.frame` | — | `frame` |
| `debug.windowShot` | `path` | `debug.result {what, ok, path, width, height, nonBlack, game, window, menu}` — the next drawn window (not headless), menu included, as a PNG |
| `debug.key` | `key` (SDL key name: `Escape`, `Down`, `Tab`, `Return`…), `shift` | `debug.result` — a real key press and release through SDL's event queue |
| `debug.motion` | `x`, `y` (window coordinates) | `debug.result` — the pointer moved there through SDL's event queue (no click) |
| `debug.click` | `x`, `y` (window coordinates) | `debug.result` — a left click through SDL's event queue (pointer moved there too) |
| `debug.pad` | `attach: true` first; then `button` (`south east west north back guide start l1 r1 l3 r3 up down left right`), `on`; or `axis` (`lx ly rx ry l2 r2`), `value` (-1..1; a trigger: -1 released, 1 fully pressed) | `debug.result` — SDL's virtual controller (Xbox-style labels: south = A). Controller events also reach a hidden window with `--test-hooks` |
| `debug.mouse` | `x`, `y` (window coordinates), `down`; or `off: true` | `debug.result {what, ok, game: [x, y, w, h], touch, frame}` — the mouse held there (instead of the real one, also headless) until `off`. 3DS: `touch` = `{x, y}` on the bottom screen (3DS pixels) and `{frameX, frameY}` in the core's frame, or null when that point isn't on the bottom screen; `frame` = the layout's frame size |
| `debug.pointer` | — | `debug.result {what, ok, x, y, down, pressedReads}` — the pointer as the core last read it (`pressedReads` = times it read "pressed" as 1) |

Without `--test-hooks` every `debug.*` message is refused with `error {fatal:false}`.

---

## Player → shell

| Message | Fields | When |
|---|---|---|
| `hello` | `player` (version), `protocol` (1) | At start, waiting for `start` (not with `--start FILE`) |
| `ready` | `console`; `core {name, version, path}`; `video {width, height, aspect, fps, hardware}`; `audio {sampleRate, active}`; `save {enabled, why}`; `snapshots` (bool); `rewind` (bool); `menu {drawn, keys}` (drawn = false when headless or ImGui couldn't start: the menu still works by message); `ports`; `link` (the `linkStatus` fields in a link session, else null); `layout` (DS `{ds, swap}`, 3DS `{n3ds, flip}`, else null); `check` (`{warmUp, measure}` in a 3DS check, else null); `controllers` (Wii: each port's controller, else null); `discs` (`{index, labels}` when the core has 2+ discs, else null); `options` (list of `{key, value, default, values, locked, visible}`) | Game loaded, window open. Use `core.name` + `core.version` to tag snapshots (never load one into a different core) |
| `firstFrame` | `n` | The first picture is up |
| `saveLoaded` | `path`, `loaded` (false = no file yet), `coreSize`, `sha256` (of the core's save memory after loading) | After start, one-file saves |
| `saveRefused` | `path`, `reason` (`wrongSize` \| `coreSize` \| `noSaveMemory` \| `unreadable`), `message`, plus `fileSize` / `expected` / `coreSize` | The save wasn't given to the core. **Saving is off this session and the file is never touched** |
| `saveChanged` | `sha256`, `path`, `size`, `reason` (`timer` \| `pause` \| `close` \| `shell`) | The save file on disk was just replaced (atomically). The `.clock` file, if any, is written in the same step |
| `snapshotSaved` | `slot`, `path`, `png` (empty if the picture failed), `size` and `sha256` (of the file), `coreSize` (the core's state; bigger than `size` when the file is compressed: GameCube, Wii, PS2), `by` | From the menu too: the shell uploads it to the slot on the server |
| `snapshotLoaded` | `path`, `slot` (when known), `by` | |
| `screenshot` | `path`, `width`, `height` | |
| `cheatsApplied` | `count` | Codes handed to the core (libretro can't say if a code was accepted) |
| `paused` / `resumed` | `by` (`shell` \| `user` \| `menu`) | Also from the keyboard shortcuts |
| `menuOpened` / `menuClosed` | `by` (`shell` \| `user`) | The pause menu opened / closed (Esc, controller Guide or Start + Select, a click, or a message) |
| `menuConfirm` | `id`, `by` | A row that needs a second press (Load, Save over) was pressed once |
| `menuState` | see `menu.state` | |
| `restarted` | `by` | The game was restarted (menu Restart, or `restart`) |
| `controlsListening` | `id` | A Controls row is waiting for a key or controller button |
| `controlsChanged` | `console`, `by`, and what changed: `keyboard` and `hotkeys` (whole maps, or `null` = back to the defaults), `gamepads: {model: map or null}` + `controller` (its name) | A button, key or shortcut was changed (or reset) in the menu. **The shell keeps it for this console** (the keyboard and shortcuts per console; a controller's map per console and controller model, the phone's rule) and sends it back in `start.controls` |
| `ffChanged` | `on`, `speed`, `by` | `by` everywhere below: `user` (menu, keys, controller), `shell`, or `close` |
| `rewindChanged` | `enabled`, `by` | |
| `muteChanged` | `on`, `by` | |
| `volumeChanged` | `level` (0-1, two decimals), `by` | |
| `displayChanged` | `fullscreen`, `integerScale`, `smooth`, `by` | Also when F / F11 / Alt+Enter changes full screen, or the window manager leaves it |
| `discChanged` | `index`, `count`, `label`, `by` | P12: a disc change (menu "Change disc" or `disc.set`). The shell may remember it per game and send `disc.set` after `ready` next time |
| `controllerChanged` | `port`, `controller`, `device` (the libretro device id handed to Dolphin), `by` | A Wii controller change (menu or `controller.set`); the shell remembers it for this game |
| `cheatToggled` | `id`, `index` (in the shell's list), `on`, `count` (cheats on now), `by` | A switch in the menu. **The shell owns the cheat list**: it saves the change; `cheatsApplied` follows |
| `layoutChanged` | `ds`, `swap`, `by` (3DS: `n3ds`, `flip`, `by`) | Also from the 3DS Flip key (Tab, controller left stick click): `by: "user"`. The shell keeps the choice for the next game |
| `checkTick` | `second`, `phase` (`warmUp` \| `measure`), `left` (seconds), `fps` (frames shown that second) | 3DS check, once a second from the first picture |
| `checkResult` | `done` (true = ran to the end), `reason` (`done` \| `cancelled` \| the closing reason), `fps` (the measured seconds), `warmUp`, `measure`, `targetFps` | 3DS / GameCube / Wii check, just before `closing {reason:"check"}`. The shell works out the result (N3dsCheck.verdict) |
| `optionsChanged` | `applied`, `refused` | |
| `saveFlushed` | `enabled` | |
| `playSeconds` | `n` (seconds since the last report), `total` (this session) | Unpaused, focused play. Every 60 s, on pause and on close. The shell adds `n` up |
| `coreMessage` | `text` | A note the core wants shown (same text within 5 s is sent once) |
| `warning` | `message` | Something the person may want to know; the game carries on |
| `linkStatus` | `state` (`off`, `waiting`, `active`, `lost`, `refused`, `noSupport`), `code` (0-5, the phone's LinkNative numbers), `role`, `cable`, `received`, `sent` (packets), `bytesReceived`, `bytesSent`, `protocol` (the core's netpacket version), `why` | Link session: whenever the state changes, and in reply to `link`. `ready.link` and `stats.link` carry the same |
| `error` | `message`, `fatal` | `fatal:true` = the player is closing |
| `closing` | `reason`: `shell` \| `window` \| `shellGone` \| `core` \| `menu` \| `frames` (test limit) \| `check` (3DS check ended or stopped) \| `error`; with `menu`: `upload` (true = "Close Game (and Upload)") | Before the final save write. The shell does the upload |
| `stats` | `audio {active, driver, coreRate, framesPushed, framesDroppedFull, framesDroppedFastForward, underruns, minQueuedMs, maxQueuedMs, minRatio, maxRatio}`, `shownFrames`, `coreFrames`, `rewindBytes`, `rewindSteps`, `rewindEvery` (frames between rewind states) | At close (and `debug.stats`). For logs and tests |
| `frame` | `n`, `coreFrames`, `width`, `height`, `hash` (FNV-1a 64 of RGB), `nonBlack`, `pixels` | `--frame-hashes K`, `debug.frame` (tests) |
| `exited` | `code` | Last line. 0 = normal, 1 = couldn't start, 2 = bad command line / no start |

---

## Controls

Same names as the browser player (`server/…/Pages/play.js`, `play-ui.js`), so a setup can be shared.

- **Buttons:** `Up Down Left Right A B X Y L R L2 R2 L3 R3 Start Select`; N64: `A B Z L R Start Up Down Left Right StickUp/Down/Left/Right CUp/CDown/CLeft/CRight`; PSP and 3DS add `StickUp/Down/Left/Right`.
- **`keyboard`:** `{button: ["KeyX", …]}`, `KeyboardEvent.code` names (`KeyA`…`KeyZ`, `Digit0-9`, `Arrow*`, `Enter`, `Space`, `ShiftRight`, `Backspace`, `F1-F12`, `Numpad*`, …).
- **`gamepad`:** `{button: ["east", …]}`: `south east west north l1 r1 l2 r2 select start l3 r3 up down left right home`, `b<n>` (standard index), `a<n>+` / `a<n>-` (axis 0-3 = left X, left Y, right X, right Y).
- **`gamepads`:** `{model: {button: [...]}}`, one controller model's whole map (the phone keeps one per controller model, D126). `model` = the USB vendor and product, `"045e:0b13"`, or the controller's name when the driver gives none. It is used instead of `gamepad` for every controller of that model.
- **`hotkeys`:** `{pause, ff, quickSave, quickLoad, screenshot, fullscreen, rewind, menu}`, and for the 3DS `flip`. `menu` is always Esc.

| Default | Keyboard | Controller (by position, Nintendo style) |
|---|---|---|
| D-pad | Arrows | D-pad, left stick |
| A / B / X / Y | X / Z / S / A | east / south / north / west |
| L / R / L2 / R2 | Q / W / E / R | shoulders / triggers |
| Start / Select | Enter / Right Shift, Backspace | Start / Back |
| N64 | Arrows = stick, Y G H B = D-pad, I J K L = C buttons, X = A, Z = B, A = Z | left stick = stick, right stick = C, south = A, west = B, triggers = Z. One controller per player (ports 1-4) |
| Shortcuts | P pause, Space hold = fast-forward, F2 quick snapshot, F4 load it, F9 screenshot, F / F11 / Alt+Enter full screen, ` (backquote) hold = rewind, **Esc = menu**; 3DS: **Tab = Flip** | **Guide button, or Start + Select together = menu**; 3DS: **left stick click = Flip** |

- A key used for a shortcut never reaches the game.
- **In the menu:** Up / Down move, Tab / Shift+Tab or Page Down / Page Up jump a section, Enter or Space pick, Left / Right change Volume, Esc or Backspace close. Controller: D-pad (held: repeats) or left stick, L1 / R1 jump a section, **A picks and B goes back by the button's own label** (Xbox A = bottom, Nintendo A = right, PlayStation Cross / Circle), Guide or Start + Select closes. Mouse: click a row; a click beside the panel closes it.
- After the menu closes, the game gets no buttons until every key, button and the mouse button are let go (the one that closed it mustn't reach the game).
- **Changing the buttons** (the menu's Controls; the phone's ControlsScreen and the browser's rules): pick a game button, then press the key or controller button (or push a stick or trigger) for it; for a shortcut, a key. One input does one thing: it is taken off any other button or shortcut first ("A is now M. Taken from B (now has no key)."). A direction keeps its stick when it gets a button, and keeps its buttons when it gets a stick. **Esc and the Guide button cancel** and can never be given to a button (they open the menu); a click anywhere cancels too, and so does 10 s with nothing pressed ("Nothing pressed, so nothing changed."). A trigger already pushed in when the wait began isn't a press. The keyboard is player 1's; a controller's map is for its model (N64, GameCube, Wii, PS2: every player with that model). The controller changed is the one pressed; the list shows the one used last.
- **The ☰ button** (mouse-only use, as the browser player's toolbar rail): top-left corner, shown for 2.5 s after the mouse moves over the window and while the mouse is on it; a click opens the menu (that press never reaches the game as a touch). Not drawn headless, with the menu open, or in the 3DS / GameCube / Wii check.
- **Mouse:** `RETRO_DEVICE_POINTER` over the game picture (DS: melonDS DS's touch mode maps it to the bottom screen; 3DS: Azahar maps it through the current layout to the bottom screen, wherever that is drawn). Left button = touching.

---

## 3DS (phase 6, `docs/windows/phase6-3ds.md`)

- **Core:** our Azahar 2126.2 build (key blob OFF), OpenGL hardware rendering. Locked options: `citra_graphics_api` OpenGL, `citra_use_libretro_save_path` "LibRetro Default" and `citra_use_virtual_sd` enabled (the save stays inside `saveFolder`), `citra_enable_touch_touchscreen` and `citra_use_cpu_jit` enabled. Upscaling is `citra_resolution_factor` (default 1, the shell may change it). Azahar has no cheats (`retro_cheat_set` is empty): the shell sends none.
- **Layouts** (the phone's N3dsLayout; frame = what the core draws at resolution factor 1):

| `n3ds` | `flip` false: frame, bottom screen | `flip` true: frame, bottom screen |
|---|---|---|
| `stacked` "Top and bottom" | 400x480, at 40,240 | 400x480, at 40,0 (bottom screen on top) |
| `single` "One screen (Flip to swap)" | 400x240, not shown (no touch) | 320x240, the whole picture |
| `large` "Big screen, small screen" | 1920x960, small at 1600,720 | 1680x960, big at 0,0 (1280x960) |
| `side` "Side by side" | 720x240, at 400,0 | 720x240, at 0,0 |

- **Flip** = Azahar's "Swap Screens" (`citra_swap_screen` Bottom). The 3DS has no stick clicks, so the controller's L3 never reaches Azahar (it would swap the screens behind the player's back): it is Flip instead.
- **3DS check** (`start.check`): the shell gives the scene (`check.3dsx`) as `rom` and a check folder as `saveFolder`, with `<saveFolder>\Azahar\sdmc\shoalcade\check.txt` = the load ("1200 200000", as the phone). No menu (Esc / Guide stops the check: `checkResult {done:false, reason:"cancelled"}`), no pause, snapshots, cheats or fast-forward (each refused with a `warning`). The corner shows the phone's words ("3DS check: warming up... 8 s", "3DS check: measuring... 12 s · 60 fps"). Advisory on Windows: the shell shows the result and never locks anything.

## GameCube, Wii, PS2 (Windows only, D218)

- **Cores:** GameCube and Wii = Dolphin libretro; PS2 = LRPS2 (`ParaLLEl PS2`) or PCEE2, the person's choice (D222 D-3). The player names the core before applying its rules and refuses a PS2 start with any other core.
- **Locked options** (a change gets a `warning`): Dolphin `dolphin_osd_enabled=disabled`, `dolphin_renderer=Hardware`, `dolphin_skip_gc_bios=enabled`, `dolphin_cheats_enabled=enabled` (Shoalcade's cheats patch), `dolphin_cheats_import=disabled`; Wii also `dolphin_ir_mode=2` (the mouse is the pointer) and `dolphin_wiimote_default_device=frontend` (the player sets each port's controller). LRPS2 `pcsx2_shared_memory_cards=disabled` (the game's own card `<saveFolder>\<game>.ps2`), `pcsx2_renderer=OpenGL`; PCEE2 `pcsx2_renderer=opengl` (its card is always the game's own: core patch). Picture size (`dolphin_efb_scale`, `pcsx2_upscale_multiplier`) stays the shell's choice.
- **OpenGL:** the newest core-profile version the driver gives (4.6 down to the core's request), not exactly the core's 3.3.
- **Controls:** the right stick is the second stick (GC C-stick; keys I J K L). Triggers are analogue (`RETRO_DEVICE_INDEX_ANALOG_BUTTON`); a trigger clicks its digital button only past 90% of its travel. GameCube by position: bottom = A, left = B, right = X, top = Y, right shoulder = Z. Wii: shake = right trigger.
- **Menu:** every section (fast-forward; a Rewind row for coarse rewind, below, D-8 kept); Controls has a **Wii controller** choice for player 1 (Wii).
- **Closing:** the game unloads before the GL context goes (the cores flush GPU work and memory cards on unload).
- **Snapshots (P8):** written compressed: `SCSTATE1` + the raw size (8 bytes, little-endian) + one zstd frame (our homebrew: 93 MB -> 0.13 MB GameCube, 108 MB -> 0.32 MB Wii). Every other console's file stays the core's raw bytes. Loading accepts both, for every console.
- **GameCube / Wii check (D-7):** `start.check` with our own scene (`tools/gc-check`: `gc-check.dol` / `wii-check.dol`) as `rom`, any save folder. Same rules as the 3DS check (no menu, pause, snapshots, cheats or speed; Esc stops it); the corner says "GameCube check: ..." / "Wii check: ...". Under Dolphin the GameCube scene runs at PAL 50 fps and the Wii scene at 60: pass `targetFps` 50 / 60. Advisory: the shell shows the result and never locks anything.
- **Coarse rewind (P10, D-8 kept):** with `rewind.enabled`, a state every 60 frames (about a second), packed as changes on another thread, ~30 kept (at least 256 MB); holding rewind steps back one of them four times a second. The game waits only for the core's own snapshot: measured, logged and in `stats.rewindCaptureMs` `{steps, average, most}`. If steps 2-6 average more than `rewind.maxHitchMs` (default two frames), rewind goes off for the session with a `warning` that says why, and the menu shows that line instead of the Rewind row. This laptop (Core Ultra 7 265U, our GameCube homebrew): Dolphin's snapshot alone takes 25-100 ms, so it goes off here.
- **Change disc (P12, any console whose core has a disk control interface: Dolphin, LRPS2, PCEE2, PCSX ReARMed):** start a multi-disc game with an `.m3u` as `rom`; the Game section gets a "Change disc" choice. `debug.input` `{port, device, index, id}` (test hook) returns what the core would read.
