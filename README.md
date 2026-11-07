# Desktop Chrome Init

LSPosed module for Google Chrome Desktop Android builds running on unsupported Android phone layouts.

## Compatibility model

The module no longer carries a table of R8-obfuscated Chrome symbols.

At process startup it reads the installed Chrome DEX files and resolves the required internals from structural signatures:

- ToolbarManager from its `initializeWithNative`-shaped method and stable Chromium field types;
- the Extensions coordinator field from the coordinator object's stable Chromium/Android field types;
- the synthetic Extensions `Supplier` from the object construction inside ToolbarManager initialization;
- the Extensions menu handler from its method signature and its reference to the resolved coordinator field.

This means ordinary R8 renames such as:

```text
hns -> jns -> gvs -> ...
rr9 -> ku9 -> ...
J1  -> L1  -> ...
a3  -> d3  -> ...
ums -> wms -> bus -> ...
```

do not require a module update.

Verified structurally against:

- Chrome 153.0.8010.49 / 801004974
- Chrome 153.0.8010.52 / 801005274
- Chrome 153.0.8010.53 / 801005374
- Chrome 154.0.8037.57 / 803705774

Chrome 155/156 and later are intended to work without new mappings as long as Chromium keeps the same underlying Extensions/Toolbar architecture. A semantic Chromium refactor can still require a module change; no implementation can guarantee compatibility with arbitrary future code changes.

## Temporary toolbar action cleanup (0.4.15)

On Chrome 158, unpinned extensions can now open using the temporary action
anchor introduced in v0.4.13. Their popup already closes normally, but the
temporary icon may remain in the phone Bottom Bar after popup dismissal.

The v0.4.14 outside-touch workaround targeted the wrong symptom and has been
reverted. Instead, the module now observes `PopupWindow.dismiss()` in Chrome
and, after Chromium's own `closePopup() -> undoPopout()` callbacks finish,
checks `ExtensionsToolbarBridge.hasActivePopup()` and
`hasPoppedOutAction()`. When both are false, it notifies the existing
action-list observer and refreshes the phone Bottom Bar layout. Pinned
actions are untouched. An unrelated popup dismissal cannot prematurely
remove a still-active temporary action.

Diagnostics (after restarting Chrome and reproducing the issue):

```sh
su -c 'logcat -d -v threadtime -b main | grep -E "DesktopChromeInit.*(PopupWindow.dismiss|popup dismiss cleanup|popup bridge)" | tail -n 100'
```

Expected: `installed PopupWindow.dismiss temporary popout cleanup` at
startup and `popup dismiss cleanup: temporary toolbar action removed`
when closing the temporary extension popup. Device verification is required.

## Unpinned Extensions popup fix (0.4.13)

Chromium 158's `ExtensionsToolbarBridge.triggerPopup()` has the Java
signature `(String, ExtensionActionPopupContents, boolean)`. The older
module incorrectly attempted to hook `(String, long, boolean)`, so the
popup width/temporary-anchor bridge could never be installed. Pinned
actions worked because they already had a toolbar anchor; unpinned
actions require a temporary popped-out anchor before the popup opens.

The hook now selects the unique native callback by its stable method name
and `(String, reference, boolean)` shape. The second parameter's actual
R8-renamed reference type is discovered at runtime. It logs installation
and each invoked action, allowing a direct check of the device behavior.

Use `su -c 'logcat -d -v threadtime -b main -s DesktopChromeInit:V'`
after force-stopping and relaunching Chrome. If recent logs have rotated,
start continuous capture before reproducing the issue. This is a source-
validated fix; live Chrome 158 testing is still required.

## Extension-only mobile experience (unmerged test, 0.4.20)

**Purpose:** use Chrome Desktop Android's compiled extension engine while
leaving the phone's normal Chrome UI and input behaviors untouched.

The module installs **only extension-related hooks**. The previous Omnibox
compatibility hooks and the temporary URL recovery hook have been removed.
No platform identity spoofing or global `DeviceInfo.isDesktop()` override is
performed. The phone's Android Bottom Bar, URL editing, suggestions, keyboard,
back gesture, and tab navigation remain Chrome-owned.

Chrome 158 already includes the native `EditUrlSuggestionProcessor`. On
non-desktop input devices, focusing an empty Omnibox may show the current
page title, URL, favicon, and Share/Copy/Edit buttons as the first zero-prefix
suggestion; the relevant `hasDesktopExperience()` test is not overridden.

**Build-time limitations.** A Desktop Android APK differs from a mobile APK
before this LSPosed module loads. Chrome 158 has build-variant defaults:

| Feature / path | Desktop build default | Extension dependency / decision |
| --- | --- | --- |
| `EnableAndroidSidePanel` | true | May be needed by extension side-panel API: do not disable blindly |
| `ChromeNativeUrlOverriding` | true | The extensions URL override registry depends on it: **preserve** |
| `UseWebUiNtpAndroid` | true | The WebUI NTP override additionally checks `DeviceInfo.isDesktop()` |
| `WebUiAndroidTheming` | true | Shared WebUI theming; not disabled without testing extension pages |
| Bookmark row display fallback | compact | Mobile layout requires a persisted preference or a Chromium-side change |

On a normal phone, `DeviceInfo.isDesktop()` should already be false because
the runtime device does not advertise `android.hardware.type.pc` (unless a
desktop command-line override is active). Thus many Desktop-exclusive runtime
paths are already inactive. Do not force it to true just to enable extensions.

**Strict meaning of “all Desktop elements removed”:** the module can leave
mobile UX unchanged and avoid activating Desktop-only UI, but cannot strip
compiled Desktop code or override every native build flag. Full parity would
require a Chromium Android build with extensions explicitly enabled while
using mobile build defaults.

**Regression checks:** CI rejects reintroduction of hard-coded Omnibox
class-name hooks or global desktop identity spoofing. This does not verify
runtime behavior. Device tests must cover: editing the URL, current-page
suggestion actions, keyboard, Back, normal tab switching, opening pinned and
unpinned extensions, closing an extension popup, and extension URL overrides.
No merge until these pass and the user explicitly requests it.

## Chrome 158 compatibility (0.4.12)

Chromium 158 still dispatches the Extensions app-menu action through
`ToolbarManager.getExtensionsToolbarCoordinator().showExtensionsMenu()`.
This module now also recognizes the getter call in the menu handler's DEX
bytecode (not only a direct coordinator field read), and treats the new
second Runnable captured by the Extensions supplier as an Omnibox focus
callback rather than incorrectly reusing the coordinator destruction callback.

**Status:** This adjustment is based on upstream Chromium 158 source. The
Chrome 158 release DEX has **not yet been verified on a device**; a successful
APK build alone does not prove the Extensions popup is working.

### Diagnostics

After reproducing a click that appears to do nothing:

```sh
su -c 'logcat -d -v threadtime | grep -Ei "DesktopChromeInit|InputMethodManager|ImeTracker|BackNavigation" | tail -n 200'
```

The key messages are `DEX symbols resolved`, `Extensions menu click`,
`source-exact repair: Runnable captures` and `Extensions-menu NPE`. If
the coordinator cannot be initialized, the module now reports the protected
null-pointer failure with a toast instead of silently ignoring it.

## What is being fixed

Desktop Chrome contains Extensions toolbar code intended for tablet/desktop toolbar layouts. On a phone layout, Chrome may skip creation of the Extensions coordinator or its synthetic factory may hard-cast the active toolbar to `ToolbarTablet`.

The module repairs the prerequisites before Chrome's own ToolbarManager initialization and lets Chrome create its own coordinator:

1. resolve the current build's relevant symbols directly from DEX;
2. inject `extensions_toolbar_container_stub` by stable resource name when the phone layout omits it;
3. intercept only the resolved synthetic Extensions `Supplier.get()`;
4. if that Supplier contains Chrome's `ToolbarTablet` hard cast, temporarily substitute a minimal `ToolbarTablet` while the factory executes;
5. immediately restore the real `ToolbarPhone`;
6. structurally replace retained references to the temporary toolbar with the real toolbar;
7. resolve and hook the current build's Extensions menu handler;
8. move Chrome's real Extensions toolbar container into the phone Bottom Bar / Custom Tab slot.

The normal path does not depend on obfuscated class, field, or method names.

## Failure behavior

Resolution is intentionally fail-closed.

If a future Chrome version changes the architecture enough that the structural resolver cannot identify a unique target, the module refuses to guess an R8 symbol. If the Extensions coordinator is unavailable, the Extensions action is suppressed rather than allowing Chrome to crash with a null coordinator.

Logs are prefixed with:

```text
DesktopChromeInit:
```

On a successfully resolved build, startup includes a line similar to:

```text
DEX symbols resolved: ToolbarManager=... supplier=... coordinator=... menu=...
```

## Build

```bash
gradle :app:assembleRelease
```

GitHub Actions builds and verifies a signed release APK and uploads it as the `DesktopChromeInit-release` artifact.

## Install

1. Install the module APK.
2. Enable it in LSPosed.
3. Scope it to Chrome (`com.android.chrome`).
4. Force-stop Chrome after installing/updating the module.
5. Launch Chrome.
6. Check the LSPosed log for `DesktopChromeInit: DEX symbols resolved`.
7. Open the Extensions menu and test pinned/unpinned extension popups.
