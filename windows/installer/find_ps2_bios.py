"""Lists every file under a folder that is a PS2 BIOS dump, whatever its name (review S12).

The installer must never ship a BIOS. tools/build_windows_release.sh already refuses files named scph* or *.bin in a
bios folder; this finds the rest by content, with the rule the Windows app uses to accept one (Ps2.check, after
PCSX2's IsBIOS in BiosTools.cpp): 4 to 8 MB, 16-byte ROM directory records from the start until one named RESET, then
records until a blank name, one of them ROMVER (14 characters inside the file).

    python find_ps2_bios.py <folder>     prints one path per BIOS found (nothing = none)
    python find_ps2_bios.py --self-test  checks the rule on made-up files
"""
import os
import struct
import sys
import tempfile

MIN_SIZE = 4 * 1024 * 1024
MAX_SIZE = 8 * 1024 * 1024


def _name(data, at):
    if at + 16 > len(data):
        return None
    raw = data[at:at + 10]
    end = raw.find(b"\0")
    if end < 0:
        return None  # ten characters with no end: not a name
    return raw[:end].decode("latin-1")


def is_ps2_bios(data, size):
    if not MIN_SIZE <= size <= MAX_SIZE:
        return False
    rec = -1
    at = 0
    while at + 16 <= len(data):
        if _name(data, at) == "RESET":
            rec = at
            break
        at += 16
    if rec < 0:
        return False
    offset = 0
    while True:
        n = _name(data, rec)
        if not n:
            return False
        file_size = struct.unpack_from("<I", data, rec + 12)[0]
        if n == "ROMVER" and offset + 14 <= len(data):
            return True
        offset += file_size if file_size % 16 == 0 else (file_size + 16) & 0xFFFFFFF0
        rec += 16


def find(folder):
    found = []
    for root, _dirs, files in os.walk(folder):
        for f in files:
            p = os.path.join(root, f)
            try:
                size = os.path.getsize(p)
                if MIN_SIZE <= size <= MAX_SIZE:
                    with open(p, "rb") as fh:
                        if is_ps2_bios(fh.read(), size):
                            found.append(p)
            except OSError:
                found.append(p + " (couldn't be read: checked by hand)")
    return found


def fake_bios(size=MIN_SIZE):
    data = bytearray(size)
    recs = [("RESET", 0x2000), ("ROMDIR", 0x40), ("EXTINFO", 0x10), ("ROMVER", 0x10)]
    for i, (n, s) in enumerate(recs):
        struct.pack_into("<10sHI", data, 0x2700 + 16 * i, n.encode(), 0, s)
    data[0x2000 + 0x40 + 0x10:0x2000 + 0x40 + 0x10 + 14] = b"0200EC20040614"
    return bytes(data)


def self_test():
    with tempfile.TemporaryDirectory() as d:
        os.makedirs(os.path.join(d, "app", "system", "pcsx2", "resources"))
        bios = os.path.join(d, "app", "system", "pcsx2", "resources", "ps2-europe.rom")
        with open(bios, "wb") as fh:
            fh.write(fake_bios())
        no_romver = os.path.join(d, "app", "system", "reset-only.bin")
        with open(no_romver, "wb") as fh:
            fh.write(fake_bios().replace(b"ROMVER", b"ROMXXX"))
        with open(os.path.join(d, "app", "system", "big.dat"), "wb") as fh:
            fh.write(bytes(MIN_SIZE))
        with open(os.path.join(d, "small.bin"), "wb") as fh:
            fh.write(fake_bios()[:MIN_SIZE // 2])
        got = find(d)
        assert got == [bios], got
    print("find_ps2_bios self-test: ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 2:
        for p in find(sys.argv[1]):
            print(p)
    else:
        sys.exit(__doc__)
