# Native Omnibox source patch

The prebuilt Google Chrome Desktop Android APK excludes clipboard matches in
`AutocompleteClassifier::DefaultOmniboxProviders` and ordinary page verbatim
matches in `ZeroSuggestVerbatimMatchProvider::IsVerbatimMatchEligible`. Those
compile-time gates cannot be repaired by Java display hooks.

For a Chromium source checkout:

```sh
python3 tools/patch_chromium_omnibox.py /path/to/chromium/src --check
python3 tools/patch_chromium_omnibox.py /path/to/chromium/src
```

Build Chromium with its normal Desktop Android configuration. The patch changes
only these two native gates to test the runtime PC/desktop form factor. It
does not enable MostVisitedSites on Desktop Android or change extension support,
global device identity, or clipboard access permissions.

No version numbers, binary offsets or R8 names are used. The tool refuses unknown
or ambiguous source layouts. It is idempotent and validates both files before
writing. This is a source-build path, not a runtime patch for a signed Google
Chrome APK. It is not an APK or a tested replacement for Google Chrome.
