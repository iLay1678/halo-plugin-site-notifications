package run.halo.sitenotifications;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Role;
import run.halo.app.core.extension.RoleBinding;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
@RequiredArgsConstructor
public class RecipientService {
    private final ReactiveExtensionClient client;

    public Mono<List<String>> resolve(RecipientRequest input) {
        var request = input.normalized();
        request.validate();
        return switch (request.target()) {
            case ALL -> client.list(User.class, RecipientService::eligibleUser, null)
                .map(user -> user.getMetadata().getName()).sort().collectList();
            case USERS -> Flux.fromIterable(request.usernames())
                .concatMap(name -> client.fetch(User.class, name)
                    .filter(RecipientService::eligibleUser)
                    .switchIfEmpty(Mono.error(MessageRequest.badRequest("用户不存在或不可接收消息：" + name)))
                    .map(user -> user.getMetadata().getName()))
                .collectList();
            case ROLES -> resolveRoles(request.roles());
        };
    }

    private Mono<List<String>> resolveRoles(List<String> roles) {
        return Flux.fromIterable(roles)
            .concatMap(name -> client.fetch(Role.class, name)
                .filter(RecipientService::selectableRole)
                .switchIfEmpty(Mono.error(MessageRequest.badRequest("角色不存在或不可选择：" + name))))
            .thenMany(client.list(RoleBinding.class,
                binding -> active(binding) && binding.getRoleRef() != null
                    && Role.KIND.equals(binding.getRoleRef().getKind())
                    && Role.GROUP.equals(binding.getRoleRef().getApiGroup())
                    && roles.contains(binding.getRoleRef().getName()), null))
            .flatMapIterable(binding -> binding.getSubjects() == null ? List.of() : binding.getSubjects())
            .filter(subject -> User.KIND.equals(subject.getKind()) && User.GROUP.equals(subject.getApiGroup()))
            .map(RoleBinding.Subject::getName).distinct()
            .concatMap(name -> client.fetch(User.class, name).filter(RecipientService::eligibleUser))
            .map(user -> user.getMetadata().getName()).sort().collectList();
    }

    public Mono<UserCandidateList> users(String keyword) {
        return client.list(User.class,
                user -> eligibleUser(user) && matches(keyword, user.getMetadata().getName(), displayName(user)),
                Comparator.comparing(user -> user.getMetadata().getName()))
            .take(100).map(user -> new UserCandidate(user.getMetadata().getName(), displayName(user)))
            .collectList().map(UserCandidateList::new);
    }

    public Mono<RoleCandidateList> roles(String keyword) {
        return client.list(Role.class,
                role -> selectableRole(role) && matches(keyword, role.getMetadata().getName(), roleDisplayName(role)),
                Comparator.comparing(role -> role.getMetadata().getName()))
            .take(100).map(role -> new RoleCandidate(role.getMetadata().getName(), roleDisplayName(role)))
            .collectList().map(RoleCandidateList::new);
    }

    static boolean eligibleUser(User user) {
        return active(user) && user.getSpec() != null && !Boolean.TRUE.equals(user.getSpec().getDisabled())
            && !"true".equals(labels(user).get(User.HIDDEN_USER_LABEL));
    }

    static boolean selectableRole(Role role) {
        return active(role) && !Set.of("anonymous", "authenticated").contains(role.getMetadata().getName())
            && !"true".equals(labels(role).get(Role.TEMPLATE_LABEL_NAME))
            && !"true".equals(labels(role).get(Role.HIDDEN_LABEL_NAME));
    }

    static boolean active(Extension extension) {
        return extension != null && extension.getMetadata() != null
            && extension.getMetadata().getDeletionTimestamp() == null;
    }

    static String displayName(User user) {
        var name = user.getSpec().getDisplayName();
        return name == null || name.isBlank() ? user.getMetadata().getName() : name;
    }

    private static Map<String, String> labels(Extension extension) {
        var labels = extension.getMetadata().getLabels();
        return labels == null ? Map.of() : labels;
    }

    private static String roleDisplayName(Role role) {
        var annotations = role.getMetadata().getAnnotations();
        return annotations == null ? role.getMetadata().getName()
            : annotations.getOrDefault("rbac.authorization.halo.run/display-name", role.getMetadata().getName());
    }

    private static boolean matches(String keyword, String name, String displayName) {
        var query = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT).trim();
        return name.toLowerCase(Locale.ROOT).contains(query) || displayName.toLowerCase(Locale.ROOT).contains(query);
    }

    @Schema(name = "UserCandidate")
    public record UserCandidate(String username, String displayName) { }
    @Schema(name = "RoleCandidate")
    public record RoleCandidate(String name, String displayName) { }
    @Schema(name = "UserCandidateList")
    public record UserCandidateList(List<UserCandidate> items) { }
    @Schema(name = "RoleCandidateList")
    public record RoleCandidateList(List<RoleCandidate> items) { }
    @Schema(name = "RecipientPreview")
    public record RecipientPreview(int total) { }
}
