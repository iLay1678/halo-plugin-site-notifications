package top.ilay.sitenotifications;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** 校验 HTTP 客户端实际连接的 DNS 结果，避免校验后再次解析导致 DNS rebinding。 */
final class WebhookAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
    private final AddressResolverGroup<?> delegate;

    WebhookAddressResolverGroup(AddressResolverGroup<?> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        var resolver = delegate.getResolver(executor);
        return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
            @Override
            protected boolean doIsResolved(InetSocketAddress address) {
                // 数字 IP 也必须经过校验，不能走已解析地址的快捷路径。
                return false;
            }

            @Override
            protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                var all = executor.<List<InetSocketAddress>>newPromise();
                doResolveAll(address, all);
                all.addListener(result -> {
                    if (result.isSuccess()) {
                        promise.trySuccess(all.getNow().getFirst());
                    } else {
                        promise.tryFailure(result.cause());
                    }
                });
            }

            @Override
            protected void doResolveAll(InetSocketAddress address,
                Promise<List<InetSocketAddress>> promise) {
                var resolved = resolver.resolveAll(address);
                resolved.addListener(result -> {
                    if (!result.isSuccess()) {
                        promise.tryFailure(result.cause());
                        return;
                    }
                    var safeAddresses = new ArrayList<InetSocketAddress>();
                    for (var item : resolved.getNow()) {
                        if (!(item instanceof InetSocketAddress target) || target.isUnresolved()
                            || WebhookUrlValidator.isUnsafeAddress(target.getAddress())) {
                            promise.tryFailure(new IllegalArgumentException("Webhook 地址不能指向本机或内网地址"));
                            return;
                        }
                        safeAddresses.add(target);
                    }
                    if (safeAddresses.isEmpty()) {
                        promise.tryFailure(new IllegalArgumentException("Webhook 地址无法解析"));
                    } else {
                        promise.trySuccess(safeAddresses);
                    }
                });
            }

            @Override
            public void close() {
                // 不关闭宿主共享的 DNS 解析器。
            }
        };
    }
}
