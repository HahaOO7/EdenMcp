# EdenMcp Design

## Overview

EdenMcp is a Paper plugin that exposes a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for a Minecraft server instance. It allows MCP-capable clients (agents, editors, ops tooling) to inspect and operate the server through a controlled, auditable capability surface.

The project is structured as a small multi-module Gradle build:

- **`core`** – MCP server, transport, capability/mode enforcement, tool registry, hook SPI, and shared domain types. Depends only on the Paper API (and MCP/transport libraries as needed); no plugin bootstrap logic.
- **`plugin`** – Paper plugin entrypoint (`EdenMcpPlugin`). Assembles the system: loads server-side configuration, wires lifecycle, and registers/boots built-in hooks from `hooks/*` into `core`.
- **`hooks/<name>`** – One module per integrated plugin/API (currently `hooks/luckperms`). Each hook contributes tools/resources for that specific target and is isolated from other hooks.

This separation keeps protocol and policy logic testable in `core` while letting `plugin` own Bukkit/Paper lifecycle concerns and hook assembly.

## Goals

- Provide a useful MCP interface for server administration and debugging, including remote access.
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
| `safe` | Inspection and diagnostics. Default. | Read-only observation: logs, config/state snapshots, metrics, player/world metadata reads, listing resources. No mutations, no commands, no messages. |
| `moderate` | Routine operator assistance. | Everything in `safe`, plus low-risk actions: sending messages/broadcasts and other explicitly classified "safe-mutation" operations, including hook actions marked moderate. No console commands, no world/data mutation, no permission or economy changes, no hook actions marked dangerous. |
| `unsafe` | Full administrative control. | Everything in `moderate`, plus console/command dispatch, privileged hook actions, and any tool classified as dangerous. |

**Runtime mode changes**
- Exposed as an authorized control operation (`eden.mode.set` / `eden.mode.get`) at `MODERATE_ACTION`, so it is callable only with a valid access key in `moderate` or `unsafe` mode, never in `safe`.
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
- `eden.logs.read` – read recent server log lines with optional filter/limit (bounded).
- `eden.config.get` – read server/EdenMcp configuration and state (full exposure of available config as requested); **secrets (access key/tokens/credentials) must always be redacted**.
- `eden.server.status` – version, TPS/MSPT, player counts, uptime, loaded worlds.
- `eden.players.list` – online players and available metadata.
- `eden.plugins.list` – installed plugins and enabled state.
- `eden.hooks.list` – server-provided hooks and their availability/enabled state (read-only discovery).
- `eden.mode.get` – current operating mode.
- Hook-provided read tools (e.g. LuckPerms lookups) when available.

### Moderate tools
- `eden.chat.send` – send a message to a player or broadcast; rate-limited and attributable (prefix/logged).
- Hook-provided moderate actions explicitly marked safe-mutation.

### Unsafe tools
- `eden.command.dispatch` – execute a console command (**`UNSAFE_ACTION` by default**, highest risk; strongly audited).
- Hook-provided privileged actions (e.g. permission mutations, if a hook exposes them) and any hook tool marked dangerous.

**Server-configurable command classification:** the server defines an ordered list of regex rules to override the required level for specific commands, reclassifying matched commands as `MODERATE_ACTION` or `SAFE_READ` where appropriate. Each rule pairs a `level` with a regex `pattern` matched against the dispatched command string; rules are evaluated in order and the **first match wins**. Patterns are **always anchored to the whole command**: unanchored rules are automatically wrapped as `^(?:...)$` with a warning, so a rule cannot downgrade a command by matching a prefix. Before matching, commands are normalized (leading `/` removed, whitespace collapsed) and any command containing control characters or separators (`;`, `&&`, `||`, backticks, `$()`) is rejected and treated as `UNSAFE_ACTION`, preventing a SAFE rule from hiding a chained dangerous command. Overrides are server-side configuration only, evaluated before execution (in addition to the active mode gate), and all command executions remain audited. Unmatched commands keep the default `UNSAFE_ACTION` classification. Crucially, `eden.command.dispatch` itself declares `UNSAFE_ACTION`, so per-command classification can never make the generic dispatch tool callable below `unsafe` or be used to escalate modes.

### Control tools
- `eden.mode.set` – change operating mode at runtime (`MODERATE_ACTION`, authorized, audited). Escalation to `unsafe` is allowed by default but can be locked by an operator via `mcp.policy.allow-unsafe-at-runtime: false`, which leaves the server console as the only way to enter `unsafe`.

> The key properties are access-key gating, level classification, and mode-filtered exposure/execution; the tool list above matches the shipped plugin.

### Resources

Resources are read-only observations exposed through the MCP resource methods. `resources/list` returns every registered resource (URI, name, optional description/mimeType, and optional `_meta`); `resources/read` takes a `uri` and returns one `contents` entry with `uri`, `mimeType`, and `text` (JSON values are serialized by the transport). Resources have no capability level above `SAFE_READ` and stay visible in every mode. A resource read is authenticated and session-bound just like a tool call; unknown URIs and reader failures produce JSON-RPC errors and are audited.

Shipped resources: `eden://config/snapshot` (the same redacted configuration/state snapshot as `eden.config.get`) and, when LuckPerms is present, `eden://luckperms/groups` contributed by the hook.

## Hook / Plugin Integration SPI

Hooks let EdenMcp integrate with other plugins without coupling `core` to them. **Available hooks are provided and enabled by the server**, assembled by the `plugin` module from the `hooks/*` modules; clients discover and use what the server exposes but do not configure which hooks exist.

### Contract (core)

```java
interface EdenHook {
    String id();                          // e.g. "luckperms"
    boolean isAvailable(Server server);   // target plugin/API present
    HookContribution contribute(HookContext ctx);
}

record HookContribution(
    List<McpTool> tools,
    List<McpResource> resources,
    Optional<Runnable> onEnable,
    Optional<Runnable> onDisable
) {}
```

- Hooks are explicitly wired/registered by `plugin` from known hook modules; only hooks whose `isAvailable` is true (and that are enabled server-side) contribute.
- Each contributed tool carries its own `level`/`sideEffects`; the hook cannot bypass mode or access-key enforcement. Contributed resources are read-only and carry a `reader` that produces content for `resources/read`.
- Hook failures are isolated: a throwing hook must not break core MCP availability (log and degrade gracefully). Tools and resources are registered only after `onEnable` succeeds; any partial registration is rolled back instance-by-instance, so a failed hook never leaves callable tools or readable resources behind. `describeHooks()` reports both contributed tool names and resource URIs.
- Each hook module covers exactly one external plugin/API integration.

### Example: LuckPerms (`hooks:luckperms`)

**Availability:** LuckPerms API present.

The hook contributes two read-only (`SAFE_READ`) tools: `eden.luckperms.user.lookup` and `eden.luckperms.groups.list`, plus the `eden://luckperms/groups` resource. Privileged mutations (group/user permission changes) are deliberately not exposed; if a hook adds them later they must be classified `UNSAFE_ACTION`.

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
    # logs-require-moderate: false   # true = hide eden.logs.read in safe mode

  tools:
    # chat-min-interval-ms: 250      # global chat throttle; 0 disables it
    # chat-sync-timeout-seconds: 5
    # command-dispatch-timeout-seconds: 10
    logs:
      # default-limit: 100
      # max-limit: 500
      # max-scan-bytes: 1048576
      # max-pattern-length: 200

hooks:
  # Server-provided hook enablement (not client-configurable)
  luckperms:
    enabled: true

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
- `eden.config.get` is broadly exposed but applies layered redaction to sensitive keys/paths so secrets are never returned.

## Auditing & Safety

- Require and verify the access key on every request; reject unauthenticated access early. Failed authentication is throttled per source IP, and auth failures are always logged.
- Log every tool invocation, resource read, and mode change with timestamp, tool/resource id, mode, caller identity (as available from transport), and a sanitized, length-bounded outcome. Resource reads share the `audit.log-tool-calls` toggle, and resource content is never written to the log. Sensitive content is **redacted at log-write time**: command text, chat messages, and tool error details are not written by default, so `eden.logs.read` cannot expose them. Operators who explicitly want detail can enable `audit.log-verbose`.
- Enforce bounded inputs: bounded request bodies (1 MiB) and JSON-RPC batches, capped SSE clients and a bounded HTTP worker pool, log line/filter bounds with regex-abuse rejection, and rate limits for chat.
- Make `unsafe` mode, remote binding, and TLS-disabled state clearly visible in startup/runtime logs (including the generated certificate SHA-256 fingerprint when applicable). A warning is emitted if audit logging is disabled.
- Treat the access key as the primary authorization gate; operators should combine it with network-level controls for remote deployments.

## Implementation Status

Implemented and covered by unit and Paper integration tests: the core mode/capability model and filtered tool registry, the resource SPI with `resources/list`/`resources/read` and hook-contributed resources, HTTP+SSE transport with built-in TLS and authenticated sessions, runtime mode control, the safe/moderate/unsafe toolsets, and the LuckPerms hook. Transport/auth hardening includes principal-bound sessions, key rotation and multi-key support, a centralized `SecurityPolicy`, optional mTLS and escalation lock, secret redaction, and build supply-chain checks (pinned Paper API, LuckPerms SHA-256 verification, Gradle dependency verification). See [docs/DEPLOYMENT.md](DEPLOYMENT.md) for the operator-facing details.

## Resolved Decisions

- **Transport:** HTTP + SSE on a dedicated MCP port (remote-capable). **Built-in TLS** is enabled by default: on first startup a self-signed certificate/keypair is generated (fingerprint logged for client verification/pinning), with support for operator-supplied certificates; TLS can be explicitly disabled for trusted/tunneled setups with a prominent warning. This allows secure remote access on normal hosting platforms without requiring SSH port-forwarding or an external reverse proxy.
- **Runtime mode:** changeable at runtime via authorized, audited control operations (`eden.mode.set`, `MODERATE_ACTION`). Escalation to `unsafe` is permitted by default; `mcp.policy.allow-unsafe-at-runtime: false` locks it to the console.
- **Commands:** command dispatch is `UNSAFE_ACTION` and cannot be reclassified below `unsafe` as a tool. The server configures per-command classification via an **ordered list of regex rules** (`level` + `pattern`), **first match wins**, always anchored to the whole command; chaining/control characters are rejected and unmatched commands remain `unsafe`.
- **Access key:** a static, owner-only `.key` file is generated on first plugin startup (256-bit random); invalid files are quarantined and regenerated. Optional rotation (`mcp key rotate`, with an in-memory grace window) and multi-key files are available for operators who need them; a fresh install requires neither.
- **Credential header:** `Authorization: Bearer <key>` is canonical; query-string and raw `Authorization` keys are rejected. `X-Eden-Key` is a deprecated opt-in alias.
- **TLS:** built-in self-signed TLS by default with owner-only keystore + random password; fingerprint logged for pinning; TLS 1.2/1.3 AEAD-only; custom keystore and mTLS optional.
- **`eden.config.get` redaction:** layered policy — never inline secret stores (report presence/status only), default-deny heuristic masking for sensitive key/path names (e.g. `key`, `token`, `secret`, `password`, `credential`, `auth`, `private`) plus an explicit denylist for known sensitive paths, with narrowly scoped allow-through exceptions for known non-secret collisions; applied to core and hook-exposed config/state.

## Open Questions

None blocking. Possible future work: per-client scopes and TTL auto-revert from `unsafe`.
