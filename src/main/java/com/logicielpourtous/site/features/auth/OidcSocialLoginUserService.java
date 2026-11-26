package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class OidcSocialLoginUserService extends OidcUserService {

    static final String MESSAGE_REFUS = "Connexion impossible avec ce compte.";

    private final UserRepository userRepository;

    public OidcSocialLoginUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        return rattacher(super.loadUser(request));
    }

    /**
     * Rattache l'identite OpenID Connect a un compte local EXISTANT, jamais de creation. Tous les refus
     * (e-mail absent ou non verifie, compte inconnu, inactif ou ambigu) ont le meme message generique,
     * qui ne dit rien sur l'existence du compte.
     */
    OidcUser rattacher(OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            throw refus();
        }

        String minuscules = email.trim().toLowerCase(Locale.ROOT);
        List<User> comptes = userRepository.findAllByEmailIgnoreCase(minuscules);
        if (comptes.size() != 1 || !comptes.get(0).isActive()) {
            throw refus();
        }

        return new CustomOidcUser(oidcUser, comptes.get(0), minuscules);
    }

    private static OAuth2AuthenticationException refus() {
        return new OAuth2AuthenticationException(new OAuth2Error("connexion_refusee"), MESSAGE_REFUS);
    }
}
