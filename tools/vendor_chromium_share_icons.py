#!/usr/bin/env python3
"""Vendor the exact upstream Chromium share PNGs for the APK (not runtime)."""
import hashlib
from pathlib import Path
from urllib.request import Request, urlopen

# Verified blob IDs from the Chromium source tree. No Chrome runtime version
# checks: these are stable fallback artwork, not binary hook offsets.
ROOT = ("https://raw.githubusercontent.com/chromium/chromium/159.0.8154.18/"
        "components/browser_ui/styles/android/java/res/")
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
        if git_sha(data) != expected:
            with urlopen(Request(ROOT + path, headers={"User-Agent": "ChromeMobileCompat-CI"}), timeout=45) as response:
                data = response.read(1024 * 1024)
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or git_sha(data) != expected:
            raise RuntimeError(f"Unexpected Chromium asset: {name}")
        dest.write_bytes(data)
        print(f"Verified Chromium asset: {name} {expected}")

if __name__ == "__main__":
    main()
