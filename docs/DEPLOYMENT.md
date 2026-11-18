# Deploying EdenMcp

EdenMcp is designed to be plug and play: install the plugin, adjust `config.yml` if needed, and connect an MCP client.

## Quick start

1. Put the built jar (for example `plugin/build/libs/plugin-1.0-all.jar`) into your server's `plugins/` folder.
2. Start the server once. On first start the plugin generates `plugins/EdenMcp/.key` and a self-signed TLS keystore. The startup log prints the certificate SHA-256 fingerprint.
3. Copy the key from `plugins/EdenMcp/.key` into your MCP client as a Bearer token:

   ```
   Authorization: Bearer <contents of .key>
   ```

4. Connect to `https://<server-address>:8765/mcp` (SSE endpoint: `/sse`). Verify the TLS fingerprint from the startup log on first connect.

For local development only, `mcp.transport.tls.enabled: false` allows plain HTTP. This emits a prominent warning and exposes the key and tool traffic in plaintext.

## What is hardened automatically

No extra configuration is required for these:

- The access key is 256 bits of randomness, stored owner-only, compared in constant time, and accepted only as `Authorization: Bearer <key>`. Query-string keys and raw `Authorization` values are rejected. An invalid or world-readable `.key` file is quarantined as `.key.invalid` and replaced with a warning instead of breaking startup.
- Failed authentication is throttled per source IP.
- Host and Origin headers are validated (derived from the configured bind address); no CORS headers are sent by default. With the default wildcard bind and no `allowed-hosts`, non-browser clients may use any `Host`, but browser requests are still constrained by the `Origin` check. Set `allowed-hosts` for strict Host enforcement.
- Request bodies, JSON-RPC batches, SSE connections, and the HTTP worker pool are bounded.
- TLS uses TLS 1.2/1.3 with AEAD ciphers only. The keystore and its random password file are owner-only. The certificate fingerprint is logged at every startup.
- MCP calls use sessions: `initialize` returns an `Mcp-Session-Id` (header and result), and `tools/list`, `tools/call`, `resources/list`, and `initialized` require it. Sessions are bound to the key that created them and expire after idle timeout. `ping` needs no session. Most MCP clients handle this automatically.
- Commands/chat are logged as metadata, not verbatim, so `eden.logs.read` cannot expose message content by default. Log filters are bounded against regex abuse.
- `eden.command.dispatch` is `UNSAFE_ACTION` and is never advertised or callable in `safe` or `moderate` mode. Command classification rules are always anchored to the whole command.

## Optional hardening

Add these to `config.yml` only if your deployment needs them:

```yaml
mcp:
  transport:
    allowed-hosts: ["mcp.example.com"]   # strict Host allowlist
    allowed-origins: ["https://app.example.com"]  # exact browser origins; enables CORS echo/OPTIONS for these
    session-idle-seconds: 1800           # session idle expiry
    max-sessions: 100
    tls:
      require-client-cert: true           # mTLS
      truststore-path: plugins/EdenMcp/client-truststore.p12
      truststore-password: ...
  auth:
    allow-legacy-header: true             # deprecated X-Eden-Key, for migration only
    max-failures: 10
    block-seconds: 60
    rotation-grace-seconds: 300           # old key valid in-memory after rotation
    multi-key-directory: keys             # optional extra owner-only key files
  policy:
    allow-unsafe-at-runtime: false        # require 'mcp mode set unsafe' from console
    logs-require-moderate: true           # hide eden.logs.read in safe mode
audit:
  log-verbose: true                       # log full commands/messages (off by default)
```

## Notes

- The access key grants full access to whatever tools the current mode exposes. Treat it like an operator password and distribute it out-of-band.
- Running with `mode: unsafe` or a non-loopback bind prints a warning. Prefer `safe` until you need more.
- `mcp mode set ...` from the server console always works; `mcp.policy.allow-unsafe-at-runtime` only restricts the MCP tool.
- To rotate the access key, run `mcp key rotate` from the server console. The new key is written to `.key` (the console does **not** print key material); existing MCP sessions are closed, and the old key stays valid only in memory for `mcp.auth.rotation-grace-seconds` (default 300). The grace period is lost on restart, so update clients promptly. Multiple keys can be added as owner-only files in the `mcp.auth.multi-key-directory` (default `keys`) folder.
- See [docs/DESIGN.md](DESIGN.md) for the security rationale behind these defaults.
