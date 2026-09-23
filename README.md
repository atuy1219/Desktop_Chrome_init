# Desktop Chrome Init

LSPosed module for Google Chrome Desktop Android builds running on unsupported Android devices.

Current verified target:

- Chrome **153.0.8010.53**
- versionCode **801005374**
- package **com.android.chrome**
- modern libxposed API **102**

The module declares a static scope containing only `com.android.chrome`, so it is not intended to load into other apps.

## What is being fixed

On Chrome Desktop Android running on a phone, opening the Extensions menu can crash with:

```text
java.lang.NullPointerException:
Attempt to write to field 'boolean rr9.f0' on a null object reference
at org.chromium.chrome.browser.ChromeTabbedActivity.a3(...)
```

Direct DEX inspection of 153.0.8010.53 / 801005374 confirms this path:

```text
ChromeTabbedActivity.a3(int, boolean, Bundle, u1h)
  -> zd4.P2()
  -> jns.J1 : rr9
  -> rr9.f0
```

The `.53` build keeps the same relevant mapping as `.52`:

- `jns` = ToolbarManager
- `jns.J1` = Extensions toolbar coordinator
- `wms` = coordinator Supplier
- `ems` = initialization Runnable

The regression in `.53` was therefore not an R8 rename of the coordinator path. The previous module still installed the menu repair with `hookAllMethods(ChromeTabbedActivity, "a3")`. Enumerating all methods on this Desktop build can force ART to resolve framework-only method signatures which do not exist on the phone OS, preventing the `a3` hook from being installed.

The module now resolves and hooks only the exact Extensions handler signature instead of enumerating every `ChromeTabbedActivity` method.

## Compatibility strategy

The compatibility work is split into two layers:

1. Structural resolution is used where practical: ToolbarManager discovery, initialize-with-native detection, toolbar/container discovery, extension coordinator discovery, and resource-name lookup.
2. The known Chrome 153 phone fallback still contains a small set of verified obfuscated mappings needed to reconstruct Chrome's own Extensions coordinator when the normal Desktop factory cannot run against `ToolbarPhone`.

Verified mappings:

- 153.0.8010.49 / 801004974: `hns`, `ums`, `cms`
- 153.0.8010.52 / 801005274: `jns`, `wms`, `ems`
- 153.0.8010.53 / 801005374: `jns`, `wms`, `ems`

This means the previous work was partially generic rather than fully version-independent. Future builds that keep the structural path working should not need a mapping update; changes to the coordinator fallback may still require DEX verification.

## Modern Xposed API

Version 0.4.0 no longer depends on the legacy `de.robv.android.xposed` API.

It uses:

- `io.github.libxposed:api:102.0.0`
- `META-INF/xposed/java_init.list`
- `META-INF/xposed/scope.list`
- `META-INF/xposed/module.prop`
- `staticScope=true`
- `exceptionMode=protective`

The large existing Chrome repair implementation is preserved behind a small module-local compatibility facade, but the runtime hook backend is the modern libxposed interceptor API. No legacy Xposed API dependency or legacy `assets/xposed_init` entry remains.

## Build

```bash
gradle :app:assembleRelease
```

GitHub Actions builds and signature-verifies the release APK and uploads it as the `DesktopChromeInit-release` artifact.

## Install

1. Install the module APK.
2. Enable the module in LSPosed.
3. Force-stop Chrome.
4. Launch Chrome and test the Extensions menu, pinned extension buttons, unpinned popup actions, and custom tabs.

The module's declared scope is fixed to **Chrome (`com.android.chrome`)**.

## Diagnostics

LSPosed log messages are prefixed with:

```text
DesktopChromeInit:
```

For the Extensions-menu crash, the important startup messages are the ToolbarManager resolution, initialize hook installation, coordinator creation, and exact `a3(int,boolean,Bundle,u1h)` hook installation.
