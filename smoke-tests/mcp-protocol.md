# MCP Plugin Smoke Test Protocol

Run this after every `ide_install_plugin` + `ide_restart` cycle to verify the new
build works before committing or raising a PR.

---

## Meta-protocol

### Prerequisites — enable the exercised tools

Most tools this protocol exercises are **disabled by default** and hidden from `tools/list`:
`ide_set_power_save_mode`, `ide_open_project`, `ide_close_project`, `ide_install_plugin`,
`ide_restart`, `ide_set_lifecycle_log_file`, and every lifecycle tool except
`ide_project_status` (see `DEFAULT_DISABLED_TOOLS` in `McpSettings.kt`). The contract checks below
also need `ide_symbol_info`, `ide_find_symbol`, `ide_file_structure`, and `ide_change_signature`.
Enable them first in
Settings → Tools → Index MCP Server → Exposed Tools, or every affected step fails with a
disabled-tool error on a fresh install.

### How to call the server

Do NOT rely on the Claude session's MCP tool schema — it is captured at session start
and goes stale the moment the plugin restarts. Call the server directly via HTTP:

```bash
curl -s -X POST http://127.0.0.1:29170/index-mcp/streamable-http \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"<tool>","arguments":{...}}}'
```

Port 29170 is IntelliJ IDEA. Other IDEs: see `IdeProductInfo.kt` for the full map.

**Both `Accept` values are required.** The Streamable HTTP spec mandates
`Accept: application/json, text/event-stream`, and since 5.0.0 the server enforces it —
`Accept: application/json` alone returns `406 Not Acceptable`. Real MCP clients already send
both; only hand-written curl needs updating.

### The install-restart-verify cycle

1. `./gradlew buildPlugin` — produces `build/distributions/<name>-<version>.zip`
2. Call `ide_install_plugin` with `project_path` pointing to this project
3. Call `ide_restart` — waits for "Restarting IDE." response
4. Poll the server until it responds (typically 15–30s):
   ```bash
   curl -s --max-time 3 http://127.0.0.1:29170/index-mcp/streamable-http \
     -X POST -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
   ```
5. Confirm restart actually happened: `restarter.log` in `~/Library/Logs/JetBrains/IntelliJIdea*/`
   must have a new timestamped entry. If not, the restart was intercepted — see fallback below.
6. **Reinstall the companion skill** — click "Get Companion Skill" in the Index MCP Server
   tool window (bottom panel), or run:
   ```bash
   mkdir -p ~/.claude/skills/ide-index-mcp
   cp -r <plugin-project>/src/main/resources/skill/ide-index-mcp/. ~/.claude/skills/ide-index-mcp/
   ```
   The skill content changes with each plugin build. Without this step, AI assistants may
   use the wrong IntelliJ MCP server (`mcp__intellij__*` instead of `mcp__intellij-index__*`)
   because the routing guidance is stale or missing.
7. Run the tests below.

### Diagnosing "old code still running"

The new code is not loaded if error messages exactly match what was seen before the
install. Compare error text — even one word difference means the new code is running.
Check the installed jar timestamp:
```bash
ls -la ~/Library/Application\ Support/JetBrains/IntelliJIdea*/plugins/jetbrains-index-mcp-plugin/lib/*.jar
```

### Fallback when `ide_restart` doesn't fire

`app.restart(true)` is async. If `restarter.log` shows no new entry after 10s:
```bash
pkill -x "idea"; sleep 3; open "/Applications/IntelliJ IDEA.app"
```

### MCP availability guarantees

The lifecycle manager never closes the last open managed project — it stays in `dormant`
to keep MCP reachable. If all projects are somehow closed anyway (e.g., the user manually
closes the last window), calling any tool **without** `project_path` automatically reopens
a managed-closed project. MCP self-heals without user intervention.

If no managed projects were ever enrolled (nothing in `closedProjectPaths`), automatic
recovery cannot happen. In that case open a project manually:
```bash
open -a "IntelliJ IDEA" /path/to/any/project
```
Check the log file directly for events that occurred while all projects were closed:
```bash
cat ~/Library/Logs/JetBrains/IntelliJIdea*/mcp-lifecycle.log
```
File writes require enabling file output first — `ide_set_lifecycle_log_file { "enabled": true }`
or debug logging; see F9 below.

---

## Test cases

Use `project_path` on every call — multiple projects are typically open.

```bash
PP=/path/to/jetbrains-index-mcp-plugin   # adjust to actual path
```

---

### 1. Plugin alive — `ide_index_status`

- Call: `ide_index_status` with `project_path`
- PASS: response contains `isDumbMode` field (true or false)
- FAIL: error or no response

---

### 2. `ide_set_power_save_mode` — round trip

- Call with `enabled: true` → PASS: success, message contains "enabled"
- Call with `enabled: false` → PASS: success, message contains "disabled"
- Call with no `enabled` param → PASS: `isError: true`, message contains "enabled"
- FAIL on any: crash, hang, or generic error

---

### 3. `ide_open_project` — validation

- Call with `path: "/nonexistent/path"` and `project_path`
- PASS: `isError: true`, message contains "Failed to open" or the path
- FAIL: HTTP 500 with empty body (unhandled exception), or hangs

---

### 3b. `ide_close_project` — last-project guard

- With a single project open, call `ide_close_project` with `project_path`
- PASS: `isError: true`, message contains "last open project" — the tool refuses to close
  the last open project so the MCP server keeps a serving context
- With 2+ projects open the call succeeds and schedules the close. Do not run that variant
  during active development; it closes the window.

---

### 4. `ide_install_plugin` — error paths

**4a. Nonexistent zip:**
- Call with `path: "/nonexistent/plugin.zip"`, `project_path`
- PASS: `isError: true`, message contains "not found"

**4b. Wrong extension:**
- Call with `path: "/some/file.jar"`, `project_path`
- PASS: `isError: true`, message contains ".zip"
- FAIL: message says "not found" — means extension is checked AFTER existence

**4c. No build output:**
- Call with no `path`, `project_path` pointing to a project without `build/distributions/`
- PASS: `isError: true`, message contains "buildPlugin" or "No plugin zip"

---

### 5. `ide_install_plugin` — auto-detect from this project

- Call with no `path`, `project_path` pointing to this project (after `./gradlew buildPlugin`)
- PASS: `isError: false`, message contains "installed from" and a plugin ID
- FAIL (zip format): "Could not read plugin ID from META-INF/plugin.xml" — means
  `readPluginId` is searching the zip root but plugin.xml is inside `lib/<plugin>.jar`
  (Gradle Plugin 2.x format). Fix: update `readPluginId` to walk nested jars.
- FAIL (no zip): "No plugin zip found" — run `./gradlew buildPlugin` first

---

### 6. `ide_restart` — silent restart

- After install, call `ide_restart` with `project_path`
- PASS: response received with "Restarting IDE.", then `restarter.log` gains a new
  timestamped entry within 15s
- FAIL: response received but no `restarter.log` entry — likely a save-dialog prompt
  intercepted the restart. Fix: `saveAll()` before `restart(true)`, called via `edtAction {}`

---

## Semantic contract smoke tests

Use a disposable project opened in the installed IDE. Include Java fixtures with overloaded
methods, a local variable, callers, a small inheritance chain, and a call chain longer than two
nodes. Include this physical Kotlin source (or an equivalent one) in a Kotlin-enabled module:

```kotlin
package smoke

interface Contract
class Implementation : Contract
enum class State { READY }
annotation class Marker
object Singleton

fun anonymous(): Contract = object : Contract {}
```

The Kotlin checks must run against the **installed IDE and its bundled Kotlin plugin**. The opt-in
`-PkotlinPluginTests=true` fixtures cover Kotlin class kinds and safe-delete parameters;
this installed-IDE smoke test additionally checks the complete protocol and tool interaction.

### S1. Fresh `initialize` → `tools/list` schema

1. Discard the MCP connection that existed before plugin install/restart. MCP clients such as
   Codex cache `ALL_TOOLS`; reconnect or restart the client before judging the schema.
2. Send a fresh initialize request:

   ```json
   {
     "jsonrpc": "2.0",
     "id": 1,
     "method": "initialize",
     "params": {
       "protocolVersion": "2025-06-18",
       "capabilities": {},
       "clientInfo": { "name": "index-mcp-smoke", "version": "1" }
     }
   }
   ```

3. On that fresh connection, call `tools/list`.
4. PASS: `capabilities.tools.listChanged` is absent or `false`, never `true`.
5. PASS: semantic/refactoring schemas expose `symbolId` and structured `target`; the `target`
   object exposes `symbolId`, `position`, `qualifiedName`, and `language` properties.
6. PASS: `ide_refactor_rename`, `ide_refactor_safe_delete`, and `ide_change_signature` each expose
   boolean `dryRun`.
7. FAIL: only a pre-update schema is visible until the client reconnects. That is stale client
   state, not a reason to advertise `listChanged: true`; the server does not yet send/serve that
   notification on every transport.

### S2. `symbolId`, unified targets, overloads, locals, and line shifts

1. Discover an overloaded Java method and a local variable through semantic search/definition.
2. PASS: results include opaque `sym_...` handles and each handle resolves the exact overload/local
   through `ide_symbol_info` with `{ "target": { "symbolId": "..." } }`.
3. Resolve the same declaration twice. PASS: both handles are valid even if they are unequal; do
   not interpret ID equality as symbol equality.
4. Insert lines above the declaration outside the IDE, call `ide_sync_files` for the changed file,
   and reuse the original handle. PASS: it resolves at the new line.
5. Preview and then apply a rename through that handle. PASS: references update and
   `updatedSymbol.symbolId` resolves the renamed declaration with current metadata.
6. Exercise all three nested variants — `target.symbolId`, `target.position`, and
   `target.qualifiedName` + `language`. PASS: each resolves; mixing nested `target` with any legacy
   top-level selector returns an error.
7. Restart the MCP server and retry the old handle. PASS: `SYMBOL_ID_EXPIRED`; rediscovery is
   required and the server does not fall back to saved coordinates.

### S3. Refactoring previews are byte-for-byte non-mutating

For each of `ide_refactor_rename`, `ide_refactor_safe_delete`, and `ide_change_signature`:

1. Record SHA-256 for every file in the disposable fixture (for example with
   `find <fixture> -type f -exec shasum -a 256 {} + | sort`).
2. Call the refactoring with `dryRun: true`. Include a Java overloaded method/caller case; for safe
   delete, cover both a referenced symbol (`canApply: false` without `force`) and an unused symbol.
3. PASS: the response has `dryRun: true`, `canApply`, populated current `target`, an
   operation-specific `plannedChange`, `affectedFiles`, `usageCount`, `conflictCount`, `warnings`,
   and `elapsedMs`.
4. Recompute SHA-256. PASS: the checksum list is byte-for-byte identical; no file appeared,
   disappeared, or changed, no document was saved, and Undo has no preview command.
5. Send the same request without `dryRun`. PASS: rename/change signature updates the exact overload
   and its callers; safe delete removes the selected symbol/file and invalidates its handle.

### S4. Kotlin metadata and structured file outline

1. Run `ide_find_class`, `ide_find_symbol`, `ide_symbol_info`, and `ide_file_structure` against the
   physical Kotlin fixture above.
2. PASS: `Contract`, `Implementation`, `State`, `Marker`, and `Singleton` are classified as
   `INTERFACE`, `CLASS`, `ENUM`, `ANNOTATION`, and `OBJECT` respectively where class-kind metadata
   is returned.
3. PASS: named declarations use `smoke.*` qualified names from Kotlin PSI.
4. PASS: the object expression is named like
   `<anonymous implementation of Contract at <file>:<line>>` and has `qualifiedName: null`.
5. PASS: `ide_file_structure` retains the formatted `structure` string and adds `nodes`; every node
   contains `name`, `kind`, nullable `signature`, `modifiers`, 1-based `line`, nullable `endLine`,
   `children`, and nullable `symbolId`.
6. Reuse a child node's ID with `ide_symbol_info`. PASS: it resolves that exact PSI declaration,
   including when two declarations are placed on nearby lines.

### S5. Hierarchy bounds and continuation

1. Call `ide_type_hierarchy` on the Java hierarchy with `maxNodes: 2`.
2. PASS: at most two non-root nodes are returned and expanded; `returnedNodes` matches the page,
   `returnedNodes == traversal.length`, `truncated == hasMore`, `elapsedMs` is present, and
   `cursor` is non-null when `hasMore` is true, unless `truncationReason` reports a terminal
   retention limit. That case retains the computed page and requires a narrower fresh query. Every traversal entry has direction `supertype` or
   `subtype` and an `element` matching the corresponding legacy direction array.
3. Continue with `{ "cursor": "...", "maxNodes": 2 }` until `hasMore: false`.
4. PASS: concatenated `traversal` pages contain no duplicate nodes and preserve the observable
   combined breadth-first order. Repeating the fresh query produces the same traversal order.
   `supertypes` and `subtypes` remain backward-compatible filtered views; do not concatenate them
   to verify combined order because that loses interleaving.
5. Repeat for `ide_call_hierarchy` in both directions with a depth that spans multiple pages.
   PASS: `calls` is a flat BFS page and never exceeds `maxNodes`; nodes have stable `nodeId`,
   `parentId`, and `depth` for their first-discovery tree. Without `maxNodes`/`cursor`, call and
   type hierarchies still return the legacy nested trees and per-node limits.
6. PASS: using a type cursor with the call tool, using a cursor through another project instance,
   or reusing it after MCP restart returns an explicit cursor error and asks for a fresh query.
7. Do not wait for push/live progress: stateless HTTP exposes progress only through page metadata.

### S6. Multi-file diagnostics and deleted-path sync

1. Call `ide_diagnostics` with two files, one containing a known error.
2. PASS: `problems` is one aggregate list and `fileAnalyses` contains one entry per requested path
   with `file`, `mode`, `fresh`, `timedOut`, and optional `message`.
3. PASS: `file` + `files` together is rejected. `line`, `column`, `startLine`, or `endLine` with
   `files` is rejected. A single `file` still returns the legacy top-level analysis metadata and
   supports intentions/range filtering.
4. Include enough slow files to exhaust the configured diagnostics timeout. PASS: the call uses one
   overall budget and marks remaining file analyses timed out; it does not wait a new full timeout
   per file.
5. Create and externally delete a nested fixture path, then call `ide_sync_files` with that deleted
   relative path.
6. PASS: `requestedPaths` echoes it, `deletedPaths` contains it, and `refreshedRoots` names the
   nearest existing parent actually refreshed. A following semantic search no longer sees the
   deleted declaration.
7. PASS: an absolute path, a `..` path, a symlink escape, or a `project_path` outside the selected
   project/content roots is rejected before any member of the batch is refreshed.

---

## Lifecycle tests

### F1. `ide_get_project_modes`

- Call with `project_path`
- PASS: `isError: false`, response contains `managed_projects` array and `total` count

---

### F2. `ide_set_project_mode` — validation

- Call with `mode: "INVALID_MODE"` → PASS: error listing valid modes (active, background, dormant, closed)
- Call with no `mode` → PASS: error mentioning "mode"
- Call with `mode: "background"` → PASS: success
- Call with `mode: "active"` → PASS: success

---

### F3. `ide_set_all_project_modes` — validation and bulk

- Call with `mode: "closed"` → PASS: error — closed is not valid for this tool
- Call with `mode: "background"` → PASS: success, response reports count of projects changed
- Call with `mode: "active"` → PASS: restores active state

---

### F4. `ide_release_project` — idempotency

- Enroll project first: call `ide_set_project_mode` with `mode: "active"`
- Call `ide_release_project` → PASS: success, response contains project name
- Call `ide_release_project` again → PASS: success, response contains "not managed"
- FAIL on second call: response says "released" again — means `AbstractMcpTool.execute()`
  re-enrolled the project before `doExecute` checked managed state. Fix: set
  `participatesInLifecycle = false` on `ReleaseProjectTool`.

---

### F5. `ide_project_status` — combined view

- Call with `project_path`
- PASS: `isError: false`, response contains `projects` array and `summary` object
- PASS: the project we routed through appears with `"open": true`
- PASS: `summary` contains `open`, `managed`, `open_not_managed`, `managed_closed` keys
- FAIL: `isError: true` when only one project is open — this tool should work with a
  single open project and no `project_path` argument

**No-project-path variant (single project open):**
- Call with no `project_path`
- PASS: same structure as above; the one open project is selected automatically

---

### F6. `ide_lifecycle_log` — ring buffer and log file

**6a. Basic response structure:**
- Call with `project_path`, `limit: 5`
- PASS: `isError: false`, response contains `events` array, `log_file` string, `buffered` integer
- PASS: `log_file` path ends with `mcp-lifecycle.log`
- PASS: `buffered` ≥ 0 (may be 0 immediately after restart before any events)
- PASS: each event in `events` has `timestamp`, `project`, `path`, `event`, `trigger` fields

**6b. Path filter:**
- Call with `project: "nonexistent-xyz"` → PASS: `events` array is empty, not an error
- Call with `project: "<basename of project_path>"` → PASS: only events for that project

**6c. Limit:**
- Call with `limit: 1` → PASS: at most 1 event returned
- Call with `limit: 500` → PASS: up to 500 events, no error

**6d. File gating:**
- Before enabling debug logging, verify no new lines appear in the log file after MCP
  tool calls (the ring buffer fills but the file does not)
- Enable via Help → Diagnostic Tools → Debug Log Settings:
  add `#com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle`
- Make a tool call to generate a lifecycle event
- PASS: new line appears in the log file within seconds, no restart needed
- FAIL: no new line — check that the category was entered correctly (leading `#` required)

---

### F7. Lifecycle state machine — transitions visible in log

This verifies the full enroll → transition → log pipeline end-to-end.

1. Call any code intelligence tool (e.g. `ide_index_status`) with `project_path` to
   trigger auto-enroll if not already managed
2. Call `ide_set_project_mode` with `mode: "dormant"` and `project_path`
3. Call `ide_lifecycle_log` with `project_path`
4. PASS: events include a `transition` event with `"from": "background"` (or `"active"`),
   `"to": "dormant"`, `"trigger": "mcp_call"`
5. Call `ide_index_status` again with `project_path` (any tool call wakes a dormant project)
6. Call `ide_lifecycle_log` again
7. PASS: events include a `wake` event with `"from": "dormant"`, `"to": "background"`,
   `"trigger": "mcp_call"`

**Expected event sequence (newest first):**
```json
{ "event": "wake",       "from": "dormant",     "to": "background", "trigger": "mcp_call" }
{ "event": "transition", "from": "background",   "to": "dormant",    "trigger": "mcp_call" }
{ "event": "enroll",     "trigger": "mcp_call" }
```

---

### F8. Auto-open from CLOSED — end-to-end

This is the core lifecycle feature. It is the slowest test (5–15s for indexing).

1. Call `ide_set_project_mode` with `mode: "closed"` and `project_path`
   - PASS: success; the project window closes
2. Immediately call `ide_index_status` with `project_path` (the now-closed project)
   - The call should block while the project reopens and indexes
   - PASS (after 5–30s): `isError: false`, `isDumbMode: false`
   - FAIL: `isError: true` with `"no_project_open"` — means `resolveOrOpen` is not
     recognising the path as a managed-closed project. Check `wasClosedByUs` returns true
     and that `reopenAndAwaitSmartMode` completes without the HTTP timeout aborting it.
3. Call `ide_lifecycle_log` with `project_path`
4. PASS: events include `"event": "opened"`, `"trigger": "auto_open"` for the project
5. PASS: `ide_project_status` now shows that project as `"open": true`

**Timing note:** If step 2 times out (curl `--max-time` exceeded), the project may
still be opening in the background. Wait 10s and retry the call — it should succeed
immediately if indexing completed. The `NonCancellable` wrapper in `reopenAndAwaitSmartMode`
ensures the open completes even if the first HTTP call timed out.

---

### F9. Monitoring — reading the lifecycle log for anomalies

Enable file output once (no restart needed), either over MCP:

```
ide_set_lifecycle_log_file { "enabled": true }
```

or via Help → Diagnostic Tools → Debug Log Settings:
add `#com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle` (see F6d).

Then watch the log:

```bash
tail -f ~/Library/Logs/JetBrains/IntelliJIdea*/mcp-lifecycle.log
```

Or query via MCP: `ide_lifecycle_log { "limit": 30 }`

**Anomaly patterns to watch for:**

| Pattern in log | What it means |
|----------------|---------------|
| `timer:focus` waking a `dormant` project | Focus alarm fires after inactivity alarm — the two timers are racing. |
| Projects cycling `background→dormant→background` repeatedly | Inactivity and focus timers leapfrogging each other. |
| `auto_open` immediately followed by `timer:inactivity→dormant` | Project auto-opened but no MCP call followed. |
| A project showing `timer:close→closed` you didn't expect | The dormant-to-closed window may be too short for your workflow. |
| `last_project_kept` trigger in the log | The lifecycle manager would have closed a project but held it dormant because closing it would drop the open managed count below the configured minimum (default 4). Normal and expected. |
| No `auto_open` after routing an MCP call to a managed-closed project | `wasClosedByUs` check not matching — likely a path normalisation mismatch. |

---

### F10. MCP availability — verifying the last-project and auto-recovery guarantees

**F10a. Last-project stays dormant:**

1. Enroll exactly one project: `ide_set_project_mode { "mode": "background" }`
2. Wait for `timer:inactivity` → dormant, then `timer:close` fires
3. Check the lifecycle log — PASS: log shows `last_project_kept` trigger instead of `dormant→closed`
4. PASS: `ide_project_status` still returns `open: true` for the project in `dormant` mode

**F10b. Auto-recovery when all projects are manually closed:**

1. Manually close the last IntelliJ project window (File → Close Project)
2. Call any MCP tool **without** `project_path`:
   ```
   ide_index_status
   ```
3. PASS: tool succeeds — a managed-closed project was auto-opened to restore routing
4. FAIL: `no_project_open` error with no recovery — means no managed projects were in
   `closedProjectPaths` (this can happen if the user closed projects that were never enrolled)
