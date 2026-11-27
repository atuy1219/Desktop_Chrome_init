#!/usr/bin/env python3
"""Patch a Chromium checkout using source anchors, not release numbers."""
import argparse
import pathlib
import re
import sys

CLASSIFIER = pathlib.Path("components/omnibox/browser/autocomplete_classifier.cc")
VERBATIM = pathlib.Path("components/omnibox/browser/zero_suggest_verbatim_match_provider.cc")
RESULT = pathlib.Path("components/omnibox/browser/autocomplete_result.cc")

CLIPBOARD_MARKER = "// CMC: restore the clipboard provider on non-PC Android."
VERBATIM_MARKER = "// CMC: allow mobile page verbatim matches on non-PC Android."
GROUP_MARKER = "// CMC: use mobile zero-prefix groups on non-PC Android."


def replace_once(text, pattern, replacement, label):
    result, count = re.subn(pattern, lambda _: replacement, text, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"{label}: expected one source anchor, found {count}")
    return result


def patch_classifier(text):
    if CLIPBOARD_MARKER in text:
        if text.count(CLIPBOARD_MARKER) != 1 or "base::android::device_info::is_desktop()" not in text:
            raise ValueError("clipboard patch marker is inconsistent")
        return text
    if '#include "base/android/device_info.h"' not in text:
        raise ValueError("classifier device_info include missing")
    return replace_once(
        text,
        r"^#elif !BUILDFLAG\(IS_DESKTOP_ANDROID\)\s*\n"
        r"\s*AutocompleteProvider::Type::kClipboard\s*\|\s*\n"
        r"\s*AutocompleteProvider::Type::kMostVisitedSites\s*\|\s*\n"
        r"#endif",
        """#elif BUILDFLAG(IS_DESKTOP_ANDROID)
      // CMC: restore the clipboard provider on non-PC Android.
      (base::android::device_info::is_desktop()
           ? AutocompleteProvider::Type::kNone
           : AutocompleteProvider::Type::kClipboard) |
#else
      AutocompleteProvider::Type::kClipboard |
      AutocompleteProvider::Type::kMostVisitedSites |
#endif""",
        "clipboard provider mask",
    )


def patch_verbatim(text):
    if VERBATIM_MARKER in text:
        if text.count(VERBATIM_MARKER) != 1 or "base::android::device_info::is_desktop()" not in text:
            raise ValueError("verbatim patch marker is inconsistent")
        return text
    if '#include "base/android/device_info.h"' not in text:
        text = replace_once(
            text, r'^#include "build/android_buildflags.h"$',
            '#include "build/android_buildflags.h"\n'
            '#if BUILDFLAG(IS_DESKTOP_ANDROID)\n'
            '#include "base/android/device_info.h"\n'
            '#endif',
            "verbatim include",
        )
    text = replace_once(
        text, r"^constexpr bool is_desktop_android = !!BUILDFLAG\(IS_DESKTOP_ANDROID\);\s*\n",
        "", "verbatim desktop build constant",
    )
    return replace_once(
        text, r"^  if \(is_desktop_android\) \{\s*\n\s*return false;\s*\n  \}",
        """#if BUILDFLAG(IS_DESKTOP_ANDROID)
  // CMC: allow mobile page verbatim matches on non-PC Android.
  if (base::android::device_info::is_desktop()) {
    return false;
  }
#endif""",
        "verbatim eligibility",
    )


def patch_result(text):
    if GROUP_MARKER in text:
        if text.count(GROUP_MARKER) != 1 or "base::android::device_info::is_desktop()" not in text:
            raise ValueError("zero-prefix group patch marker is inconsistent")
        return text
    if '#include "base/android/device_info.h"' not in text:
        text = replace_once(
            text, r'^#include "build/android_buildflags.h"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("src", type=pathlib.Path)
    parser.add_argument("--check", action="store_true", help="validate anchors without writing")
    args = parser.parse_args()
    try:
        files = [(args.src / CLASSIFIER, patch_classifier),
                 (args.src / VERBATIM, patch_verbatim),
                 (args.src / RESULT, patch_result)]
        # Validate all anchors before writing any file.
        changes = []
        for path, patch in files:
            original = path.read_text(encoding="utf-8")
            updated = patch(original)
            changes.append((path, original, updated))
        for path, original, updated in changes:
            if original != updated and not args.check:
                path.write_text(updated, encoding="utf-8")
            print(f"{'already patched' if original == updated else 'compatible' if args.check else 'patched'}: {path}")
    except (OSError, ValueError) as exc:
        print(f"Refusing to apply an unverified patch: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
,
            '#include "build/android_buildflags.h"\n'
            '#if BUILDFLAG(IS_DESKTOP_ANDROID)\n'
            '#include "base/android/device_info.h"\n'
            '#endif',
            "result include",
        )
    return replace_once(
        text,
        r"^    \} else if constexpr \(is_android_mobile\) \{\s*\n"
        r"      if \(omnibox::IsNTPPage\(page_classification\)\) \{",
        """    // CMC: use mobile zero-prefix groups on non-PC Android.
    } else if (is_android_mobile
#if BUILDFLAG(IS_DESKTOP_ANDROID)
               || !base::android::device_info::is_desktop()
#endif
    ) {
      if (omnibox::IsNTPPage(page_classification)) {""",
        "zero-prefix section selection",
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("src", type=pathlib.Path)
    parser.add_argument("--check", action="store_true", help="validate anchors without writing")
    args = parser.parse_args()
    try:
        files = [(args.src / CLASSIFIER, patch_classifier),
                 (args.src / VERBATIM, patch_verbatim)]
        # Validate all anchors before writing either file.
        changes = []
        for path, patch in files:
            original = path.read_text(encoding="utf-8")
            updated = patch(original)
            changes.append((path, original, updated))
        for path, original, updated in changes:
            if original != updated and not args.check:
                path.write_text(updated, encoding="utf-8")
            print(f"{'already patched' if original == updated else 'compatible' if args.check else 'patched'}: {path}")
    except (OSError, ValueError) as exc:
        print(f"Refusing to apply an unverified patch: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
