package com.logicielpourtous.site.config;

import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * ClientRegistrationRepository dynamique : lit les credentials OAuth2 LAZILY
 * depuis les MailAccount Google/Microsoft déjà configurés en base.
 *
 * Lazy = pas de requête BDD au démarrage, uniquement à la première demande OAuth2.
 * Aucun credential dans application.properties.
 */
@Configuration
public class SocialOAuth2Config {

    private final MailAccountRepository mailAccountRepository;

    public SocialOAuth2Config(MailAccountRepository mailAccountRepository) {
        this.mailAccountRepository = mailAccountRepository;
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        return new LazyMailAccountRegistrationRepository(mailAccountRepository);
    }

    // ── Implémentation lazy (requête BDD à chaque appel, pas au démarrage) ───

    static class LazyMailAccountRegistrationRepository
            implements ClientRegistrationRepository, Iterable<ClientRegistration> {

        private final MailAccountRepository repo;

        LazyMailAccountRegistrationRepository(MailAccountRepository repo) {
            this.repo = repo;
        }

        @Override
        public ClientRegistration findByRegistrationId(String id) {
            return switch (id) {
                case "google"    -> buildGoogle(repo).orElse(null);
                case "microsoft" -> buildMicrosoft(repo).orElse(null);
                default          -> null;
            };
        }

        @Override
        public Iterator<ClientRegistration> iterator() {
            List<ClientRegistration> list = new ArrayList<>();
            buildGoogle(repo).ifPresent(list::add);
            buildMicrosoft(repo).ifPresent(list::add);
            return list.iterator();
        }
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    static Optional<ClientRegistration> buildGoogle(MailAccountRepository repo) {
        return repo.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2)
                .filter(a -> a.getClientId() != null && !a.getClientId().isBlank())
                .findFirst()
                .map(a -> ClientRegistration.withRegistrationId("google")
                        .clientId(a.getClientId())
                        .clientSecret(a.getClientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .scope("openid", "email", "profile")
                        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                        .tokenUri("https://oauth2.googleapis.com/token")
                        .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                        .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                        .userNameAttributeName("email")
                        .clientName("Google")
                        .build());
    }

    static Optional<ClientRegistration> buildMicrosoft(MailAccountRepository repo) {
        return repo.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .filter(a -> a.getClientId() != null && !a.getClientId().isBlank())
                .findFirst()
                .map(a -> {
                    String tenant = a.getMsTenantId() != null ? a.getMsTenantId() : "common";
                    return ClientRegistration.withRegistrationId("microsoft")
                            .clientId(a.getClientId())
                            .clientSecret(a.getClientSecret())
                            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                            .scope("openid", "email", "profile", "User.Read")
                            .authorizationUri("https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize")
                            .tokenUri("https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token")
                            .jwkSetUri("https://login.microsoftonline.com/" + tenant + "/discovery/v2.0/keys")
                            .userInfoUri("https://graph.microsoft.com/v1.0/me")
                            .userNameAttributeName("userPrincipalName")
                            .clientName("Microsoft")
                            .build();
                });
    }
}
