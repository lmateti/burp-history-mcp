# 0.2.0

- Added text-range read using history ID and consistent UTF-16 coordinates.
- Added cooperative incremental searches preserving original Matcher state.
- Added exact deduplication across slices and provisional count semantics.
- Added up to three active idle-expiring search sessions without active-session eviction.
- Added immediately preceding cursor replay and explicit expired/old cursor errors.
- Added cancellation flag handling and documented unsafe/uninterruptible boundaries.
- Added discoverable input/output schemas and detailed model-facing instructions.
- Added structuredContent with compatibility text representation.
- Replaced smoke script with inspector supporting continuation, range read, and full validation.
- Bundled NanoHTTPD server.
