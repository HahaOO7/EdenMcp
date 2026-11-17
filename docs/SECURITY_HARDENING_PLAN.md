# EdenMcp — MCP Transport & Auth Hardening Plan

Status: Phase 0 and Phase 1 implemented and tested; Phase 2 remains optional follow-up work.
Scope: the `core` HTTP+SSE transport, the access-key auth path, and the authorization decisions
reached through that path (operating mode, tool exposure, command dispatch, log reads). Companion
to `docs/DESIGN.md`; where this plan and the design doc disagree, this plan proposes updating the
design doc (see §11).

Implementation note: Phase 1 delivered optional key rotation (`mcp key rotate`) with an in-memory
grace period, optional multi-key files, session IDs bound to a principal, a centralized
`SecurityPolicy`, optional mTLS and runtime-unsafe lock, and build supply-chain checks (pinned
Paper API version, LuckPerms SHA-256 verification, Gradle dependency verification metadata).

## 0. Deployment constraint (drives every decision below)

The product goal is **plug and play**: install the plugin, configure MCP, done. No CA setup,
no manual key provisioning, no reverse proxy, no extra files to author for the default path.
`docs/DESIGN.md:55` explicitly requires remote use without SSH port-forwarding or a proxy.

Therefore each control follows these rules:

- **Secure by default, zero setup.** Hardening applies automatically with the shipped config.
- **No new mandatory config keys.** Defaults are derived from existing `mcp.*` settings
  (`bind`, `port`, `mode`) wherever possible.
- **Opt-in complexity.** mTLS, custom keystores, alternative auth headers, key rotation,
  per-client scopes, and escalation locks are optional knobs for operators who need them —
  never prerequisites for a working install.
- **Never break a fresh install to reject a bad one.** Invalid key material or a misconfigured
  allowlist is repaired/recovered with a loud warning, not a startup failure.
- **Docs stay short.** The deployment guide is: install → read `.key` → note TLS fingerprint →
  connect. Everything else lives in an explicitly optional "advanced" section.

This changes how several recommendations are packaged: they are automatic behaviors with
optional overrides, not new steps the operator must perform. See §12 for the operator view.

## 1. Goals and threat model

EdenMcp exposes powerful tools (console command dispatch, chat, log/config reads) over a
network port protected by a single shared secret. The current implementation has the right
building blocks (random key, constant-time compare, TLS by default, mode filtering, audit
log) but several gaps that make the "access key + mode" boundary weaker than intended.

Assets to protect:

- The access key (single credential granting full remote tool access).
- The MCP tool surface, especially `eden.command.dispatch` and mode escalation.
- Server logs, config, and chat content reachable through `SAFE_READ` tools.
- Availability of the Minecraft server and the MCP endpoint.

Adversaries considered:

- Unauthenticated network scanners / opportunistic attackers reaching the MCP port.
- A client that possesses the key but should be constrained by `safe`/`moderate` mode.
- A browser-based attacker using DNS rebinding or a cross-origin page against a local MCP port.
- A malicious or compromised MCP client sending oversized/batched/regex-heavy requests.
- Anyone with read access to server logs or the plugin data folder.

Non-goals for the default install (available later as opt-in, see §7):

- Mandatory operator-managed PKI, client certificates, or external auth services.
- Manual key provisioning, rotation ceremonies, or per-client key files.
- Requiring a reverse proxy or tunnel to be secure.

## 2. Current state — gap summary

| ID | Gap | Location |
| --- | --- | --- |
| G1 | Key accepted via `?key=`/`?access_key=` query string and raw `Authorization` without `Bearer`; trims presented value. | `core/.../transport/McpHttpServer.java:274-294`, `core/.../auth/AccessKeyManager.java:47-51` |
| G2 | One static global key; no per-client identity, scopes, expiry, rotation, or revocation. | `core/.../auth/AccessKeyManager.java:26-51` |
| G3 | No session model: no `Mcp-Session-Id`, no initialize requirement, no per-session principal, SSE streams authorized once and never re-checked. | `McpHttpServer.java:93-131,175-194,456-464` |
| G4 | `eden.command.dispatch` declares `SAFE_READ`, so it is advertised and callable in `safe` mode; a server regex rule can classify `mcp mode set unsafe` as `SAFE_READ` → key holder escalates `safe → unsafe`. | `plugin/.../tools/CommandDispatchTool.java:35-38`, `plugin/build.gradle.kts:85-92`, integration test `:177-184` |
| G5 | `CommandClassification` has a permissive `find()` fallback after `matches()`, so non-anchored SAFE patterns downgrade embedded/dangerous commands. | `core/.../config/CommandClassification.java:22-40` |
| G6 | Full console commands, chat messages, and raw tool error strings are logged and then re-exposed through `eden.logs.read` (`SAFE_READ`). | `CommandDispatchTool.java:59`, `ChatSendTool.java:71,84`, `LogsReadTool.java:27-30`, `McpHttpServer.java:190-193,246-250` |
| G7 | TLS keystore password hardcoded (`edenmcp`); generated `.p12` is world-readable; no protocol/cipher restrictions; no mTLS; full `keytool -list` output logged. | `McpHttpServer.java:371-447`, `plugin/run/plugins/EdenMcp/mcp-keystore.p12` |
| G8 | No Host/Origin validation, no CORS policy, no CSRF defense, no auth-failure throttling, no body/batch-size limits, unbounded cached executor + unbounded SSE clients. | `McpHttpServer.java:41-45,78-82,133-161,264-301` |
| G9 | Client-controlled regex filter compiled over up to 1 MiB of log text (ReDoS); no server-side HTTP idle/read timeouts. | `LogsReadTool.java:57-59,88-93`, `McpHttpServer.java` |
| G10 | Existing key files accepted without format/length/permission/symlink checks; config/audit knobs can disable tool/mode audit logging. | `AccessKeyManager.java:27-34`, `EdenConfig.java:41-42`, `AuditLogger.java:19-40` |
| G11 | No auth unit tests; integration tests run with TLS disabled and cover only "no key → error"; no negative-key, query-key, SSE, batch, Host/Origin, or size-limit tests. | `plugin/src/test/.../McpIntegrationTest.java`, no `core/src/test` |
| G12 | Supply chain: LuckPerms jar downloaded over HTTPS without checksum; Paper version uses dynamic `+`; no dependency verification. | `plugin/build.gradle.kts:24-39`, `core/build.gradle.kts:2` |

## 3. Workstream A — Authentication (P0)

### A1. Restrict credential transport (automatic, no config)
File: `core/src/main/java/at/haha007/edenMcp/core/transport/McpHttpServer.java:274-294`.

- Remove the `?key=` / `?access_key=` query-string fallback entirely. Return 401 if the key is
  not in a header. Query strings leak through access logs, history, proxies, and referrers.
- Require `Authorization: Bearer <key>` as the canonical form. No operator setup: clients
  already send this (see `McpIntegrationTest.java:350-367`).
- Keep `X-Eden-Key` as a **deprecated, opt-in** alias: `mcp.auth.allow-legacy-header`
  (default `false`), with a startup warning if enabled. No config key needed for the default.
- Reject raw `Authorization` values without a recognized scheme. Do not trim the presented
  key; compare exact bytes. Trim only when reading the `.key` file. Add
  `WWW-Authenticate: Bearer` on 401 responses.

### A2. Validate the key at rest (automatic recovery)
File: `core/.../auth/AccessKeyManager.java:26-51`.

- Auto-generate as today when `.key` is missing — keep the existing plug-and-play behavior.
- On load, require exactly 64 lowercase hex chars. If the file is invalid (wrong length,
  non-hex, group/world-readable, or a symlink), **move it to `.key.invalid` and generate a
  fresh owner-only key**, logging a prominent warning with the path. Do not hard-fail startup:
  a broken key file must not take down the server.
- Keep `CREATE_NEW`, `SecureRandom`, and `MessageDigest.isEqual` (all already correct).

### A3. Optional key rotation / multiple keys (P1 — opt-in)
- Keep the single auto-generated `.key` as the default. Rotation is for operators who need it;
  nothing in a fresh install requires a rotation procedure.
- Change `AccessKeyManager` to hold active keys (id → material) when a `keys/` directory or
  multi-key file is present, so the default path stays a single `.key`.
- Provide a one-command rotation (`eden key rotate`): generate new key, keep the old one valid
  for a short grace period, print the new key once to console, audit the event. No manual file
  editing and no downtime.
- Add bounded per-source auth-failure throttling (e.g. 10 failures → 60 s block, capped) and a
  small constant delay on failure. This is automatic; add tuning knobs only if needed.
- Keep audit logging of auth failures unconditional.

### A4. Optional per-client identity (P2 — opt-in)
- Default principal remains "the auto-generated key" — no operator action and no change to
  audit output shape for existing installs beyond an optional key id field.
- When multi-key mode is enabled, attach a principal (key id + optional label) to requests and
  SSE connections, carried into `HookContext.callerId()` and audit records.

## 4. Workstream B — Authorization and mode integrity (P0/P1)

### B1. Fix tool advertisement and dispatch classification (P0, automatic)
Files: `plugin/.../tools/CommandDispatchTool.java:35-38`,
`core/.../tool/ToolRegistry.java:28-38`, `core/.../mode/OperatingMode.java`.

- Set `CommandDispatchTool.level()` to `UNSAFE_ACTION` so the registry/mode gate hides it in
  `safe` and `moderate`. Keep per-command effective classification as an *additional* check in
  `execute()` for `unsafe`, not as a bypass of the tool-level gate.
- If classified console commands must be callable below `unsafe`, add a separate
  `eden.command.safe-dispatch` tool with a level matching the maximum allowed classification.
  Never expose a generic dispatch tool at `SAFE_READ`.
- Update the integration test that escalates `safe → unsafe` via a SAFE-classified
  `mcp mode set unsafe` rule (`plugin/build.gradle.kts:85-92`, `McpIntegrationTest.java:177-184`).
  Escalation must go through `eden.mode.set` at `MODERATE_ACTION`, not through command
  classification. This is a code/test change, not an operator step.

### B2. Tighten command classification (P0, automatic)
File: `core/.../config/CommandClassification.java:22-40`.

- Remove the `find()` fallback. Require fully anchored patterns (`matches()` on the whole
  command) and auto-anchor unanchored rules with `^...$`, logging a warning. Keep first-match-wins
  and default `UNSAFE_ACTION`.
- Normalize before matching: collapse whitespace, reject NUL/control chars, and reject command
  chaining (`; | & \n \r`) so a SAFE rule cannot be satisfied by a prefix of a dangerous command.
- Unit-test exact match, normalization, near-miss (unanchored `say` must not downgrade
  `safe say`), chaining, and default-unmatched. Existing operator classification rules remain
  valid; auto-anchoring only makes them stricter.

### B3. Guard mode escalation (P1 — opt-in lock)
- **Default is unchanged:** `eden.mode.set` stays `MODERATE_ACTION` and runtime escalation to
  `unsafe` remains available, so the plug-and-play workflow and existing tests keep working.
- Add an **optional** policy `mcp.policy.allow-unsafe-at-runtime` (default `true`) that an
  operator can set to `false` to require console/config for `unsafe`. This implements
  DESIGN.md:40 without changing the default experience.
- Optionally add a TTL auto-revert from `unsafe` when the lock policy is enabled. All mode
  changes stay audited with principal, previous/new mode, and timestamp (already implemented).

### B4. Enforce checks in one place
- Centralize the final decision in `McpHttpServer.handleToolsCall` (currently
  `ToolRegistry.canExecute` at `:230-240`) behind a `SecurityPolicy` abstraction shared by
  transport, registry, and tools. Side-effecting tools re-check via `HookContext`. Unit-test
  every (mode × level × optional scope) combination.

## 5. Workstream C — HTTP/SSE transport hardening (P0/P1, automatic)

File: `core/.../transport/McpHttpServer.java`.

- **Host/Origin allowlist (P0)**: derive the default allowlist automatically from
  `mcp.transport.bind` (plus `localhost`/`127.0.0.1` when bound to loopback), so a normal
  install needs **no new config**. Reject requests whose `Host` is outside that set; validate
  `Origin` when present. Add optional `mcp.transport.allowed-hosts` / `allowed-origins`
  overrides for reverse-proxy or DNS setups. This is the primary DNS-rebinding / cross-origin
  defense.
- **CORS (P0)**: emit no `Access-Control-Allow-*` headers by default. Only when an operator
  explicitly configures `allowed-origins`, echo exact matches (never `*` with credentials) and
  handle `OPTIONS`. No config → no browser cross-origin access, which is the safe default.
- **Request limits (P0)**: cap request body (e.g. 1 MiB) and JSON-RPC batch size (e.g. 20),
  reject oversized/deep JSON, close offending connections. Replace `is.readAllBytes()` at
  `:141-142` with a bounded read. Internal defaults; optional tuning keys only if operators ask.
- **Auth throttling (P0)**: see A3; applied to `/mcp`, `/message`, and `/sse`.
- **Timeouts and resource bounds (P1)**: server-side socket idle/read timeouts; bound SSE
  clients (default e.g. 20) and replace `newCachedThreadPool` (`:41-45`) with a bounded pool;
  expire/re-authenticate long-lived SSE streams and close them on key rotation. All internal
  defaults with optional overrides — no setup required.
- **Session model (P1)**: require `initialize` before `tools/list`/`tools/call`, issue and
  validate `Mcp-Session-Id`, bind session → principal, invalidate on auth failure/rotation,
  and honor the client's protocol version when supported. Internal behavior; no config.
- **Error hygiene (P1)**: return generic `-32603 "Internal error"` to clients; keep exception
  details in server logs (`:190-193`, `:246-250`).

## 6. Workstream D — TLS (P0/P1, automatic default; optional extras)

- All hardening below applies **automatically** to the auto-generated self-signed keystore;
  the operator changes nothing for the default path.
- Remove the hardcoded keystore password at `McpHttpServer.java:381`; generate the PKCS#12
  keystore in-process. For custom keystores, read the password from an operator-supplied config
  value/file with owner-only permissions — optional mode only.
- Write `mcp-keystore.p12` with owner-only POSIX permissions and check permissions on load;
  clean up the existing world-readable `plugin/run/plugins/EdenMcp/mcp-keystore.p12` artifact.
- Set explicit `SSLParameters`: TLS 1.2+ only, AEAD/forward-secret cipher allowlist, disable
  renegotiation where possible, configured via `HttpsConfigurator`.
- Log only the SHA-256 certificate fingerprint, as DESIGN.md:56 already promises, instead of
  the full `keytool -list` output (`:414-419`). This is the one client-side step: pin/verify the
  fingerprint on first connect.
- **Optional, off by default:** mTLS (`mcp.transport.tls.require-client-cert`, default `false`)
  and custom keystores. Document them as advanced hardening, not installation requirements.
- Keep TLS enabled by default and keep the plaintext warning when disabled. Add a warning when
  binding non-loopback with a self-signed cert, noting that clients should pin the fingerprint.

## 7. Workstream E — Secret leakage and input safety (P1, automatic)

- Redact at **log-write time** in `CommandDispatchTool.java:59`, `ChatSendTool.java:71,84`,
  and tool error paths: log tool id, classification, target type, and length instead of full
  commands/chat. Add `audit.log-verbose` (default `false`) for operators who explicitly want
  detail. Zero setup for the default: sensitive content is simply never written.
- Prefer write-time redaction over changing `eden.logs.read`'s level, so the existing
  `SAFE_READ` tool surface and plug-and-play behavior are preserved. Offer an **optional**
  `mcp.tools.logs.require-moderate` for operators who want stricter separation.
- Bound the client regex filter in `LogsReadTool`: cap pattern length, reject nested-quantifier
  patterns or run matching with a timeout/linear engine, and cap matched line length. Keep the
  existing 1 MiB / 500-line bounds. Internal defaults, no config.
- Audit defaults stay `true` for tool/mode logging; warn when disabled; never allow disabling
  auth-failure logging. Include the optional principal/key id from A4.

## 8. Workstream F — Supply chain and build (P2, invisible to operators)

- Verify the SHA-256 of the LuckPerms runtime jar in `downloadLuckPerms`
  (`plugin/build.gradle.kts:24-39`); enable Gradle dependency verification/lockfile.
- Replace dynamic Paper `+` versions where practical; keep Gson updated. Review
  `disableHtmlEscaping()` (responses are JSON, so low risk).
- These are build-time only and do not add any server-side setup or config.

## 9. Phasing and acceptance criteria

**Phase 0 — critical fixes (all automatic; no new operator steps)**
A1, A2, B1, B2, C (Host/Origin derived from bind, CORS default-off, limits, throttling),
D (no hardcoded password, owner-only keystore, TLS 1.2+, fingerprint-only logging),
E (write-time redaction basics).

Acceptance:
- A fresh install with only existing `mcp.*` values set produces all of the above automatically.
- No query-string auth; invalid key files are repaired, not fatal.
- `eden.command.dispatch` hidden in `safe`; no unanchored downgrade rules.
- Wrong `Host`/`Origin` rejected; no CORS headers by default; oversized requests rejected.
- Keystore not world-readable, no known password; full commands/chat absent from default logs.

**Phase 1 — optional hardening features (all auto-on where safe, opt-in where invasive)**
A3 (throttling automatic, rotation opt-in), A4, B3 (lock opt-in), B4, C (sessions, bounds),
D (mTLS opt-in), E (regex bounds, optional log-read level), F.

Acceptance (all met):
- Key rotation via one console command (`mcp key rotate`) with an in-memory grace period;
  documented but not required.
- Sessions bound to principal, SSE capped, `initialize` required for `tools/list`/`tools/call`/
  `resources/list`; `ping` needs no session.
- Optional policy can block unsafe escalation and optionally require mTLS without affecting
  default installs.
- Dependency checksums verified at build time (LuckPerms jar SHA-256, pinned Paper API, Gradle
  dependency verification metadata).

**Phase 2 — follow-ups**
Per-client scopes (`read`, `chat`, `commands`, `mode`) mapped to key ids; short-lived tokens or
OAuth/OIDC if remote multi-client use becomes a goal; full MCP session spec compliance;
stdio/streamable-HTTP transports only if needed (current `mcp.transport.type` is inert).

## 10. Testing strategy (required with each phase)

Create `core/src/test` (none exists today) and extend `plugin/src/test`.

Unit tests (`core`): `AccessKeyManager` (generation, invalid-key recovery, permissions/symlink,
exact verify, multi-key/rotation, throttling); `CommandClassification` (anchoring, normalization,
chaining, default unsafe); `SecurityPolicy`/mode matrix; `ConfigRedactor`; `LogsReadTool`
(pattern caps and redaction).

Integration tests (`plugin`):
- **Plug-and-play smoke test (new):** start with only the shipped config changed (`enabled`,
  `mode`, `bind`, `port`) and assert every phase-0 control is active without extra config.
- Negative auth: missing/wrong key, raw `Authorization`, query-string key (must fail), legacy
  header under flag, throttling.
- TLS-enabled run (tests currently disable TLS): TLS 1.2+ enforced, TLS 1.0/1.1 rejected,
  fingerprint log format, optional mTLS mode rejects certless clients.
- Host/Origin: wrong `Host` rejected, disallowed `Origin` rejected, CORS headers absent.
- Limits/sessions: oversized body/batch, SSE cap, regex DoS pattern, `tools/call` before
  `initialize` rejected, key revocation closes SSE.
- Mode integrity: `safe` cannot list/call dispatch or set `unsafe` (replace the current
  escalation test); audit entries contain principal and redacted arguments.

Keep test server logs out of CI output or redact them, since today they stream full commands
and chat messages.

## 11. Documentation updates

Because the install flow must stay short, documentation changes are mostly about *what not to
tell the operator* and which knobs are optional:

- **New `docs/DEPLOYMENT.md` (short, ~1 page):** install plugin → start server → read the key
  from `plugins/EdenMcp/.key` → note the TLS fingerprint from startup log → connect with
  `Authorization: Bearer <key>`. Mention that `eden.logs.read` exposes anything written to
  server logs, so logs are redacted by default. Then a clearly separated **"Optional hardening"**
  section: rotation, mTLS, custom keystore, allowed hosts/origins, escalation lock, per-client
  scopes. No "required setup" list beyond the four steps.
- **New `docs/CONFIGURATION.md`:** document only existing keys as required; mark every new key
  from this plan as optional with its automatic default. Avoid a wall of mandatory settings.
- **Update `docs/DESIGN.md`:** mode escalation policy (§39-43), command dispatch classification
  (§100-105), TLS fingerprint/pinning (§56), auth (§58-65), auditing (§205-211), and mark
  hardening step 9 (§223) done. Update the "no rotation required" decision (§231) to "rotation
  available but optional" and the open questions (§234-236) with resolved header/cert/log
  decisions. Emphasize that new controls are automatic, preserving the goal at §17 and §55.
- Keep a short security note in the README (none exists yet) pointing to the deployment guide.

## 12. What the operator actually does (unchanged)

**Required (same as today):**

1. Drop the plugin jar into `plugins/`.
2. Start the server; edit `config.yml` only if the defaults (`mode: safe`, port, bind) need changing.
3. Copy the key from `plugins/EdenMcp/.key` into the MCP client as a Bearer token.
4. Optionally verify the TLS fingerprint from the startup log.

**Automatic, no action:** owner-only key/keystore files, secure key validation and recovery,
Bearer-only auth, derived Host/Origin allowlist, no CORS, request/batch limits, auth throttling,
TLS 1.2+ with sane ciphers, redacted logs, bounded logs regex, bounded SSE/executor, sessions.

**Optional, only if needed:** key rotation or multiple keys, per-client scopes, mTLS, custom
keystore, explicit host/origin lists, escalation lock, verbose audit logging.
