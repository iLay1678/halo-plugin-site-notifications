package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookHeadersTest {
    @Test
    void rejectsTransportOverridesInjectionAndCaseInsensitiveDuplicates() {
        for (var name : java.util.List.of("Host", "Content-Length", "Connection", "invalid name")) {
            assertThrows(IllegalArgumentException.class, () -> WebhookHeaders.normalize(Map.of(name, "test")));
        }
        assertThrows(IllegalArgumentException.class, () -> WebhookHeaders.normalize(Map.of("X-Test", "a\r\nHost: local")));
        assertThrows(IllegalArgumentException.class, () -> WebhookHeaders.normalize(Map.of("X-Test", "a", "x-test", "b")));
        assertEquals(Map.of("Authorization", "Bearer test"), WebhookHeaders.normalize(Map.of("Authorization", "Bearer test")));
        assertEquals("POST", WebhookHttpMethod.normalize(null));
        assertEquals("PATCH", WebhookHttpMethod.normalize(" patch "));
        assertThrows(IllegalArgumentException.class, () -> WebhookHttpMethod.normalize("TRACE"));
    }
}
