package top.ilay.sitenotifications;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@RequiredArgsConstructor
public class SuperAdminGuard {
    private final ReactiveExtensionClient client;

    public Mono<User> currentAdmin() {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .filter(SuperAdminGuard::isSuperAdmin)
            .flatMap(authentication -> client.fetch(User.class, authentication.getName()))
            .filter(RecipientService::eligibleUser)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "仅超级管理员可以管理站内通知")));
    }

    static boolean isSuperAdmin(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_super-role".equals(authority.getAuthority()));
    }
}
