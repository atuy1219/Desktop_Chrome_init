# Chrome Mobile Compatibility

LSPosed compatibility module for using Desktop Chrome on Android phones with mobile Chrome UI and touch behavior while retaining desktop-only features such as extensions.

## 0.5.8-clipboard-text (test only)

- Share PNGs are now committed as exact upstream blobs; CI verifies them offline to avoid GitHub HTTP 429 failures.

- Fallback page icon uses the exact Chromium 159 filled continent globe vector from ui/android/java/res/drawable/ic_globe_24dp.xml rather than a generic longitude/latitude globe. Real favicon remains first choice.
- Clipboard fallback now supports copied text and HTTP(S) links; follows Chromium ClipboardSuggestionProcessor's search/globe decoration and reveal/conceal eye assets. The stable fixed-height fallback row inherits native suggestion text metrics/colors and stays hidden by default. Tap refines the Omnibox; user submits manually. It is not a native autocomplete provider.
- Fresh single-item text only, no sensitive-flagged items, no unknown/OTR profile, no startup clipboard reads, no persistence or logging of contents; revalidate clip timestamp before reveal/refine. Native candidate takes precedence. Currently attached to the current-page fallback flow.
- Chromium artwork BSD notices in APK assets. Device verification required.

## 0.5.7-leading-favicon (test only)

- Explicitly bind the page's decoration ImageView, mobile Chrome's 24dp icon size, no favicon tint and rounded outline. Measure actual native decoration/title geometry. If the unbound/inlined SuggestionLayout has no usable leading slot, insert a dedicated leading sibling and reserve the required width, preserving the existing share/copy/edit row.
- A direct Chrome FaviconHelper local database request is resolved by the source name or a verified call site from TabFavicon's async method. Never select the identical foreign-favicon signature by guesswork. Uses the active tab's own profile and verified GURL only; no outside requests, no profile fallback. Retains TabFavicon retrieval and bounded retries when the local route is unavailable.
- Uses explicit icon reference rather than a recursive first-ImageView heuristic. Logs geometry, availability and callback status without page contents. Device verification required.

## 0.5.6-omnibox-icons (test only)

- All page-card renderers use the actual Chrome share/copy/edit drawables when available. No Android framework save/share/edit icon substitutions. Copy/edit fallback geometry is from Chromium 159's original BSD-licensed vector assets; share fallback uses Chromium's original LTR/RTL PNG artwork, committed to the repository and verified against upstream Git blob IDs offline during CI. No download is needed. Icons use a common small-icon size and tint.
- TabFavicon discovery uses matching JNI signatures, not obfuscated Tab names. Re-resolves cached failures, prefers the live Tab class loader, distinguishes from(Tab) from get(Tab) by its object-construction instruction, creates a native TabFavicon user-data object if needed, requests Chrome's own local favicon DB via Promise, and uses bounded retries. Only the current card receives a valid bitmap and its tint is cleared. No external favicon service or network request is introduced.
- Source references: Chromium EditUrlSuggestionProcessor, OmniboxDrawableState, TabFavicon, components/embedder_support/android/java/res/drawable/ic_content_copy.xml and components/browser_ui/styles/android/java/res/drawable/ic_edit_24dp.xml (Chromium BSD license).
- Device verification required.

## 0.5.5-integrated-fallback (test only)

- Consolidates PR #21 into PR #23: current-page card, copy/share/edit actions, Chrome view reuse, recent URL clipboard fallback, favicon discovery, and measured Bottom Bar glyph alignment.
- These are temporary Java UI fallbacks, not restored native candidate generation. The page fallback skips an existing native edit-card adapter item when detectable. Clipboard fallback remains URL-only and requires a confirmed non-incognito profile. Native view roles are inherited from PR #21 and not guaranteed across future releases.
- PR #23's modern module identity, working native bootstrap and null-tag Vector logging take precedence over earlier experimental implementations. All migrated UI diagnostics use the same logging bridge.
- Independent PR #22 is a historical device-state diagnostic; not required for the main module. Source-rebuild recipes from PR #21 remain historical because the target is official Google Chrome.
- Real-device testing is still required; no native function patch is installed.

## 0.5.4-native-probe (test only)

- Vector logging: all Java framework log calls use a null tag; native snapshot reports are returned to Java via JNI and emitted through the same libxposed logging bridge. Native early loader messages remain available in logcat.

- Official Google Chrome APK remains unchanged; no source rebuild, no synthetic suggestion UI.
- Integrates PR #21's read-only ELF diagnostic using libxposed 102's getModuleApplicationInfo() instead of PackageManager/classloader string heuristics. Explicit JNI load is independent of optional native_init callback support.
- The Omnibox desktop capability override now applies only inside the actual edit-card eligibility method. All other calls retain their original result. Existing IME workaround and extension layout hooks remain.
- Native candidate generation and zero-prefix grouping are separate compiled-out paths. No native patch is installed yet. The diagnostic records actual mapped Chrome ELF build IDs and symbol visibility; absent symbols are not guessed. No URL, page text or clipboard contents are read.
- Install the test build (disable old com.atuy.desktopchromeinit), enable and scope com.atuy.chromemobilecompat to com.android.chrome, force-stop Chrome and launch. After opening Omnibox, run:

```sh
su -c 'grep -rhF "com.atuy.chromemobilecompat:" /data/adb/lspd/log | tail -n 150'
```

- Offline analysis: `python3 tools/inspect_chrome_native.py <apk> [more APKs...]`. Include the Trichrome library package if Chrome uses one. Do not merge until real-device verification.

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
su -c 'logcat -d -v threadtime -b main | grep -E "ChromeMobileCompat.*(PopupWindow.dismiss|popup dismiss cleanup|popup bridge)" | tail -n 100'
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

Use `su -c 'logcat -d -v threadtime -b main -s ChromeMobileCompat:V'`
after force-stopping and relaunching Chrome. If recent logs have rotated,
start continuous capture before reproducing the issue. This is a source-
validated fix; live Chrome 158 testing is still required.

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

On unsupported phone devices that install the Desktop Android Chrome APK, the
module additionally attempts two isolated Omnibox hooks:

- `OmniboxCapabilities.hasDesktopExperience(Context)`: historical 0.4.12 workaround retained the URL. Limited to the card eligibility call in 0.5.3; the global desktop identity remains untouched.
- `OmniboxFeatures.isDebounceKeyboardVisibilityEnabled()`: disable the
  internal 150 ms IME visibility debounce and use immediate show/hide requests
  instead. This is an experimental workaround for stale keyboard state; it
  does not repair a system-level InputDispatcher or navigation-gesture failure.

Both changes are applied only when the Chrome package has an Extensions menu
resource and Android does not report `android.hardware.type.pc`. If R8 has
renamed the relevant Java classes/methods in the installed Chrome APK, the
hooks fail safely and log that they were not installed.

### Diagnostics

After reproducing a click that appears to do nothing:

```sh
su -c 'logcat -d -v threadtime | grep -Ei "ChromeMobileCompat|InputMethodManager|ImeTracker|BackNavigation" | tail -n 200'
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
ChromeMobileCompat:
```

On a successfully resolved build, startup includes a line similar to:

```text
DEX symbols resolved: ToolbarManager=... supplier=... coordinator=... menu=...
```

## Build

```bash
gradle :app:assembleRelease
```

GitHub Actions builds and verifies a signed release APK and uploads it as the `ChromeMobileCompat-release` artifact.

## Install

1. Disable/uninstall the previous Desktop Chrome Init module, if installed, then install the new module APK.
2. Enable it in LSPosed.
3. Scope it to Chrome (`com.android.chrome`).
4. Force-stop Chrome after installing/updating the module.
5. Launch Chrome.
6. Check the LSPosed log for `ChromeMobileCompat: DEX symbols resolved`.
7. Open the Extensions menu and test pinned/unpinned extension popups.
