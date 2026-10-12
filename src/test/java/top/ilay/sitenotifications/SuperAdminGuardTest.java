package top.ilay.sitenotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ReactiveExtensionClient;

class SuperAdminGuardTest {
    @Test
    void normalAndAnonymousUsersCannotManageMessages() {
        var guard = new SuperAdminGuard(mock(ReactiveExtensionClient.class));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> guard.currentAdmin().block()).getStatusCode());
        var authentication = new UsernamePasswordAuthenticationToken("alice", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_authenticated")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> guard.currentAdmin().contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block()).getStatusCode());
    }

    @Test
    void superRoleUsesCurrentAuthenticatedUserAndRejectsDisabledAdmin() {
        var client = mock(ReactiveExtensionClient.class);
        var guard = new SuperAdminGuard(client);
        var admin = RecipientServiceTest.user("admin");
        when(client.fetch(User.class, "admin")).thenReturn(Mono.just(admin));
        var authentication = new UsernamePasswordAuthenticationToken("admin", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        assertEquals("admin", guard.currentAdmin().contextWrite(
            ReactiveSecurityContextHolder.withAuthentication(authentication)).block().getMetadata().getName());
        admin.getSpec().setDisabled(true);
        assertThrows(ResponseStatusException.class,
            () -> guard.currentAdmin().contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block());
    }
}
