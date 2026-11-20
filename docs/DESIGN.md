# EdenMcp Design

## Overview

EdenMcp is a Paper plugin that exposes a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for a Minecraft server instance. It allows MCP-capable clients (agents, editors, ops tooling) to inspect and operate the server through a controlled, auditable capability surface.

The project is structured as a small multi-module Gradle build:

- **`api`** – published, stable contract for third-party hooks (`EdenMcpApi`, `Hook`, tool/resource types). No dependency on `core`; released through JitPack.
- **`core`** – MCP server, transport, capability/mode enforcement, tool registries, and `HookManager`. Depends on `api`; no plugin bootstrap logic.
- **`plugin`** – Paper plugin entrypoint (`EdenMcpPlugin`). Assembles the system: loads server-side configuration, wires lifecycle, and registers/boots built-in hooks from `hooks/*` into `core`.
- **`hooks/<name>`** – One module per integrated plugin/API (currently `hooks/luckperms` and `hooks/spark`). Each hook contributes tools/resources for that specific target and is isolated from other hooks.

This separation keeps protocol and policy logic testable in `core` while letting `plugin` own Bukkit/Paper lifecycle concerns and hook assembly.

## Goals

- Provide a useful MCP interface for server administration and debugging, including remote access and admin-level text file inspection/editing under the server directory.
- Enforce a clear safety boundary through explicit operating modes that can be changed at runtime.
- Require authenticated access (access key) for all MCP tool/resource interactions.
- Allow optional plugin integrations (hooks) to extend the MCP surface without hard dependencies.
- Keep available hooks and their enablement server-authoritative.

## Non-goals (initial)

- Remote multi-server orchestration.
- A general-purpose arbitrary code execution tool (belongs outside MCP or behind explicit unsafe policy with strong warnings).
- Client-side control over which hooks exist on the server (clients discover server-provided capabilities only).

## Operating Modes

EdenMcp runs in exactly one mode at a time. The mode is configured on the server (default at startup) and **can be changed at runtime** by an authorized caller (see Access Control). Mode determines the maximum capability set exposed and executable.

| Mode | Intent | Allowed capability classes |
| --- | --- | --- |
| `safe` | Inspection and diagnostics. Default. | Read-only observation: logs, directory listings and any UTF-8 text file under the server directory, metrics, player/world metadata reads, listing resources. No mutations, no commands, no messages. |
| `moderate` | Routine operator assistance. | Everything in `safe`, plus low-risk actions: sending messages/broadcasts and other explicitly classified "safe-mutation" operations, including hook actions marked moderate. No console commands, no world/data mutation, no permission or economy changes, no hook actions marked dangerous. |
| `unsafe` | Full administrative control. | Everything in `moderate`, plus console/command dispatch, creating/overwriting text files under the server directory, privileged hook actions, and any tool classified as dangerous. |

**Runtime mode changes**
- Exposed as an authorized control operation (`edenmcp.mode.set` / `edenmcp.mode.get`) at `MODERATE_ACTION`, so it is callable only with a valid access key in `moderate` or `unsafe` mode, never in `safe`.
- Mode changes take effect immediately for subsequent tool discovery and execution.
- All mode changes are audited (previous mode, new mode, caller/session, timestamp).
- `unsafe` transitions must be prominently logged.
- By default an authenticated client may escalate to `unsafe` at runtime. Operators who want to force console-only escalation can set `mcp.policy.allow-unsafe-at-runtime: false` (default `true`); the server console command `mcp mode set unsafe` is unaffected by this lock. Command dispatch can never be used to escalate modes.

**Invariants**
- A tool is registered with a required mode/capability level; the effective registry exposed to MCP is filtered by the active mode.
- Escalation is never implicit: a `safe` server never advertises or executes `moderate`/`unsafe` tools.
- Tool descriptions state side effects and required mode so clients can plan correctly.

## Transport & Access Control

### Transport

- **Primary/preferred transport:** HTTP with SSE (remote-capable), suitable for MCP clients connecting over the network.
- The server binds to a configured host/port (separate from the Minecraft game port). Remote operation on normal hosting platforms is a supported deployment model, so the MCP endpoint must be usable without requiring SSH port-forwarding or an external reverse proxy.
- **TLS:** to protect the access key and data in transit, the transport enables **built-in TLS** by default. On first startup the plugin generates a self-signed certificate/keypair (stored in the plugin data folder) and enables TLS, logging the certificate **SHA-256 fingerprint** for out-of-band verification (trust-on-first-use/pinning by the client). The generated keystore and its random password file are written owner-only; there is no hardcoded keystore password. TLS 1.2/1.3 with AEAD ciphers only is enforced. Operators may instead supply their own keystore (`mode: custom`) or enable optional mTLS with a truststore (`require-client-cert` + truststore). TLS can be explicitly disabled for trusted-network or tunneled deployments, but this produces a prominent warning because plain HTTP exposes the access key and tool I/O.
- **Sessions:** `initialize` issues an `Mcp-Session-Id` (returned as a header and in the result). `tools/list`, `tools/call`, `resources/list`, `resources/read`, and `notifications/initialized` require a valid session; `ping` is a connectivity check that does not. Sessions are bound to the principal (key id) that created them, expire after an idle timeout (`mcp.transport.session-idle-seconds`, default 1800), are capped (`mcp.transport.max-sessions`, default 100), and are invalidated on key rotation. `/message` accepts the session id via header or `sessionId` query parameter. These are internal defaults; clients handle them automatically.
- **Response cap:** every serialized JSON response is bounded by `mcp.transport.max-response-chars` (default 1,000,000; minimum 512). Cap enforcement operates on whole JSON values rather than byte slices: structured tool/resource payloads drop entries and report `truncated: true` plus `omittedCount`, strings are truncated with an explicit `[truncated]` marker, and an oversized JSON-RPC batch drops whole response entries and appends a truncation error response so the batch stays valid. The JSON-RPC envelope (`jsonrpc`, `id`, `result`/`error`) and MCP `content[].type`/`text` and `isError` shape are preserved. Normal payloads are serialized once for `content.text` and once for the final envelope; the redundant intermediate `toJson` -> `toJsonTree` conversion in `rpcResult` is gone. Oversized payloads only pay an extra parse/cap pass.
- **Call deadlines:** tool execution and resource reads run on a dedicated virtual-thread-per-task executor, not the bounded HTTP worker pool. Each call has `mcp.transport.call-timeout-seconds` (default 30; minimum 1) to complete; on expiry the task is cancelled, a clean error tool result / JSON-RPC error is returned, and the audit outcome is `timeout`. Cancellation interrupts the virtual thread, but work that ignores interruption may keep running in the background; it can no longer block the client.
- **Request shape limits:** before any recursive GSON conversion of a request, the parsed tree is walked iteratively (explicit stack, no recursion) and rejected with HTTP 400 / JSON-RPC `-32600` if nested objects/arrays exceed `mcp.transport.max-json-depth` (default 64; minimum 8) or the total object-key count is excessive. A `StackOverflowError` at the request boundary (parser or dispatch) is caught specifically and converted to the same clean 400 rather than escaping into the HTTP handler. Protocol string fields (`method`, tool `name`, resource `uri`, ...) are read strictly: JSON numbers, booleans, arrays and objects are never coerced into strings.

### Authentication (access key)

- **All access requires a key.** Every MCP interaction (tool/resource listing, resource reads, and tool invocation) must present a valid access key.
- **Credential transport:** the key is accepted only as `Authorization: Bearer <key>`. Query-string keys (`?key=`/`?access_key=`) and raw `Authorization` values without the `Bearer` scheme are rejected; presented values are compared as exact bytes (no trimming). A `WWW-Authenticate: Bearer` header is returned on 401. The deprecated `X-Eden-Key` alias is opt-in via `mcp.auth.allow-legacy-header` (default `false`) for migration only.
- **Key provisioning (initial):** on first startup, if no key exists, the plugin generates a 256-bit (64 hex char) random key and writes it to a static server-side `.key` file in the plugin data folder, owner-only. No key provisioning is required from the operator; the key is copied to authorized clients out-of-band. Rotation/multiple keys are optional hardening, not required for a fresh install.
- **Key validation and recovery:** on load, a `.key` file must be a regular, owner-only file containing exactly 64 lowercase hex characters. An invalid, world-readable, or symlinked file is quarantined as `.key.invalid` and replaced with a fresh key, with a loud warning — never a startup failure.
- The key is stored/managed server-side only; it is never chosen by, nor accepted as authoritative configuration from, the client.
- Missing/invalid keys are rejected before any tool is exposed or executed. Repeated failures from a source IP are throttled (`mcp.auth.max-failures` / `mcp.auth.block-seconds`, defaults 10 / 60).
- Keys must be compared in constant time (`MessageDigest.isEqual`), never logged, and never returned by config/status tools.
- **Rotation (optional):** `mcp key rotate` from the server console writes a fresh key to `.key`, closes sessions bound to the old principal, and keeps the old key valid only in memory for `mcp.auth.rotation-grace-seconds` (default 300). The console output points to the `.key` file rather than printing key material. The grace period is intentionally in-memory only and is lost on restart.
- **Multiple keys (optional):** owner-only key files in `mcp.auth.multi-key-directory` (default `keys`, relative to the data folder) each add an independently revocable principal with an id derived from the filename. Absent the directory, the default path remains a single `.key`.
- **Host/Origin checks:** the allowed `Host` set is derived automatically from `mcp.transport.bind` (plus loopback names), so no new config is needed; `Origin` is validated when present. Wrong or disallowed values are rejected (403). With a wildcard bind and no `allowed-hosts`, non-browser clients are allowed any `Host` (browsers remain constrained by `Origin`). No CORS headers are emitted by default; when `mcp.transport.allowed-origins` is configured, exact matches are echoed and `OPTIONS` preflight is answered. Optional `mcp.transport.allowed-hosts` / `allowed-origins` lists cover reverse-proxy/DNS setups.
- The access key is the primary authorization gate; transport security is still required on untrusted networks. With built-in TLS enabled (default, including the generated self-signed certificate), the key and data are encrypted in transit; if TLS is explicitly disabled, operators must rely on a trusted network, tunnel, or reverse proxy and accept the risk of plaintext exposure.

## Capability Model

Rather than scattering mode checks, each tool declares capabilities/annotations:

- `level`: `SAFE_READ | MODERATE_ACTION | UNSAFE_ACTION` (minimum mode required).
- `sideEffects`: `NONE | OBSERVABLE | MUTATING | DANGEROUS` (for auditing/client display).
- `tags`: e.g. `logs`, `config`, `chat`, `profiling`, `commands`, `permissions`, `mode`.

Mode check is centralized: `canExecute(activeMode, tool.level)`. The active mode is the primary ceiling; server-side policy (e.g. temporary restrictions) may further constrain execution but clients do not configure server capabilities.

Suggested mapping:
- `safe` → permits `SAFE_READ`
- `moderate` → permits `SAFE_READ`, `MODERATE_ACTION`
- `unsafe` → permits all levels

> "Everything is fully exposed" is interpreted as: the server does not hide or curate available tools by capability class beyond the active mode/auth gate—tools at or below the current mode are exposed to authenticated clients. Exposure is therefore governed by **access key + active mode** (and server-side hook availability), not by client-side tool selection.

## MCP Surface (initial)

### Safe (read-only) tools/resources
- `edenmcp.logs.read` – read recent server log lines with optional filter/limit (bounded).
- `edenmcp.file.list` (`SAFE_READ`) – list entries under a directory in the server root (empty/`"."` path lists the root itself). Entries carry name, server-relative path, type and size; results are sorted and entry-count bounded, with `truncated` reporting when the cap hid more entries. Optional `recursive` descends up to a capped `depth` (symlinks are never followed), and an optional glob `pattern` filters server-relative paths.
- `edenmcp.file.read` (`SAFE_READ`) – read any UTF-8 text file under the server directory, supplied as a relative path. Paths are confined to the server root: absolute paths, `..` traversal, and symlinks resolving outside the root are rejected. Reads are size-bounded, decode UTF-8 strictly (malformed sequences are rejected), reject NUL/binary content, and require a regular file. Optional zero-based `offset` and `limit` return a line-aligned slice with `lineStart`, `lineCount` and `hasMore`. Configuration is read from the config files with this tool; no separate config-snapshot tool/resource is exposed.
- `edenmcp.file.write` (`UNSAFE_ACTION`) – create or overwrite a UTF-8 text file under the same root; parent directories must already exist. The write goes to a temp file in the same directory and is moved into place atomically (falling back to a plain move where atomic moves are unsupported), preserving the original POSIX permissions. Only advertised/executable in `unsafe` mode and also checked inside the tool.
- `edenmcp.server.status` – version, TPS/MSPT, player counts, uptime, loaded worlds.
- `edenmcp.players.list` – online players and available metadata.
- `edenmcp.plugins.list` – installed plugins and enabled state.
- `edenmcp.hooks.list` – server-provided hooks and their availability/enabled state (read-only discovery).
- `edenmcp.mode.get` – current operating mode.
- Hook-provided read tools (e.g. LuckPerms user, permission, group, track and audit lookups) when available.
- Hook-provided privileged mutations (e.g. LuckPerms permission/group/track changes) when classified `UNSAFE_ACTION` and allowed by policy.
- Spark (`SAFE_READ`): `edenmcp.spark.stats.cpu`, `edenmcp.spark.stats.tps`, `edenmcp.spark.stats.mspt`, `edenmcp.spark.stats.gc`, `edenmcp.spark.profiler.info`, and `edenmcp.spark.data.list`/`read`/`inspect` for bounded access to locally saved raw profile/heap files. Raw protobuf inspection is schema-free and done locally; no data is uploaded.

### Moderate tools
- `edenmcp.chat.send` – send a message to a player or broadcast; rate-limited and attributable (prefix/logged).
- Hook-provided moderate actions explicitly marked safe-mutation, including `edenmcp.spark.profiler.control` and `edenmcp.spark.diagnostics.run`. Spark results are always saved locally (`--save-to-file`); uploading is not exposed. Note: although `edenmcp.spark.diagnostics.run` is classified `MODERATE_ACTION`, `command=heapdump` has an effective **UNSAFE** sub-gate and reports `DANGEROUS` response metadata; it is rate-limited to one invocation per 60 seconds and writes a potentially multi-GB heap dump to a local file (optionally after a GC via `runGcBefore`).

### Unsafe tools
- `edenmcp.command.dispatch` – execute a console command (**`UNSAFE_ACTION` by default**, highest risk; strongly audited).
- Hook-provided privileged actions (e.g. permission mutations, if a hook exposes them) and any hook tool marked dangerous.

**Server-configurable command classification:** the server defines an ordered list of regex rules to override the required level for specific commands, reclassifying matched commands as `MODERATE_ACTION` or `SAFE_READ` where appropriate. Each rule pairs a `level` with a regex `pattern` matched against the dispatched command string; rules are evaluated in order and the **first match wins**. Patterns are **always anchored to the whole command**: unanchored rules are automatically wrapped as `^(?:...)$` with a warning, so a rule cannot downgrade a command by matching a prefix. Before matching, commands are normalized (leading `/` removed, whitespace collapsed) and any command containing control characters or separators (`;`, `&&`, `||`, backticks, `$()`) is rejected and treated as `UNSAFE_ACTION`, preventing a SAFE rule from hiding a chained dangerous command. At config parse time a lightweight linear validator rejects rules with nested quantifiers (such as `^(a+)+$`) or quantified alternation (such as `^(a|aa)+$`), naming the offending rule, so an admin typo cannot cause catastrophic backtracking on every dispatch. Overrides are server-side configuration only, evaluated before execution (in addition to the active mode gate), and all command executions remain audited. Unmatched commands keep the default `UNSAFE_ACTION` classification. Crucially, `edenmcp.command.dispatch` itself declares `UNSAFE_ACTION`, so per-command classification can never make the generic dispatch tool callable below `unsafe` or be used to escalate modes.

### Control tools
- `edenmcp.mode.set` – change operating mode at runtime (`MODERATE_ACTION`, authorized, audited). Escalation to `unsafe` is allowed by default but can be locked by an operator via `mcp.policy.allow-unsafe-at-runtime: false`, which leaves the server console as the only way to enter `unsafe`.

> The key properties are access-key gating, level classification, and mode-filtered exposure/execution; the tool list above matches the shipped plugin.

### Resources

Resources are read-only observations exposed through the MCP resource methods. `resources/list` returns every registered resource (URI, name, optional description/mimeType, and optional `_meta`); `resources/read` takes a `uri` and returns one `contents` entry with `uri`, `mimeType`, and `text` (JSON values are serialized by the transport). Resources have no capability level above `SAFE_READ` and stay visible in every mode. A resource read is authenticated and session-bound just like a tool call; unknown URIs and reader failures produce JSON-RPC errors and are audited.

Shipped resources: `edenmcp://luckperms/groups` when LuckPerms is present, and `edenmcp://spark/stats` when Spark is present (aggregated CPU/TPS/MSPT/GC snapshot).

## Hook / Plugin Integration SPI

Hooks let EdenMcp integrate with other plugins without coupling `core` to them. **Available hooks are provided and enabled by the server**: built-ins are assembled by the `plugin` module, and third-party plugins register hooks at runtime through the published `api` service. Clients only discover what the server exposes; they never configure which hooks exist. See [HOOK_API.md](HOOK_API.md) for the full public contract.

### Contract (`api`)

```java
interface EdenMcpApi {
    HookRegistration registerHook(Plugin owner, Hook hook);
    Registration registerTool(Plugin owner, McpTool tool);
    Registration registerResource(Plugin owner, McpResource resource);
    List<HookInfo> hooks();
    boolean isAcceptingRegistrations();
}

interface Hook {
    String id();                          // e.g. "luckperms"
    default boolean isAvailable(Server server) { return true; }
    HookContribution contribute(HookContext ctx);
    default void onEnable() {}
    default void onDisable() {}
}

record HookContribution(List<McpTool> tools, List<McpResource> resources) {}
```

- The API is published through Bukkit's `ServicesManager` and discovered by other plugins; `EdenMcpApiAvailableEvent` supports plugins that load earlier.
- Tool names must be `edenmcp.<hookId>.*` and resource URIs `edenmcp://<hookId>/*`. Violations are skipped with a warning and reported in `HookInfo.skipped()`.
- External hooks are capped at `MODERATE_ACTION` and may not declare `DANGEROUS` side effects unless an operator opts in under `hooks.external`. Built-in hooks are server-authoritative and are not capped.
- Each contributed tool carries its own `level`/`sideEffects`; the hook cannot bypass mode or access-key enforcement. Contributed resources are read-only and carry a `reader` that produces content for `resources/read`.
- Hook failures are isolated: a throwing hook must not break core MCP availability. Tools and resources are registered only after `onEnable` succeeds; partial registrations are rolled back instance-by-instance. `HookInfo` reports enabled state, contributions, skipped items and the last error.
- EdenMcp unregisters all hooks owned by a plugin when that plugin disables; `Registration#unregister()` allows explicit cleanup.

### Example: LuckPerms (`hooks:luckperms`)

**Availability:** LuckPerms API present.

The hook contributes read-only (`SAFE_READ`) tools: `edenmcp.luckperms.user.lookup`, `edenmcp.luckperms.user.permission.check`, `edenmcp.luckperms.user.groups`, `edenmcp.luckperms.groups.list`, `edenmcp.luckperms.group.get`, `edenmcp.luckperms.tracks.list`, `edenmcp.luckperms.track.get` and `edenmcp.luckperms.audit.read`, plus the `edenmcp://luckperms/groups` resource. Results are bounded (node/audit limits).

It also contributes privileged mutation tools classified `UNSAFE_ACTION` (and therefore only available in `unsafe` mode, consistent with the built-in hook's server-authoritative policy): `edenmcp.luckperms.user.permission.set`, `edenmcp.luckperms.group.permission.set`, `edenmcp.luckperms.group.create`, `edenmcp.luckperms.user.group.add`, `edenmcp.luckperms.user.group.remove`, `edenmcp.luckperms.group.meta.set`, `edenmcp.luckperms.user.track.promote`, and `edenmcp.luckperms.user.track.demote`. These validate inputs, persist changes through LuckPerms, and record audit entries via LuckPerms' action logger (tagged with the MCP caller id).

### Example: Spark (`hooks:spark`)

**Availability:** Paper's bundled `spark` plugin is loaded (`softdepend` ensures it precedes EdenMcp).

Spark's public API is accessed reflectively so no snapshot repository or shaded API copy is required. The hook contributes read-only statistics tools, profiler status, a raw-data toolset (list, chunked base64 read, schema-free protobuf structure inspection), and the `edenmcp://spark/stats` resource. Profiler/diagnostic commands are built from strict allowlists, dispatched on the server thread with a captured reply, and audited; results are saved locally and never uploaded. Raw files are restricted to Spark's own directory, to `.sparkprofile`/`.hprof` extensions, and to simple file names to prevent traversal.

## Configuration

The shipped [`config.yml`](../plugin/src/main/resources/config.yml) (server-side) has this shape:

```yaml
mcp:
  enabled: true
  mode: safe                # safe | moderate | unsafe (startup default; changeable at runtime)

  transport:
    type: http-sse          # preferred, remote-capable
    bind: 0.0.0.0           # explicit bind address (separate MCP port)
    port: 8765
    # Optional (secure defaults applied when omitted):
    # allowed-hosts: []     # derived from bind + loopback by default
    # allowed-origins: []   # browser origins; none allowed by default
    # max-body-bytes: 1048576
    # max-batch-size: 20
    # max-sse-clients: 20
    # session-idle-seconds: 1800   # session idle expiry
    # max-sessions: 100
    # thread-pool-core-size: 8 / max-size: 32 / keep-alive-seconds: 60 / queue-capacity: 256
    # shutdown-grace-seconds: 1
    # sse-keepalive-ms: 15000        # min 1000; key re-authenticated on every keepalive
    # session-prune-interval-ms: 30000
    # cors-max-age-seconds: 600
    # auth-failure-prune-threshold: 1000
    # read-buffer-bytes: 8192
    # max-response-chars: 1000000     # cap serialized JSON responses (min 512); truncates to valid JSON
    # call-timeout-seconds: 30        # deadline for tool execution / resource reads (min 1)
    # max-json-depth: 64               # max nested JSON objects/arrays per request (min 8)
    tls:
      enabled: true         # default; protects access key/data in transit
      mode: self-signed     # self-signed (auto-generated on first start) | custom
      # keystore-path / keystore-password when mode=custom
      # require-client-cert: false   # optional mTLS; set truststore-path too
      # truststore-path / truststore-password
      # self-signed-key-size: 2048   # minimum 2048
      # self-signed-validity-days: 3650

  auth:
    # Access key is stored in a server-side .key file in the plugin data folder.
    # On first startup, if missing, a cryptographically random key is generated.
    # allow-legacy-header: false   # deprecated X-Eden-Key alias
    # max-failures: 10             # auth failures before a temporary block
    # block-seconds: 60
    # rotation-grace-seconds: 300  # old key valid in-memory after `mcp key rotate`
    # multi-key-directory: keys    # optional extra owner-only key files

  policy:
    # allow-unsafe-at-runtime: true  # false = require console for 'unsafe'
    # logs-require-moderate: false   # true = hide edenmcp.logs.read in safe mode

  tools:
    # chat-min-interval-ms: 250      # global chat throttle; 0 disables it
    # chat-sync-timeout-seconds: 5
    # command-dispatch-timeout-seconds: 10
    # list-max-entries: 200          # cap for built-in list tools (reports hasMore)
    logs:
      # default-limit: 100
      # max-limit: 500
      # max-scan-bytes: 1048576         # tail window for plain logs
      # max-decompressed-bytes: 16777216 # cap for .gz/.zip decompression (zip-bomb protection)
      # max-pattern-length: 200
    files:
      # max-read-bytes: 4194304   # edenmcp.file.read response cap
      # max-write-bytes: 1048576  # edenmcp.file.write content cap
      # max-list-entries: 500     # edenmcp.file.list result cap
      # max-list-depth: 10        # edenmcp.file.list recursion cap

hooks:
  # Server-provided hook enablement (not client-configurable)
  luckperms:
    enabled: true
  spark:
    enabled: true
  # Policy for hooks registered by other plugins through the public API
  external:
    default-enabled: true
    max-capability-level: moderate
    allow-dangerous-side-effects: false
    overrides: {}
    #  myplugin:
    #    enabled: true
    #    max-capability-level: unsafe
    #    allow-dangerous-side-effects: true

commands:
  # Command dispatch is UNSAFE_ACTION by default.
  # Ordered regex rules matched against the dispatched command string; first match wins.
  classification:
    # - level: safe        # safe | moderate
    #   pattern: "^list(\\s.*)?$"
    # - level: moderate
    #   pattern: "^say(\\s.*)?$"

audit:
  log-tool-calls: true
  log-mode-changes: true
  # log-verbose: false      # log full commands/messages (off by default)
  # max-value-length: 200   # sanitized audit value truncation
```

- Default mode is `safe`. A cryptographically random, owner-only access key is generated into a static `.key` file on first startup; protect this file and distribute the key out-of-band to authorized clients. All new keys above are **optional** with secure defaults — a fresh install needs none of them.
- Hooks listed here reflect server-owned integrations assembled by `plugin`; clients cannot add or reconfigure hooks.
- Command classification rules are server-side only (ordered regex list, first match wins); unmatched commands remain `UNSAFE_ACTION` by default. Patterns are always anchored to the whole command.
- There are no secret config values to hide: `edenmcp.file.list` / `edenmcp.file.read` expose the server's config files directly, so no separate config-snapshot tool or resource is provided. The access key is stored in a separate owner-only `.key` file and is protected by the transport authentication gate. Note that `edenmcp.file.read` can read `.key` too, so an authenticated client is effectively an administrator.

## Auditing & Safety

- Require and verify the access key on every request; reject unauthenticated access early. Failed authentication is throttled per source IP, and auth failures are always logged.
- Log every tool invocation, resource read, and mode change with timestamp, tool/resource id, mode, caller identity (as available from transport), and sanitized, length-bounded values. Control characters — including CR/LF — are stripped from every caller-derived audit field (caller, tool name, URI, mode, outcome), and transports sanitize user-supplied identifiers before their own log lines, so one request can never forge an extra log entry. Resource reads share the `audit.log-tool-calls` toggle, and resource content is never written to the log. Sensitive content is **redacted at log-write time**: command text, chat messages, and tool error details are not written by default, so `edenmcp.logs.read` cannot expose them. Operators who explicitly want detail can enable `audit.log-verbose`.
- Enforce bounded inputs and outputs: bounded request bodies (1 MiB), JSON-RPC batches and JSON nesting/key counts (`mcp.transport.max-json-depth`), capped SSE clients and a bounded HTTP worker pool, log line/filter bounds with regex-abuse rejection, rejection of catastrophically backtracking command-classification rules, idle eviction of partial auth-failure records, bounded/timed-out TLS keystore generation, rate limits for chat, a maximum serialized response size (`mcp.transport.max-response-chars`) with structurally valid truncation, and a per-call deadline (`mcp.transport.call-timeout-seconds`) executed off the HTTP pool.
- Make `unsafe` mode, remote binding, and TLS-disabled state clearly visible in startup/runtime logs (including the generated certificate SHA-256 fingerprint when applicable). A warning is emitted if audit logging is disabled.
- Treat the access key as the primary authorization gate; operators should combine it with network-level controls for remote deployments.

## Implementation Status

Implemented and covered by unit and Paper integration tests: the core mode/capability model and filtered tool registry, the resource SPI with `resources/list`/`resources/read` and hook-contributed resources, HTTP+SSE transport with built-in TLS and authenticated sessions, runtime mode control, the safe/moderate/unsafe toolsets, root-confined text file list/read/write, and the LuckPerms and Spark hooks. Transport/auth hardening includes principal-bound sessions, key rotation and multi-key support, a centralized `SecurityPolicy`, optional mTLS and escalation lock, bounded/truncated responses and per-call execution timeouts, and build supply-chain checks (pinned Paper API, LuckPerms SHA-256 verification, Gradle dependency verification). See [docs/DEPLOYMENT.md](DEPLOYMENT.md) for the operator-facing details.

## Resolved Decisions

- **Transport:** HTTP + SSE on a dedicated MCP port (remote-capable). **Built-in TLS** is enabled by default: on first startup a self-signed certificate/keypair is generated (fingerprint logged for client verification/pinning), with support for operator-supplied certificates; TLS can be explicitly disabled for trusted/tunneled setups with a prominent warning. This allows secure remote access on normal hosting platforms without requiring SSH port-forwarding or an external reverse proxy.
- **Runtime mode:** changeable at runtime via authorized, audited control operations (`edenmcp.mode.set`, `MODERATE_ACTION`). Escalation to `unsafe` is permitted by default; `mcp.policy.allow-unsafe-at-runtime: false` locks it to the console.
- **Commands:** command dispatch is `UNSAFE_ACTION` and cannot be reclassified below `unsafe` as a tool. The server configures per-command classification via an **ordered list of regex rules** (`level` + `pattern`), **first match wins**, always anchored to the whole command; chaining/control characters are rejected and unmatched commands remain `unsafe`.
- **Access key:** a static, owner-only `.key` file is generated on first plugin startup (256-bit random); invalid files are quarantined and regenerated. Optional rotation (`mcp key rotate`, with an in-memory grace window) and multi-key files are available for operators who need them; a fresh install requires neither.
- **Credential header:** `Authorization: Bearer <key>` is canonical; query-string and raw `Authorization` keys are rejected. `X-Eden-Key` is a deprecated opt-in alias.
- **TLS:** built-in self-signed TLS by default with owner-only keystore + random password; fingerprint logged for pinning; TLS 1.2/1.3 AEAD-only; custom keystore and mTLS optional.
- **No secret configs:** EdenMcp is an admin tool, so config values are read from the config files with `edenmcp.file.read`; no config-snapshot tool/resource is needed. Secret material is not part of the YAML config (the access key is a separate `.key` file), and access is gated by the access key + TLS.
- **Server-directory file access:** `edenmcp.file.list` and `edenmcp.file.read` (`SAFE_READ`) and `edenmcp.file.write` (`UNSAFE_ACTION`) operate on relative paths under the server working directory. Lexical `..`/absolute-path checks plus real-path checks on every existing component prevent traversal and symlink escapes; reads require regular files, decode UTF-8 strictly, reject NUL/binary content, and are size-bounded; listings are entry/depth bounded and report truncation; writes are atomic and guarded inside the tool, not just by mode-filtered discovery. Java cannot fully close the TOCTOU window where a local actor swaps a parent directory for a symlink between resolution and the OS call.

## Open Questions

None blocking. Possible future work: per-client scopes and TTL auto-revert from `unsafe`.
