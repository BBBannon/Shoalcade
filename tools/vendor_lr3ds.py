"""Copies Argosy's Vulkan LibretroDroid fork into android/lr3ds as a renamed copy used by 3DS only (D153).

Every other console keeps stock LibretroDroid 0.14.0 from Maven, untouched. The rename stops the two clashing:
  Kotlin/Java package  com.swordfish.libretrodroid -> com.swordfish.libretrodroid3ds
  native library       libretrodroid                -> libretrodroid3ds

Usage: python -I tools/vendor_lr3ds.py [path-to-argosy-checkout]
Source: rommapp/argosy-launcher (GPL-3.0), pinned commit below, with submodules
libadrenotools (BSD-2) and rcheevos (MIT) checked out.
"""
import os
import re
import shutil
import subprocess
import sys

PIN = "68f1368b61707f8c4ad001b89eb095a705a46143"
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/Android/src/argosy")
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(HERE, "android", "lr3ds")

head = subprocess.run(["git", "-C", SRC, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
if head != PIN:
    sys.exit(f"Argosy checkout is at {head}, expected pinned {PIN}")

# Parts of Oboe we never build (samples, apps, tests, docs): left out to keep the copy small.
SKIP = {os.path.join("src", "main", "cpp", "oboe", d) for d in ("samples", "apps", "tests", "docs")}

def ignore(folder, names):
    rel = os.path.relpath(folder, os.path.join(SRC, "libretrodroid"))
    out = [n for n in names if os.path.join(rel, n) in SKIP or n == ".git"]
    return out

if os.path.exists(DEST):
    shutil.rmtree(DEST)
shutil.copytree(os.path.join(SRC, "libretrodroid"), DEST, ignore=ignore)
shutil.copy(os.path.join(SRC, "LICENSE"), os.path.join(DEST, "LICENSE"))

# Move the Kotlin/Java sources to the new package folder.
old_pkg = os.path.join(DEST, "src", "main", "java", "com", "swordfish", "libretrodroid")
shutil.move(old_pkg, old_pkg + "3ds")

TEXT = (".kt", ".java", ".cpp", ".h", ".c", ".gradle", ".txt", ".pro")
for root, _, files in os.walk(DEST):
    if os.sep + "oboe" + os.sep in root + os.sep or os.sep + "rcheevos" in root or "libadrenotools" in root:
        continue  # third-party code: never edited
    for f in files:
        if not f.endswith(TEXT):
            continue
        p = os.path.join(root, f)
        s = open(p, encoding="utf-8", errors="surrogateescape").read()
        n = s.replace("com.swordfish.libretrodroid", "com.swordfish.libretrodroid3ds")
        n = n.replace("com/swordfish/libretrodroid/", "com/swordfish/libretrodroid3ds/")
        n = n.replace("Java_com_swordfish_libretrodroid_", "Java_com_swordfish_libretrodroid3ds_")
        n = n.replace('System.loadLibrary("libretrodroid")', 'System.loadLibrary("libretrodroid3ds")')
        if f == "CMakeLists.txt" and root.endswith(os.path.join("src", "main", "cpp")):
            n = re.sub(r"\b(add_library|target_link_options|target_link_libraries|target_sources|target_compile_definitions)\(libretrodroid\b",
                       r"\1(libretrodroid3ds", n)
        n = n.replace("com.swordfish.libretrodroid3ds3ds", "com.swordfish.libretrodroid3ds")  # guard against re-runs
        if n != s:
            open(p, "w", encoding="utf-8", errors="surrogateescape", newline="").write(n)

# Our Android build tools reject package= in a library manifest (the namespace is in build.gradle).
man = os.path.join(DEST, "src", "main", "AndroidManifest.xml")
m = open(man, encoding="utf-8").read()
open(man, "w", encoding="utf-8", newline="").write(re.sub(r'\s+package="[^"]*"', "", m))

# Nothing may still point at the stock names.
left = []
for root, _, files in os.walk(DEST):
    for f in files:
        if f.endswith(TEXT):
            s = open(os.path.join(root, f), encoding="utf-8", errors="surrogateescape").read()
            if re.search(r"com[./_]swordfish[./_]libretrodroid(?!3ds)", s) or 'loadLibrary("libretrodroid")' in s:
                left.append(os.path.relpath(os.path.join(root, f), DEST))
if left:
    sys.exit("Stock names left in: " + ", ".join(left))
open(os.path.join(DEST, "VENDORED.txt"), "w", encoding="utf-8").write(
    f"Copied from rommapp/argosy-launcher {PIN} (libretrodroid/), GPL-3.0.\n"
    "Renamed for Shoalcade 3DS only (D153) by tools/vendor_lr3ds.py. Do not edit by hand: change the script and re-run.\n")
print(f"Vendored Argosy LibretroDroid {PIN[:7]} into {DEST} (renamed to libretrodroid3ds)")
