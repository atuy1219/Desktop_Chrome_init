#!/usr/bin/env python3
"""Offline ELF inventory for user-provided Chrome APK splits.

Usage:
    python tools/inspect_chrome_native.py base.apk split_chrome.apk \
        split_config.ja.apk split_dev_ui.apk split_on_demand.apk

Only APK-native libraries, ELF build IDs, and exported symbol tables are
processed. The script never modifies an APK and never handles Chrome profiles.
stdlib-only, accepts huge libraries by streaming into temporary files.
"""
import hashlib
import mmap
import os
import struct
import sys
import tempfile
import zipfile

ELF64_HEADER = struct.Struct("<16sHHIQQQIHHHHHH")
ELF64_PHDR = struct.Struct("<IIQQQQQQ")
ELF64_SHDR = struct.Struct("<IIQQQQIIQQ")
ELF64_SYM = struct.Struct("<IBBHQQ")


def find_build_id(view, phoff, phnum, phentsize):
    for j in range(phnum):
        off = phoff + j * phentsize
        if off + ELF64_PHDR.size > len(view):
            break
        p_type, _, offset, _, _, filesz, _, _ = ELF64_PHDR.unpack_from(view, off)
        if p_type != 4 or not 0 < filesz < 1048576:
            continue
        if offset + filesz > len(view):
            continue
        end = offset + filesz
        pos = offset
        while pos + 12 <= end:
            namesz, descsz, ntype = struct.unpack_from("<III", view, pos)
            pos += 12
            name_end = pos + namesz
            desc_start = pos + (namesz + 3) // 4 * 4
            desc_end = desc_start + descsz
            if desc_end > end:
                break
            if ntype == 3 and view[pos:name_end] == b"GNU\x00":
                return bytes(view[desc_start:desc_end]).hex()
            pos = desc_start + (descsz + 3) // 4 * 4
    return "not-found"


def inspect_elf(path):
    with open(path, "rb") as f:
        with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm:
            if len(mm) < ELF64_HEADER.size or mm[:4] != b"\x7fELF":
                return {"type": "not-ELF"}
            if mm[4] != 2 or mm[5] != 1:
                return {"type": f"unsupported-ELF-class:{mm[4]}-data:{mm[5]}"}
            (ident, typ, machine, version, entry, phoff, shoff, flags,
             ehsize, phentsize, phnum, shentsize, shnum,
             shstrndx) = ELF64_HEADER.unpack_from(mm)
            result = {
                "type": "ELF64",
                "machine": machine,
                "build_id": find_build_id(mm, phoff, phnum, phentsize),
                "dynamic_symbols": 0,
                "zero_suggest_exports": [],
            }
            if shentsize < ELF64_SHDR.size or shnum > 100000:
                return result
            sections = []
            for j in range(shnum):
                off = shoff + j * shentsize
                if off + ELF64_SHDR.size > len(mm):
                    break
                sections.append(ELF64_SHDR.unpack_from(mm, off))
            for sec in sections:
                # SHT_DYNSYM = 11, sh_link points at the string table.
                if sec[1] != 11 or sec[6] >= len(sections):
                    continue
                dynstr = sections[sec[6]]
                string_off, string_size = dynstr[4], dynstr[5]
                sym_off, sym_size, sym_ent = sec[4], sec[5], sec[9]
                if (string_off + string_size > len(mm)
                        or sym_off + sym_size > len(mm)
                        or sym_ent < ELF64_SYM.size or sym_ent == 0):
                    continue
                count = min(sym_size // sym_ent, 10_000_000)
                result["dynamic_symbols"] += count
                for j in range(count):
                    st_off = sym_off + j * sym_ent
                    if st_off + ELF64_SYM.size > len(mm):
                        break
                    st_name = ELF64_SYM.unpack_from(mm, st_off)[0]
                    if st_name >= string_size:
                        continue
                    at = string_off + st_name
                    end = mm.find(b"\x00", at, string_off + string_size)
                    if end < 0:
                        continue
                    symbol = mm[at:end]
                    if b"ZeroSuggestVerbatimMatchProvider" in symbol:
                        result["zero_suggest_exports"].append(
                            symbol.decode("ascii", "replace")[:180])
            return result


def inspect_apk(apk):
    with zipfile.ZipFile(apk) as z:
        entries = z.infolist()
        dex_count = sum(x.filename.endswith(".dex") for x in entries)
        libs = [x for x in entries
                if x.filename.endswith(".so") and not x.is_dir()]
        print(f"APK {os.path.basename(apk)} size={os.path.getsize(apk)} "
              f"dex={dex_count} native-libs={len(libs)}")
        for member in libs:
            digest = hashlib.sha256()
            tmp = tempfile.NamedTemporaryFile(
                prefix="chrome-elf-", suffix=".so", delete=False)
            path = tmp.name
            try:
                with z.open(member) as source, tmp:
                    while True:
                        data = source.read(8 << 20)
                        if not data:
                            break
                        digest.update(data)
                        tmp.write(data)
                result = inspect_elf(path)
                print("  LIB", member.filename,
                      f"size={member.file_size}",
                      f"sha256={digest.hexdigest()}",
                      f"elf={result.get('type')}",
                      f"machine={result.get('machine')}",
                      f"build_id={result.get('build_id')}",
                      f"dynsyms={result.get('dynamic_symbols')}")
                for symbol in result.get("zero_suggest_exports", [])[:15]:
                    print("    ZERO_SUGGEST_EXPORT", symbol)
            finally:
                if os.path.exists(path):
                    os.unlink(path)


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: inspect_chrome_native.py <chrome.apk> [split.apk...]")
    for path in sys.argv[1:]:
        try:
            inspect_apk(path)
        except (OSError, zipfile.BadZipFile) as exc:
            print(f"ERROR {path}: {exc}", file=sys.stderr)


if __name__ == "__main__":
    main()
