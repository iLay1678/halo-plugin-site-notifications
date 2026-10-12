package top.ilay.sitenotifications;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationConfig {
    @Bean
    public Clock siteNotificationClock() {
        return Clock.systemUTC();
    }
}
