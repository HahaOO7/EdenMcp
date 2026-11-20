# EdenMcp

EdenMcp is a [Paper](https://papermc.io/) plugin that exposes a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for a Minecraft server. It lets MCP-capable clients (AI agents, editors, operations tooling) inspect and operate a server through a controlled, authenticated, and audited capability surface.

The MCP endpoint runs on its own HTTP port and supports remote access. It is authenticated with a server-generated access key and secured with TLS by default.

## Features

- **HTTP + SSE transport** with built-in TLS (auto-generated self-signed certificate by default; custom keystore and optional mTLS supported)
- **Access-key authentication** — random 256-bit key stored owner-only in the plugin data folder (`Authorization: Bearer <key>`), constant-time comparison, per-IP failure throttling, rotation, and optional multi-key support
- **Three operating modes** (`safe`, `moderate`, `unsafe`) that gate which tools are advertised and executable; mode can be changed at runtime and is audited
- **Session handling** — `initialize` issues an `Mcp-Session-Id`; sessions are principal-bound, idle-expiring, and invalidated on key rotation
- **Auditing with redaction** — tool calls and mode changes are logged, while command/chat content and tool errors are omitted from audit logs by default
- **Server-authoritative hooks** — integrations contribute tools through a small SPI; currently LuckPerms (read-only lookups) and Spark (profiling, diagnostics, raw data)
- **Full admin file access** — `edenmcp.file.list` (including recursive/glob listings) and `edenmcp.file.read` (with line-based paging) discover and read any UTF-8 text file under the server directory; `edenmcp.file.write` atomically creates/overwrites files in `unsafe` mode
- **Root-confined file access** — paths are relative to the server directory; `..` traversal and symlinks escaping it are rejected. Config values are read from the config files themselves; there is no separate config-snapshot tool.

## Requirements

- **Java 25** (Gradle toolchain; required to build and run)
- **Paper 26.3** (the plugin declares `api-version: '26.3'`)
- Gradle **9.8.2** via the included wrapper (`./gradlew`)
- Optional: [LuckPerms](https://luckperms.net/) 5.x for the LuckPerms hook (the integration test setup downloads a pinned jar automatically)
- Optional: Spark — Paper bundles it by default; the Spark hook activates when the `spark` plugin is present

## Building

```bash
./gradlew build                 # builds all modules; includes the shaded plugin jar
./gradlew test                  # unit tests + Paper integration tests
./gradlew :core:test            # core unit tests only
./gradlew integrationTest       # alias for the plugin integration tests
./gradlew :plugin:runServer     # launch a local Paper dev server
```

The deployable shaded jar is written to `plugin/build/libs/plugin-1.0-all.jar`.

Integration tests provision a Paper server under `plugin/build/integration-server/` and automatically download both the Paper server jar and a LuckPerms jar; their SHA-256 checksums are pinned in `gradle.properties`. A local `plugin/run/` directory is not required. To use an existing Paper jar instead, pass `-Pintegration.paperJar=/path/to/paper.jar`.

## Installation

1. Copy `plugin/build/libs/plugin-1.0-all.jar` into your server's `plugins/` directory.
2. Start the server once. EdenMcp generates `plugins/EdenMcp/.key` (the access key) and a self-signed TLS keystore. Note the certificate **SHA-256 fingerprint** printed in the startup log.
3. Give the key to authorized MCP clients as a Bearer token:

   ```
   Authorization: Bearer <contents of plugins/EdenMcp/.key>
   ```

4. Connect to `https://<server-address>:8765/mcp` (JSON-RPC endpoint). The SSE stream is at `/sse`, with messages posted to `/message`. Verify the logged TLS fingerprint on first connect.

Plain HTTP can be enabled with `mcp.transport.tls.enabled: false` for local development only; this emits a warning and exposes the key and tool traffic in plaintext.

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for deployment and hardening details.

## Configuration

Configuration lives in `plugins/EdenMcp/config.yml`; the shipped defaults are in [`plugin/src/main/resources/config.yml`](plugin/src/main/resources/config.yml). Key options:

| Path | Purpose |
| --- | --- |
| `mcp.enabled` | Enable/disable the MCP server |
| `mcp.mode` | Startup mode: `safe` (default), `moderate`, or `unsafe` |
| `mcp.transport.bind` / `port` | Bind address and MCP port (default `0.0.0.0:8765`) |
| `mcp.transport` | HTTP worker pool, SSE keepalive, session pruning, CORS age, request buffer and shutdown grace |
| `mcp.transport.max-response-chars` | Maximum serialized JSON response size (default `1000000`, minimum `512`); oversized tool/resource payloads are trimmed to valid JSON with `truncated`/`omittedCount`, and oversized strings end in `[truncated]`. JSON-RPC batches drop whole responses and append an explicit truncation error entry. |
| `mcp.transport.call-timeout-seconds` | Deadline for each tool execution and resource read (default `30`, minimum `1`); timed-out calls return a clean error and are audited as `timeout` instead of tying up an HTTP worker |
| `mcp.transport.max-json-depth` | Maximum nesting of JSON objects/arrays in a request (default `64`, minimum `8`); deeper trees, trees with excessive keys, and stack-overflow-prone input are rejected with HTTP 400 / JSON-RPC `-32600` |
| `mcp.transport.tls` | TLS mode, custom keystore, optional mTLS, generated key size/validity |
| `mcp.auth` | Failure throttling, key rotation grace period, extra key files |
| `mcp.policy.allow-unsafe-at-runtime` | Set `false` to require the console for escalation to `unsafe` |
| `mcp.tools.chat-min-interval-ms` | Global chat-send throttle (default `250`; `0` disables it) |
| `mcp.tools.chat-sync-timeout-seconds` / `command-dispatch-timeout-seconds` | Main-thread wait limits for chat and console commands |
| `mcp.tools.logs` | Log-read default/max lines, tail scan bytes, decompression cap, max regex filter length |
| `mcp.tools.files` | Max entries / recursion depth for `edenmcp.file.list`, max bytes for `edenmcp.file.read` / `edenmcp.file.write` |
| `mcp.tools.list-max-entries` | Max entries returned by the built-in list tools (default `200`); responses include `hasMore` |
| `audit.max-value-length` | Truncation length for sanitized audit values (default `200`) |
| `hooks.luckperms.enabled` | Enable the LuckPerms hook |
| `hooks.spark.enabled` | Enable the Spark profiling hook (Paper ships Spark) |
| `commands.classification` | Ordered regex rules that reclassify specific console commands; patterns with nested quantifiers or quantified alternation are rejected at config parse time to prevent catastrophic backtracking |
| `audit` | Tool-call/mode-change logging and optional verbose logging |

Tool results, resource contents, and whole JSON-RPC batches are bounded by `mcp.transport.max-response-chars`; truncation is always applied to whole JSON values (or string prefixes with a `[truncated]` marker), so clients never receive malformed JSON. Tools and resource readers execute on a dedicated virtual-thread executor with a `mcp.transport.call-timeout-seconds` deadline; an expired call is cancelled, reported as an error result (`timeout` in the audit log), and can no longer hold the HTTP worker.

`/mcp reload` refreshes mode, audit settings, and command classification; transport and hook changes require a restart.

### Console commands

All `mcp` subcommands require the `edenmcp.admin` permission (granted to operators by default):

| Command | Action |
| --- | --- |
| `/mcp mode get` | Show the current operating mode |
| `/mcp mode set <safe\|moderate\|unsafe>` | Change the mode (also works when runtime escalation is locked) |
| `/mcp key rotate` | Rotate the access key, close old sessions, and point to the new `.key` file |
| `/mcp reload` | Reload configuration |

## Operating modes

| Mode | Capabilities |
| --- | --- |
| `safe` (default) | Read-only inspection: status, logs, directory listings and any text file under the server directory, players, plugins, hooks, mode, Spark statistics/profiler status/raw data |
| `moderate` | Everything in `safe`, plus chat, `edenmcp.mode.set`, Spark profiler control and diagnostic commands |
| `unsafe` | Everything above, plus console command dispatch, writing text files under the server directory, and privileged hook actions |

Tools are filtered by mode before they are advertised, so clients never see tools they cannot execute. Mode changes take effect immediately for subsequent discovery and calls. By default an authenticated client can escalate to `unsafe` via `edenmcp.mode.set`; set `mcp.policy.allow-unsafe-at-runtime: false` to restrict that to the server console.

## MCP tools

Read-only (`safe`):

- `edenmcp.server.status` — version, TPS/MSPT, players, uptime, worlds
- `edenmcp.players.list` — online players and metadata
- `edenmcp.plugins.list` — installed plugins and enabled state
- `edenmcp.hooks.list` — available/enabled hooks
- `edenmcp.logs.list` — list available log files (plain, `.gz`, `.zip`) in `logs/`
- `edenmcp.logs.read` — bounded recent log lines from `latest.log` or a `file` from `logs.list`; `.gz`/`.zip` are decompressed with a size cap, and results report `truncated` and reject malformed UTF-8/binary content
- `edenmcp.file.list` — list a directory under the server root (default: root); optional `recursive`, `depth`, and glob `pattern`, with `truncated` reporting
- `edenmcp.file.read` — read a UTF-8 text file (relative path, bounded size); optional `offset`/`limit` return a line-aligned slice with `hasMore`
- `edenmcp.mode.get` — current mode
- `edenmcp.luckperms.user.lookup`, `edenmcp.luckperms.user.permission.check`, `edenmcp.luckperms.user.groups`, `edenmcp.luckperms.groups.list`, `edenmcp.luckperms.group.get`, `edenmcp.luckperms.tracks.list`, `edenmcp.luckperms.track.get`, `edenmcp.luckperms.audit.read` — when LuckPerms is installed
- `edenmcp.spark.stats.cpu`, `edenmcp.spark.stats.tps`, `edenmcp.spark.stats.mspt`, `edenmcp.spark.stats.gc` — Spark's performance statistics with optional windows
- `edenmcp.spark.profiler.info` — current Spark profiler status
- `edenmcp.spark.data.list`, `edenmcp.spark.data.read`, `edenmcp.spark.data.inspect` — bounded access to raw Spark profile/heap files; `inspect` decodes protobuf structure locally

Read-only resource: `edenmcp://spark/stats` returns an aggregated CPU/TPS/MSPT/GC snapshot.

Moderate:

- `edenmcp.chat.send` — send a message to a player or broadcast (throttled via `mcp.tools.chat-min-interval-ms`, attributable)
- `edenmcp.mode.set` — change the operating mode (audited)
- `edenmcp.spark.profiler.control` — start/stop/cancel the profiler; **results are always saved locally, never uploaded**
- `edenmcp.spark.diagnostics.run` — bounded tick/GC monitor toggles, heap dumps (local files), TPS/GC reports. `command=heapdump` is an effective **UNSAFE** sub-gate (despite the tool's moderate classification), is rate-limited to one per 60 seconds, optionally triggers GC via `runGcBefore`, and writes a potentially multi-GB file locally — never upload it.

Unsafe:

- `edenmcp.command.dispatch` — execute a console command (strongly audited; cannot be reclassified below `unsafe`)
- `edenmcp.file.write` — atomically create or overwrite a UTF-8 text file under the server directory (audited; preserves existing POSIX permissions)
- `edenmcp.luckperms.user.permission.set`, `edenmcp.luckperms.group.permission.set`, `edenmcp.luckperms.group.create`, `edenmcp.luckperms.user.group.add`, `edenmcp.luckperms.user.group.remove`, `edenmcp.luckperms.group.meta.set`, `edenmcp.luckperms.user.track.promote`, `edenmcp.luckperms.user.track.demote` — LuckPerms mutations (only in `unsafe` mode; audited in LuckPerms' action log)

## Project structure

```text
api/                     Published, stable API for third-party hooks (JitPack artifact)
core/                    MCP transport, auth, mode/capability policy, tool registries, HookManager
plugin/                  Paper plugin bootstrap, built-in tools, console commands, resources
hooks/luckperms/         LuckPerms integration module (read-only tools)
hooks/spark/             Spark profiling hook (statistics, control, diagnostics, raw data)
docs/DESIGN.md           Architecture, capability model, and design decisions
docs/HOOK_API.md         Public hook/tool registration API
docs/DEPLOYMENT.md       Installation, TLS/auth setup, and optional hardening
```

`core` holds protocol and policy logic independently of plugin lifecycle concerns; `plugin` assembles the system and registers hooks; each `hooks/<name>` module integrates one external plugin/API.

## Extending EdenMcp from another plugin

Other plugins can register MCP tools and resources at runtime through the `api` artifact, which
is published via JitPack:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { compileOnly("com.github.haha007.EdenMcp:edenmcp-api:1.0") }
```

Declare `softdepend: [EdenMcp]` in `plugin.yml`, then look up the service and register a hook:

```java
var provider = Bukkit.getServicesManager().getRegistration(EdenMcpApi.class);
if (provider != null) {
    Registration registration = provider.getProvider().registerHook(this, myHook);
}
```

Tool names must use `edenmcp.<hookId>.*` and resource URIs `edenmcp://<hookId>/*`. External hooks
are capped at `moderate` capabilities by default; an operator can raise the cap per hook in
`hooks.external.overrides`. See [docs/HOOK_API.md](docs/HOOK_API.md) for the full contract,
lifecycle and examples.

## Security notes

- Treat the `.key` file like an operator password: keep it owner-only and distribute it out-of-band. Any authenticated client can list and read text files under the server directory (including `.key`) with `edenmcp.file.list` / `edenmcp.file.read`, so the access key is the full admin boundary. It is never printed by the rotation command.
- Keep TLS enabled for any non-local deployment; verify the logged certificate fingerprint on first connect.
- Start in `safe` mode and escalate only as needed. Running in `unsafe` mode or binding to a non-loopback address produces a warning. `edenmcp.file.write` can overwrite any text file under the server directory, including plugin configs.
- Audit logging is on by default. `audit.log-verbose` logs full command/message content and is off by default.
- Invalid, symlinked, or world-readable `.key` files are quarantined as `.key.invalid` and replaced with a fresh key rather than failing startup.

For the full rationale and configuration reference, see [docs/DESIGN.md](docs/DESIGN.md) and [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## License

EdenMcp is licensed under the [MIT License](LICENSE) — use, modify, and distribute it freely, including commercially. Provided "AS IS", without warranty or liability.
