#!/usr/bin/env bash
# Shoalcade: make the phone app's PSP support files (android/app/src/main/assets/PPSSPP.zip) from PPSSPP's own source.
# Part of Shoalcade, GPL-3.0-or-later.
#
#   tools/fetch_ppsspp_assets.sh            write android/app/src/main/assets/PPSSPP.zip (left alone if already right)
#   tools/fetch_ppsspp_assets.sh OUT.zip    write it somewhere else
#   tools/fetch_ppsspp_assets.sh --check [ZIP]   only check a zip you already have
#
# PPSSPP.zip is not in git (it is PPSSPP's, not ours). It is PPSSPP's assets/ folder plus its LICENSE.TXT, inside a
# PPSSPP/ folder, with assets/debugger (a submodule, not needed) left empty: the same layout as the libretro buildbot's
# assets/system/PPSSPP.zip that the released apps were built with. Fonts are PPSSPP's own community replacements, no
# Sony BIOS or firmware (docs/licences.md, "PPSSPP support files").
#
# The files are taken from hrydgard/ppsspp at the exact commit the app's PSP core was built from (the core's .so
# carries the short hash). The result is checked against CONTENT_SHA256: the SHA-256 of a list of every entry's name
# and the SHA-256 of its bytes, which the PPSSPP.zip in every released APK also gives. The zip container itself is
# not byte-identical to the buildbot's (that was packed by a different zip tool; only the packing differs), so the
# check is on the files inside, which is what the app unpacks.
#
# Needs: git, Python 3 (for the zip; no `zip` needed), network access to github.com. Linux or Windows Git Bash.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PPSSPP_REPO=https://github.com/hrydgard/ppsspp.git
PPSSPP_COMMIT=902e5ccd8c1400b4387b73942491cded655e5f9c # docs/licences.md; same as windows/cores/cores.lock
CONTENT_SHA256=9bdf58e1c6d201c87473a3ce35cedcc22294d472a9198ae7d390b982a665ff55 # 176 entries, buildbot zip of 22/10/2026
SRC=${SHOALCADE_PPSSPP_SRC:-$HOME/deps/ppsspp-assets-src}
CORE_SO=$ROOT/android/app/src/main/jniLibs/arm64-v8a/libppsspp_libretro_android.so

PY=
for p in python3 python; do
  if command -v "$p" >/dev/null 2>&1 && "$p" -I -c 'import zipfile, hashlib' >/dev/null 2>&1; then PY=$p; break; fi
done
[ -n "$PY" ] || { echo "fetch_ppsspp_assets: needs Python 3" >&2; exit 1; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) win() { cygpath -m "$1"; } ;; *) win() { printf '%s' "$1"; } ;; esac

# zipper.py MODE ...: "hash ZIP" prints the content hash; "build SRC OUT" packs SRC/assets + SRC/LICENSE.TXT.
zipper() {
  "$PY" -I - "$@" <<'PY'
import hashlib, os, sys, zipfile

def content_hash(entries):
    # entries: (name, bytes or None for a folder). One line per entry, sorted by name.
    lines = []
    for name, data in sorted(entries, key=lambda e: e[0]):
        lines.append(("folder  " if data is None else hashlib.sha256(data).hexdigest() + "  ") + name)
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()

def zip_entries(path):
    with zipfile.ZipFile(path) as z:
        return [(i.filename, None if i.is_dir() else z.read(i)) for i in z.infolist()]

def src_entries(src):
    out = [("PPSSPP/", None)]
    base = os.path.join(src, "assets")
    for d, dirs, files in os.walk(base):
        dirs.sort()
        rel = os.path.relpath(d, base).replace(os.sep, "/")
        if rel == "debugger" or rel.startswith("debugger/"):
            dirs[:] = []
            files = []
        if rel != ".":
            out.append(("PPSSPP/" + rel + "/", None))
        for f in files:
            with open(os.path.join(d, f), "rb") as fh:
                out.append(("PPSSPP/" + ("" if rel == "." else rel + "/") + f, fh.read()))
    if "PPSSPP/debugger/" not in {n for n, _ in out}:
        out.append(("PPSSPP/debugger/", None))
    with open(os.path.join(src, "LICENSE.TXT"), "rb") as fh:
        out.append(("PPSSPP/LICENSE.TXT", fh.read()))
    return out

def build(entries, out):
    # Fixed date and modes, sorted order: the same input always gives the same zip.
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(entries, key=lambda e: e[0]):
            i = zipfile.ZipInfo(name, date_time=(2026, 10, 22, 23, 6, 12))
            i.create_system = 3
            if data is None:
                i.external_attr = (0o40755 << 16) | 0x10
                z.writestr(i, b"")
            else:
                i.external_attr = 0o100644 << 16
                i.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(i, data)

mode = sys.argv[1]
if mode == "hash":
    print(content_hash(zip_entries(sys.argv[2])))
elif mode == "build":
    e = src_entries(sys.argv[2])
    build(e, sys.argv[3])
    print(content_hash(zip_entries(sys.argv[3])))
else:
    sys.exit("unknown mode " + mode)
PY
}

check() { # ZIP -> 0 if its contents are the pinned ones
  local got
  got=$(zipper hash "$(win "$1")" | tr -d '\r')
  if [ "$got" = "$CONTENT_SHA256" ]; then
    echo "OK: $1 has the pinned PPSSPP files (content SHA-256 $got)"
  else
    echo "DIFFERENT: $1 content SHA-256 $got, expected $CONTENT_SHA256" >&2
    return 1
  fi
}

if [ "${1:-}" = --check ]; then
  check "${2:-$ROOT/android/app/src/main/assets/PPSSPP.zip}"
  exit
fi
OUT=${1:-$ROOT/android/app/src/main/assets/PPSSPP.zip}

# The core in the app must be the one these files belong to.
if [ -f "$CORE_SO" ] && ! grep -a -q "${PPSSPP_COMMIT:0:7}" "$CORE_SO"; then
  echo "fetch_ppsspp_assets: $CORE_SO does not carry PPSSPP commit ${PPSSPP_COMMIT:0:7}." >&2
  echo "  The PSP core has changed: update PPSSPP_COMMIT and CONTENT_SHA256 here and in docs/licences.md." >&2
  exit 1
fi

if [ -f "$OUT" ]; then
  if check "$OUT"; then echo "Nothing to do."; exit 0; fi
  echo "fetch_ppsspp_assets: $OUT exists but is not the pinned file. Move it aside and run again (never overwritten)." >&2
  exit 1
fi

# Shallow, blobless, sparse fetch of only assets/ and LICENSE.TXT at the pinned commit (submodules not fetched).
if [ ! -e "$SRC/.git" ]; then
  mkdir -p "$SRC"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin "$PPSSPP_REPO"
fi
git -C "$SRC" cat-file -e "$PPSSPP_COMMIT^{commit}" 2>/dev/null ||
  git -C "$SRC" fetch -q --depth 1 --filter=blob:none origin "$PPSSPP_COMMIT"
git -C "$SRC" config core.sparseCheckout true
# The bytes as committed: Git for Windows would otherwise write CRLF (autocrlf, and core.eol=native for files that
# PPSSPP's .gitattributes marks as text, e.g. mime/ppsspp.xml).
git -C "$SRC" config core.autocrlf false
git -C "$SRC" config core.eol lf
mkdir -p "$SRC/.git/info"
printf '%s\n' /.gitmodules /assets/ /LICENSE.TXT > "$SRC/.git/info/sparse-checkout"
git -C "$SRC" -c advice.detachedHead=false checkout -q --detach "$PPSSPP_COMMIT"
git -C "$SRC" read-tree -mu HEAD
[ "$(git -C "$SRC" rev-parse HEAD)" = "$PPSSPP_COMMIT" ] || { echo "$SRC: HEAD is not $PPSSPP_COMMIT" >&2; exit 1; }
[ -z "$(git -C "$SRC" status --porcelain --untracked-files=all -- assets LICENSE.TXT)" ] ||
  { echo "fetch_ppsspp_assets: $SRC has changed files; delete the folder and run again" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
TMP="$OUT.part"
rm -f "$TMP"
zipper build "$(win "$SRC")" "$(win "$TMP")" >/dev/null
if ! check "$TMP"; then rm -f "$TMP"; exit 1; fi
mv "$TMP" "$OUT"
echo "Wrote $OUT"
