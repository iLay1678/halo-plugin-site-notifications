package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import top.ilay.sitenotifications.SiteMessage.Phase;
import top.ilay.sitenotifications.SiteMessage.SendMode;

class MessageServiceTest {
    TestStore store;
    RecipientService recipients;
    MessageService service;

    @BeforeEach
    void setup() {
        store = new TestStore();
        recipients = mock(RecipientService.class);
        when(recipients.resolve(any())).thenReturn(Mono.just(List.of("alice")));
        service = new MessageService(store.reactive, recipients, MessageRequestTest.CLOCK);
    }

    @Test
    void createsFrozenSnapshotAndDefaultImmediate() {
        var message = service.create(MessageRequestTest.request(null, null), RecipientServiceTest.user("admin")).block();
        assertEquals(Phase.PENDING, message.getStatus().getPhase());
        assertEquals(List.of("alice"), message.getSpec().getRecipients());
        assertEquals(SendMode.IMMEDIATE, message.getSpec().getSendMode());
        assertEquals("admin", message.getSpec().getSender());
    }

    @Test
    void uncertainCreateResultReusesPersistedTask() {
        when(store.reactive.create(any(SiteMessage.class))).thenAnswer(invocation -> Mono.defer(() -> {
            var message = invocation.getArgument(0, SiteMessage.class);
            message.getMetadata().setVersion(0L);
            store.put(message);
            return Mono.error(new IllegalStateException("模拟写入后连接中断"));
        }));
        var message = service.create(MessageRequestTest.request(null, null), RecipientServiceTest.user("admin")).block();
        assertEquals(Phase.PENDING, message.getStatus().getPhase());
        assertEquals(message.getMetadata().getName(), store.fetch(SiteMessage.class, message.getMetadata().getName())
            .orElseThrow().getMetadata().getName());
    }

    @Test
    void duplicateSubmissionDoesNotResolveAgainOrRestoreCancelledMessageAfterDeadline() {
        var input = MessageRequestTest.request(SendMode.SCHEDULED, MessageRequestTest.NOW.plusSeconds(60));
        var message = service.create(input, RecipientServiceTest.user("admin")).block();
        var cancelled = service.cancel(message.getMetadata().getName(), "admin").block();
        var laterRecipients = mock(RecipientService.class);
        var laterService = new MessageService(store.reactive, laterRecipients,
            java.time.Clock.fixed(MessageRequestTest.NOW.plusSeconds(120), java.time.ZoneOffset.UTC));
        var duplicate = laterService.create(input, RecipientServiceTest.user("admin")).block();
        assertEquals(cancelled.getMetadata().getName(), duplicate.getMetadata().getName());
        assertEquals(Phase.CANCELLED, duplicate.getStatus().getPhase());
        verify(laterRecipients, never()).resolve(any());
        var different = new MessageRequest(input.requestId(), "其他标题", input.content(), input.type(), input.target(),
            input.usernames(), input.roles(), input.sendMode(), input.scheduledAt());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> laterService.create(different, RecipientServiceTest.user("admin")).block()).getStatusCode());
    }

    @Test
    void rejectsEmptySnapshot() {
        when(recipients.resolve(any())).thenReturn(Mono.just(List.of()));
        assertThrows(ResponseStatusException.class,
            () -> service.create(MessageRequestTest.request(null, null), RecipientServiceTest.user("admin")).block());
        verify(store.reactive, never()).create(any(SiteMessage.class));
    }

    @Test
    void repeatCancellationPreservesFirstAuditAndCancelledCannotRetry() {
        var message = MessageReconcilerTest.message(Phase.SCHEDULED, MessageRequestTest.NOW.minusSeconds(1));
        store.put(message);
        var first = service.cancel("task", "admin").block();
        var second = service.cancel("task", "other-admin").block();
        assertEquals(first.getStatus().getCancelledAt(), second.getStatus().getCancelledAt());
        assertEquals("admin", second.getStatus().getCancelledBy());
        assertEquals(first.getMetadata().getVersion(), second.getMetadata().getVersion());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.retry("task").block()).getStatusCode());
    }

    @Test
    void retriesPreserveDeliveredAndWakeController() {
        var message = MessageReconcilerTest.message(Phase.PARTIAL_FAILED, null);
        message.getStatus().getDelivered().add("alice");
        message.getStatus().getFailures().put("bob", "失败");
        store.put(message);
        var retried = service.retry("task").block();
        assertEquals(List.of("alice"), retried.getStatus().getDelivered());
        assertEquals(Phase.PENDING, retried.getStatus().getPhase());
        assertEquals(0, retried.getStatus().getFailures().size());
        assertEquals("0", retried.getMetadata().getAnnotations()
            .get("site-notifications.halo.run/retry-version"));
    }

    @Test
    void cancellationConflictsAreBounded() {
        store.put(MessageReconcilerTest.message(Phase.SCHEDULED, MessageRequestTest.NOW.plusSeconds(60)));
        store.beforeReactiveUpdate = message -> { throw new OptimisticLockingFailureException("持续竞争"); };
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.cancel("task", "admin").block()).getStatusCode());
        verify(store.reactive, org.mockito.Mockito.times(4)).update(any(SiteMessage.class));
    }
}
