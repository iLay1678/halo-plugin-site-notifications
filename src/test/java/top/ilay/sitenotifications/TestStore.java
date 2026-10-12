package top.ilay.sitenotifications;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ReactiveExtensionClient;

/** 模拟 Halo 的分离快照和资源版本更新，避免共享对象掩盖并发缺陷。 */
class TestStore {
    final ReactiveExtensionClient reactive = mock(ReactiveExtensionClient.class);
    final ExtensionClient synchronous = mock(ExtensionClient.class);
    final Map<String, Extension> records = new HashMap<>();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Consumer<SiteMessage> beforeSyncUpdate = message -> { };
    Consumer<SiteMessage> beforeReactiveUpdate = message -> { };

    TestStore() {
        when(reactive.fetch(eq(SiteMessage.class), anyString()))
            .thenAnswer(invocation -> Mono.defer(() -> Mono.justOrEmpty(fetch(SiteMessage.class, invocation.getArgument(1)))));
        when(reactive.create(any(SiteMessage.class))).thenAnswer(invocation -> Mono.fromCallable(() -> {
            var input = invocation.getArgument(0, SiteMessage.class);
            input.getMetadata().setVersion(0L);
            input.getMetadata().setCreationTimestamp(MessageRequestTest.NOW);
            put(input);
            return copy(input);
        }));
        when(reactive.update(any(SiteMessage.class))).thenAnswer(invocation -> Mono.fromCallable(() -> {
            var input = invocation.getArgument(0, SiteMessage.class);
            beforeReactiveUpdate.accept(input);
            return save(input);
        }));
        when(synchronous.fetch(eq(SiteMessage.class), anyString()))
            .thenAnswer(invocation -> fetch(SiteMessage.class, invocation.getArgument(1)));
        doAnswer(invocation -> {
            var input = invocation.getArgument(0, SiteMessage.class);
            beforeSyncUpdate.accept(input);
            save(input);
            return null;
        }).when(synchronous).update(any(SiteMessage.class));
    }

    synchronized void put(Extension extension) {
        records.put(key(extension.getClass(), extension.getMetadata().getName()), copy(extension));
    }

    synchronized SiteMessage save(SiteMessage input) {
        var current = fetch(SiteMessage.class, input.getMetadata().getName()).orElseThrow();
        if (!current.getMetadata().getVersion().equals(input.getMetadata().getVersion())) {
            throw new OptimisticLockingFailureException("版本冲突");
        }
        var saved = copy(input);
        saved.getMetadata().setVersion(current.getMetadata().getVersion() + 1);
        put(saved);
        return saved;
    }

    synchronized <E extends Extension> Optional<E> fetch(Class<E> type, String name) {
        var value = records.get(key(type, name));
        return value == null ? Optional.empty() : Optional.of(type.cast(copy(value)));
    }

    <E extends Extension> E copy(E input) {
        return (E) mapper.convertValue(input, input.getClass());
    }

    private String key(Class<?> type, String name) {
        return type.getName() + "/" + name;
    }
}
