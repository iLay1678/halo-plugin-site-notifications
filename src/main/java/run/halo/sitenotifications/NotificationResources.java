package run.halo.sitenotifications;

import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;

/** 注册 Halo 通知中心使用的事件类型和正文模板。 */
final class NotificationResources {
    static final String REASON_TYPE = "site-notifications-message";

    private NotificationResources() {
    }

    static void ensure(ExtensionClient client) {
        if (client.fetch(ReasonType.class, REASON_TYPE).isEmpty()) {
            client.create(reasonType());
        }
        if (client.fetch(NotificationTemplate.class, REASON_TYPE + "-default").isEmpty()) {
            client.create(template());
        }
    }

    private static ReasonType reasonType() {
        var reasonType = new ReasonType();
        var metadata = new Metadata();
        metadata.setName(REASON_TYPE);
        reasonType.setMetadata(metadata);
        var spec = new ReasonType.Spec();
        spec.setDisplayName("站内通知");
        spec.setDescription("管理员发送的站内公告和普通消息");
        reasonType.setSpec(spec);
        return reasonType;
    }

    private static NotificationTemplate template() {
        var template = new NotificationTemplate();
        var metadata = new Metadata();
        metadata.setName(REASON_TYPE + "-default");
        template.setMetadata(metadata);
        var spec = new NotificationTemplate.Spec();
        var selector = new NotificationTemplate.ReasonSelector();
        selector.setReasonType(REASON_TYPE);
        selector.setLanguage("default");
        spec.setReasonSelector(selector);
        var content = new NotificationTemplate.Template();
        content.setTitle("[(${title})]");
        content.setRawBody("[(${content})]");
        content.setHtmlBody("<div style=\"white-space: pre-wrap\" th:text=\"${content}\"></div>");
        spec.setTemplate(content);
        template.setSpec(spec);
        return template;
    }
}
