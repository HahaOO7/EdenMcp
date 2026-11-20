# EdenMcp Tool / Hook Registration API — Design

Status: **implemented**. This document describes the public, versioned API that lets other
Bukkit/Paper plugins contribute MCP tools and resources to EdenMcp at runtime.

Confirmed decisions: default capability cap for external hooks is `moderate`; strict
`edenmcp.<hookId>.` / `edenmcp://<hookId>/` namespacing is enforced; the API artifact is
published through JitPack; both the Java package and MCP names use `edenmcp`.

## 1. Goals and non-goals

### Goals

- Any plugin can register tools and resources with EdenMcp **without rebuilding EdenMcp**.
- A single registration path for both built-in and third-party integrations.
- EdenMcp remains **server-authoritative**: mode gating, access keys, policy, auditing and
  capability limits stay in EdenMcp, never in the contributing plugin.
- Isolation: a broken, slow-disabling or badly configured hook cannot take down core MCP
  service or other hooks.
- Automatic cleanup when the owning plugin disables or EdenMcp disables.
- Discoverability through `edenmcp.hooks.list` and `EdenMcpApi#hooks()`.
- A small, stable, published `api` artifact independent of transport/auth internals.

### Non-goals

- MCP *clients* registering hooks (clients only discover server capabilities).
- Letting hooks bypass `CapabilityLevel`, access-key checks, audit, or `SecurityPolicy`.
- Hot-replacing core tools or resources.
- Cross-server orchestration.

## 2. Module and artifact layout

Add a new Gradle module:

```text
api/                    # NEW - published, stable contract only
core/                   # depends on api; transport, auth, registries, HookManager impl
hooks/luckperms/        # depend on api only (no core internals)
hooks/spark/            # depend on api only
plugin/                 # depends on api + core; assembles and publishes the service
```

`api` has **no dependency on `core`** and no Shadow. It exposes Gson (used by
`ResourceContents#toMap`) and `compileOnly` the Paper API and JetBrains annotations.

Only `api` is published. JitPack builds it from the GitHub tag; `jitpack.yml` selects JDK 25
and publishes only that module. The publication uses the JitPack multi-module group
`com.github.haha007.EdenMcp` and artifact id `edenmcp-api`. Consumers use:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { compileOnly("com.github.haha007.EdenMcp:edenmcp-api:1.0") }
```

```yaml
# consumer plugin.yml
softdepend: [EdenMcp]
```

`compileOnly` is important: the API classes are loaded from EdenMcp's shaded jar at runtime.
Bundling the API jar into a consumer plugin creates a second copy of the interface class and
will fail with `LinkageError`/service lookup misses. Shadow must not relocate `at.haha007.edenmcp.api`.

## 3. Public API surface (package `at.haha007.edenmcp.api`)

### 3.1 Entrypoint

```java
public interface EdenMcpApi {
    /** Register a full hook. Throws only for validation errors. */
    HookRegistration registerHook(Plugin owner, Hook hook) throws HookRegistrationException;

    /** Convenience: wrap one tool in an internal, owner-named hook. */
    Registration registerTool(Plugin owner, McpTool tool) throws HookRegistrationException;

    /** Convenience: wrap one resource in an internal, owner-named hook. */
    Registration registerResource(Plugin owner, McpResource resource) throws HookRegistrationException;

    /** Read-only descriptor list (same data backing edenmcp.hooks.list). */
    List<HookInfo> hooks();

    /** False once EdenMcp has begun shutting down. */
    boolean isAcceptingRegistrations();
}
```

Discovered through Bukkit's service locator:

```java
RegisteredServiceProvider<EdenMcpApi> provider =
        Bukkit.getServicesManager().getRegistration(EdenMcpApi.class);
```

EdenMcp also fires `EdenMcpApiAvailableEvent` immediately after publishing the service, so
plugins that load before EdenMcp (e.g. `load: STARTUP`) can defer registration instead of
polling.

### 3.2 Hook and contribution

```java
public interface Hook {
    /** Unique stable id, [a-z0-9][a-z0-9._-]{0,63}, e.g. "myplugin". */
    String id();

    default String displayName() { return id(); }

    /** Main thread. Return false to leave the hook disabled. */
    default boolean isAvailable(Server server) { return true; }

    /** Main thread. Called every time the hook is enabled. */
    HookContribution contribute(HookContext ctx) throws Exception;

    /** Main thread, optional. */
    default void onEnable() {}
    default void onDisable() {}
}

public record HookContribution(List<McpTool> tools, List<McpResource> resources) {
    public HookContribution { /* null -> List.of(), defensive copies */ }
    public static HookContribution of(List<McpTool> tools, List<McpResource> resources) { ... }
    public static HookContribution tools(McpTool... tools) { ... }
    public static HookContribution empty() { ... }
}

public record HookContext(Server server, Plugin owner, Plugin edenMcp, Logger logger) {}
```

Lifecycle callbacks move from `HookContribution`'s `Optional<Runnable>` fields onto `Hook`
as default methods. The old factories stay as deprecated shims for one major version.

### 3.3 Contracts contributed by hooks

These types move from `core` into `api` unchanged in shape:

- `McpTool` (`name`, `description`, `level`, `sideEffects`, `tags`, `inputSchema`, `execute`, `exposed`)
- `McpResource` + `ResourceReader`, `ResourceContext`, `ResourceContents`
- `ToolContext`, `ToolResult`
- `CapabilityLevel`, `SideEffects`, `OperatingMode`

Plus two API-only helpers:

```java
public interface Registration {
    Plugin owner();
    String id();                  // hook id / tool name / resource URI
    RegistrationState state();    // ENABLED | DISABLED | FAILED | UNREGISTERED
    boolean unregister();         // idempotent, owner-checked
    default void close() { unregister(); }
}

public interface HookRegistration extends Registration {
    Hook hook();
    HookInfo info();
    void setEnabled(boolean enabled);   // admin-controlled enable/disable
    boolean isEnabled();
}

public record HookInfo(
        String id,
        String displayName,
        String source,                  // "builtin" | "external" | "tool" | "resource"
        Plugin owner,
        boolean enabledInConfig,
        boolean available,
        boolean enabled,
        List<String> tools,
        List<String> resources,
        List<String> skipped,           // contributions rejected by policy
        String lastError                // nullable
) {}

public class HookRegistrationException extends RuntimeException {
    public enum Reason {
        API_UNAVAILABLE, INVALID_ID, INVALID_NAME, INVALID_URI, DUPLICATE_ID,
        NAME_COLLISION, LEVEL_NOT_ALLOWED, SIDE_EFFECT_NOT_ALLOWED, NOT_OWNER
    }
    public Reason reason() { ... }
}
```

`Sync` utility (api module) — tool `execute()` runs on HTTP worker threads, so hooks must
schedule Bukkit access:

```java
public final class Sync {
    public static <T> T call(Plugin owner, Callable<T> task) throws Exception;
    public static void run(Plugin owner, Runnable task);
}
```

### 3.4 Direct tool/resource registration

`registerTool` / `registerResource` are implemented in EdenMcp as thin adapters that create
an internal hook with a unique generated id `ext.<sanitized-owner-name>.tools-<n>` /
`.resources-<n>`, so repeated calls from the same plugin never collide. The tool is wrapped
in a renaming decorator so a `name()` of `"status"` is advertised as
`edenmcp.<hookId>.status`. Everything then flows through the same `HookManager` path — there is
only one internal registration mechanism.

## 4. Registration and lifecycle

1. Consumer enables, looks up `EdenMcpApi`, calls `registerHook(this, hook)` on the **main
   server thread**. API calls off the main thread throw `IllegalStateException`.
2. EdenMcp validates id, names, URIs, ownership and policy **before mutating any state**.
3. A `HookState` is created and stored; a `HookRegistration` handle is returned.
4. If EdenMcp is live and the hook is enabled by config, `enable` runs immediately:
   `isAvailable` → `contribute` → `onEnable` → register tools → register resources.
   Any failure is caught, already-registered contributions are rolled back by exact instance,
   `onDisable` is attempted, and state becomes `FAILED` with `lastError` recorded.
5. `setEnabled(false)` calls `onDisable`, unregisters contributions by exact instance + prefix,
   and marks the hook disabled. `setEnabled(true)` re-runs step 4 (so `contribute()` must be
   safe to call repeatedly).
6. `unregister()` (or `close()`) disables, removes the state and frees the id. It is idempotent
   and verifies the caller's owner identity.
7. EdenMcp listens for `PluginDisableEvent` and unregisters **all** hooks whose owner is the
   disabling plugin, so cleanup happens even if the consumer forgets.
8. During EdenMcp `onDisable`: service is unregistered, `isAcceptingRegistrations()` becomes
   false, all hooks are disabled and unregistered, audit lines written.

Startup order in `EdenMcpPlugin#onEnable` is: config → audit/keys/policy → registries →
HookManager → built-in hooks via `registerKnownHook` (the same manager path) → core
tools/resources → `enableAvailableHooks()` → **publish service + fire event** → HTTP server →
commands.

## 5. Security and validation

All checks live in EdenMcp; hook code is untrusted input.

| Check | Rule |
| --- | --- |
| Hook id | `[a-z0-9][a-z0-9._-]{0,63}`; reserved ids (`core`, `mcp`, `system`, built-in hook ids, core tool namespaces) and the `edenmcp.` / `ext.` prefixes rejected; unique across all registrations |
| Tool name | `edenmcp\.[a-z0-9][a-z0-9._-]{0,190}`, must start with `edenmcp.<hookId>.`; globally unique |
| Resource URI | must start with `edenmcp://<hookId>/`; globally unique |
| Nulls | null lists/elements are skipped with a warning, never throwing into the manager |
| Namespaces | core reserves bare `edenmcp.<name>` tools and `edenmcp://config/*`; external hooks cannot overwrite them |
| Capability cap | policy-derived maximum level per hook (default `MODERATE_ACTION` for external hooks) |
| Side effects | `DANGEROUS` requires explicit per-hook opt-in |
| Policy violation | offending tool/resource is **skipped** with a loud warning and listed in `HookInfo.skipped()`; the rest of the hook still enables |
| Audit | register, unregister, enable/disable changes logged as `[AUDIT] hook-*` (capability surface changes) |
| Result bounds | add a serialized-size cap for tool results and resource content (all hooks, not just external) |

New config block:

```yaml
hooks:
  luckperms: { enabled: true }
  spark: { enabled: true }
  external:
    default-enabled: true
    max-capability-level: moderate      # safe | moderate | unsafe
    allow-dangerous-side-effects: false
    overrides:
      myplugin:
        enabled: true
        max-capability-level: unsafe    # explicit operator opt-in
        allow-dangerous-side-effects: true
```

This keeps the current posture: a newly installed plugin cannot silently add an
`UNSAFE_ACTION` tool; the operator must opt in.

## 6. Refactors (completed)

- **api (new):** contract types listed in §3 plus `EdenMcpApi`, `Hook`, `HookContribution`,
  `HookContext`, `Registration`, `HookInfo`, exception, `Sync` and the availability event.
- **core:** `HookManager` gained `register/enable/disable/unregister/unregisterAllFor`;
  `HookState` carries owner, source, policy, skipped, lastError and handle; `HookContext` is
  scoped per hook; `describeHooks()` returns richer descriptors. The old `core.hook.EdenHook`
  was replaced by `api.Hook`.
- **plugin:** publishes/unpublishes the service, fires `EdenMcpApiAvailableEvent`, adds the
  `PluginDisableEvent` listener, registers built-ins through the manager, maps `HookInfo` to
  the `edenmcp.hooks.list` payload.
- **hooks/\*:** imports switched from `core.*` to `api.*`; no behaviour change.
- **build:** `api` publishing config for JitPack; `core`, hooks and `plugin` depend on `api`;
  Shadow keeps the api package unrelocated so consumers share one class identity.

## 7. Example consumer

```java
public final class MyPlugin extends JavaPlugin {
    private Registration registration;

    @Override public void onEnable() {
        var provider = getServer().getServicesManager().getRegistration(EdenMcpApi.class);
        if (provider == null) {
            getLogger().warning("EdenMcp absent; MCP integration disabled.");
            return;
        }

        Hook hook = new Hook() {
            public String id() { return "myplugin"; }
            public String displayName() { return "MyPlugin integration"; }
            public HookContribution contribute(HookContext ctx) {
                McpTool info = new McpTool() {
                    public String name() { return "edenmcp.myplugin.info"; }
                    public String description() { return "MyPlugin info (read-only)."; }
                    public CapabilityLevel level() { return CapabilityLevel.SAFE_READ; }
                    public SideEffects sideEffects() { return SideEffects.NONE; }
                    public List<String> tags() { return List.of("myplugin"); }
                    public Map<String, Object> inputSchema() {
                        return Map.of("type", "object", "properties", Map.of());
                    }
                    public ToolResult execute(ToolContext c) {
                        return ToolResult.ok(Map.of("ok", true));
                    }
                };
                return HookContribution.of(List.of(info),
                        List.of(McpResource.text("edenmcp://myplugin/info", "MyPlugin info",
                                "Info JSON", "application/json", () -> Map.of("ok", true))));
            }
        };

        registration = provider.getProvider().registerHook(this, hook);
    }

    @Override public void onDisable() {
        if (registration != null) registration.unregister(); // also auto-cleaned by EdenMcp
    }
}
```

Operator opt-in for a privileged hook:

```yaml
hooks:
  external:
    overrides:
      myplugin:
        max-capability-level: unsafe
        allow-dangerous-side-effects: true
```

## 8. Testing plan

- `api` unit tests: id/name/URI validation, defensive copies, decorators, `Sync`.
- `core` tests: dynamic register/enable/disable/unregister; duplicate id and name collision;
  owner mismatch; policy capping and `skipped`; failure rollback; repeated `contribute()`;
  `unregisterAllFor`; state transitions after failed enable.
- Integration test: a tiny test plugin in `plugin/build/integration-server/plugins/` that
  registers a hook via the service; assert `tools/list`, `resources/list`, `resources/read`,
  `edenmcp.hooks.list`, plugin-disable cleanup, and level filtering in `safe` mode.

## 9. Implementation status

Done: `api` module with the contract types and JitPack publication; `HookManager` dynamic
registration, handles, ownership, policy caps and namespacing validation; service publication,
`EdenMcpApiAvailableEvent`, plugin-disable cleanup; `registerTool`/`registerResource`, `Sync`,
the `hooks.external` config block, and the package/MCP-namespace rename.

Remaining follow-ups: re-evaluate hook enablement on `/mcp reload`, add serialized result-size
bounds for all hooks, and add the integration-test plugin described in §8.
