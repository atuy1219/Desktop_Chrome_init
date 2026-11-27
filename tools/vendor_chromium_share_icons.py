#!/usr/bin/env python3
"""Verify the committed upstream Chromium share PNGs offline."""
import hashlib
from pathlib import Path

# Verified blob IDs from the Chromium source tree. No Chrome runtime version
# checks: these are stable fallback artwork, not binary hook offsets.
ICONS = {
    "chromium_share_ltr.png": (
        "drawable-xxxhdpi/ic_share_white_24dp.png",
        "6742e904ee35e544f72485f7f3be1809c01e9050"),
    "chromium_share_rtl.png": (
        "drawable-ldrtl-xxxhdpi/ic_share_white_24dp.png",
        "5ea7a0d07173a6c378d76065a852752bd927775e"),
}
OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets"

def git_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (path, expected) in ICONS.items():
        dest = OUT / name
        data = dest.read_bytes() if dest.exists() else b""
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or git_sha(data) != expected:
            raise RuntimeError(f"Unexpected Chromium asset: {name}")
        print(f"Verified Chromium asset: {name} {expected}")

if __name__ == "__main__":
    main()
