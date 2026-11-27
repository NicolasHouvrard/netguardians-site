package com.logicielpourtous.site.config;

import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;
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

    private final ConfigurationCompteUnique compteUnique;

    public SocialOAuth2Config(MailAccountRepository mailAccountRepository, Environment env,
                              ConfigurationCompteUnique compteUnique) {
        this.mailAccountRepository = mailAccountRepository;
        this.env = env;
        this.compteUnique = compteUnique;
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        return new LazyMailAccountRegistrationRepository(mailAccountRepository,
                new AutheliaRegistration(compteUnique, env));
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

    /** Vrai si l'emetteur, l'identifiant et le secret du compte unique sont renseignes. Ne fait AUCUN appel reseau. */
    public static boolean autheliaConfigure(ConfigurationCompteUnique compteUnique) {
        return compteUnique.configure();
    }

    private static String valeur(Environment env, String cle) {
        String v = env.getProperty(cle);
        return v == null ? "" : v.trim();
    }

    /**
     * Fournisseur OpenID Connect « authelia », pilote par les reglages du compte unique (base d'abord, puis les
     * proprietes {@code spring.security.oauth2.client.*.authelia.*}). Absent (null) tant qu'un des trois est vide :
     * aucun appel reseau. La decouverte de l'emetteur est paresseuse (jamais depuis /login), avec des delais courts,
     * UN SEUL essai a la fois (les autres appelants recoivent null aussitot) et au plus un essai toutes les 30 s apres
     * un echec ; un succes est garde. Quand {@code version()} des reglages change, le registre est reconstruit a la
     * demande suivante (et un nouvel essai est permis aussitot).
     */
    static final class AutheliaRegistration {
        private static final Logger LOG = LoggerFactory.getLogger(AutheliaRegistration.class);

        /** Registre garde et version des reglages avec lesquels il a ete construit. */
        private record EnCache(long version, ClientRegistration registration) { }

        private final ConfigurationCompteUnique reglages;
        private final List<String> scopes;
        private final int delaiMs;
        private final long attenteApresEchecMs;
        private final ReentrantLock verrou = new ReentrantLock();
        private volatile EnCache enCache;
        private volatile long prochainEssai;
        /** Version des reglages pour laquelle {@link #prochainEssai} a ete fixe. */
        private volatile long versionDeLAttente = Long.MIN_VALUE;

        AutheliaRegistration(ConfigurationCompteUnique reglages, Environment env) {
            this(reglages, env, 3_000, 30_000);
        }

        AutheliaRegistration(ConfigurationCompteUnique reglages, Environment env, int delaiMs, long attenteApresEchecMs) {
            this.reglages = reglages;
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

        ClientRegistration get() {
            long version = reglages.version();
            EnCache cache = enCache;
            if (cache != null && cache.version() == version) {
                return cache.registration();
            }
            if (!reglages.configure()) {
                return null;
            }
            if (versionDeLAttente != version) {
                prochainEssai = 0; // nouveaux reglages : pas d'attente heritee de l'ancien echec
            }
            if (System.currentTimeMillis() < prochainEssai || !verrou.tryLock()) {
                return null;
            }
            try {
                cache = enCache;
                if (cache != null && cache.version() == version) {
                    return cache.registration();
                }
                if (System.currentTimeMillis() < prochainEssai) {
                    return null;
                }
                String issuerUri = reglages.issuerUri();
                try {
                    ClientRegistration reg = decouvrir(issuerUri, reglages.clientId(), reglages.clientSecret());
                    enCache = new EnCache(version, reg);
                    return reg;
                } catch (RuntimeException e) {
                    versionDeLAttente = version;
                    prochainEssai = System.currentTimeMillis() + attenteApresEchecMs;
                    LOG.warn("Connexion unique : decouverte de l'emetteur '{}' impossible ({}) ; nouvel essai dans {} s.",
                            issuerUri, e.getClass().getSimpleName(), attenteApresEchecMs / 1000);
                    return null;
                }
            } finally {
                verrou.unlock();
            }
        }

        private ClientRegistration decouvrir(String issuerUri, String clientId, String clientSecret) {
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
