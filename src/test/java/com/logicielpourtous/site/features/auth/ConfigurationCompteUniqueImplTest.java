package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl.ReglagesModifies;
import com.logicielpourtous.site.features.commun.ChiffrementSecrets;

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
    private ChiffrementSecrets chiffreur;
    private ConfigurationCompteUniqueImpl config;

    /** Instantane de la ligne, comme le ferait une lecture SQL (copie, pas la meme instance). */
    private static ReglagesCompteUnique copie(ReglagesCompteUnique o) {
        if (o == null) {
            return null;
        }
        ReglagesCompteUnique c = new ReglagesCompteUnique();
        c.setIssuerUri(o.getIssuerUri());
        c.setClientId(o.getClientId());
        c.setClientSecret(o.getClientSecret());
        c.setUrlPublique(o.getUrlPublique());
        c.setMaitreUrlPublique(o.getMaitreUrlPublique());
        c.setRetourDeconnexion(o.getRetourDeconnexion());
        c.setVersion(o.getVersion());
        c.setModifiePar(o.getModifiePar());
        return c;
    }

    @BeforeEach
    void init() {
        env = new MockEnvironment();
        chiffreur = new ChiffrementSecrets("cle-de-test-non-secrete-0123456789", "deadbeefdeadbeef");
        repo = mock(ReglagesCompteUniqueRepository.class);
        when(repo.findById(ReglagesCompteUnique.ID_UNIQUE)).thenAnswer(i -> Optional.ofNullable(copie(stockage.get())));
        when(repo.verrouiller()).thenAnswer(i -> Optional.ofNullable(stockage.get()));
        when(repo.creerSiAbsente()).thenAnswer(i -> {
            stockage.compareAndSet(null, new ReglagesCompteUnique());
            return 1;
        });
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
        assertThat(chiffreur.dechiffrer(enBase)).isEqualTo(SECRET);
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

    @Test
    void une_lecture_concurrente_pendant_l_enregistrement_ne_republie_pas_l_ancienne_valeur() throws Exception {
        config.enregistrer(complets(), "admin");
        ReglagesCompteUnique avant = copie(stockage.get());
        config.invalider();

        CountDownLatch lectureCommencee = new CountDownLatch(1);
        CountDownLatch feuVert = new CountDownLatch(1);
        // Le premier lecteur obtient l'ANCIENNE ligne puis est suspendu avant de publier son resultat.
        when(repo.findById(ReglagesCompteUnique.ID_UNIQUE)).thenAnswer(i -> {
            lectureCommencee.countDown();
            feuVert.await(10, TimeUnit.SECONDS);
            return Optional.of(avant);
        }).thenAnswer(i -> Optional.ofNullable(copie(stockage.get())));

        Thread lecteur = new Thread(() -> config.issuerUri());
        lecteur.start();
        assertThat(lectureCommencee.await(10, TimeUnit.SECONDS)).isTrue();

        config.enregistrer(reglages("https://sso.exemple.org", "site-1", null, null, null, null), "admin");
        feuVert.countDown();
        lecteur.join(10_000);

        assertThat(config.issuerUri()).isEqualTo("https://sso.exemple.org");
        assertThat(config.version()).isEqualTo(stockage.get().getVersion());
    }

    @Test
    void un_secret_illisible_ne_fait_pas_tomber_la_lecture_des_reglages() {
        config.enregistrer(complets(), "admin");
        stockage.get().setClientSecret("0123abcd-pas-un-texte-chiffre");
        config.invalider();

        assertThat(config.configure()).isFalse();
        assertThat(config.clientSecret()).isEmpty();
        assertThat(config.issuerUri()).isEqualTo("https://auth.exemple.org");
    }

    @Test
    void remplacer_le_secret_ne_touche_pas_les_autres_champs() {
        config.enregistrer(complets(), "admin");
        long v = config.version();

        config.remplacerSecret("nouveau-secret-de-test-987654321", "admin2");

        assertThat(config.clientSecret()).isEqualTo("nouveau-secret-de-test-987654321");
        assertThat(config.issuerUri()).isEqualTo("https://auth.exemple.org");
        assertThat(config.clientId()).isEqualTo("site-1");
        assertThat(config.urlPublique()).isEqualTo("https://app.exemple.org");
        assertThat(config.version()).isGreaterThan(v);
        assertThat(stockage.get().getModifiePar()).isEqualTo("admin2");
    }

    @Test
    void effacer_le_secret_rend_configure_faux() {
        config.enregistrer(complets(), "admin");
        assertThat(config.configure()).isTrue();

        config.effacerSecret("admin");

        assertThat(config.configure()).isFalse();
        assertThat(config.clientSecret()).isEmpty();
        assertThat(config.lireReglagesEnBase().secretDefini()).isFalse();
        assertThat(config.clientId()).isEqualTo("site-1");
    }

    @Test
    void null_laisse_le_champ_inchange_et_vide_l_efface() {
        env.setProperty(K_ISSUER, "https://auth.env.exemple.org");
        config.enregistrer(complets(), "admin");

        config.enregistrer(reglages(null, "site-2", null, null, null, null), "admin");
        assertThat(config.issuerUri()).isEqualTo("https://auth.exemple.org");
        assertThat(config.clientId()).isEqualTo("site-2");
        assertThat(config.urlPublique()).isEqualTo("https://app.exemple.org");

        // Chaine vide : la valeur de la base est effacee, l'environnement reprend la main.
        config.enregistrer(reglages("", null, null, "", null, null), "admin");
        assertThat(config.issuerUri()).isEqualTo("https://auth.env.exemple.org");
        assertThat(config.urlPublique()).isEmpty();
        assertThat(config.clientId()).isEqualTo("site-2");
        var brut = config.lireReglagesEnBase();
        assertThat(brut.issuerUri()).isEmpty();
        assertThat(brut.clientId()).isEqualTo("site-2");
        assertThat(brut.secretDefini()).isTrue();
    }

    @Test
    void vider_l_emetteur_et_l_identifiant_sans_effacer_le_secret_donne_un_etat_refuse_ou_explicite() {
        config.enregistrer(complets(), "admin");

        // Choix : etat incoherent evite en effacant AUSSI le secret enregistre (retour complet a l'environnement).
        config.enregistrer(reglages("", "", null, "", null, null), "admin");

        assertThat(config.lireReglagesEnBase().secretDefini()).isFalse();
        assertThat(config.configure()).isFalse();
    }

    @Test
    void enregistrer_avec_emetteur_et_identifiant_vides_venant_de_l_environnement_conserve_le_secret_en_base() {
        env.setProperty(K_ISSUER, "https://auth.exemple.org");
        env.setProperty(K_ID, "id-env");
        config.remplacerSecret(SECRET, "admin");

        // Le formulaire renvoie "" pour ces deux champs (valeurs d'environnement, rien en base) : le secret reste.
        config.enregistrer(reglages("", "", null, "https://app.exemple.org", null, null), "admin");

        assertThat(config.lireReglagesEnBase().secretDefini()).isTrue();
        assertThat(config.clientSecret()).isEqualTo(SECRET);
    }

    @Test
    void l_emetteur_et_le_maitre_en_adresse_ip_sont_refuses() {
        assertThatThrownBy(() -> config.enregistrer(reglages("https://10.0.0.5", "site-1", SECRET, null, null, null),
                "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET, null,
                "https://[::1]", null), "admin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void le_domaine_parent_suit_l_emetteur_effectif_apres_modification() {
        env.setProperty(K_ISSUER, "https://auth.env.exemple.org");
        config.enregistrer(reglages("https://auth.exemple.org", "site-1", SECRET, null, null, null), "admin");
        // On vide l'emetteur de la base : l'environnement (parent env.exemple.org) devient la reference.
        assertThatThrownBy(() -> config.enregistrer(reglages("", null, null, "https://app.exemple.org", null, null),
                "admin")).isInstanceOf(IllegalArgumentException.class);
    }
}
