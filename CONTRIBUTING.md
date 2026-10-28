# Contributing to Shoalcade

**Thank you for helping.** Bug reports, fixes and ideas are all welcome.

---

## The ground rules

- **Your own games only.** Shoalcade never provides or finds ROMs, BIOS, firmware or keys.
- **Never attach or link** ROMs, BIOS files, firmware, keys or game saves — in issues, pull requests or chat. They'll be removed and the thread closed.
- **No piracy features.** Pull requests that add ROM sourcing, ROM-site scraping or similar will be closed.
- **Never post private details:** server addresses, Jellyfin tokens, passwords. Remove them from logs first.
- **Security problems** go privately — see [SECURITY.md](SECURITY.md), never a public issue.

---

## Questions and ideas

- **Chat and quick questions:** [Join us on Discord][discord]
- **Bugs:** open a GitHub issue with the **Bug report** template
- **Ideas:** open a GitHub issue with the **Feature request** template

---

## Making a change

1. **Open an issue first** for anything bigger than a small fix, so we can agree the approach.
2. **Keep it small and focused** — one change per pull request.
3. **Saves are precious.** Never replace a save without keeping a copy first. Conflicts must ask the player, never pick silently.
4. **Keep the wording consistent.** The phone app and the browser use the same words for the same things (e.g. **Use this save**, **snapshot**, **Keep Jellyfin's**). Match what's already there.
5. **UK English** in anything players see (colour, licence, centre).
6. **Server plugin:** every endpoint needs a Jellyfin login, and only ROM items are ever served. If the app and plugin API both change, change them in the **same pull request**.
7. **Check it builds** before you open the pull request:

```bash
cd android && ./gradlew assembleDebug
dotnet build server/Jellyfin.Plugin.Shoalcade -c Release
```

Build steps: see [Building from source](README.md#building-from-source).

---

## Licence

- Shoalcade is **GPL-3.0** ([LICENSE](LICENSE)).
- By sending a contribution, you agree it's released under **GPL-3.0** too.
- Adding a new library or emulator core? Say so in the pull request, with its licence and source, so the credits in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) stay correct.

<!-- Discord invite: change it here only. -->
[discord]: https://discord.gg/SRDBXwhre
