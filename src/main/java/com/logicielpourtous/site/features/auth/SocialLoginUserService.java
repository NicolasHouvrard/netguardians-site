package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class SocialLoginUserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    public SocialLoginUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(request);

        String provider = request.getClientRegistration().getRegistrationId();
        String email = extractEmail(oAuth2User, provider);

        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_found"), "Email non disponible depuis " + provider);
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

        return new CustomOAuth2User(oAuth2User, user, email.toLowerCase());
    }

    private String extractEmail(OAuth2User user, String provider) {
        return switch (provider) {
            case "microsoft" -> {
                String mail = user.getAttribute("mail");
                yield mail != null ? mail : user.getAttribute("userPrincipalName");
            }
            default -> user.getAttribute("email");
        };
    }
}
