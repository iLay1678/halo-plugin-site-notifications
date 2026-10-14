package top.ilay.sitenotifications;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 校验用户配置的出站地址。 */
final class WebhookUrlValidator {
    private WebhookUrlValidator() {
    }

    static Mono<URI> validate(String value) {
        if (value == null || value.isBlank()) {
            return Mono.error(new IllegalArgumentException("Webhook 地址不能为空"));
        }
        if (value.length() > 4096) {
            return Mono.error(new IllegalArgumentException("Webhook 地址不能超过 4096 字符"));
        }
        final URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException error) {
            return Mono.error(new IllegalArgumentException("Webhook 地址格式不正确", error));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getFragment() != null
            || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return Mono.error(new IllegalArgumentException("Webhook 地址必须使用 HTTPS 和标准端口"));
        }
        return Mono.fromCallable(() -> {
                var addresses = InetAddress.getAllByName(uri.getHost());
                if (Arrays.stream(addresses).anyMatch(WebhookUrlValidator::isUnsafeAddress)) {
                    throw new IllegalArgumentException("Webhook 地址不能指向本机或内网地址");
                }
                return uri;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(error -> error instanceof IllegalArgumentException
                ? error : new IllegalArgumentException("Webhook 地址无法解析", error));
    }

    static boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        var host = address.getHostAddress().toLowerCase(Locale.ROOT);
        if (host.equals("169.254.169.254") || host.equals("100.100.100.200")) {
            return true;
        }
        if (address instanceof Inet6Address ipv6) {
            var first = ipv6.getAddress()[0] & 0xff;
            var second = ipv6.getAddress()[1] & 0xff;
            // 只接收全球单播地址，同时排除 6to4 和 Teredo 隧道地址。
            return (first & 0xe0) != 0x20 || (first == 0x20 && second == 0x02)
                || (first == 0x20 && second == 0x01 && ipv6.getAddress()[2] == 0
                    && ipv6.getAddress()[3] == 0);
        }
        var bytes = address.getAddress();
        var first = bytes[0] & 0xff;
        var second = bytes[1] & 0xff;
        return first == 0 || first >= 240 || (first == 100 && second >= 64 && second <= 127)
            || (first == 198 && (second == 18 || second == 19));
    }
}
