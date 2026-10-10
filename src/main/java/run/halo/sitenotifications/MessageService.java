package run.halo.sitenotifications;

import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.sitenotifications.SiteMessage.Phase;
import run.halo.sitenotifications.SiteMessage.SendMode;

@Service
@RequiredArgsConstructor
public class MessageService {
    private static final int MAX_UPDATE_ATTEMPTS = 4;
    private final ReactiveExtensionClient client;
    private final RecipientService recipients;
    private final Clock clock;

    public Mono<SiteMessage> create(MessageRequest input, User sender) {
        return Mono.defer(() -> {
            var request = input.normalized();
            request.validateStructure();
            var name = "sm-" + digest(sender.getMetadata().getName(), request.requestId());
            return client.fetch(SiteMessage.class, name)
                .flatMap(existing -> matching(existing, request))
                .switchIfEmpty(Mono.defer(() -> {
                    request.validate(clock);
                    return recipients.resolve(request.recipientRequest()).flatMap(names -> {
                        if (names.isEmpty()) {
                            return Mono.error(MessageRequest.badRequest("接收名单为空，请调整接收范围"));
                        }
                        var message = newMessage(name, request, names, sender);
                        return client.create(message)
                            .onErrorResume(error -> client.fetch(SiteMessage.class, name)
                                .flatMap(existing -> matching(existing, request))
                                .switchIfEmpty(Mono.error(error)));
                    });
                }));
        });
    }

    private static SiteMessage newMessage(String name, MessageRequest request, List<String> recipients, User sender) {
        var message = new SiteMessage();
        var metadata = new Metadata();
        metadata.setName(name);
        message.setMetadata(metadata);
        var spec = new SiteMessage.Spec();
        spec.setRequestId(request.requestId());
        spec.setTitle(request.title());
        spec.setContent(request.content());
        spec.setType(request.type());
        spec.setTarget(request.target());
        spec.setUsernames(request.usernames());
        spec.setRoles(request.roles());
        spec.setRecipients(recipients);
        spec.setSender(sender.getMetadata().getName());
        spec.setSenderDisplayName(RecipientService.displayName(sender));
        spec.setSendMode(request.sendMode());
        spec.setScheduledAt(request.scheduledAt());
        message.setSpec(spec);
        var status = new SiteMessage.Status();
        status.setPhase(request.sendMode() == SendMode.SCHEDULED ? Phase.SCHEDULED : Phase.PENDING);
        message.setStatus(status);
        return message;
    }

    private Mono<SiteMessage> matching(SiteMessage message, MessageRequest request) {
        return request.matches(message.getSpec()) ? Mono.just(message)
            : Mono.error(conflict("请求标识已被其他内容使用，请重新创建发送请求"));
    }

    public Mono<SiteMessage> get(String name) {
        return client.fetch(SiteMessage.class, name).filter(RecipientService::active)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "发送任务不存在")));
    }

    public Mono<SiteMessagePage> list(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            return Mono.error(MessageRequest.badRequest("页码必须大于 0，每页数量必须为 1–100"));
        }
        return client.listBy(SiteMessage.class, new ListOptions(),
                PageRequestImpl.of(page, size, Sort.by(Sort.Direction.DESC, "metadata.creationTimestamp")))
            .map(result -> new SiteMessagePage(result.getPage(), result.getSize(), result.getTotal(), result.getItems()));
    }

    public Mono<SiteMessage> cancel(String name, String operator) {
        return update(name, message -> {
            var status = message.getStatus();
            if (status.getPhase() == Phase.CANCELLED) {
                return;
            }
            if (message.getSpec().getSendMode() != SendMode.SCHEDULED || status.getPhase() != Phase.SCHEDULED) {
                throw conflict("任务已开始发送或不是定时任务，无法取消");
            }
            status.setPhase(Phase.CANCELLED);
            status.setCancelledAt(clock.instant());
            status.setCancelledBy(operator);
        }, MAX_UPDATE_ATTEMPTS, true);
    }

    public Mono<SiteMessage> retry(String name) {
        return update(name, message -> {
            var status = message.getStatus();
            if (status.getPhase() != Phase.FAILED && status.getPhase() != Phase.PARTIAL_FAILED) {
                throw conflict(status.getPhase() == Phase.CANCELLED ? "已取消的任务不能重试" : "只有失败的发送任务可以重试");
            }
            status.setPhase(Phase.PENDING);
            status.getFailures().clear();
            status.setCompletedAt(null);
            // Halo 不为仅 status 改动触发 watcher，重试需变更元数据以唤醒控制器。
            var annotations = message.getMetadata().getAnnotations();
            annotations = annotations == null ? new HashMap<>() : new HashMap<>(annotations);
            annotations.put("site-notifications.halo.run/retry-version", message.getMetadata().getVersion().toString());
            message.getMetadata().setAnnotations(annotations);
        }, MAX_UPDATE_ATTEMPTS, false);
    }

    private Mono<SiteMessage> update(String name, Consumer<SiteMessage> mutation, int attempts, boolean cancel) {
        return Mono.defer(() -> get(name).flatMap(message -> {
            if (cancel && message.getStatus().getPhase() == Phase.CANCELLED) {
                return Mono.just(message);
            }
            mutation.accept(message);
            return client.update(message);
        })).onErrorResume(OptimisticLockingFailureException.class, error -> attempts > 1
            ? update(name, mutation, attempts - 1, cancel)
            : Mono.error(conflict("任务正在更新，请刷新后重试")));
    }

    static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    static String digest(String first, String second) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((first + "\0" + second).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少 SHA-256", error);
        }
    }

    @Schema(name = "SiteMessagePage")
    public record SiteMessagePage(int page, int size, long total, List<SiteMessage> items) { }
}
