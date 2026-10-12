package top.ilay.sitenotifications;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.Notification;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.ReasonAttributes;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import top.ilay.sitenotifications.SiteMessage.Phase;
import top.ilay.sitenotifications.SiteMessage.SendMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageReconciler implements Reconciler<Reconciler.Request> {
    private static final int BATCH_SIZE = 25;
    private static final String SUBJECT_API_VERSION = "v1alpha1";
    private static final String SUBJECT_KIND = "SiteMessageRecipient";
    private final ExtensionClient client;
    private final Clock clock;
    private final NotificationCenter notificationCenter;

    @Override
    public Result reconcile(Request request) {
        var optional = client.fetch(SiteMessage.class, request.name());
        if (optional.isEmpty() || !RecipientService.active(optional.get())) {
            return Result.doNotRetry();
        }
        var message = optional.get();
        var status = message.getStatus();
        if (status.getPhase() != Phase.PENDING && status.getPhase() != Phase.SCHEDULED
            && status.getPhase() != Phase.SENDING) {
            return Result.doNotRetry();
        }
        if (message.getSpec().getSendMode() == SendMode.SCHEDULED) {
            var delay = Duration.between(clock.instant(), message.getSpec().getScheduledAt());
            if (!delay.isNegative() && !delay.isZero()) {
                return Result.requeue(delay.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : delay);
            }
        }
        try {
            // 必须先持久化 SENDING；与取消操作竞争同一资源版本。
            if (status.getPhase() != Phase.SENDING) {
                status.setPhase(Phase.SENDING);
                if (status.getStartedAt() == null) {
                    status.setStartedAt(clock.instant());
                }
                client.update(message);
                refreshVersion(message);
            }
            var processed = new HashSet<>(status.getDelivered());
            processed.addAll(status.getFailures().keySet());
            var remaining = message.getSpec().getRecipients().stream().filter(name -> !processed.contains(name)).toList();
            for (var recipient : remaining.stream().limit(BATCH_SIZE).toList()) {
                deliver(message, recipient);
                // 每个结果立即保存；重启后的未保存 Notification 用稳定名称去重。
                client.update(message);
                refreshVersion(message);
            }
            if (remaining.size() > BATCH_SIZE) {
                return Result.requeue(Duration.ofMillis(100));
            }
            status.setPhase(completedPhase(status));
            status.setCompletedAt(clock.instant());
            client.update(message);
            return Result.doNotRetry();
        } catch (OptimisticLockingFailureException error) {
            return Result.requeue(Duration.ofMillis(200));
        }
    }

    private static Phase completedPhase(SiteMessage.Status status) {
        if (status.getFailures().isEmpty()) {
            return Phase.SENT;
        }
        return status.getDelivered().isEmpty() ? Phase.FAILED : Phase.PARTIAL_FAILED;
    }

    private void refreshVersion(SiteMessage message) {
        var latest = client.fetch(SiteMessage.class, message.getMetadata().getName())
            .orElseThrow(() -> new IllegalStateException("发送任务已被移除"));
        message.setMetadata(latest.getMetadata());
    }

    private void deliver(SiteMessage message, String recipient) {
        var reasonName = reasonName(message.getMetadata().getName(), recipient);
        try {
            if (notificationExists(reasonName, recipient)) {
                delivered(message, recipient);
                return;
            }
            var user = client.fetch(User.class, recipient);
            if (user.isEmpty() || !RecipientService.eligibleUser(user.get())) {
                message.getStatus().getFailures().put(recipient, "用户已删除、禁用或不可接收消息");
                return;
            }
            ensureSubscription(recipient);
            notificationCenter.notify(reason(message, recipient, reasonName)).block(Duration.ofSeconds(30));
            delivered(message, recipient);
        } catch (RuntimeException error) {
            // 通知中心可能已经创建收件箱消息后才返回异常，先用稳定 reason 检查结果。
            if (notificationExists(reasonName, recipient)) {
                delivered(message, recipient);
                return;
            }
            log.warn("站内通知投递失败，任务 {}，接收人 {}", message.getMetadata().getName(), recipient, error);
            message.getStatus().getFailures().put(recipient, "通知投递失败，请重试");
        }
    }

    private boolean notificationExists(String reasonName, String recipient) {
        return client.list(Notification.class,
                notification -> notification.getSpec() != null
                    && reasonName.equals(notification.getSpec().getReason())
                    && recipient.equals(notification.getSpec().getRecipient()),
                Comparator.comparing(notification -> notification.getMetadata().getName()))
            .stream().findAny().isPresent();
    }

    private void ensureSubscription(String recipient) {
        var name = "sn-sub-" + MessageService.digest(NotificationResources.REASON_TYPE, recipient);
        if (client.fetch(Subscription.class, name).isPresent()) {
            return;
        }
        var subscription = new Subscription();
        var metadata = new Metadata();
        metadata.setName(name);
        subscription.setMetadata(metadata);
        var spec = new Subscription.Spec();
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(recipient);
        spec.setSubscriber(subscriber);
        spec.setUnsubscribeToken(Subscription.generateUnsubscribeToken());
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(NotificationResources.REASON_TYPE);
        var subject = new Subscription.ReasonSubject();
        subject.setApiVersion(SUBJECT_API_VERSION);
        subject.setKind(SUBJECT_KIND);
        subject.setName(recipient);
        interestReason.setSubject(subject);
        spec.setReason(interestReason);
        subscription.setSpec(spec);
        client.create(subscription);
    }

    static Reason reason(SiteMessage message, String recipient, String reasonName) {
        var reason = new Reason();
        var metadata = new Metadata();
        metadata.setName(reasonName);
        metadata.setCreationTimestamp(message.getMetadata().getCreationTimestamp());
        reason.setMetadata(metadata);
        var spec = new Reason.Spec();
        spec.setReasonType(NotificationResources.REASON_TYPE);
        spec.setAuthor(message.getSpec().getSender());
        var subject = Reason.Subject.builder()
            .apiVersion(SUBJECT_API_VERSION)
            .kind(SUBJECT_KIND)
            .name(recipient)
            .title(message.getSpec().getTitle())
            .build();
        spec.setSubject(subject);
        var attributes = new ReasonAttributes();
        attributes.put("title", message.getSpec().getType() == SiteMessage.MessageType.ANNOUNCEMENT
            ? "【公告】" + message.getSpec().getTitle() : message.getSpec().getTitle());
        attributes.put("content", message.getSpec().getContent());
        spec.setAttributes(attributes);
        reason.setSpec(spec);
        return reason;
    }

    private static void delivered(SiteMessage message, String recipient) {
        if (!message.getStatus().getDelivered().contains(recipient)) {
            message.getStatus().getDelivered().add(recipient);
        }
        message.getStatus().getFailures().remove(recipient);
    }

    static String reasonName(String messageName, String recipient) {
        return "smn-" + MessageService.digest(messageName, recipient);
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new SiteMessage()).workerCount(1).syncAllOnStart(true).build();
    }
}
