#!/usr/bin/env python3
"""
Verify that every libvosk.so has stderr/stdin/stdout weakened (STB_WEAK) in .dynsym.

Spec 14 §1.9 / inv04 §8 risk 2. On Lollipop (API 21-22) Bionic does not export
stderr/stdin/stdout as symbols, so a stock (STB_GLOBAL) libvosk.so fails to dlopen.
patch_vosk_weaken.py makes them weak; this script proves the patched copy is the one
that ended up in the APK (and not the AAR's stock copy, risk X4).

Usage:
  verify_vosk_patch.py <file.apk | libvosk.so> [...]

Exit 0 when every libvosk.so found is patched and at least one was found; 1 otherwise.
Supports ELF32 (armeabi-v7a, x86) and ELF64 (arm64-v8a, x86_64).
"""

import struct
import sys
import zipfile

STB_NAMES = {0: "LOCAL", 1: "GLOBAL", 2: "WEAK"}
CHECKED = (b"stderr", b"stdin", b"stdout")


def bindings(data):
    """Return {name: binding} for the CHECKED symbols in .dynsym."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    if is64:
        e_phoff, = struct.unpack_from("<Q", data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    else:
        e_phoff, = struct.unpack_from("<I", data, 0x1c)
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x2a)

    # PT_LOAD segments map virtual addresses to file offsets; PT_DYNAMIC holds the tags.
    loads, dyn = [], None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", data, off)
        if is64:
            p_offset, p_vaddr = struct.unpack_from("<QQ", data, off + 0x08)
            p_filesz, = struct.unpack_from("<Q", data, off + 0x20)
        else:
            p_offset, p_vaddr = struct.unpack_from("<II", data, off + 0x04)
            p_filesz, = struct.unpack_from("<I", data, off + 0x10)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if dyn is None:
        raise ValueError("no PT_DYNAMIC")

    def to_off(va):
        for vaddr, offset, size in loads:
            if vaddr <= va < vaddr + size:
                return va - vaddr + offset
        return va

    tags = {}
    entsz = 16 if is64 else 8
    fmt = "<qQ" if is64 else "<iI"
    for i in range(dyn[1] // entsz):
        tag, val = struct.unpack_from(fmt, data, dyn[0] + i * entsz)
        if tag == 0:
            break
        tags.setdefault(tag, val)
    symtab, strtab, strsz = to_off(tags[6]), to_off(tags[5]), tags[10]
    syment = tags.get(11, 24 if is64 else 16)
    if 4 in tags:  # DT_HASH: nchains == number of symbols
        nsyms = struct.unpack_from("<II", data, to_off(tags[4]))[1]
    else:
        nsyms = (strtab - symtab) // syment

    found = {}
    for i in range(nsyms):
        off = symtab + i * syment
        st_name, = struct.unpack_from("<I", data, off)
        st_info = data[off + 4] if is64 else data[off + 12]
        if st_name >= strsz:
            continue
        end = data.index(b"\x00", strtab + st_name)
        name = bytes(data[strtab + st_name:end])
        if name in CHECKED:
            found[name.decode()] = STB_NAMES.get(st_info >> 4, str(st_info >> 4))
    return found


def check_one(label, data):
    found = bindings(data)
    bad = {n: b for n, b in found.items() if b != "WEAK"}
    status = "OK  " if not bad else "FAIL"
    detail = ", ".join(f"{n}={b}" for n, b in sorted(found.items())) or "no stdio symbols"
    print(f"{status} {label}: {detail}")
    return not bad


def main(paths):
    if not paths:
        print(__doc__)
        return 2
    seen, ok = 0, True
    for path in paths:
        if path.endswith(".apk"):
            with zipfile.ZipFile(path) as z:
                libs = [n for n in z.namelist() if n.startswith("lib/") and n.endswith("/libvosk.so")]
                if not libs:
                    print(f"FAIL {path}: no lib/*/libvosk.so in the APK")
                    ok = False
                for name in sorted(libs):
                    seen += 1
                    ok &= check_one(f"{path}!{name}", z.read(name))
        else:
            seen += 1
            with open(path, "rb") as f:
                ok &= check_one(path, f.read())
    if seen == 0:
        ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
