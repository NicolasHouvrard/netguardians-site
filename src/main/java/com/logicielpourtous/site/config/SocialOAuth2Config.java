package com.logicielpourtous.site.config;

import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
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

    private static final String PREFIXE = "spring.security.oauth2.client.";

    /** Vrai si les trois proprietes du fournisseur authelia sont renseignees. Ne fait AUCUN appel reseau. */
    public static boolean autheliaConfigure(Environment env) {
        return !valeur(env, PREFIXE + "registration.authelia.client-id").isEmpty()
                && !valeur(env, PREFIXE + "registration.authelia.client-secret").isEmpty()
                && !valeur(env, PREFIXE + "provider.authelia.issuer-uri").isEmpty();
    }

    private static String valeur(Environment env, String cle) {
        String v = env.getProperty(cle);
        return v == null ? "" : v.trim();
    }

    /**
     * Fournisseur OpenID Connect « authelia », pilote par les proprietes
     * {@code spring.security.oauth2.client.registration.authelia.client-id / client-secret} et
     * {@code spring.security.oauth2.client.provider.authelia.issuer-uri}. Absent (null) tant qu'une des trois
     * est vide : aucun appel reseau. La decouverte de l'emetteur est paresseuse (jamais depuis /login), avec
     * des delais courts, UN SEUL essai a la fois (les autres appelants recoivent null aussitot) et au plus un
     * essai toutes les 30 s apres un echec ; un succes est garde.
     */
    static final class AutheliaRegistration {
        private static final Logger LOG = LoggerFactory.getLogger(AutheliaRegistration.class);

        private final String clientId;
        private final String clientSecret;
        private final String issuerUri;
        private final List<String> scopes;
        private final int delaiMs;
        private final long attenteApresEchecMs;
        private final ReentrantLock verrou = new ReentrantLock();
        private volatile ClientRegistration enCache;
        private volatile long prochainEssai;

        AutheliaRegistration(Environment env) {
            this(env, 3_000, 30_000);
        }

        AutheliaRegistration(Environment env, int delaiMs, long attenteApresEchecMs) {
            this.clientId = valeur(env, PREFIXE + "registration.authelia.client-id");
            this.clientSecret = valeur(env, PREFIXE + "registration.authelia.client-secret");
            this.issuerUri = valeur(env, PREFIXE + "provider.authelia.issuer-uri");
            this.scopes = portee(valeur(env, PREFIXE + "registration.authelia.scope"));
            this.delaiMs = delaiMs;
            this.attenteApresEchecMs = attenteApresEchecMs;
        }

        /** La portee contient toujours « openid » (sinon la connexion passerait hors du chemin OpenID Connect). */
        static List<String> portee(String configuree) {
            List<String> liste = new ArrayList<>();
            liste.add("openid");
            String brut = configuree.isEmpty() ? "email,profile" : configuree;
            for (String x : brut.split("[, ]+")) {
                if (!x.isEmpty() && !liste.contains(x)) {
                    liste.add(x);
                }
            }
            return liste;
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
            if (System.currentTimeMillis() < prochainEssai || !verrou.tryLock()) {
                return null;
            }
            try {
                if (enCache != null) {
                    return enCache;
                }
                if (System.currentTimeMillis() < prochainEssai) {
                    return null;
                }
                try {
                    enCache = decouvrir();
                    return enCache;
                } catch (RuntimeException e) {
                    prochainEssai = System.currentTimeMillis() + attenteApresEchecMs;
                    LOG.warn("Connexion unique : decouverte de l'emetteur '{}' impossible ({}) ; nouvel essai dans {} s.",
                            issuerUri, e.getClass().getSimpleName(), attenteApresEchecMs / 1000);
                    return null;
                }
            } finally {
                verrou.unlock();
            }
        }

        private ClientRegistration decouvrir() {
            String base = sansBarre(issuerUri);
            SimpleClientHttpRequestFactory usine = new SimpleClientHttpRequestFactory();
            usine.setConnectTimeout(delaiMs);
            usine.setReadTimeout(delaiMs);
            Map<String, Object> configuration = RestClient.builder().requestFactory(usine).build()
                    .get().uri(base + "/.well-known/openid-configuration")
                    .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (configuration == null || !base.equals(sansBarre(String.valueOf(configuration.get("issuer"))))) {
                throw new IllegalStateException("emetteur inattendu");
            }
            return ClientRegistrations.fromOidcConfiguration(configuration)
                    .registrationId("authelia")
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .scope(scopes)
                    .clientName("Authelia")
                    .build();
        }

        private static String sansBarre(String u) {
            return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
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
