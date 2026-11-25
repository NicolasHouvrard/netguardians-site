package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** Apres un changement de mot de passe reussi, tout le monde arrive sur /accueil. */
class PasswordChangeControllerTest {

    private String changer(Role role) {
        User u = new User();
        u.setEmail("e@exemple.fr");
        u.setPassword("x");
        u.setActive(true);
        u.setRoles(Set.of(role));
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByEmail("e@exemple.fr")).thenReturn(Optional.of(u));
        PasswordEncoder enc = mock(PasswordEncoder.class);
        when(enc.encode(any())).thenReturn("hash");
        PasswordChangeController c = new PasswordChangeController(repo, enc);
        return c.changePassword(new CustomUserDetails(u), "motdepasse1", "motdepasse1", new MockHttpSession(),
                new ExtendedModelMap(), new RedirectAttributesModelMap());
    }

    @Test
    void admin_versAccueil() {
        assertThat(changer(new Role("ADMIN", "Admin", "ESN"))).isEqualTo("redirect:/accueil");
    }

    @Test
    void esn_versAccueil() {
        assertThat(changer(new Role("TECH", "Tech", "ESN"))).isEqualTo("redirect:/accueil");
    }

    @Test
    void client_versAccueil() {
        assertThat(changer(new Role("CLIENT", "Client", "CLIENT"))).isEqualTo("redirect:/accueil");
    }
}
