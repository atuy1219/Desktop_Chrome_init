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

## Native row retention when R8 optimizes action creation (v0.4.23 — TEST ONLY)

The v0.4.22 device log shows that Chrome's genuine ViewHolder factory WORKED.
The error came from creating three action buttons, not from constructing
the Chrome suggestion row. Therefore v0.4.23 never discards a genuine
BaseSuggestionView merely because its R8-optimized action builder is
inaccessible. The original dark/light Chrome content, typography, dimensions
and background remain. Only the three action buttons are placed in a
right-aligned transparent overlay when their native LayoutParams are
unavailable. The width is read from Chrome's actual
`omnibox_suggestion_action_button_width` dimension; each icon is drawn
with Chrome's own drawable and action foreground and, when possible,
an instance of the real ActionButtonView discovered from a live row.

Expected log if R8 prevents direct action creation:

    Current-page card native: native BaseSuggestionView kept; action buttons use measured native-layout overlay
    Current-page card native: using original Chromium EDIT_URL_SUGGESTION BaseSuggestionView with external action overlay

The old independent two-row card is now a final fallback ONLY for failure
to construct the native suggestion view. v0.4.23 remains test-only; check
native-like horizontal icon layout, theme contrast, sharing, copy, edit and IME.

## Chromium native current-page view reuse (v0.4.22 — TEST ONLY)

The v0.4.21 custom overlay made text and controls too dark and had an
unlike-Chrome second action row. Chromium 158 does **not** use a separate
special layout for the mobile Edit URL card:
`OmniboxViewHolderFactory` registers `EDIT_URL_SUGGESTION = 1` with
`BaseSuggestionView(R.layout.omnibox_basic_suggestion)` and the standard
`SuggestionViewViewBinder`; `EditUrlSuggestionProcessor` supplies page
text, favicon, and the three action buttons.

v0.4.22 first asks the *already running* OmniboxSuggestionsDropdown's own
ViewHolder factory to construct its `EDIT_URL_SUGGESTION` view. This reuses
Chrome's **actual** XML, typography, icon positions, hit areas, selection
foreground and sizing code, without requiring the R8-obfuscated class names.
The three native `ActionButtonView` instances receive the full verified
Tab URL and Share/Copy/Edit click handlers.

This does not reinstate the C++ `ZeroSuggestVerbatimMatchProvider` and
does not call the native `EditUrlSuggestionProcessor.populateModel()`, since
the Desktop Android build never sends it a valid suggestion. It is native
*view* reuse with external data and actions, not a native autocomplete match.

If the Chrome factory has been removed by R8 or cannot be found, v0.4.22
falls back to v0.4.21's view but derives text colors and the selectable
background from a real on-screen Omnibox suggestion to avoid illegible
dark-on-dark text. The log distinguishes the two paths.

**Validate in Chrome 158, night/light and incognito:** native-view log,
first-row title/URL, action placement, Share/Copy/Edit, keyboard, scrolling,
focusing/unfocusing, and zero-prefix suggestions. No merge before testing.

Source:
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/omnibox/java/src/org/chromium/chrome/browser/omnibox/suggestions/OmniboxViewHolderFactory.java
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/omnibox/java/src/org/chromium/chrome/browser/omnibox/suggestions/base/BaseSuggestionView.java
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/omnibox/java/src/org/chromium/chrome/browser/omnibox/suggestions/base/SuggestionLayout.java

## Native-like current-page suggestion and measured Bottom Bar (0.4.21, TEST ONLY)

Chromium 158 Desktop Android builds suppress the ordinary active-page URL
verbatim result in `ZeroSuggestVerbatimMatchProvider` using
`BUILDFLAG(IS_DESKTOP_ANDROID)`. Merely disabling Omnibox Java hooks cannot
restore it. The stock Java `EditUrlSuggestionProcessor` needs that
zero-prefix suggestion in position zero.

`CurrentPageCardFix` provides a **native-like Android view** as the first
row of Chrome's already-existing Omnibox suggestions container. It does not
inject fake autocomplete results or change Chrome's URL editing or IME code.

- When the unfocused URL bar gains focus and the input becomes empty, resolve
  the current Chrome `Tab` and its **full** GURL. Do not use the shortened
  displayed hostname as the clipboard/share/edit target.
- On normal HTTP/HTTPS pages, show the page title (fallback: host), URL and
  Share/Copy/Edit actions above the existing suggestion list.
- Actions share through the Android chooser, copy the full URL, or refine the
  URL bar for editing. A favicon is used when directly available from Tab;
  otherwise an icon fallback is displayed.
- Hide the extra row immediately when typing begins or the URL bar loses focus.
- If current-tab URL resolution is unavailable due to R8 or an unexpected
  Chrome refactor, fail closed and log a diagnostic instead of showing a
  misleading URL or interfering with Chrome's autocomplete.

**Limit:** this reproduces the UI and user actions but is not Chrome's
native `EditUrlSuggestionProcessor`; native metrics and internal share-sheet
integration are not claimed. Tests on the actual release APK are required.
The implementation uses Android lifecycle/View APIs, Chrome resource IDs and
validated runtime Tab getters, with structural reflection fallback instead
of hard-coded obfuscated Java class names.

### Bottom Bar icon alignment

Chromium 158's `BottomBarView.applyDynamicDimensions()` changes native
button padding when the bar height/top inset changes. Its
`BottomBarConfigUtils.getBottomBarHeightDp()` clamps a requested height to
**48–60 dp** and defaults to 56 dp. A requested 40 dp therefore does **not**
produce a 40 dp Bottom Bar in this source version. The extension container
originates from the tablet-only `extensions_toolbar_container.xml` and uses
different height/margin attributes from native Bottom Bar buttons.

In 0.4.21, the module uses the **measured pixel height and the actual icon
content-box center** of Chrome's `new_tab_button` (fallback: tab switcher or
menu) to align the entire extension cluster vertically via translation Y.
No static 40/56/60 dp offset or required flag value. The native bar's
positions and slot widths are not moved. The Custom Tab-specific layout is
unchanged.

Source references (exact version):
- https://github.com/chromium/chromium/blob/158.0.8127.22/components/omnibox/browser/zero_suggest_verbatim_match_provider.cc
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/omnibox/java/src/org/chromium/chrome/browser/omnibox/suggestions/editurl/EditUrlSuggestionProcessor.java
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/bottombar/java/src/org/chromium/chrome/browser/ui/bottombar/BottomBarView.java
- https://github.com/chromium/chromium/blob/158.0.8127.22/chrome/browser/ui/android/bottombar/java/src/org/chromium/chrome/browser/ui/bottombar/BottomBarConfigUtils.java

**Device checks before merge:**
1. Tap address bar on a normal HTTPS page: editor remains empty; title, full
   URL and Share/Copy/Edit appear at top, preceding other suggestions.
2. Share, Copy and Edit use the **full** URL. Typing and blurring remove the
   card cleanly, leaving native suggestions/keyboard working.
3. Repeat with ordinary, incognito, loading, and native (new-tab) pages.
4. Check 40, 48, 56 and 60 dp flag values, top/bottom address bar and
   changed device font size. Compare Extensions vs new-tab glyph **center**.
5. Check pinned/unpinned extension popups and temporary icon cleanup.

PR #21 must remain **UNMERGED** until the user runs and accepts the device tests.

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
