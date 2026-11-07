# Chrome Desktop State Probe

Independent, read-only libxposed API 102 module for understanding what the
installed Chrome APK thinks about the Android device. Does **not** modify the
browser. Targets the main `com.android.chrome` process.

### Installation and isolation

1. Install `ChromeDesktopStateProbe.apk`.
2. In LSPosed / Vector, enable **Chrome Desktop State Probe** with only
   `com.android.chrome` scoped.
3. Disable **Desktop Chrome Init** for Chrome to measure the unmodified
   Desktop Chrome APK. No installed extensions need to be removed: the states
   checked here are device and build predicates, not extension preferences.
4. Force-stop and reopen Chrome, then collect:

```sh
su -c 'am force-stop com.android.chrome'
su -c 'logcat -c'
su -c 'monkey -p com.android.chrome 1' >/dev/null
sleep 5
su -c 'logcat -d -v threadtime -b main' \
  | grep 'ChromeDesktopProbe.*CHROME_STATE' \
  | tail -n 160
```

Some Chrome classes live in `split_chrome.apk`; the probe samples after
`Application.attach`, `onPackageReady`, and/or Activity resume.

### Results

Every line is classified by evidence:

- **DIRECT:** method or field value read inside the Chrome process.
- **ANDROID:** actual Android OS hardware/Configuration API value.
- **CHROME_RESOURCE:** Chrome's own resource bucket, used for form factor.
- **DERIVED_CHROMIUM_158:** source-derived evaluation of the stock Chromium
  158 formula, using observed component values.
- **UNAVAILABLE:** an original Chromium class/method/field is R8-obfuscated,
  optimized away, not loaded, or inaccessible. This is **not** `false`.

Primary output:

- `DeviceInfo.isDesktop()`
- `OmniboxCapabilities.hasDesktopExperience()`
- `OmniboxCapabilities.isDesktopPlatform()`
- `OmniboxCapabilities.hasPrecisionPointerExperience()`
- `OmniboxCapabilities.areAnimationsEnabled()`
- `DeviceFormFactor.isNonMultiDisplayContextOnTablet()`
- `DeviceInput.supportsAlphabeticKeyboard()`
- `DeviceInput.supportsPrecisionPointer()`
- `BuildConfig.IS_DESKTOP_ANDROID`, when readable
- Chrome flags: Android Side Panel, WebUI NTP, WebUI theming, and native URL
  overriding (when readable)

The module does not log URL, page title, browsing history, clipboard,
accounts, or typed text.

### Important limitation

The native `BUILDFLAG(IS_DESKTOP_ANDROID)` is compiled into Chromium C++.
It cannot be evaluated or changed by this Java diagnostic. A Java
`BuildConfig.IS_DESKTOP_ANDROID` field, if still present, is useful evidence
of the build flavor, but not a direct measurement of native code state.

R8 obfuscates `OmniboxCapabilities` in the official Chrome release, so the
actual Java method result may be `UNAVAILABLE`. The source-derived
`DERIVED.hasDesktopExperience` is printed **only if all dependencies are
readable**; it is not silently labeled DIRECT.

The probe is a separate APK and separate module ID. It can be removed without
altering Desktop Chrome Init, user data, Chrome settings, or extensions.
