#!/usr/bin/env python3
"""Shoalcade: writes THIRD_PARTY_LICENCES.txt, the licence text of every part bundled in the phone app and the plugin.
Part of Shoalcade, GPL-3.0-or-later.

    python3 -I tools/make_third_party_licences.py           rewrite THIRD_PARTY_LICENCES.txt
    python3 -I tools/make_third_party_licences.py --check   only check the committed file is up to date

The file is committed, so building needs no network: android/app/build.gradle.kts copies it into the APK (opened from
About -> Other licences) and server/build.sh copies it into the plugin zip next to LICENSE.txt.

Each part's own licence file(s) are read from its source at the exact commit that is shipped (a shallow, blobless,
sparse git fetch of just those files into SHOALCADE_LICENCE_SRC, default ~/deps/licence-src), from files vendored in
this repo, or from the pinned EmulatorJS npm tarball (checked against server/emulatorjs.lock). A file that is the same
as one of the full licence texts at the top is not repeated. Keep the commits here in step with docs/licences.md,
About.kt (Licences.parts) and THIRD_PARTY_NOTICES.md.

Needs: git, Python 3, network access to github.com and registry.npmjs.org (first run).
"""
import difflib
import hashlib
import io
import os
import re
import subprocess
import sys
import tarfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "THIRD_PARTY_LICENCES.txt")
SRC = os.environ.get("SHOALCADE_LICENCE_SRC") or os.path.join(os.path.expanduser("~"), "deps", "licence-src")
COMMON = os.path.join(ROOT, "windows", "installer", "licences", "common")  # Debian's standard texts, vendored

# Full texts, printed once at the top. (id, title, file)
STANDARD = [
    ("GPL-3.0", "GNU General Public License, version 3", os.path.join(ROOT, "LICENSE")),
    ("GPL-2.0", "GNU General Public License, version 2", os.path.join(COMMON, "GPL-2")),
    ("LGPL-2.1", "GNU Lesser General Public License, version 2.1", os.path.join(COMMON, "LGPL-2.1")),
    ("LGPL-3.0", "GNU Lesser General Public License, version 3", os.path.join(COMMON, "LGPL-3")),
    ("MPL-2.0", "Mozilla Public License, version 2.0", os.path.join(COMMON, "MPL-2.0")),
    ("Apache-2.0", "Apache License, version 2.0", os.path.join(COMMON, "Apache-2.0")),
]

# Git sources: key -> (repo, commit). Commits are the shipped ones (docs/licences.md).
REPOS = {
    "libretrodroid": ("https://github.com/Swordfish90/LibretroDroid.git", "8835c3098514390a271e36983957f7bb5f40abf1"),  # tag 0.14.0
    "mgba": ("https://github.com/libretro/mgba.git", "7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6"),
    "melonds": ("https://github.com/JesseTG/melonds-ds.git", "f394adbacb5722ee97c1b37c8064da9a25818310"),
    "gpsp": ("https://github.com/libretro/gpsp.git", "5819380c2ffb0900219d700a382ee68c464ebb99"),
    "ppsspp": ("https://github.com/hrydgard/ppsspp.git", "902e5ccd8c1400b4387b73942491cded655e5f9c"),
    "ppsspp-ffmpeg": ("https://github.com/hrydgard/ppsspp-ffmpeg.git", None),  # commit: PPSSPP's ffmpeg submodule
    "pcsx": ("https://github.com/libretro/pcsx_rearmed.git", "c8816799b50388e61cfe237fe2cdbb7d8175f20a"),
    "mupen": ("https://github.com/libretro/mupen64plus-libretro-nx.git", "15bada85906593376e8fd697f1b0b3af3a20f512"),
    "azahar": ("https://github.com/azahar-emu/azahar.git", "219d743a1e698101fe1ab8bef43e6563126c3c27"),
    "nipplejs": ("https://github.com/yoannmoinet/nipplejs.git", "9fca6e72bb99318d69092075015140179f4e32bf"),  # tag v0.10.2
    "socketio": ("https://github.com/socketio/socket.io.git", "8d5528aa2abc530f9e35f53935083894cec38c99"),  # socket.io-client@4.8.1
}

# Parts, in order. Each: (title, used for, licence summary, source line, [files]). A file is ("git", repo key, path),
# ("repo", path in this repo), ("npm", package, path in the tarball) or ("text", heading, text).
APP = [
    ("LibretroDroid 0.14.0", "Emulator front-end (every console but 3DS)", "GPL-3.0; bundles Oboe (Apache-2.0) and libretro-common (MIT, notice in each source file)",
     "https://github.com/Swordfish90/LibretroDroid/tree/0.14.0", [("git", "libretrodroid", "LICENSE")]),
    ("mGBA 0.11-212-7a12d6d", "Game Boy, Game Boy Color, Game Boy Advance", "MPL-2.0",
     "https://github.com/libretro/mgba/tree/7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6", [("git", "mgba", "LICENSE")]),
    ("melonDS DS 1.4.0", "Nintendo DS", "GPL-3.0",
     "https://github.com/JesseTG/melonds-ds/tree/f394adbacb5722ee97c1b37c8064da9a25818310", [("git", "melonds", "LICENSE")]),
    ("gpSP v1.1.0-5819380", "Game Boy Advance link play", "GPL-2.0-or-later",
     "https://github.com/libretro/gpsp/tree/5819380c2ffb0900219d700a382ee68c464ebb99", [("git", "gpsp", "COPYING")]),
    ("PPSSPP (commit 902e5cc)", "PSP, and its support files (PPSSPP.zip)",
     "GPL-2.0-or-later, PSPSDK parts BSD (in LICENSE.TXT); FFmpeg LGPL-2.1-or-later; bundled libraries as below",
     "https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c",
     [("git", "ppsspp", "LICENSE.TXT"), ("git", "ppsspp-ffmpeg", "LICENSE.md"), ("git", "ppsspp", "ext/cityhash/COPYING"),
      ("git", "ppsspp", "ext/gason/LICENSE"), ("git", "ppsspp", "ext/libdeflate/COPYING"), ("git", "ppsspp", "ext/naett-lib/LICENSE"),
      ("git", "ppsspp", "ext/snappy/COPYING"), ("git", "ppsspp", "ext/udis86/LICENSE"), ("git", "ppsspp", "ext/zlib/LICENSE"),
      ("text", "PSP support files (PPSSPP.zip): fonts and data",
       "flash0/font/*.pgf: free replacement PSP fonts made for PPSSPP by nassau-tk (not Sony's); the Japanese font is\n"
       "based on Ume-font (\"Unlimited permission is granted to use, copy, and distribute it, with or without\n"
       "modification\"). Roboto Condensed: Apache-2.0 (full text above). Inconsolata: SIL Open Font License 1.1 (its\n"
       "copyright and licence notice are inside the font file). gamecontrollerdb.txt: SDL_GameControllerDB, zlib licence\n"
       "(https://github.com/mdqinc/SDL_GameControllerDB). The rest is part of PPSSPP (GPL-2.0-or-later). No Sony BIOS or\n"
       "firmware. Every file is PPSSPP's assets/ folder at the commit above (tools/fetch_ppsspp_assets.sh).")]),
    ("PCSX ReARMed r26l-119-gc881679", "PlayStation (PS1)",
     "GPL-2.0-or-later; parts: libchdr BSD-3-Clause, LZMA SDK public domain, lightrec LGPL-2.1-or-later, GNU lightning LGPL-3.0-or-later, libretro-common MIT",
     "https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a",
     [("git", "pcsx", "COPYING"), ("git", "pcsx", "deps/libchdr/LICENSE.txt"), ("git", "pcsx", "deps/libchdr/deps/lzma-26.02/LICENSE"),
      ("git", "pcsx", "deps/lightrec/COPYING"), ("git", "pcsx", "deps/lightning/COPYING")]),
    ("Mupen64Plus-Next 3.0-GLES3 15bada8", "Nintendo 64",
     "GPL-3.0 as combined (mupen64plus-core GPL-2.0-or-later, GLideN64 GPL-2.0 with GPL-3.0 parts, cxd4 CC0, paraLLEl MIT, GNU lightning LGPL-3.0-or-later, more in the files below)",
     "https://github.com/libretro/mupen64plus-libretro-nx/tree/15bada85906593376e8fd697f1b0b3af3a20f512",
     [("git", "mupen", "LICENSE"), ("git", "mupen", "mupen64plus-core/LICENSES"), ("git", "mupen", "mupen64plus-rsp-hle/LICENSES"),
      ("git", "mupen", "mupen64plus-rsp-cxd4/COPYING"), ("git", "mupen", "GLideN64/LICENSE"),
      ("git", "mupen", "GLideN64/licenses/GLSL-FXAA/LICENSE.md"), ("git", "mupen", "GLideN64/licenses/Glow/LICENSE"),
      ("git", "mupen", "GLideN64/licenses/gles2n64/LICENSE"), ("git", "mupen", "GLideN64/licenses/readerwriterqueue/LICENSE.md"),
      ("git", "mupen", "mupen64plus-rsp-paraLLEl/LICENSE.MIT"), ("git", "mupen", "mupen64plus-rsp-paraLLEl/lightning/COPYING.LESSER"),
      ("git", "mupen", "mupen64plus-video-paraLLEl/parallel-rdp/LICENSE")]),
    ("Azahar 2126.2 (commit 219d743) + Shoalcade patches", "Nintendo 3DS",
     "GPL-3.0-or-later (some parts GPL-2.0-or-later); its bundled libraries are listed in dist/license.md below",
     "https://github.com/azahar-emu/azahar/tree/219d743a1e698101fe1ab8bef43e6563126c3c27 (patches: tools/azahar-patches)",
     [("git", "azahar", "license.txt"), ("git", "azahar", "dist/license.md")]),
    ("LibretroDroid, Argosy's Vulkan version (commit 68f1368), renamed copy in android/lr3ds", "3DS emulator front-end",
     "GPL-3.0; bundles libadrenotools and linkernsbypass (BSD-2-Clause), rcheevos (MIT), Oboe (Apache-2.0), SoundTouch (LGPL-2.1)",
     "https://github.com/rommapp/argosy-launcher/tree/68f1368b61707f8c4ad001b89eb095a705a46143/libretrodroid",
     [("repo", "android/lr3ds/LICENSE"), ("repo", "android/lr3ds/src/main/cpp/libadrenotools/LICENSE"),
      ("repo", "android/lr3ds/src/main/cpp/libadrenotools/lib/linkernsbypass/LICENSE"),
      ("repo", "android/lr3ds/src/main/cpp/rcheevos/LICENSE"), ("repo", "android/lr3ds/src/main/cpp/oboe/LICENSE"),
      ("repo", "android/lr3ds/src/main/cpp/soundtouch/COPYING.TXT")]),
    ("3DS check scene", "The 3DS check's test scene", "GPL-3.0-or-later (Shoalcade); based on devkitPro 3ds-examples (public domain); libctru and citro3d (zlib)",
     "tools/n3ds-check in Shoalcade's source; https://github.com/devkitPro/libctru, https://github.com/devkitPro/citro3d", []),
    ("AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, WorkManager, Security Crypto (with Google Tink)", "App framework",
     "Apache-2.0", "https://android.googlesource.com/platform/frameworks/support, https://github.com/JetBrains/kotlin, https://github.com/tink-crypto/tink-java", []),
    ("OkHttp 4.12.0 and Okio", "Celio Link connection (WebSocket)", "Apache-2.0",
     "https://github.com/square/okhttp/tree/parent-4.12.0, https://github.com/square/okio", []),
]

PLUGIN = [
    ("EmulatorJS 4.2.3", "Browser player front-end (ejs/)", "GPL-3.0 (also in the zip as ejs/LICENSE.txt)",
     "https://github.com/EmulatorJS/EmulatorJS/tree/v4.2.3 (npm @emulatorjs/emulatorjs 4.2.3)", [("npm", "emulatorjs", "package/LICENSE")]),
    ("nipplejs (inside EmulatorJS, ejs/src/nipplejs.js)", "Touch joystick", "MIT",
     "https://github.com/yoannmoinet/nipplejs (the bundled file names no version; licence file from tag v0.10.2)",
     [("git", "nipplejs", "LICENSE")]),
    ("Socket.IO client 4.8.1 (inside EmulatorJS, ejs/src/socket.io.min.js)", "Not used by Shoalcade", "MIT", "https://github.com/socketio/socket.io",
     [("npm-header", "emulatorjs", "package/data/src/socket.io.min.js"), ("git", "socketio", "packages/socket.io-client/LICENSE")]),
    ("EmulatorJS core builds 4.2.3: mGBA, melonDS, PCSX ReARMed, Mupen64Plus-Next", "Browser: GB/GBC/GBA, DS, PS1, N64",
     "mGBA MPL-2.0; melonDS GPL-3.0; PCSX ReARMed GPL-2.0-or-later; Mupen64Plus-Next GPL-2.0-or-later (full texts above)",
     "https://github.com/EmulatorJS/mgba, https://github.com/EmulatorJS/melonDS, https://github.com/EmulatorJS/pcsx_rearmed, "
     "https://github.com/EmulatorJS/mupen64plus-libretro-nx/tree/c35e55e87cbaa4cfc8355d8f91ceaa2beb212e9d", []),
    ("RomPatcher.js 3.2.1 core (Pages/rompatcher.js)", "Patch a game page", "MIT", "https://github.com/marcrobledo/RomPatcher.js/tree/v3.2.1",
     [("repo-header", "server/Jellyfin.Plugin.Shoalcade/Pages/rompatcher.js")]),
    ("JellyEMU 0.9.2.4 (code ported into the scanner)", "Console, region and disc detection; name cleaning", "MIT",
     "https://github.com/Jellyfin-PG/JellyEmu", [("notices-block", "THIRD_PARTY_NOTICES.md", "MIT License")]),
    ("Shoalcade phone app (app/Shoalcade.apk)", "Phone app download from your Jellyfin", "GPL-3.0, with every part in section A",
     "https://github.com/BBBannon/Shoalcade (at the release's tag)", []),
]


def run(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout


def fetch(key, paths, commit_override=None):
    """Shallow, blobless, sparse checkout of just `paths` at the pinned commit. Returns the folder."""
    url, commit = REPOS[key]
    commit = commit_override or commit
    d = os.path.join(SRC, key)
    if not os.path.isdir(os.path.join(d, ".git")):
        os.makedirs(d, exist_ok=True)
        run("git", "init", "-q", cwd=d)
        run("git", "remote", "add", "origin", url, cwd=d)
    if subprocess.run(["git", "cat-file", "-e", commit + "^{commit}"], cwd=d, capture_output=True).returncode != 0:
        run("git", "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", commit, cwd=d)
    run("git", "config", "core.sparseCheckout", "true", cwd=d)
    run("git", "config", "core.autocrlf", "false", cwd=d)
    run("git", "config", "core.eol", "lf", cwd=d)
    os.makedirs(os.path.join(d, ".git", "info"), exist_ok=True)
    with open(os.path.join(d, ".git", "info", "sparse-checkout"), "w", newline="\n") as f:
        f.write("".join("/" + p + "\n" for p in sorted(set(paths))))
    run("git", "-c", "advice.detachedHead=false", "checkout", "-q", "--detach", commit, cwd=d)
    run("git", "read-tree", "-mu", "HEAD", cwd=d)
    if run("git", "rev-parse", "HEAD", cwd=d).strip() != commit:
        sys.exit(d + ": HEAD is not " + commit)
    return d, commit


def npm_tarball(name):
    """The pinned EmulatorJS tarball (.testbed/dl, as server/build.sh keeps it, else downloaded), SHA-256 checked."""
    with open(os.path.join(ROOT, "server", "emulatorjs.lock"), encoding="utf-8") as f:
        pins = {p[0]: (p[1], p[2]) for p in (l.split() for l in f if l.strip() and not l.startswith("#"))}
    ver, sha = pins[name]
    cached = os.path.join(ROOT, ".testbed", "dl", "ejs-" + name + ".tgz")
    if os.path.isfile(cached):
        data = open(cached, "rb").read()
    else:
        url = "https://registry.npmjs.org/@emulatorjs/%s/-/%s-%s.tgz" % (name, name, ver)
        data = urllib.request.urlopen(url, timeout=120).read()
    if hashlib.sha256(data).hexdigest() != sha:
        sys.exit("SHA-256 mismatch for EmulatorJS " + name + ": refusing")
    return tarfile.open(fileobj=io.BytesIO(data), mode="r:gz"), ver


def first_comment(text):
    m = re.match(r"\s*/\*!?(.*?)\*/", text, re.S)
    if not m:
        sys.exit("no licence header comment found")
    lines = [re.sub(r"^\s*\* ?", "", l).rstrip() for l in m.group(1).splitlines()]
    return "\n".join(lines).strip("\n")


def clean(text):
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "")
    return "\n".join(l.rstrip() for l in text.split("\n")).strip("\n") + "\n"


def norm(text):
    return " ".join(text.split())


def near_standard(text, std):
    """The id of the full licence this file is a copy of (98% of words the same, same length within 2%), or None."""
    words = text.split()
    for i, _, s in std:
        sw = s.split()
        if abs(len(words) - len(sw)) > 0.02 * len(sw):
            continue
        m = difflib.SequenceMatcher(None, words, sw, autojunk=False)
        if m.ratio() >= 0.98:
            return i
    return None


def build():
    std = [(i, t, clean(open(p, encoding="utf-8").read())) for i, t, p in STANDARD]
    std_norm = {norm(txt): i for i, _, txt in std}

    # Fetch every git file first (one sparse set per repo).
    want = {}
    for parts in (APP, PLUGIN):
        for part in parts:
            for f in part[4]:
                if f[0] == "git":
                    want.setdefault(f[1], []).append(f[2])
    got = {}
    for key in want:
        if key == "ppsspp-ffmpeg":
            continue
        got[key] = fetch(key, want[key] + ([".gitmodules"] if key == "ppsspp" else []))
    if "ppsspp-ffmpeg" in want:
        ff = run("git", "ls-tree", "HEAD", "ffmpeg", cwd=got["ppsspp"][0]).split()[2]  # the submodule's commit
        got["ppsspp-ffmpeg"] = fetch("ppsspp-ffmpeg", want["ppsspp-ffmpeg"], ff)
    tars = {}

    def read(f):
        kind = f[0]
        if kind == "git":
            d, commit = got[f[1]]
            with open(os.path.join(d, f[2]), encoding="utf-8", errors="replace") as fh:
                return "%s at %s, %s" % (REPOS[f[1]][0].removesuffix(".git"), commit[:12], f[2]), fh.read()
        if kind in ("repo", "repo-header"):
            with open(os.path.join(ROOT, f[1]), encoding="utf-8") as fh:
                t = fh.read()
            return "Shoalcade source, " + f[1], first_comment(t) if kind == "repo-header" else t
        if kind in ("npm", "npm-header"):
            if f[1] not in tars:
                tars[f[1]] = npm_tarball(f[1])
            tar, ver = tars[f[1]]
            t = tar.extractfile(f[2]).read().decode("utf-8", "replace")
            return "npm @emulatorjs/%s %s, %s" % (f[1], ver, f[2]), first_comment(t) if kind == "npm-header" else t
        if kind == "notices-block":
            t = open(os.path.join(ROOT, f[1]), encoding="utf-8").read().replace("\r\n", "\n")
            m = re.search(r"```\n(" + re.escape(f[2]) + r".*?)```", t, re.S)
            if not m:
                sys.exit("block not found in " + f[1])
            return "Shoalcade source, " + f[1], m.group(1)
        if kind == "text":
            return f[1], f[2]
        sys.exit("unknown file kind " + kind)

    out = []
    w = out.append
    w("Shoalcade: third-party licences\n")
    w("===============================\n\n")
    w("Shoalcade is free software under the GNU General Public License version 3 (LICENSE.txt). It is built on the\n"
      "parts below. For each one: what it is used for, its licence, where its exact source is, and its own licence\n"
      "file(s), copied from that source (only line endings and spaces at line ends tidied). A file that is one of the\n"
      "full licences in section S (word for word, or apart from small details such as an old postal address) is not\n"
      "repeated: it says which one. More detail (versions, hashes, checks): THIRD_PARTY_NOTICES.md and\n"
      "docs/licences.md in the Shoalcade source, https://github.com/BBBannon/Shoalcade\n\n"
      "No games, BIOS, firmware or keys are included in anything Shoalcade ships.\n\n"
      "This file is made by tools/make_third_party_licences.py. Libraries built inside an emulator core keep their own\n"
      "notices in that core's source at the commit given; the ones listed here are the core's top-level licence files.\n\n")
    toc = [("S", "Full licence texts")] + [("A", "Phone app (APK)")] + [("B", "Jellyfin plugin (server zip)")]
    for s, t in toc:
        w("  %s. %s\n" % (s, t))
    w("\n")

    def head(title, ch="="):
        w("\n" + title + "\n" + ch * len(title) + "\n\n")

    head("S. Full licence texts")
    for i, title, txt in std:
        head("S.%s %s" % (i, title), "-")
        w(txt)

    for letter, name, parts in (("A", "Phone app (APK)", APP), ("B", "Jellyfin plugin (server zip)", PLUGIN)):
        head("%s. %s" % (letter, name))
        for n, (title, use, lic, source, files) in enumerate(parts, 1):
            head("%s.%d %s" % (letter, n, title), "-")
            w("Used for: %s\nLicence:  %s\nSource:   %s\n" % (use, lic, source))
            for f in files:
                where, txt = read(f)
                txt = clean(txt)
                w("\n--- %s ---\n" % where)
                same = std_norm.get(norm(txt))
                near = None if same else near_standard(txt, std)
                if same:
                    w("(Word for word the %s text in section S.%s.)\n" % (same, same))
                elif near:
                    w("(The %s text, as in section S.%s apart from small differences in wording or layout, e.g. the Free\n"
                      "Software Foundation's old postal address. Not repeated here.)\n" % (near, near))
                else:
                    w(txt)
    return "".join(out)


def main():
    text = build()
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        have = open(OUT, encoding="utf-8").read().replace("\r\n", "\n") if os.path.isfile(OUT) else ""
        if have != text:
            sys.exit("THIRD_PARTY_LICENCES.txt is out of date: run tools/make_third_party_licences.py")
        print("OK: THIRD_PARTY_LICENCES.txt is up to date (%d bytes)" % len(text.encode()))
        return
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("Wrote %s (%d bytes)" % (OUT, len(text.encode())))


if __name__ == "__main__":
    main()
