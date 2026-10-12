package top.ilay.sitenotifications;

import tools.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.ilay.sitenotifications.SiteMessage.MessageType;
import top.ilay.sitenotifications.SiteMessage.SendMode;
import top.ilay.sitenotifications.SiteMessage.Target;

@Schema(name = "MessageRequest")
public record MessageRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100) String requestId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200) String title,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 20000) String content,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MessageType type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Target target,
    List<String> usernames,
    List<String> roles,
    @Schema(defaultValue = "IMMEDIATE") SendMode sendMode,
    @JsonDeserialize(using = ScheduledTimeDeserializer.class) Instant scheduledAt
) {
    public MessageRequest normalized() {
        return new MessageRequest(trim(requestId), trim(title), content, type, target,
            RecipientRequest.normalizeNames(usernames), RecipientRequest.normalizeNames(roles),
            sendMode == null ? SendMode.IMMEDIATE : sendMode, scheduledAt);
    }

    public void validateStructure() {
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
            throw badRequest("请求标识不能为空且不能超过 100 个字符");
        }
        if (title == null || title.isBlank() || title.length() > 200) {
            throw badRequest("标题必须为 1–200 个字符");
        }
        if (content == null || content.isBlank() || content.length() > 20000) {
            throw badRequest("正文必须为 1–20000 个字符");
        }
        if (type == null) {
            throw badRequest("请选择消息类型");
        }
        recipientRequest().validate();
        if (sendMode == SendMode.SCHEDULED && scheduledAt == null) {
            throw badRequest("指定时间发送必须提供含时区的发送时间");
        }
        if (sendMode != SendMode.SCHEDULED && scheduledAt != null) {
            throw badRequest("立即发送不能携带计划发送时间");
        }
    }

    public void validate(Clock clock) {
        validateStructure();
        if (sendMode == SendMode.SCHEDULED && !scheduledAt.isAfter(clock.instant())) {
            throw badRequest("计划发送时间必须晚于当前服务器时间");
        }
    }

    public RecipientRequest recipientRequest() {
        return new RecipientRequest(target, usernames, roles);
    }

    public boolean matches(SiteMessage.Spec spec) {
        return Objects.equals(requestId, spec.getRequestId())
            && Objects.equals(title, spec.getTitle()) && Objects.equals(content, spec.getContent())
            && type == spec.getType() && target == spec.getTarget()
            && Objects.equals(usernames, spec.getUsernames()) && Objects.equals(roles, spec.getRoles())
            && sendMode == spec.getSendMode() && Objects.equals(scheduledAt, spec.getScheduledAt());
    }

    static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
