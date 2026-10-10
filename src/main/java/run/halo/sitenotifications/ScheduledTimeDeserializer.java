package run.halo.sitenotifications;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DatabindException;

/** 仅请求时间字段使用严格 ISO 偏移时间，禁止 Jackson 把数字解释成 Unix 时间戳。 */
public class ScheduledTimeDeserializer extends ValueDeserializer<Instant> {
    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            throw DatabindException.from(parser, "计划发送时间必须是带时区偏移的 ISO 日期时间字符串");
        }
        try {
            return OffsetDateTime.parse(parser.getString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException error) {
            throw DatabindException.from(parser, "计划发送时间必须是带时区偏移的 ISO 日期时间字符串", error);
        }
    }
}
