package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl.ReglagesModifies;
import com.logicielpourtous.site.features.mail.MailEncryptionConverter;

class ConfigurationCompteUniqueImplTest {

    private static final String K_ISSUER = "spring.security.oauth2.client.provider.authelia.issuer-uri";
    private static final String K_ID = "spring.security.oauth2.client.registration.authelia.client-id";
    private static final String K_SECRET = "spring.security.oauth2.client.registration.authelia.client-secret";
    private static final String K_MAITRE = "app.auth.authelia.maitre-url-publique";
    private static final String K_RETOUR = "app.auth.authelia.retour-deconnexion";

    private static final String SECRET = "secret-de-test-unique-0123456789";

    private final AtomicReference<ReglagesCompteUnique> stockage = new AtomicReference<>();
    private MockEnvironment env;
    private ReglagesCompteUniqueRepository repo;
    private MailEncryptionConverter chiffreur;
    private ConfigurationCompteUniqueImpl config;

    @BeforeEach
    void init() {
        env = new MockEnvironment();
        chiffreur = new MailEncryptionConverter();
        chiffreur.setKey("cle-de-test-non-secrete-0123456789");
        repo = mock(ReglagesCompteUniqueRepository.class);
        when(repo.findById(ReglagesCompteUnique.ID_UNIQUE)).thenAnswer(i -> Optional.ofNullable(stockage.get()));
        when(repo.save(any(ReglagesCompteUnique.class))).thenAnswer(i -> {
            stockage.set(i.getArgument(0));
            return i.getArgument(0);
        });
        config = new ConfigurationCompteUniqueImpl(repo, env, chiffreur);
    }

    private static ReglagesModifies reglages(String issuer, String id, String secret, String url, String maitre,
            String retour) {
        return new ReglagesModifies(issuer, id, secret, url, maitre, retour);
    }

    private ReglagesModifies complets() {
        return reglages("https://auth.exemple.org", "site-1", SECRET, "https://app.exemple.org", null, null);
    }

    @Test
    void la_base_prime_sur_l_environnement() {
        env.setProperty(K_ISSUER, "https://auth.env.org");
        env.setProperty(K_ID, "id-env");
        env.setProperty(K_SECRET, "secret-env");
        config.enregistrer(complets(), "admin");

        assertThat(config.issuerUri()).isEqualTo("https://auth.exemple.org");
        assertThat(config.clientId()).isEqualTo("site-1");
        assertThat(config.clientSecret()).isEqualTo(SECRET);
        assertThat(config.urlPublique()).isEqualTo("https://app.exemple.org");
        assertThat(config.configure()).isTrue();
    }

    @Test
    void base_vide_et_environnement_renseigne_donne_les_valeurs_de_l_environnement() {
        env.setProperty(K_ISSUER, "https://auth.env.org");
        env.setProperty(K_ID, "id-env");
        env.setProperty(K_SECRET, "secret-env");
        env.setProperty(K_MAITRE, "https://maitre.env.org");
        env.setProperty(K_RETOUR, "https://app.env.org/login?logout");

        assertThat(config.issuerUri()).isEqualTo("https://auth.env.org");
        assertThat(config.clientId()).isEqualTo("id-env");
        assertThat(config.clientSecret()).isEqualTo("secret-env");
        assertThat(config.maitreUrlPublique()).isEqualTo("https://maitre.env.org");
        assertThat(config.retourDeconnexion()).isEqualTo("https://app.env.org/login?logout");
        assertThat(config.configure()).isTrue();
        assertThat(config.version()).isZero();
    }

    @Test
    void base_vide_et_environnement_vide_n_est_pas_configure() {
        assertThat(config.configure()).isFalse();
        assertThat(config.issuerUri()).isEmpty();
        assertThat(config.clientId()).isEmpty();
        assertThat(config.clientSecret()).isEmpty();
        assertThat(config.urlPublique()).isEmpty();
        assertThat(config.retourDeconnexion()).isEmpty();
        assertThat(config.maitreUrlPublique()).isEmpty();
    }

    @Test
    void le_secret_est_chiffre_en_base_et_dechiffre_a_la_lecture() {
        config.enregistrer(complets(), "admin");

        String enBase = stockage.get().getClientSecret();
        assertThat(enBase).isNotBlank().isNotEqualTo(SECRET).doesNotContain(SECRET);
        assertThat(chiffreur.convertToEntityAttribute(enBase)).isEqualTo(SECRET);
        assertThat(config.clientSecret()).isEqualTo(SECRET);
        assertThat(stockage.get().getModifiePar()).isEqualTo("admin");
    }

    @Test
    void un_secret_absent_a_l_enregistrement_conserve_le_secret_existant() {
        config.enregistrer(complets(), "admin");
        config.enregistrer(reglages("https://auth.exemple.org", "site-2", "  ", "https://app.exemple.org", null, null),
                "admin");

        assertThat(config.clientId()).isEqualTo("site-2");
        assertThat(config.clientSecret()).isEqualTo(SECRET);
    }

    @Test
    void retour_deconnexion_est_deduit_de_l_adresse_publique() {
        config.enregistrer(complets(), "admin");
        assertThat(config.retourDeconnexion()).isEqualTo("https://app.exemple.org/login?logout");

        config.enregistrer(reglages("https://auth.exemple.org", "site-1", null, "https://app.exemple.org/", null, null),
                "admin");
        assertThat(config.retourDeconnexion()).isEqualTo("https://app.exemple.org/login?logout");
    }

    @Test
    void un_retour_explicite_valide_prime_sur_la_deduction() {
        config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET, "https://app.exemple.org", null,
                "https://autre.exemple.org/bye"), "admin");
        assertThat(config.retourDeconnexion()).isEqualTo("https://autre.exemple.org/bye");
    }

    @Test
    void une_adresse_http_est_refusee_a_l_enregistrement() {
        assertThatThrownBy(() -> config.enregistrer(reglages("http://auth.exemple.org", "site-1", SECRET, null, null,
                null), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET,
                "http://app.exemple.org", null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.enregistrer(reglages("https://u:p@auth.exemple.org", "site-1", SECRET, null,
                null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET,
                "https://app.exemple.org/#x", null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stockage.get()).isNull();
    }

    @Test
    void une_adresse_hors_domaine_parent_du_portail_est_refusee() {
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET,
                "https://app.autre.org", null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET,
                "https://app.exemple.org", null, "https://evil.org/x"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // Piege du suffixe : "malexemple.org" ne finit pas par ".exemple.org".
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET,
                "https://app.malexemple.org", null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
        // Il ne reste qu'un label une fois le premier retire : refus.
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.local", "site-1", SECRET,
                "https://app.local", null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stockage.get()).isNull();
        // Le domaine parent lui-meme et un sous-domaine du parent sont acceptes.
        config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET, "https://exemple.org", null,
                "https://a.b.exemple.org/x"), "admin");
        assertThat(config.urlPublique()).isEqualTo("https://exemple.org");
    }

    @Test
    void les_longueurs_sont_bornees() {
        String longue = "https://app." + "a".repeat(400) + ".exemple.org";
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET, longue,
                null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "x".repeat(300), SECRET, null,
                null, null), "admin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void la_version_change_apres_un_enregistrement() {
        long v0 = config.version();
        config.enregistrer(complets(), "admin");
        long v1 = config.version();
        config.enregistrer(complets(), "admin");
        long v2 = config.version();

        assertThat(v1).isGreaterThan(v0);
        assertThat(v2).isGreaterThan(v1);
    }

    @Test
    void le_secret_n_apparait_dans_aucun_toString_ni_message_d_erreur() {
        ReglagesModifies m = reglages("http://auth.exemple.org", "site-1", SECRET, null, null, null);
        assertThat(m.toString()).doesNotContain(SECRET);

        assertThatThrownBy(() -> config.enregistrer(m, "admin")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(SECRET);

        config.enregistrer(complets(), "admin");
        assertThat(stockage.get().toString()).doesNotContain(SECRET);
        assertThat(config.toString()).doesNotContain(SECRET);
    }
}
