# Android mobile Chrome with Extensions (Chromium 158, experimental)

## Why the stock Desktop APK cannot display the standard current-page card

Device tests with v0.4.20 confirm that merely removing Desktop Java Omnibox
hooks **does not** bring back the Android current-page suggestion. This is a
native compile-time difference, not an LSPosed hook registration failure.

Chromium 158.0.8127.22 contains these complementary behaviors:

- `components/omnibox/browser/zero_suggest_verbatim_match_provider.cc`:
  `IsVerbatimMatchEligible()` returns `false` on ordinary web pages when
  `BUILDFLAG(IS_DESKTOP_ANDROID)` is true.
- `chrome/browser/ui/android/omnibox/java/src/org/chromium/chrome/browser/omnibox/suggestions/editurl/EditUrlSuggestionProcessor.java`:
  the native page card (title, URL, Share/Copy/Edit) is rendered only if the
  first supplied autocomplete suggestion is the active tab's verbatim match,
  and `hasDesktopExperience(context)` is false.

Thus the Desktop APK removes the upstream suggestion **before** the Java
processor sees it. Faking `hasDesktopExperience()` or restoring text with
`TextView.setText()` is not a complete fix.

Relevant sources:

- https://github.com/chromium/chromium/blob/158.0.8127.22/components/omnibox/browser/zero_suggest_verbatim_match_provider.cc
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/omnibox/java/src/org/chromium/chrome/browser/omnibox/suggestions/editurl/EditUrlSuggestionProcessor.java

## Preferred architecture: mobile Chromium + the extension core

The GN arguments have **separate** switches:

- `is_desktop_android = false` selects the ordinary Android mobile build,
  including `IS_DESKTOP_ANDROID=false` in native Omnibox code.
- `enable_desktop_android_extensions = true` compiles the extension bridge
  and `enable_extensions_core=true` while retaining the Android build.
  The `enable_extensions` flag remains false on Android as upstream intends.

See `chromium/args.mobile-extensions.gn` for the candidate configuration.
These independent arguments exist in Chromium 158:

- https://github.com/chromium/chromium/blob/158.0.8127.22/build/config/chrome_build.gni
- https://github.com/chromium/chromium/blob/158.0.8127.22/extensions/buildflags/buildflags.gni
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/extensions/BUILD.gn

**This combination has not been built or run by this project.** It may
encounter compile/link errors or extension runtime assumptions because
upstream primarily tests extensions with Desktop Android defaults. Do not
describe it as a working release until the binary and device tests pass.

### GN / Ninja proof-of-concept

After installing depot_tools, Android build dependencies, and checking out
the matching Chromium source:

```sh
# From Chromium src/:
gn gen out/MobileExtensions --args='
target_os="android"
target_cpu="arm64"
is_debug=false
is_desktop_android=false
enable_desktop_android_extensions=true
'
gn args out/MobileExtensions --list | grep -E 'is_desktop_android|enable_desktop_android_extensions|enable_extensions_core'
autoninja -C out/MobileExtensions chrome_public_apk
```

Upstream target `chrome_public_apk` is declared under
`chrome/android/BUILD.gn`. It normally produces a Chromium-branded APK,
not a Google-signed Chrome APK. Its package/signature cannot simply replace
the official `com.android.chrome` installation. The module's current
package-scope assumes `com.android.chrome`; a future compatible custom
Chromium build needs an intentional, validated package-scope configuration.
Do not uninstall an existing Chrome installation or expect its profile data
to migrate automatically.

### Acceptance tests on the resulting APK

1. Native Android toolbar and bottom bar, tab switcher, keyboard, back,
   settings, bookmarks, history and new-tab page behave like mobile Chrome.
2. On a regular HTTPS tab, focusing the address bar gives an empty editor
   and the active page's native Share/Copy/Edit card.
3. Extension installation, background service worker, permissions, content
   scripts and pinned/unpinned action popups work.
4. Extension URL overrides, popups, storage, runtime messaging and
   requested side-panel APIs work, or their unsupported behavior is clearly
   documented.
5. Upgrade to a newer Chromium release without relying on R8 Java names,
   then repeat acceptance tests.

## Existing desktop APK: honest scope of an LSPosed-only solution

The LSPosed module may remove some Desktop-only **Java** UI behavior or
show a custom Android page card. However, it cannot change the already
compiled `IS_DESKTOP_ANDROID` constant in `libchrome.so` by toggling a
Java `hasDesktopExperience()` result. Native binary patching could be
version-specific and fragile. Synthesizing native autocomplete matches from
Java requires additional API-specific hooks and would not be equivalent to
enabling the standard native provider.

Consequently, v0.4.20 is **not** a completed 'mobile Chrome with only
extensions' implementation. Keep its PR unmerged. Do not attempt a false
success claim by only adding another URL-focus Java hook.
