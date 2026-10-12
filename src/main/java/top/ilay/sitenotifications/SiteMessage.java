package top.ilay.sitenotifications;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "site-notifications.halo.run", version = "v1alpha1", kind = "SiteMessage",
    plural = "sitemessages", singular = "sitemessage")
public class SiteMessage extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Status status;

    public enum MessageType { ANNOUNCEMENT, MESSAGE }
    public enum Target { ALL, USERS, ROLES }
    public enum SendMode { IMMEDIATE, SCHEDULED }
    public enum Phase { PENDING, SCHEDULED, SENDING, SENT, PARTIAL_FAILED, FAILED, CANCELLED }

    @Data
    @Schema(name = "SiteMessageSpec")
    public static class Spec {
        private String requestId;
        @Schema(minLength = 1, maxLength = 200)
        private String title;
        @Schema(minLength = 1, maxLength = 20000)
        private String content;
        private MessageType type;
        private Target target;
        private List<String> usernames = new ArrayList<>();
        private List<String> roles = new ArrayList<>();
        private List<String> recipients = new ArrayList<>();
        private String sender;
        private String senderDisplayName;
        private SendMode sendMode;
        private Instant scheduledAt;
    }

    @Data
    @Schema(name = "SiteMessageStatus")
    public static class Status {
        private Phase phase;
        private List<String> delivered = new ArrayList<>();
        private Map<String, String> failures = new LinkedHashMap<>();
        private Instant startedAt;
        private Instant completedAt;
        private Instant cancelledAt;
        private String cancelledBy;
    }
}
