package top.ilay.sitenotifications;

import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.ExtensionClient;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;

/** 注册持久化发送任务模型；控制器由 Halo 的插件生命周期管理。 */
@Component
public class SiteNotificationsPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final ExtensionClient client;

    public SiteNotificationsPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        ExtensionClient client) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.client = client;
    }

    @Override
    public void start() {
        schemeManager.register(SiteMessage.class);
        NotificationResources.ensure(client);
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(SiteMessage.class));
    }
}
