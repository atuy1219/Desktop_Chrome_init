import unittest

from patch_chromium_omnibox import patch_classifier, patch_verbatim, patch_result


class SourcePatchTest(unittest.TestCase):
    def test_classifier(self):
        src = '''#include "base/android/device_info.h"
#elif !BUILDFLAG(IS_DESKTOP_ANDROID)
      AutocompleteProvider::Type::kClipboard |
      AutocompleteProvider::Type::kMostVisitedSites |
#endif
'''
        patched = patch_classifier(src)
        self.assertIn("base::android::device_info::is_desktop()", patched)
        self.assertEqual(patched, patch_classifier(patched))
        self.assertEqual(patched.count("kMostVisitedSites"), 1)

    def test_verbatim(self):
        src = '''#include "build/android_buildflags.h"
constexpr bool is_desktop_android = !!BUILDFLAG(IS_DESKTOP_ANDROID);
  if (is_desktop_android) {
    return false;
  }
'''
        patched = patch_verbatim(src)
        self.assertIn("base::android::device_info::is_desktop()", patched)
        self.assertEqual(patched, patch_verbatim(patched))

    def test_grouping(self):
        src = '''#include "build/android_buildflags.h"
    } else if constexpr (is_android_mobile) {
      if (omnibox::IsNTPPage(page_classification)) {
'''
        patched = patch_result(src)
        self.assertIn("|| !base::android::device_info::is_desktop()", patched)
        self.assertEqual(patched, patch_result(patched))

    def test_unknown_source_rejected(self):
        for patch in (patch_classifier, patch_verbatim, patch_result):
            with self.subTest(patch=patch.__name__):
                with self.assertRaises(ValueError):
                    patch("unknown source")

    def test_ambiguous_source_rejected(self):
        anchor = '''    } else if constexpr (is_android_mobile) {
      if (omnibox::IsNTPPage(page_classification)) {
'''
        with self.assertRaises(ValueError):
            patch_result('#include "build/android_buildflags.h"\n' + anchor + anchor)


if __name__ == "__main__":
    unittest.main()
