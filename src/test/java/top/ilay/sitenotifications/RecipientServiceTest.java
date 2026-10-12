package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Role;
import run.halo.app.core.extension.RoleBinding;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.ilay.sitenotifications.SiteMessage.Target;

class RecipientServiceTest {
    ReactiveExtensionClient client;
    RecipientService service;
    List<User> users;
    List<Role> roles;
    List<RoleBinding> bindings;

    @BeforeEach
    void setup() {
        client = mock(ReactiveExtensionClient.class);
        service = new RecipientService(client);
        var disabled = user("disabled");
        disabled.getSpec().setDisabled(true);
        var hidden = user("hidden");
        hidden.getMetadata().setLabels(Map.of(User.HIDDEN_USER_LABEL, "true"));
        var deleted = user("deleted");
        deleted.getMetadata().setDeletionTimestamp(MessageRequestTest.NOW);
        users = List.of(user("alice"), user("bob"), disabled, hidden, deleted);
        var template = role("template");
        template.getMetadata().setLabels(Map.of(Role.TEMPLATE_LABEL_NAME, "true"));
        roles = List.of(role("editor"), role("reader"), role("anonymous"), template);
        var wrongGroup = RoleBinding.create("hidden", "editor");
        wrongGroup.getRoleRef().setApiGroup("other.group");
        var wrongSubject = RoleBinding.create("bob", "reader");
        wrongSubject.getSubjects().getFirst().setApiGroup("other.group");
        bindings = List.of(RoleBinding.create("alice", "editor"), RoleBinding.create("alice", "reader"),
            RoleBinding.create("bob", "editor"), RoleBinding.create("disabled", "editor"), wrongGroup, wrongSubject);
        when(client.list(eq(User.class), any(), any())).thenAnswer(invocation ->
            Flux.fromIterable(users).filter(invocation.getArgument(1, Predicate.class)));
        when(client.list(eq(RoleBinding.class), any(), isNull())).thenAnswer(invocation ->
            Flux.fromIterable(bindings).filter(invocation.getArgument(1, Predicate.class)));
        when(client.fetch(eq(User.class), anyString())).thenAnswer(invocation ->
            Mono.justOrEmpty(users.stream().filter(user -> user.getMetadata().getName().equals(invocation.getArgument(1))).findFirst()));
        when(client.fetch(eq(Role.class), anyString())).thenAnswer(invocation ->
            Mono.justOrEmpty(roles.stream().filter(role -> role.getMetadata().getName().equals(invocation.getArgument(1))).findFirst()));
    }

    @Test
    void allExcludesDisabledHiddenDeletedUsers() {
        assertEquals(List.of("alice", "bob"), service.resolve(new RecipientRequest(Target.ALL, null, null)).block());
    }

    @Test
    void explicitUsersAreValidatedAndDeduplicated() {
        assertEquals(List.of("alice", "bob"), service.resolve(new RecipientRequest(Target.USERS, List.of("bob", "alice", "alice"), null)).block());
        for (var name : List.of("disabled", "hidden", "deleted", "missing")) {
            assertThrows(ResponseStatusException.class,
                () -> service.resolve(new RecipientRequest(Target.USERS, List.of(name), null)).block());
        }
    }

    @Test
    void roleMembersUseDirectMatchingReferencesAndDeduplicate() {
        assertEquals(List.of("alice", "bob"), service.resolve(new RecipientRequest(Target.ROLES, null, List.of("editor", "reader"))).block());
        assertEquals(List.of("alice"), service.resolve(new RecipientRequest(Target.ROLES, null, List.of("reader"))).block());
        for (var name : List.of("anonymous", "template", "missing")) {
            assertThrows(ResponseStatusException.class,
                () -> service.resolve(new RecipientRequest(Target.ROLES, null, List.of(name))).block());
        }
    }

    static User user(String name) {
        var user = new User();
        var metadata = new Metadata();
        metadata.setName(name);
        user.setMetadata(metadata);
        user.getSpec().setDisplayName(name);
        return user;
    }

    static Role role(String name) {
        var role = new Role();
        var metadata = new Metadata();
        metadata.setName(name);
        role.setMetadata(metadata);
        return role;
    }
}
