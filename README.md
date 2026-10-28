# Burp History MCP 0.2.0

Complete Java/Maven Burp extension, MCP server exposing one readonly MCP tool, `search_proxy_history`, supporting `search` and `read` operations with bundled NanoHTTPD server. 

## Build / install
JDK 21+ for Maven, Java release target 21; compatible Burp with Montoya 2026.7. First build needs Maven dependencies.

```sh
mvn clean verify
```

Open pom.xml as a Maven project in IntelliJ, reload dependencies, then clean/verify with the provided command. Load `target/history-mcp.jar`. Output prints endpoint and bearer token. Treat token as a secret.

## Default client connection information
- Streamable HTTP: `http://127.0.0.1:9877/mcp`
- Authorization: `Bearer TOKEN`
- Recommended request timeout: 30 seconds or more.
- MCP Protocol revisions implemented: 2025-06-18 and 2025-11-25. This is not the 2026 protocol revision.

MCP is manually implemented. JSON responses, no legacy SSE or stdio bridge supported and no server-initiated progress messages. POST requires Content-Length. Search/cursor namespace is extension-wide, not per-user.

## Search Example
```json
{
  "operation":"search",
  "pattern":"api[_-]?key",
  "target":"response_body",
  "context_before":200,
  "context_after":200,
  "max_matches":50,
  "work_budget_ms":5000,
  "url_regex":"https://app\\.example\\.com/.*",
  "in_scope_only":false
}
```

Defaults: operation=search; target=response_body; context=200 each; max_matches=50; work_budget_ms=5000. Java regex inline flags accepted, no slash delimiters. pattern 1..8192 chars; URL regex <=8192. URL filtering uses find semantics before message extraction. Scope uses current Burp scope.

Targets:
- response_body / request_body: Montoya bodyToString().
- response / request: Montoya full-message toString().
- both: full request and full response independently, NOT bodies-only.

Context: 0..10000 UTF-16 code units on each side of every match. Not lines, wire bytes, or a search limit. Context affects snippet equality. Matcher.find() visits non-overlapping matches, including zero-length matches. Multiple unique snippets can come from one response.

### Incremental results
Each call stops at a unique-result page limit (1..500), approximately 250000 snippet characters (can overshoot by one snippet), cooperative budget checkpoint (10..20000ms), or snapshot exhaustion.

```json
{
  "operation":"search",
  "results":[],
  "returned_results":0,
  "search_complete":false,
  "stop_reason":"work_budget",
  "next_cursor":"opaque-token"
}
```

This abbreviated example omits metrics. Empty pages with next_cursor are VALID. Continue even after an empty page to exhaust the search:

```json
{"cursor":"opaque-token"}
```

Cursor-only means no other arguments. Original pattern, filters, context, page size, work budget and live matcher state are retained. Completion can require an empty final page because page filling does not probe ahead. Next cursor null means exhausted. search_complete false means partial coverage, NOT a complete no-match answer. No guarantee time to first useful hit; budget calls may visit only duplicate or non-matching content.

### Counts and deduplication
One exact snippet string across any selected entries/targets/offsets is returned only once PER SESSION. No normalization or hashing. Dedup map persists across slices. Duplicate snippets within one response also collapse. Context windows are not merged unless their complete strings match.

Result includes snippet, occurrence_count_so_far, representative metadata (history_id, timestamp, URL, method, status if response exists, target, absolute match/snippet positions and relative match positions).

occurrence_count_so_far is recorded when its PAGE is emitted. Future pages can find duplicates of earlier results; those earlier count fields do not update. Final aggregate counts describe all examined matches, but do not retroactively make earlier per-result counts final.

Metrics: snapshot/examined/selected entries, messages/characters searched, raw_match_count_so_far, unique_snippet_count_so_far, duplicates_removed_so_far, history_access_ms, call_work_ms, total_work_ms, counts_final, stop_reason, offset_units. total_work_ms excludes client waiting, serialization and transfer. counts_final applies to aggregate counts only; count_note explains this in every result.

Snapshot is a list of references, reverse history-list traversal (not guaranteed chronological sort), excluding later additions. It does NOT freeze underlying edited messages or scope changes. Do not edit history/switch projects mid-search for reproducibility. Message decoding and compression behavior follows Montoya; verify your actual encoded responses. Binary content not automatically excluded.

## Read / expand without regex
```json
{
  "operation":"read",
  "history_id":1842,
  "target":"response_body",
  "offset":42000,
  "length":12000
}
```

Use history_id and target from a representative. Use match_start or snippet_start as coordinates. length defaults 12000, range 1..100000. offset may equal total_length (empty result); greater offsets fail. End clamps to message length. Both forbidden. No context/search arguments accepted.

Output: operation, history_id, target, start, end, total_length, text, offset_units, has_more_before, has_more_after, url, method.

This is TEXT range access, not raw bytes. UTF-16 offsets must never be treated as byte offsets. Non-BMP characters use two units; a boundary can fall between surrogate halves. JSON escaping preserves representation; consumers must handle UTF-16 semantics. Copy coordinates exactly from the same target.

No HTTP requests or regex scans are performed. History ID lookup currently traverses the history list; selected message is still materialized as text. Therefore read is not constant-time or a guaranteed disk-range read. Reads do not invalidate or touch session expiry. If entry was removed, project changed, or response missing, explicit error. Changed message content can make old offsets point elsewhere; no immutable historical-version guarantee.

## Session lifecycle
Up to 3 ACTIVE search sessions by default. Starting a search does not evict active sessions; capacity error is explicit. Sessions expire 10 minutes after their last successful search/continuation call (idle TTL). Expiry discards state. A range read does not refresh search TTL. To free abandoned sessions, wait for expiry or unload/reload; there is no separate close operation in this version.

Cursor tokens are opaque. The immediately preceding continuation can be retried idempotently; older cursors return an explicit error. A new successful advance makes the older replay token invalid. Completed sessions retain only the last response/replay token, not matcher/history/dedup state; starting a new search clears completed replay records. Initial calls cannot be retried idempotently by request ID; a repeated initial call starts a distinct search. Clients should use distinct JSON-RPC request IDs, especially with multiple connections.

One tool call at a time across sessions. Concurrent calls receive a retryable tool error, not a hidden queue. Reads also use this gate. Independent request threads allow cancellation notifications while search is running.

## Cancellation and timeout limits — important
Supports notifications/cancelled with params.requestId. Cancellation flag is checked between regex matches/entries and during read lookup. Cancellation discards affected search session; cancelled response is unused (HTTP 202). On a plain socket/client timeout without notification, server does not automatically infer cancellation. Never assume a hard network timeout returns usable partial results.

work_budget_ms is COOPERATIVE, not a hard latency bound. A single Matcher.find(), URL regex, history API call, decoding, or allocation can exceed it. There is no per-character deadline wrapper: interrupting mid-find would leave matcher resumability uncertain. Normal slice exits happen only at safe boundaries. Java regex can catastrophically backtrack or overflow its stack; use trusted patterns. This version does not sandbox Java regex or guarantee recovery from OOM/StackOverflowError. For adversarial patterns, a non-backtracking engine or process isolation is future work.

A single huge message/expensive find can STILL trigger your harness timeout. Raise client timeout, narrow pattern/target, or address that case explicitly; do not describe this as eliminated by slicing. Snapshot time is outside the per-slice matcher budget and included separately in metrics. This trade-off is deliberate and visible.

## Limits / tuning
Java system properties before extension load:
- historymcp.port=9877
- historymcp.maxSessions=3
- historymcp.sessionTtlMs=600000
- historymcp.maxUnique=100000 (per session)
- historymcp.maxRetainedChars=16000000 (unique snippet UTF-16 lengths per session)
- historymcp.maxSnippetChars=200000 (individual extracted snippet)
- historymcp.maxActiveTextChars=128000000 (one decoded selected message)
- historymcp.token (optional preset)
- historymcp.allowedOrigin (one exact Origin; default rejects all requests with Origin).

Resource-limit/error/cancellation discards affected search session, never silently evicts seen snippets or claims completeness. Results already emitted remain partial. Narrow query/start new session after fixing error. Character limits are not exact JVM heap caps: metadata, strings, snapshots, matchers, page copies and JSON consume additional heap. Up to 3 sessions each retaining large active text can use substantial memory. Materialization can allocate BEFORE size check.

## Model-facing documentation
src/main/resources/tool.json is the exact tools/list definition. Includes oneOf branches for new search, read, and cursor-only; descriptions of context units, continuation including empty pages, and counts; and output schema for both operations. Successful responses supply both structuredContent and serialized JSON text content (MCP compatibility), so a client that exposes both may incur duplicate token cost. Error results use isError=true and text error content, no success payload.

## References
https://modelcontextprotocol.io/specification/2025-06-18/server/tools
https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation
https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/proxy/Proxy.html
