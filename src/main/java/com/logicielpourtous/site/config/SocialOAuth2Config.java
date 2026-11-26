package com.logicielpourtous.site.config;

import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
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

    private final Environment env;

    public SocialOAuth2Config(MailAccountRepository mailAccountRepository, Environment env) {
        this.mailAccountRepository = mailAccountRepository;
        this.env = env;
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        return new LazyMailAccountRegistrationRepository(mailAccountRepository, new AutheliaRegistration(env));
    }

    // ── Implémentation lazy (requête BDD à chaque appel, pas au démarrage) ───

    static class LazyMailAccountRegistrationRepository
            implements ClientRegistrationRepository, Iterable<ClientRegistration> {

        private final MailAccountRepository repo;
        private final AutheliaRegistration authelia;

        LazyMailAccountRegistrationRepository(MailAccountRepository repo, AutheliaRegistration authelia) {
            this.repo = repo;
            this.authelia = authelia;
        }

        @Override
        public ClientRegistration findByRegistrationId(String id) {
            return switch (id) {
                case "google"    -> buildGoogle(repo).orElse(null);
                case "microsoft" -> buildMicrosoft(repo).orElse(null);
                case "authelia"  -> authelia.get();
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

    /**
     * Fournisseur OpenID Connect « authelia », pilote par les proprietes
     * {@code spring.security.oauth2.client.registration.authelia.client-id / client-secret} et
     * {@code spring.security.oauth2.client.provider.authelia.issuer-uri}. Absent (null) tant qu'une des trois
     * est vide : aucun appel reseau, aucun changement de comportement. La decouverte de l'emetteur se fait
     * paresseusement ; un succes est garde, un echec (Authelia injoignable) est retente au plus toutes les 30 s
     * et ne fait jamais echouer la page de connexion locale.
     */
    static final class AutheliaRegistration {
        private static final String PREFIXE = "spring.security.oauth2.client.";
        private static final long ATTENTE_APRES_ECHEC_MS = 30_000;

        private final String clientId;
        private final String clientSecret;
        private final String issuerUri;
        private final String scopes;
        private volatile ClientRegistration enCache;
        private volatile long prochainEssai;

        AutheliaRegistration(Environment env) {
            this.clientId = valeur(env, PREFIXE + "registration.authelia.client-id");
            this.clientSecret = valeur(env, PREFIXE + "registration.authelia.client-secret");
            this.issuerUri = valeur(env, PREFIXE + "provider.authelia.issuer-uri");
            String s = valeur(env, PREFIXE + "registration.authelia.scope");
            this.scopes = s.isEmpty() ? "openid,email,profile" : s;
        }

        private static String valeur(Environment env, String cle) {
            String v = env.getProperty(cle);
            return v == null ? "" : v.trim();
        }

        boolean configure() {
            return !clientId.isEmpty() && !clientSecret.isEmpty() && !issuerUri.isEmpty();
        }

        ClientRegistration get() {
            if (!configure()) {
                return null;
            }
            ClientRegistration cache = enCache;
            if (cache != null) {
                return cache;
            }
            if (System.currentTimeMillis() < prochainEssai) {
                return null;
            }
            try {
                ClientRegistration r = ClientRegistrations.fromIssuerLocation(issuerUri)
                        .registrationId("authelia")
                        .clientId(clientId)
                        .clientSecret(clientSecret)
                        .scope(java.util.Arrays.stream(scopes.split("[, ]+")).filter(x -> !x.isEmpty()).toList())
                        .clientName("Authelia")
                        .build();
                enCache = r;
                return r;
            } catch (RuntimeException e) {
                prochainEssai = System.currentTimeMillis() + ATTENTE_APRES_ECHEC_MS;
                return null;
            }
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
