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
