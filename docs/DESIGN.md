# EdenMcp Design

## Overview

EdenMcp is a Paper plugin that exposes a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for a Minecraft server instance. It allows MCP-capable clients (agents, editors, ops tooling) to inspect and operate the server through a controlled, auditable capability surface.

The project is structured as a small multi-module Gradle build:

- **`core`** – MCP server, transport, capability/mode enforcement, tool registry, hook SPI, and shared domain types. Depends only on the Paper API (and MCP/transport libraries as needed); no plugin bootstrap logic.
- **`plugin`** – Paper plugin entrypoint (`EdenMcpPlugin`). Assembles the system: loads server-side configuration, wires lifecycle, and registers/boots built-in hooks from `hooks/*` into `core`.
- **`hooks/<name>`** – One module per integrated plugin/API (e.g. `hooks:spark`, `hooks:luckperms`). Each hook contributes tools/resources for that specific target and is isolated from other hooks.

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
- Exposed as an authorized control operation (e.g. `eden.mode.set` / `eden.mode.get`) classified so that it is only callable with a valid access key; policy may restrict who/when can escalate to `unsafe`.
- Mode changes take effect immediately for subsequent tool discovery and execution.
- All mode changes are audited (previous mode, new mode, caller/session, timestamp).
- `unsafe` transitions must be prominently logged.

**Invariants**
- A tool is registered with a required mode/capability level; the effective registry exposed to MCP is filtered by the active mode.
- Escalation is never implicit: a `safe` server never advertises or executes `moderate`/`unsafe` tools.
- Tool descriptions state side effects and required mode so clients can plan correctly.

## Transport & Access Control

### Transport

- **Primary/preferred transport:** HTTP with SSE (remote-capable), suitable for MCP clients connecting over the network.
- The server binds to a configured host/port (separate from the Minecraft game port). Remote operation on normal hosting platforms is a supported deployment model, so the MCP endpoint must be usable without requiring SSH port-forwarding or an external reverse proxy.
- **TLS:** to protect the access key and data in transit, the transport supports **built-in TLS**. On first startup the plugin generates a self-signed certificate/keypair (stored in the plugin data folder) and enables TLS by default, logging the certificate fingerprint for out-of-band verification (trust-on-first-use/pinning by the client). Operators may instead supply their own certificate/keystore. TLS can be explicitly disabled for trusted-network or tunneled deployments, but this must produce a prominent warning because plain HTTP exposes the access key and tool I/O.

### Authentication (access key)

- **All access requires a key.** Every MCP interaction (session establishment, tool/resource listing, and tool invocation) must present a valid access key.
- **Key provisioning (initial):** on first startup, if no key exists, the plugin generates a cryptographically random key and writes it to a static server-side `.key` file in the plugin data folder. The server reads this key on startup; no runtime key rotation is required for now. Operators can copy the key to authorized clients out-of-band.
- The key is stored/managed server-side only; it is never chosen by, nor accepted as authoritative configuration from, the client.
- Missing/invalid keys are rejected before any tool is exposed or executed.
- Keys must be compared in constant time, never logged, and never returned by config/status tools.
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
- `eden.players.list` / player detail – online players and available metadata.
- `eden.plugins.list` – installed plugins and enabled state.
- `eden.hooks.list` – server-provided hooks and their availability/enabled state (read-only discovery).
- `eden.mode.get` – current operating mode.
- Hook-provided read tools (e.g. Spark profiler status/results) when available.

### Moderate tools
- `eden.chat.send` – send a message to a player or broadcast; rate-limited and attributable (prefix/logged).
- Hook-provided moderate actions explicitly marked safe-mutation.

### Unsafe tools
- `eden.command.dispatch` – execute a console command (**`UNSAFE_ACTION` by default**, highest risk; strongly audited).
- `eden.command.player` – execute as player if supported (**`UNSAFE_ACTION` by default**, strongly audited).
- Hook-provided privileged actions (e.g. permission mutations via LuckPerms) and any hook tool marked dangerous.

**Server-configurable command classification:** the server defines an ordered list of regex rules to override the required level for specific commands, reclassifying matched commands as `MODERATE_ACTION` or `SAFE_READ` where appropriate. Each rule pairs a `level` with a regex `pattern` matched against the dispatched command string; rules are evaluated in order and the **first match wins**. Overrides are server-side configuration only, evaluated before execution (in addition to the active mode gate), and all command executions remain audited. Unmatched commands keep the default `UNSAFE_ACTION` classification. Operators should anchor patterns (e.g. `^...$`) to avoid unintended matches.

### Control tools
- `eden.mode.set` – change operating mode at runtime (authorized, audited). Level/policy defined so it cannot be abused without proper authorization.

> Exact tool names/arguments are finalized during implementation; the key properties are access-key gating, level classification, and mode-filtered exposure/execution.

## Hook / Plugin Integration SPI

Hooks let EdenMcp integrate with other plugins without coupling `core` to them. **Available hooks are provided and enabled by the server**, assembled by the `plugin` module from the `hooks/*` modules; clients discover and use what the server exposes but do not configure which hooks exist.

### Contract (core)

```java
interface EdenHook {
    String id();                          // e.g. "spark"
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
- Each contributed tool carries its own `level`/`sideEffects`; the hook cannot bypass mode or access-key enforcement.
- Hook failures are isolated: a throwing hook must not break core MCP availability (log and degrade gracefully).
- Each hook module covers exactly one external plugin/API integration.

### Example: Spark (`hooks:spark`)

**Availability:** `Bukkit.getPluginManager().getPlugin("spark") != null` and/or Spark API present.

**Potential tools (illustrative, classified):**
- `eden.spark.health` (`SAFE_READ`) – profiler/platform summary.
- `eden.spark.profiler.start` (`MODERATE_ACTION`) – start a profiling session with bounded parameters.
- `eden.spark.profiler.stop` (`MODERATE_ACTION`) – stop and return/export reference.
- `eden.spark.profiler.status` / results read (`SAFE_READ`) – inspect last/current profiling data.

Profiling controls are classified as `MODERATE_ACTION` (observable impact, but permitted in moderate mode). Parameters should remain bounded and operations audited.

### Example: LuckPerms (`hooks:luckperms`)

**Availability:** LuckPerms API present.

Likely `SAFE_READ` for lookups and `UNSAFE_ACTION` for mutations (group/user permission changes), if exposed. Included as a hook module placeholder to validate the SPI.

## Configuration

Proposed `config.yml` shape (illustrative, server-side):

```yaml
mcp:
  enabled: true
  mode: safe                # safe | moderate | unsafe (startup default; changeable at runtime)

  transport:
    type: http-sse          # preferred, remote-capable
    bind: 0.0.0.0           # explicit bind address (separate MCP port)
    port: 8765
    tls:
      enabled: true         # default; protects access key/data in transit
      mode: self-signed     # self-signed (auto-generated on first start) | custom
      # cert-path / key-path / keystore options when mode=custom
    # base-path / proxy-aware options as needed

  auth:
    # Access key is stored in a server-side .key file in the plugin data folder.
    # On first startup, if missing, a cryptographically random key is generated.

hooks:
  # Server-provided hook enablement (not client-configurable)
  spark:
    enabled: true
  luckperms:
    enabled: true

commands:
  # Command dispatch is UNSAFE_ACTION by default.
  # Ordered regex rules matched against the dispatched command string; first match wins.
  classification:
    # - level: safe        # safe | moderate
    #   pattern: "^spark health(\\s.*)?$"
    # - level: moderate
    #   pattern: "^say(\\s.*)?$"

audit:
  log-tool-calls: true
  log-mode-changes: true
```

- Default mode is `safe`. A cryptographically random access key is generated into a static `.key` file on first startup; protect this file and distribute the key out-of-band to authorized clients.
- Hooks listed here reflect server-owned integrations assembled by `plugin`; clients cannot add or reconfigure hooks.
- Command classification rules are server-side only (ordered regex list, first match wins); unmatched commands remain `UNSAFE_ACTION` by default. Patterns should be anchored to avoid unintended matches.
- `eden.config.get` is broadly exposed but governed by the layered redaction policy above to prevent secret/credential leaks.

## Auditing & Safety

- Require and verify the access key on every request/session; reject unauthenticated access early.
- Log every tool invocation and mode change with timestamp, tool/control id, mode, caller/session identity (as available from transport), and outcome; redact sensitive arguments and never log keys.
- Enforce bounded inputs (line limits for logs, rate limits for chat, timeouts for hook operations).
- Make `unsafe` mode, remote binding, and TLS-disabled state clearly visible in startup/runtime logs (including the generated certificate fingerprint when applicable).
- Treat the access key as the primary authorization gate; operators should combine it with network-level controls for remote deployments.

## Implementation Plan

1. **Core skeleton**: mode enum, capability/level types, tool registry with mode filtering, hook SPI, audit logger, access-key verifier abstraction.
2. **MCP transport & protocol**: HTTP+SSE transport (remote-capable), authenticated session/request handling, tool/resource advertisement derived from filtered registry.
3. **Runtime mode control**: `eden.mode.get`/`eden.mode.set`, immediate effect on exposure/execution, audited transitions.
4. **Safe toolset**: status, plugin/hook list, bounded log/config readers (with secret redaction).
5. **Moderate toolset**: chat/broadcast with rate limiting and attribution.
6. **Unsafe toolset**: command dispatch with strong auditing.
7. **Spark hook**: read-only profiling data first, then classified control actions.
8. **LuckPerms hook (follow-up)**: read-only lookups, then mutations only as unsafe.
9. **Docs & hardening**: config reference, deployment/auth guidance for remote use, tests for auth, mode enforcement, and registry filtering.

## Resolved Decisions

- **Transport:** HTTP + SSE on a dedicated MCP port (remote-capable). **Built-in TLS** is enabled by default: on first startup a self-signed certificate/keypair is generated (fingerprint logged for client verification/pinning), with support for operator-supplied certificates; TLS can be explicitly disabled for trusted/tunneled setups with a prominent warning. This allows secure remote access on normal hosting platforms without requiring SSH port-forwarding or an external reverse proxy.
- **Runtime mode:** changeable at runtime via authorized, audited control operations.
- **Profiling:** Spark profiling controls (`start`/`stop`) are `MODERATE_ACTION`; status/results reads are `SAFE_READ`.
- **Commands:** command dispatch is `UNSAFE_ACTION` by default; the server configures classification via an **ordered list of regex rules** (`level` + `pattern`) matched against the dispatched command string, **first match wins**, to reclassify commands as `moderate` or `safe`; unmatched commands remain `unsafe`.
- **Access key:** a static `.key` file is generated on first plugin startup (cryptographically random); no rotation required for now.
- **`eden.config.get` redaction:** layered policy — never inline secret stores (report presence/status only), default-deny heuristic masking for sensitive key/path names (e.g. `key`, `token`, `secret`, `password`, `credential`, `auth`, `private`) plus an explicit denylist for known sensitive paths, with narrowly scoped allow-through exceptions for known non-secret collisions; applied to core and hook-exposed config/state.

## Open Questions

None blocking for the initial design. Remaining work is implementation-detail finalization during development (e.g. exact MCP tool schemas/arguments, HTTP+SSE endpoint/auth header conventions and certificate format/keystore handling, log-file discovery/bounds, chat rate limits, and command string normalization/canonicalization for regex matching).
