package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import run.halo.app.notification.NotificationContext;
import run.halo.app.notification.ReasonAttributes;

class WebhookPayloadTest {
    @Test
    void mapsNotificationContextToStablePayload() {
        var messagePayload = new NotificationContext.MessagePayload();
        messagePayload.setTitle("标题");
        messagePayload.setRawBody("正文");
        messagePayload.setHtmlBody("<p>正文</p>");
        var attributes = new ReasonAttributes();
        attributes.put("reason", "test");
        messagePayload.setAttributes(attributes);
        var message = new NotificationContext.Message();
        message.setPayload(messagePayload);
        message.setRecipient("alice");
        message.setTimestamp(Instant.parse("2026-10-14T00:00:00Z"));
        var context = new NotificationContext();
        context.setMessage(message);

        var payload = WebhookPayload.from(context);
        assertEquals("1", payload.version());
        assertEquals("标题", payload.title());
        assertEquals("alice", payload.recipient());
        assertEquals("test", payload.attributes().get("reason"));
    }
}
