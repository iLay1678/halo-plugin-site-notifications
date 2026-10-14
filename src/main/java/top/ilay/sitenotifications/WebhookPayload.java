package top.ilay.sitenotifications;

import java.time.Instant;
import java.util.Map;
import run.halo.app.notification.NotificationContext;

/** Webhook JSON 载荷的固定字段。 */
record WebhookPayload(
    String version,
    String title,
    String rawBody,
    String htmlBody,
    String recipient,
    Instant timestamp,
    NotificationContext.Subject subject,
    Map<String, Object> attributes) {

    static WebhookPayload from(NotificationContext context) {
        var message = context.getMessage();
        var payload = message.getPayload();
        return new WebhookPayload(
            "1",
            payload.getTitle(),
            payload.getRawBody(),
            payload.getHtmlBody(),
            message.getRecipient(),
            message.getTimestamp(),
            message.getSubject(),
            payload.getAttributes());
    }
}
