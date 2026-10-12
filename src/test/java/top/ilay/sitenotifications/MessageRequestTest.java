package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import top.ilay.sitenotifications.SiteMessage.MessageType;
import top.ilay.sitenotifications.SiteMessage.SendMode;
import top.ilay.sitenotifications.SiteMessage.Target;

class MessageRequestTest {
    static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static MessageRequest request(SendMode mode, Instant scheduledAt) {
        return new MessageRequest("request-1", "通知", "第一行\n第二行", MessageType.MESSAGE,
            Target.USERS, List.of("alice"), null, mode, scheduledAt).normalized();
    }

    @Test
    void defaultsToImmediateAndNormalizesSelection() {
        var request = new MessageRequest(" id ", " 标题 ", "正文", MessageType.MESSAGE,
            Target.USERS, List.of("bob", " alice ", "bob"), null, null, null).normalized();
        request.validate(CLOCK);
        assertEquals(SendMode.IMMEDIATE, request.sendMode());
        assertEquals(List.of("alice", "bob"), request.usernames());
        assertEquals("标题", request.title());
    }

    @Test
    void validatesContentTargetsAndTiming() {
        assertThrows(ResponseStatusException.class, () -> new MessageRequest("id", " ", "正文",
            MessageType.MESSAGE, Target.ALL, null, null, null, null).normalized().validate(CLOCK));
        assertThrows(ResponseStatusException.class, () -> new MessageRequest("id", "标题", " ",
            MessageType.MESSAGE, Target.ALL, null, null, null, null).normalized().validate(CLOCK));
        assertThrows(ResponseStatusException.class, () -> new RecipientRequest(null, null, null).normalized().validate());
        assertThrows(ResponseStatusException.class, () -> new RecipientRequest(Target.USERS, null, null).normalized().validate());
        assertThrows(ResponseStatusException.class, () -> new RecipientRequest(Target.ALL, List.of("alice"), null).normalized().validate());
        assertThrows(ResponseStatusException.class, () -> request(SendMode.SCHEDULED, null).validate(CLOCK));
        assertThrows(ResponseStatusException.class, () -> request(SendMode.SCHEDULED, NOW).validate(CLOCK));
        assertThrows(ResponseStatusException.class, () -> request(SendMode.IMMEDIATE, NOW.plusSeconds(60)).validate(CLOCK));
    }

    @Test
    void acceptsFutureOffsetTimeAsUtcInstant() {
        var scheduled = OffsetDateTime.parse("2026-10-09T12:01:00+08:00").toInstant();
        var request = request(SendMode.SCHEDULED, scheduled);
        request.validate(CLOCK);
        assertEquals(NOW.plusSeconds(60), request.scheduledAt());
    }
}
