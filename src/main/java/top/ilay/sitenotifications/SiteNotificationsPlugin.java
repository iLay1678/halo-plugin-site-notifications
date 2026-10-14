package top.ilay.sitenotifications;

import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.ExtensionClient;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/** 注册持久化发送任务模型；控制器由 Halo 的插件生命周期管理。 */
@Component
@Slf4j
public class SiteNotificationsPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final ExtensionClient client;
    private final WebhookConfigStore webhookConfigStore;
    private final WebhookUserCleanup webhookUserCleanup;

    public SiteNotificationsPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        ExtensionClient client, WebhookConfigStore webhookConfigStore, WebhookUserCleanup webhookUserCleanup) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.client = client;
        this.webhookConfigStore = webhookConfigStore;
        this.webhookUserCleanup = webhookUserCleanup;
    }

    @Override
    public void start() {
        schemeManager.register(SiteMessage.class);
        NotificationResources.ensure(client);
        try {
            webhookConfigStore.migrateLegacy().block(java.time.Duration.ofMinutes(2));
        } catch (Exception error) {
            log.warn("迁移历史 Webhook 配置失败，保留未完成数据，下次启动继续尝试 ({})",
                error.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        webhookUserCleanup.releaseFinalizers();
        schemeManager.unregister(Scheme.buildFromType(SiteMessage.class));
    }
}
