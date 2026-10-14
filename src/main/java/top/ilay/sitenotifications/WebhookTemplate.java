package top.ilay.sitenotifications;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import run.halo.app.notification.NotificationContext;

/** 在 JSON 字符串值中替换占位符，序列化时统一处理引号和换行。 */
final class WebhookTemplate {
    static final int MAX_BYTES = 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");
    private static final Set<String> FIELDS = Set.of("version", "title", "rawBody", "htmlBody",
        "recipient", "timestamp", "subject", "attributes");
    private static final Set<String> SUBJECT_FIELDS = Set.of("apiVersion", "kind", "name", "title", "url");

    private WebhookTemplate() {
    }

    static Mono<Void> validate(String template) {
        return Mono.fromRunnable(() -> {
            if (template != null && !template.isBlank()) parse(template);
        });
    }

    static String render(NotificationContext context, String template) {
        JsonNode values = MAPPER.valueToTree(WebhookPayload.from(context));
        if (template == null || template.isBlank()) return serialize(values);
        return serialize(replace(parse(template), values));
    }

    private static JsonNode parse(String template) {
        if (template.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Webhook 模板不能超过 64 KiB");
        }
        try {
            var root = MAPPER.readTree(template);
            if (root == null || !(root.isObject() || root.isArray())) {
                throw new IllegalArgumentException("Webhook 模板必须是 JSON 对象或数组");
            }
            validateNode(root);
            return root;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Webhook 模板必须是合法 JSON", error);
        }
    }

    private static void validateNode(JsonNode node) {
        if (node.isTextual()) {
            var matcher = PLACEHOLDER.matcher(node.textValue());
            while (matcher.find()) validateName(matcher.group(1));
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().contains("{{")) {
                    throw new IllegalArgumentException("占位符只能用于 JSON 值");
                }
                validateNode(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(WebhookTemplate::validateNode);
        }
    }

    private static void validateName(String name) {
        if (FIELDS.contains(name)) return;
        if (name.startsWith("subject.") && SUBJECT_FIELDS.contains(name.substring(8))) return;
        if (name.startsWith("attributes.") && name.substring(11).matches("[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)*")) return;
        throw new IllegalArgumentException("未知 Webhook 占位符：" + name);
    }

    private static JsonNode replace(JsonNode node, JsonNode values) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                ((ObjectNode) node).set(entry.getKey(), replace(entry.getValue(), values)));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) ((ArrayNode) node).set(i, replace(node.get(i), values));
        } else if (node.isTextual()) {
            var matcher = PLACEHOLDER.matcher(node.textValue());
            if (matcher.matches()) return lookup(values, matcher.group(1)).deepCopy();
            var buffer = new StringBuilder();
            while (matcher.find()) {
                var value = lookup(values, matcher.group(1));
                var text = value.isNull() ? "" : value.isTextual() ? value.textValue() : value.toString();
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(text));
            }
            matcher.appendTail(buffer);
            return MAPPER.getNodeFactory().textNode(buffer.toString());
        }
        return node;
    }

    private static JsonNode lookup(JsonNode values, String name) {
        var current = values;
        for (var part : name.split("\\.")) {
            current = current.path(part);
            if (current.isMissingNode() || current.isNull()) return NullNode.instance;
        }
        return current;
    }

    private static String serialize(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Webhook 请求正文无法生成 JSON", error);
        }
    }
}
