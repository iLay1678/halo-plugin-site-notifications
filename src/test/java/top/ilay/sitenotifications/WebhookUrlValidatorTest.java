package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookUrlValidatorTest {
    @Test
    void acceptsHttpsPublicHost() {
        var uri = WebhookUrlValidator.validate("https://8.8.8.8/hooks?id=1").block();
        assertEquals("8.8.8.8", uri.getHost());
    }

    @Test
    void rejectsNonHttpsAndUnsafeAddresses() {
        assertThrows(IllegalArgumentException.class,
            () -> WebhookUrlValidator.validate("http://example.com").block());
        assertThrows(IllegalArgumentException.class,
            () -> WebhookUrlValidator.validate("https://127.0.0.1/hook").block());
        assertThrows(IllegalArgumentException.class,
            () -> WebhookUrlValidator.validate("https://user:pass@example.com/hook").block());
    }

    @Test
    void rejectsPrivateAndSpecialPurposeAddresses() {
        for (var host : List.of("169.254.169.254", "10.0.0.1", "192.168.0.1", "172.16.1.1",
            "100.64.0.1", "0.1.2.3", "224.0.0.1", "[::1]", "[fc00::1]", "[fe80::1]",
            "[2002:a00:1::]", "[2001:0::1]", "[::ffff:127.0.0.1]")) {
            assertThrows(IllegalArgumentException.class,
                () -> WebhookUrlValidator.validate("https://" + host).block(), host);
        }
    }
}
