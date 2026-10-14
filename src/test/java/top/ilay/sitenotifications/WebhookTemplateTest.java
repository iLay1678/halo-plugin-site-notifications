package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import run.halo.app.notification.NotificationContext;

class WebhookTemplateTest {
    @Test
    void replacesStringAndStructuredValues() {
        var context = new NotificationContext();
        var payload = new NotificationContext.MessagePayload();
        payload.setTitle("标题");
        payload.setRawBody("含\"引号\"和换行");
        payload.setHtmlBody("<p>正文</p>");
        var message = new NotificationContext.Message();
        message.setRecipient("alice");
        message.setTimestamp(Instant.parse("2026-10-14T00:00:00Z"));
        message.setPayload(payload);
        context.setMessage(message);

        var body = WebhookTemplate.render(context,
            "{\"title\":\"{{title}}\",\"body\":\"{{rawBody}}\",\"time\":\"{{timestamp}}\"}");
        assertEquals("标题", WebhookTestJson.read(body, "title"));
        assertEquals("含\"引号\"和换行", WebhookTestJson.read(body, "body"));
        assertEquals("2026-10-14T00:00:00Z", WebhookTestJson.read(body, "time"));
    }

    @Test
    void rejectsUnknownAndMalformedTemplates() {
        assertThrows(IllegalArgumentException.class,
            () -> WebhookTemplate.validate("{\"body\":\"{{unknown}}\"}").block());
        assertThrows(IllegalArgumentException.class,
            () -> WebhookTemplate.validate("{\"body\":").block());
    }

    static class WebhookTestJson {
        static String read(String body, String field) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get(field).asText();
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }
    }
}
