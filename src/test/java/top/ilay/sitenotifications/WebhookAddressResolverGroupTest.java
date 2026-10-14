package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookAddressResolverGroupTest {
    @Test
    void checksResolvedLiteralAddressesBeforeConnecting() throws Exception {
        var group = new WebhookAddressResolverGroup(NoopAddressResolverGroup.INSTANCE);
        try {
            var resolver = group.getResolver(ImmediateEventExecutor.INSTANCE);
            var blocked = resolver.resolve(new InetSocketAddress("127.0.0.1", 443)).await();
            assertInstanceOf(IllegalArgumentException.class, blocked.cause());
            var publicAddress = new InetSocketAddress("8.8.8.8", 443);
            var accepted = resolver.resolve(publicAddress).await();
            assertTrue(accepted.isSuccess());
            assertEquals(publicAddress, accepted.getNow());
        } finally {
            group.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAHostWhenAnyActualDnsResultIsPrivate() throws Exception {
        AddressResolverGroup<InetSocketAddress> delegate = mock(AddressResolverGroup.class);
        AddressResolver<InetSocketAddress> resolver = mock(AddressResolver.class);
        var executor = ImmediateEventExecutor.INSTANCE;
        when(delegate.getResolver(executor)).thenReturn(resolver);
        when(resolver.resolveAll(any())).thenReturn(executor.newSucceededFuture(List.of(
            new InetSocketAddress("8.8.8.8", 443), new InetSocketAddress("10.0.0.1", 443))));
        var group = new WebhookAddressResolverGroup(delegate);
        try {
            var result = group.getResolver(executor)
                .resolveAll(InetSocketAddress.createUnresolved("changed.example", 443)).await();
            assertInstanceOf(IllegalArgumentException.class, result.cause());
        } finally {
            group.close();
        }
    }
}
