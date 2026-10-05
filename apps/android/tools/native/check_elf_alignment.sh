#!/usr/bin/env bash
# Report the PT_LOAD alignment of every native library in one or more APKs (spec 14 §1.9).
# 16 KB-page devices need 0x4000 (2**14) or larger. REPORT ONLY: always exits 0, unless
# --strict is given, in which case it exits 1 when any library is below 16 KB.
#
#   tools/native/check_elf_alignment.sh [--strict] app-lite/build/outputs/apk/debug/app-lite-debug.apk ...
set -euo pipefail

strict=0
if [[ "${1:-}" == "--strict" ]]; then strict=1; shift; fi
if [[ $# -eq 0 ]]; then
  echo "usage: $0 [--strict] <apk> [...]" >&2
  exit 2
fi

python3 - "$strict" "$@" <<'PY'
import struct, sys, zipfile
strict = sys.argv[1] == "1"
low = 0
for apk in sys.argv[2:]:
    with zipfile.ZipFile(apk) as z:
        libs = sorted(n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so"))
        print(f"{apk}: {len(libs)} native libraries")
        for name in libs:
            d = z.read(name)
            is64 = d[4] == 2
            phoff = struct.unpack_from("<Q" if is64 else "<I", d, 0x20 if is64 else 0x1c)[0]
            phentsize, phnum = struct.unpack_from("<HH", d, 0x36 if is64 else 0x2a)
            aligns = []
            for i in range(phnum):
                off = phoff + i * phentsize
                if struct.unpack_from("<I", d, off)[0] != 1:  # PT_LOAD
                    continue
                aligns.append(struct.unpack_from("<Q", d, off + 0x30)[0] if is64
                              else struct.unpack_from("<I", d, off + 0x1c)[0])
            a = min(aligns) if aligns else 0
            ok = a >= 0x4000
            low += not ok
            print(f"  {'16K ' if ok else '4K  '} 0x{a:x}  {name}")
print(f"{low} librar{'y' if low == 1 else 'ies'} below 16 KB alignment" + ("" if strict else " (report only)"))
sys.exit(1 if strict and low else 0)
PY
