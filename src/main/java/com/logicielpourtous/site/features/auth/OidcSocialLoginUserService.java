package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class OidcSocialLoginUserService extends OidcUserService {

    private final UserRepository userRepository;

    public OidcSocialLoginUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(request);

        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_found"), "Email non disponible depuis Google");
        }

        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new OAuth2AuthenticationException(
                        new OAuth2Error("user_not_found"),
                        "Aucun compte trouvé pour " + email + ". Inscrivez-vous d'abord."));

        if (!user.isActive()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("account_disabled"),
                    "Compte non activé. Vérifiez l'email de confirmation.");
        }

        return new CustomOidcUser(oidcUser, user, email.toLowerCase());
    }
}
