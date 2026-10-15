package top.ilay.sitenotifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Webhook 配置和历史数据使用的 JSON 编解码器。 */
final class WebhookJson {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private WebhookJson() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Webhook 配置无法生成 JSON", error);
        }
    }

    static <T> T read(String value, TypeReference<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Webhook 配置 JSON 格式无效", error);
        }
    }
}
