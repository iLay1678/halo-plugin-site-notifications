package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.Notification;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Reconciler.Request;
import top.ilay.sitenotifications.SiteMessage.MessageType;
import top.ilay.sitenotifications.SiteMessage.Phase;
import top.ilay.sitenotifications.SiteMessage.SendMode;
import top.ilay.sitenotifications.SiteMessage.Target;

class MessageReconcilerTest {
    TestStore store;
    MessageReconciler reconciler;
    MessageService service;
    NotificationCenter notificationCenter;

    @BeforeEach
    void setup() {
        store = new TestStore();
        notificationCenter = mock(NotificationCenter.class);
        when(notificationCenter.notify(any(Reason.class))).thenAnswer(invocation -> {
            store.put(notificationFor(invocation.getArgument(0, Reason.class)));
            return reactor.core.publisher.Mono.empty();
        });
        when(store.synchronous.list(eq(Notification.class), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var predicate = (java.util.function.Predicate<Notification>) invocation.getArgument(1);
            return store.records.values().stream()
                .filter(value -> value instanceof Notification)
                .map(Notification.class::cast)
                .filter(predicate)
                .toList();
        });
        reconciler = new MessageReconciler(store.synchronous, MessageRequestTest.CLOCK,
            notificationCenter);
        service = new MessageService(store.reactive, mock(RecipientService.class), MessageRequestTest.CLOCK);
        when(store.synchronous.fetch(eq(Notification.class), anyString()))
            .thenAnswer(invocation -> store.fetch(Notification.class, invocation.getArgument(1)));
        when(store.synchronous.fetch(eq(User.class), anyString()))
            .thenAnswer(invocation -> Optional.of(RecipientServiceTest.user(invocation.getArgument(1))));
        doAnswer(invocation -> { store.put(invocation.getArgument(0, Notification.class)); return null; })
            .when(store.synchronous).create(any(Notification.class));
    }

    @Test
    void futureTaskNeverSendsAndExpiredTaskRecoversAfterRestart() {
        store.put(message(Phase.SCHEDULED, MessageRequestTest.NOW.plusSeconds(60)));
        assertTrue(reconciler.reconcile(new Request("task")).reEnqueue());
        verify(store.synchronous, never()).create(any(Notification.class));
        var restarted = new MessageReconciler(store.synchronous,
            Clock.fixed(MessageRequestTest.NOW.plusSeconds(120), ZoneOffset.UTC), notificationCenter);
        assertFalse(restarted.reconcile(new Request("task")).reEnqueue());
        assertEquals(Phase.SENT, store.fetch(SiteMessage.class, "task").orElseThrow().getStatus().getPhase());
        verify(notificationCenter, times(1)).notify(any(Reason.class));
    }

    @Test
    void cancellationWinsResourceVersionCompetitionAndNoNotificationIsCreated() {
        store.put(message(Phase.SCHEDULED, MessageRequestTest.NOW.minusSeconds(1)));
        store.beforeSyncUpdate = stale -> {
            store.beforeSyncUpdate = ignored -> { };
            assertEquals(Phase.CANCELLED, service.cancel("task", "admin").block().getStatus().getPhase());
        };
        assertTrue(reconciler.reconcile(new Request("task")).reEnqueue());
        assertFalse(reconciler.reconcile(new Request("task")).reEnqueue());
        var restarted = new MessageReconciler(store.synchronous,
            Clock.fixed(MessageRequestTest.NOW.plusSeconds(120), ZoneOffset.UTC), notificationCenter);
        assertFalse(restarted.reconcile(new Request("task")).reEnqueue());
        verify(store.synchronous, never()).create(any(Notification.class));
        assertEquals(Phase.CANCELLED, store.fetch(SiteMessage.class, "task").orElseThrow().getStatus().getPhase());
    }

    @Test
    void sendStartWinsResourceVersionCompetitionAndCancellationReturnsConflict() {
        store.put(message(Phase.SCHEDULED, MessageRequestTest.NOW.minusSeconds(1)));
        store.beforeReactiveUpdate = stale -> {
            store.beforeReactiveUpdate = ignored -> { };
            reconciler.reconcile(new Request("task"));
        };
        var error = assertThrows(ResponseStatusException.class, () -> service.cancel("task", "admin").block());
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(Phase.SENT, store.fetch(SiteMessage.class, "task").orElseThrow().getStatus().getPhase());
        verify(notificationCenter, times(1)).notify(any(Reason.class));
    }

    @Test
    void persistedDeliveredRecipientIsNotRecreatedAfterUserDeletesInboxNotification() {
        var message = message(Phase.SENDING, null);
        message.getStatus().getDelivered().add("alice");
        store.put(message);
        reconciler.reconcile(new Request("task"));
        verify(store.synchronous, never()).create(any(Notification.class));
        assertEquals(Phase.SENT, store.fetch(SiteMessage.class, "task").orElseThrow().getStatus().getPhase());
    }

    @Test
    void existingReadNotificationIsPreservedDuringRestartRecovery() {
        var message = message(Phase.SENDING, null);
        store.put(message);
        var notification = new Notification();
        var notificationMetadata = new Metadata();
        notificationMetadata.setName("existing");
        notification.setMetadata(notificationMetadata);
        var notificationSpec = new Notification.NotificationSpec();
        notificationSpec.setRecipient("alice");
        notificationSpec.setReason(MessageReconciler.reasonName("task", "alice"));
        notification.setSpec(notificationSpec);
        notification.getSpec().setUnread(false);
        notification.getSpec().setLastReadAt(MessageRequestTest.NOW);
        store.put(notification);
        reconciler.reconcile(new Request("task"));
        verify(store.synchronous, never()).create(any(Notification.class));
        var saved = store.fetch(Notification.class, notification.getMetadata().getName()).orElseThrow();
        assertFalse(saved.getSpec().isUnread());
        assertEquals(MessageRequestTest.NOW, saved.getSpec().getLastReadAt());
    }

    @Test
    void uncertainNotificationCreateResultIsConfirmedFromStorage() {
        store.put(message(Phase.PENDING, null));
        doAnswer(invocation -> {
            store.put(notificationFor(invocation.getArgument(0, Reason.class)));
            return reactor.core.publisher.Mono.error(new IllegalStateException("模拟写入后连接中断"));
        }).when(notificationCenter).notify(any(Reason.class));
        assertFalse(reconciler.reconcile(new Request("task")).reEnqueue());
        var saved = store.fetch(SiteMessage.class, "task").orElseThrow();
        assertEquals(Phase.SENT, saved.getStatus().getPhase());
        assertEquals(List.of("alice"), saved.getStatus().getDelivered());
        verify(notificationCenter, times(1)).notify(any(Reason.class));
    }

    @Test
    void failuresArePersistedAndRetryOnlyCreatesUnsuccessfulRecipients() {
        var message = message(Phase.PENDING, null);
        message.getSpec().setRecipients(List.of("alice", "bob"));
        store.put(message);
        org.mockito.Mockito.reset(notificationCenter);
        when(notificationCenter.notify(any(Reason.class))).thenAnswer(invocation -> {
            var reason = invocation.getArgument(0, Reason.class);
            if (reason.getSpec().getSubject().getName().equals("bob")) {
                return reactor.core.publisher.Mono.error(new IllegalStateException("模拟存储失败"));
            }
            store.put(notificationFor(reason));
            return reactor.core.publisher.Mono.empty();
        });
        reconciler.reconcile(new Request("task"));
        var failed = store.fetch(SiteMessage.class, "task").orElseThrow();
        assertEquals(Phase.PARTIAL_FAILED, failed.getStatus().getPhase());
        assertEquals(List.of("alice"), failed.getStatus().getDelivered());
        assertTrue(failed.getStatus().getFailures().containsKey("bob"));
        service.retry("task").block();
        when(notificationCenter.notify(any(Reason.class))).thenAnswer(invocation -> {
            store.put(notificationFor(invocation.getArgument(0, Reason.class)));
            return reactor.core.publisher.Mono.empty();
        });
        reconciler.reconcile(new Request("task"));
        verify(notificationCenter, times(3)).notify(any(Reason.class));
        assertEquals(Phase.SENT, store.fetch(SiteMessage.class, "task").orElseThrow().getStatus().getPhase());
    }

    @Test
    void deletedOrDisabledUserFailsAndDoesNotChangeFrozenSnapshot() {
        store.put(message(Phase.PENDING, null));
        var disabled = RecipientServiceTest.user("alice");
        disabled.getSpec().setDisabled(true);
        when(store.synchronous.fetch(User.class, "alice")).thenReturn(Optional.of(disabled));
        reconciler.reconcile(new Request("task"));
        var result = store.fetch(SiteMessage.class, "task").orElseThrow();
        assertEquals(Phase.FAILED, result.getStatus().getPhase());
        assertEquals(List.of("alice"), result.getSpec().getRecipients());
        verify(store.synchronous, never()).create(any(Notification.class));
    }

    @Test
    void processesBoundedBatchAndResumesFromPersistedProgress() {
        var message = message(Phase.PENDING, null);
        message.getSpec().setRecipients(IntStream.range(0, 30).mapToObj(i -> "user-" + i).toList());
        store.put(message);
        assertTrue(reconciler.reconcile(new Request("task")).reEnqueue());
        assertEquals(25, store.fetch(SiteMessage.class, "task").orElseThrow().getStatus().getDelivered().size());
        var restarted = new MessageReconciler(store.synchronous, MessageRequestTest.CLOCK, notificationCenter);
        assertFalse(restarted.reconcile(new Request("task")).reEnqueue());
        assertEquals(30, store.fetch(SiteMessage.class, "task").orElseThrow().getStatus().getDelivered().size());
        verify(notificationCenter, times(30)).notify(any(Reason.class));
    }

    @Test
    void escapesHtmlAndMarksAnnouncement() {
        var message = message(Phase.PENDING, null);
        message.getSpec().setType(MessageType.ANNOUNCEMENT);
        message.getSpec().setContent("<script>alert(1)</script>\n&正文");
        message.getSpec().setSenderDisplayName("<管理员>");
        var reasonName = MessageReconciler.reasonName("task", "alice");
        var reason = MessageReconciler.reason(message, "alice", reasonName);
        assertEquals("<script>alert(1)</script>\n&正文", reason.getSpec().getAttributes().get("content"));
        assertEquals("【公告】通知", reason.getSpec().getAttributes().get("title"));
        assertEquals(reasonName, reason.getMetadata().getName());
    }

    private Notification notificationFor(Reason reason) {
        var notification = new Notification();
        var metadata = new Metadata();
        metadata.setName("notification-" + reason.getMetadata().getName());
        notification.setMetadata(metadata);
        var spec = new Notification.NotificationSpec();
        spec.setRecipient(reason.getSpec().getSubject().getName());
        spec.setReason(reason.getMetadata().getName());
        spec.setUnread(true);
        notification.setSpec(spec);
        return notification;
    }

    static SiteMessage message(Phase phase, Instant scheduledAt) {
        var message = new SiteMessage();
        var metadata = new Metadata();
        metadata.setName("task");
        metadata.setVersion(0L);
        metadata.setCreationTimestamp(MessageRequestTest.NOW);
        message.setMetadata(metadata);
        var spec = new SiteMessage.Spec();
        spec.setRequestId("request-1");
        spec.setTitle("通知");
        spec.setContent("正文");
        spec.setType(MessageType.MESSAGE);
        spec.setTarget(Target.USERS);
        spec.setUsernames(List.of("alice"));
        spec.setRecipients(List.of("alice"));
        spec.setSender("admin");
        spec.setSenderDisplayName("管理员");
        spec.setSendMode(scheduledAt == null ? SendMode.IMMEDIATE : SendMode.SCHEDULED);
        spec.setScheduledAt(scheduledAt);
        message.setSpec(spec);
        var status = new SiteMessage.Status();
        status.setPhase(phase);
        message.setStatus(status);
        return message;
    }
}
