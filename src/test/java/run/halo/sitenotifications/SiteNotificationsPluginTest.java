package run.halo.sitenotifications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.plugin.PluginContext;
import run.halo.app.extension.SchemeManager;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteNotificationsPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @Mock
    ExtensionClient extensionClient;

    @InjectMocks
    SiteNotificationsPlugin plugin;

    @Test
    void contextLoads() {
        when(extensionClient.fetch(eq(ReasonType.class), eq(NotificationResources.REASON_TYPE)))
            .thenReturn(Optional.empty());
        when(extensionClient.fetch(eq(NotificationTemplate.class),
            eq(NotificationResources.REASON_TYPE + "-default"))).thenReturn(Optional.empty());
        plugin.start();
        plugin.stop();
        verify(schemeManager).register(SiteMessage.class);
        verify(schemeManager).unregister(any(Scheme.class));
    }
}
