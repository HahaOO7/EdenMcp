# EdenMcp

EdenMcp is a [Paper](https://papermc.io/) plugin that exposes a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for a Minecraft server. It lets MCP-capable clients (AI agents, editors, operations tooling) inspect and operate a server through a controlled, authenticated, and audited capability surface.

The MCP endpoint runs on its own HTTP port and supports remote access. It is authenticated with a server-generated access key and secured with TLS by default.

## Features

- **HTTP + SSE transport** with built-in TLS (auto-generated self-signed certificate by default; custom keystore and optional mTLS supported)
- **Access-key authentication** — random 256-bit key stored owner-only in the plugin data folder (`Authorization: Bearer <key>`), constant-time comparison, per-IP failure throttling, rotation, and optional multi-key support
- **Three operating modes** (`safe`, `moderate`, `unsafe`) that gate which tools are advertised and executable; mode can be changed at runtime and is audited
- **Session handling** — `initialize` issues an `Mcp-Session-Id`; sessions are principal-bound, idle-expiring, and invalidated on key rotation
- **Auditing with redaction** — tool calls and mode changes are logged, while commands/chat messages and secrets are redacted by default
- **Server-authoritative hooks** — integrations contribute tools through a small SPI; currently LuckPerms (read-only lookups)
- **Hardened defaults** — bounded request bodies/batches/SSE clients, Host/Origin validation, no CORS headers unless configured, and secret redaction in `eden.config.get`

## Requirements

- **Java 25** (Gradle toolchain; required to build and run)
- **Paper 26.3** (the plugin declares `api-version: '26.3'`)
- Gradle **9.8.2** via the included wrapper (`./gradlew`)
- Optional: [LuckPerms](https://luckperms.net/) 5.x for the LuckPerms hook (the integration test setup downloads a pinned jar automatically)

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
| `mcp.transport.tls` | TLS mode, custom keystore, optional mTLS, generated key size/validity |
| `mcp.auth` | Failure throttling, key rotation grace period, extra key files |
| `mcp.policy.allow-unsafe-at-runtime` | Set `false` to require the console for escalation to `unsafe` |
| `mcp.tools.chat-min-interval-ms` | Global chat-send throttle (default `250`; `0` disables it) |
| `mcp.tools.chat-sync-timeout-seconds` / `command-dispatch-timeout-seconds` | Main-thread wait limits for chat and console commands |
| `mcp.tools.logs` | Log-read default/max lines, tail scan bytes, max regex filter length |
| `audit.max-value-length` | Truncation length for sanitized audit values (default `200`) |
| `hooks.luckperms.enabled` | Enable the LuckPerms hook |
| `commands.classification` | Ordered regex rules that reclassify specific console commands |
| `audit` | Tool-call/mode-change logging and optional verbose logging |

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
| `safe` (default) | Read-only inspection: status, logs, config snapshot, players, plugins, hooks, mode |
| `moderate` | Everything in `safe`, plus sending/broadcasting chat and the `eden.mode.set` control tool |
| `unsafe` | Everything above, plus console command dispatch and privileged hook actions |

Tools are filtered by mode before they are advertised, so clients never see tools they cannot execute. Mode changes take effect immediately for subsequent discovery and calls. By default an authenticated client can escalate to `unsafe` via `eden.mode.set`; set `mcp.policy.allow-unsafe-at-runtime: false` to restrict that to the server console.

## MCP tools

Read-only (`safe`):

- `eden.server.status` — version, TPS/MSPT, players, uptime, worlds
- `eden.players.list` — online players and metadata
- `eden.plugins.list` — installed plugins and enabled state
- `eden.hooks.list` — available/enabled hooks
- `eden.logs.read` — bounded recent log lines with optional filter
- `eden.config.get` — config/state snapshot with secrets redacted
- `eden.mode.get` — current mode
- `eden.luckperms.user.lookup`, `eden.luckperms.groups.list` — when LuckPerms is installed

Moderate:

- `eden.chat.send` — send a message to a player or broadcast (throttled via `mcp.tools.chat-min-interval-ms`, attributable)
- `eden.mode.set` — change the operating mode (audited)

Unsafe:

- `eden.command.dispatch` — execute a console command (strongly audited; cannot be reclassified below `unsafe`)

## Project structure

```text
core/                    MCP transport, auth, mode/capability policy, tool registry, hook SPI
plugin/                  Paper plugin bootstrap, built-in tools, console commands, resources
hooks/luckperms/         LuckPerms integration module (read-only tools)
docs/DESIGN.md           Architecture, capability model, and design decisions
docs/DEPLOYMENT.md       Installation, TLS/auth setup, and optional hardening
```

`core` holds protocol and policy logic independently of plugin lifecycle concerns; `plugin` assembles the system and registers hooks; each `hooks/<name>` module integrates one external plugin/API.

## Security notes

- Treat the `.key` file like an operator password: keep it owner-only and distribute it out-of-band. It is never returned by config/status tools or printed by the rotation command.
- Keep TLS enabled for any non-local deployment; verify the logged certificate fingerprint on first connect.
- Start in `safe` mode and escalate only as needed. Running in `unsafe` mode or binding to a non-loopback address produces a warning.
- Audit logging is on by default. `audit.log-verbose` logs full command/message content and is off by default.
- Invalid, symlinked, or world-readable `.key` files are quarantined as `.key.invalid` and replaced with a fresh key rather than failing startup.

For the full rationale and configuration reference, see [docs/DESIGN.md](docs/DESIGN.md) and [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).
