# Desktop Chrome Init

LSPosed module for Google Chrome Desktop Android builds running on unsupported Android devices.

This repository currently targets:

- Chrome **153.0.8010.49**
- versionCode **801004974**
- package **com.android.chrome**

## What is being fixed

On the target build, opening the Extensions menu can crash with:

```text
java.lang.NullPointerException:
Attempt to write to field 'boolean rr9.f0' on a null object reference
at org.chromium.chrome.browser.ChromeTabbedActivity.a3(...)
```

Direct inspection of the target APK shows that the crashing path is:

```text
ChromeTabbedActivity.a3(...)
  -> zd4.P2()
  -> hns.J1        // rr9 / ExtensionsToolbarCoordinatorImpl
  -> rr9.f0 = true // crashes when J1 == null
```

For this build:

- `hns` corresponds to `ToolbarManager`
- `hns.l(...)` corresponds to `ToolbarManager.initializeWithNative(...)`
- `hns.J1` stores the Extensions toolbar coordinator
- `rr9` is the obfuscated `ExtensionsToolbarCoordinatorImpl`

The normal creation block in `hns.l(...)` is skipped when either:

1. `extensions_toolbar_container_stub` is missing from the current toolbar layout, or
2. the Chrome Android task supplier (`hns.D1`) returns null.

The module repairs those prerequisites and replays only the coordinator-creation portion instead of re-running the entire ToolbarManager initialization.

## Repair strategy

When the Extensions menu is selected, the module:

1. obtains `ToolbarManager` via `zd4.P2()`;
2. checks `hns.J1`;
3. if needed, retries Chrome's own `ChromeActivity.initializeChromeAndroidTask` path;
4. finds or injects `extensions_toolbar_container_stub`;
5. reconstructs Chrome's synthetic Extensions coordinator supplier using the values captured from `hns.l(...)`;
6. asks Chrome's existing Android-task cache to create/retrieve the coordinator;
7. stores the result back into `hns.J1`;
8. lets the original `ChromeTabbedActivity.a3(...)` continue.

If the repair cannot be completed, the module suppresses only this known Extensions-menu NPE instead of allowing Chrome to terminate. The failure reason is written to the LSPosed log.

## Build

```bash
gradle :app:assembleRelease
```

A GitHub Actions workflow also builds the APK and uploads it as an artifact.

## Install

1. Build/install the module APK.
2. Enable it in LSPosed.
3. Scope it to **Chrome (`com.android.chrome`)**.
4. Force-stop Chrome.
5. Launch Chrome and open the Extensions menu.
6. If it still does not open, inspect the LSPosed log for entries beginning with `DesktopChromeInit`.

## Compatibility

The repair code intentionally contains obfuscated symbol names from versionCode `801004974`. Those names may change in later Chrome builds. The menu detection itself also checks the resource name `extensions_menu_menu_id`, but the internal repair is considered build-specific.
