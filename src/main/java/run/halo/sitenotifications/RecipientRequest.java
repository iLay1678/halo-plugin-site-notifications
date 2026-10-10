package run.halo.sitenotifications;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import run.halo.sitenotifications.SiteMessage.Target;

@Schema(name = "RecipientRequest")
public record RecipientRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Target target,
    List<String> usernames,
    List<String> roles
) {
    public RecipientRequest normalized() {
        return new RecipientRequest(target, normalizeNames(usernames), normalizeNames(roles));
    }

    public void validate() {
        if (target == null) {
            throw MessageRequest.badRequest("请选择接收范围");
        }
        if (target == Target.USERS && usernames.isEmpty()) {
            throw MessageRequest.badRequest("请至少选择一个用户");
        }
        if (target == Target.ROLES && roles.isEmpty()) {
            throw MessageRequest.badRequest("请至少选择一个角色");
        }
        if (target != Target.USERS && !usernames.isEmpty()
            || target != Target.ROLES && !roles.isEmpty()) {
            throw MessageRequest.badRequest("接收对象与所选范围不匹配");
        }
    }

    static List<String> normalizeNames(List<String> names) {
        if (names == null) {
            return List.of();
        }
        if (names.size() > 10000 || names.stream().anyMatch(
            name -> name == null || name.isBlank() || name.length() > 253)) {
            throw MessageRequest.badRequest("接收对象名称无效或数量超过 10000");
        }
        return names.stream().map(String::trim).distinct().sorted().toList();
    }
}
