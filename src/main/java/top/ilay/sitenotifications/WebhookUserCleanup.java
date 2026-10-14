package top.ilay.sitenotifications;

import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.pf4j.Extension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.user.service.UserPreCreatingHandler;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/** 保持用户删除保护，直到 Webhook 配置被垃圾回收器实际删除。 */
@Component
@Extension
@RequiredArgsConstructor
public class WebhookUserCleanup implements Reconciler<Reconciler.Request>, UserPreCreatingHandler {
    static final String FINALIZER = "site-notifications.halo.run/webhook-cleanup";
    private static final Set<String> FINALIZERS = Set.of(FINALIZER);
    private final ExtensionClient client;
    private final WebhookConfigStore configStore;

    @Override
    public Mono<Void> preCreating(User user) {
        return configStore.clearLegacy(user.getMetadata().getName())
            .then(Mono.fromRunnable(() -> ExtensionUtil.addFinalizers(user.getMetadata(), FINALIZERS)));
    }

    @Override
    public Result reconcile(Request request) {
        var optional = client.fetch(User.class, request.name());
        if (optional.isEmpty()) return Result.doNotRetry();
        var user = optional.get();
        if (!ExtensionUtil.isDeleted(user)) {
            if (ExtensionUtil.addFinalizers(user.getMetadata(), FINALIZERS)) client.update(user);
            return Result.doNotRetry();
        }
        var config = client.fetch(ConfigMap.class, DefaultWebhookConfigStore.configMapName(user));
        if (config.isPresent()) {
            if (!ExtensionUtil.isDeleted(config.get())) client.delete(config.get());
            return Result.requeue(Duration.ofSeconds(1));
        }
        configStore.clearLegacy(request.name()).block(Duration.ofSeconds(30));
        if (ExtensionUtil.removeFinalizers(user.getMetadata(), FINALIZERS)) client.update(user);
        return Result.doNotRetry();
    }

    /** 停用时释放本插件保护，避免停止的控制器阻塞用户删除。 */
    void releaseFinalizers() {
        for (var name : client.listAllNames(User.class, new ListOptions(), Sort.by("metadata.name"))) {
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    client.fetch(User.class, name).ifPresent(user -> {
                        if (ExtensionUtil.removeFinalizers(user.getMetadata(), FINALIZERS)) client.update(user);
                    });
                    break;
                } catch (OptimisticLockingFailureException error) {
                    if (attempt == 3) throw error;
                }
            }
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new User()).workerCount(1).syncAllOnStart(true).build();
    }
}
