package sg.edu.rp.sandbox.gateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the gateway routes the AI assistant service exactly like the other
 * backend services: {@code Path=/api/v1/ai/**} with {@code StripPrefix=2}, targeting
 * the configurable AI service URI ({@code ${AI_URI:http://localhost:8086}}).
 *
 * <p>Parses the real {@code application.yml} route table directly (no live backend
 * needed, and no reliance on the test profile which intentionally omits routes), so
 * it fails loudly if the {@code ai-service} route is removed or misconfigured.
 */
class AiServiceRouteTest {

    @Test
    @SuppressWarnings("unchecked")
    void aiServiceRouteStripsPrefixAndTargetsAiUri() {
        // Read the MAIN application.yml (the test profile's copy on the classpath
        // intentionally omits routes, so load the source file by path instead).
        Path appYml = Path.of("src", "main", "resources", "application.yml");
        assertThat(Files.exists(appYml))
                .as("gateway main application.yml at %s", appYml.toAbsolutePath())
                .isTrue();

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(appYml)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("Failed to read gateway application.yml", e);
        }

        List<Map<String, Object>> routes = (List<Map<String, Object>>) dig(
                root, "spring", "cloud", "gateway", "server", "webflux", "routes");
        assertThat(routes).as("gateway routes").isNotNull();

        Map<String, Object> ai = routes.stream()
                .filter(r -> "ai-service".equals(r.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ai-service route not found in gateway configuration"));

        // Targets the AI service (default localhost:8086, overridable via AI_URI).
        assertThat((String) ai.get("uri")).contains("AI_URI").contains("8086");

        // Predicate: Path=/api/v1/ai/**
        assertThat((List<String>) ai.get("predicates"))
                .anyMatch(p -> p.equals("Path=/api/v1/ai/**"));

        // Filter: StripPrefix=2 (so /api/v1/ai/chat -> /ai/chat on the service).
        assertThat((List<String>) ai.get("filters"))
                .anyMatch(f -> f.equals("StripPrefix=2"));
    }

    @SuppressWarnings("unchecked")
    private static Object dig(Map<String, Object> node, String... path) {
        Object current = node;
        for (String key : path) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }
}
