package at.haha007.edenmcp.core.config;

import at.haha007.edenmcp.api.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigRedactorTest {

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    private static String rendered(Object value) {
        return String.valueOf(value);
    }

    @Test
    void sensitiveLeafValuesAreMaskedEverywhere() {
        Map<String, Object> config = map(
                "key", "s3cr3t-key-value",
                "api-token", "s3cr3t-token-value",
                "Password", "s3cr3t-pw-value",
                "client_secret", "s3cr3t-client-secret-value",
                "credential", "s3cr3t-cred-value",
                "auth", "s3cr3t-auth-value",
                "privateKey", "s3cr3t-private-value",
                "keystore", "s3cr3t-keystore-value",
                "passphrase", "s3cr3t-passphrase-value",
                "host", "localhost"
        );

        Object out = ConfigRedactor.redact(config, "root");
        Map<?, ?> redacted = (Map<?, ?>) out;

        for (String key : List.of("key", "api-token", "Password", "client_secret", "credential",
                "auth", "privateKey", "keystore", "passphrase")) {
            assertEquals("[REDACTED]", redacted.get(key), "sensitive key was not masked: " + key);
        }
        assertEquals("localhost", redacted.get("host"), "non-sensitive values must survive");

        String text = rendered(out);
        for (String secret : List.of("s3cr3t-key-value", "s3cr3t-token-value", "s3cr3t-pw-value",
                "s3cr3t-client-secret-value", "s3cr3t-cred-value", "s3cr3t-auth-value",
                "s3cr3t-private-value", "s3cr3t-keystore-value", "s3cr3t-passphrase-value")) {
            assertFalse(text.contains(secret), "secret leaked into redacted config: " + secret);
        }
    }

    @Test
    void denylistedPathsReportPresenceOnly() {
        Object nested = ConfigRedactor.redact(map("key", "leak-1"), "mcp.auth");
        assertEquals(Map.of("status", "[PRESENT]"), nested);

        Object keyLeaf = ConfigRedactor.redact("leak-2", "mcp.auth.key");
        assertEquals("[PRESENT]", keyLeaf);

        Object list = ConfigRedactor.redact(List.of("leak-3"), "auth");
        assertEquals(Map.of("status", "[PRESENT]"), list);

        Object deeper = ConfigRedactor.redact("leak-4", "mcp.auth.keystore.password");
        assertEquals("[PRESENT]", deeper, "every descendant of a denylisted path is denied");

        Object upperCase = ConfigRedactor.redact("leak-5", "MCP.AUTH.KEY");
        assertEquals("[PRESENT]", upperCase, "path matching is case-insensitive");

        assertNull(ConfigRedactor.redact(null, "auth.key"));

        String text = rendered(List.of(nested, keyLeaf, list, deeper, upperCase));
        for (String leak : List.of("leak-1", "leak-2", "leak-3", "leak-4", "leak-5")) {
            assertFalse(text.contains(leak), "denylisted value leaked: " + leak);
        }
    }

    @Test
    void nestedMapsAndListsAreRedactedRecursively() {
        Map<String, Object> tls = map("keystorePassword", "tls-password-value", "enabled", true);
        Map<String, Object> transport = map("tls", tls, "port", 8765);
        List<Object> servers = new ArrayList<>();
        servers.add(map("url", "https://one", "token", "list-token-value"));
        servers.add(map("url", "https://two", "password", "list-pw-value"));
        Map<String, Object> config = map("mcp", map("transport", transport), "servers", servers);

        Object out = ConfigRedactor.redact(config, "");
        Map<?, ?> root = (Map<?, ?>) out;
        Map<?, ?> tr = (Map<?, ?>) ((Map<?, ?>) root.get("mcp")).get("transport");
        Map<?, ?> redactedTls = (Map<?, ?>) tr.get("tls");

        assertEquals("[REDACTED]", redactedTls.get("keystorePassword"));
        assertEquals(true, redactedTls.get("enabled"));
        assertEquals(8765, tr.get("port"));

        List<?> redactedServers = (List<?>) root.get("servers");
        assertEquals("https://one", ((Map<?, ?>) redactedServers.get(0)).get("url"));
        assertEquals("[REDACTED]", ((Map<?, ?>) redactedServers.get(0)).get("token"));
        assertEquals("[REDACTED]", ((Map<?, ?>) redactedServers.get(1)).get("password"));

        String text = rendered(out);
        for (String secret : List.of("tls-password-value", "list-token-value", "list-pw-value")) {
            assertFalse(text.contains(secret), "secret leaked from a nested structure: " + secret);
        }
    }

    @Test
    void sensitiveKeysHoldingContainersReportPresenceOnly() {
        Map<String, Object> config = map(
                "keystore", map("path", "/etc/secret.p12", "password", "inner-pw"),
                "credentials", List.of("a", "b")
        );

        Object out = ConfigRedactor.redact(config, "root");
        Map<?, ?> redacted = (Map<?, ?>) out;

        assertEquals(Map.of("status", "[PRESENT]"), redacted.get("keystore"));
        assertEquals(Map.of("status", "[PRESENT]"), redacted.get("credentials"));

        String text = rendered(out);
        assertFalse(text.contains("/etc/secret.p12"));
        assertFalse(text.contains("inner-pw"));
    }

    @Test
    void blankAndNullSensitiveValuesKeepTheirShape() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("password", "");
        config.put("token", null);
        config.put("secret", "   ");

        Map<?, ?> redacted = (Map<?, ?>) ConfigRedactor.redact(config, "root");

        assertEquals("", redacted.get("password"));
        assertNull(redacted.get("token"));
        assertEquals("", redacted.get("secret"), "blank values stay blank rather than looking like hidden secrets");

        assertNull(ConfigRedactor.redact(null, "password"));
        assertEquals("[REDACTED]", ConfigRedactor.redact(42, "password"));
    }

    @Test
    void sensitiveLeafNamesAreHonouredWithoutAnEnclosingMap() {
        assertEquals("[REDACTED]", ConfigRedactor.redact("value", "some.path.apikey"));
        assertEquals("value", ConfigRedactor.redact("value", "some.path.label"));
        assertEquals("value", ConfigRedactor.redact("value", null));
        assertEquals(7, ConfigRedactor.redact(7, null));
    }

    @Test
    void redactionDoesNotMutateTheLiveConfiguration() {
        String secret = "do-not-leak-key-value";
        Map<String, Object> config = map("token", secret, "port", 8765, "nested", map("enabled", true));

        Object out = ConfigRedactor.redact(config, "root");
        Map<?, ?> redacted = (Map<?, ?>) out;

        assertNotSame(config, out);
        assertEquals(8765, redacted.get("port"));
        assertEquals(Map.of("enabled", true), redacted.get("nested"));
        assertEquals("[REDACTED]", redacted.get("token"));
        assertEquals(secret, config.get("token"), "the running config must not be mutated by redaction");
    }
}
