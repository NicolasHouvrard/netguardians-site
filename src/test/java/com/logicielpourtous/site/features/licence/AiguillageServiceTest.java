package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** Regles de l'aiguillage cote Maitre, sans base : les depots et le validateur sont simules. */
class AiguillageServiceTest {

    private static final long APPELANT = 10L;
    private static final String MAITRE = "https://maitre.exemple.test";

    private LicenceServeurRepository serveurs;
    private UserRepository users;
    private AppartenanceRepository appartenances;
    private ValidateurJetonAuthelia validateur;
    private AiguillageService service;
    private User alice;

    @BeforeEach
    void init() {
        serveurs = mock(LicenceServeurRepository.class);
        users = mock(UserRepository.class);
        appartenances = mock(AppartenanceRepository.class);
        validateur = mock(ValidateurJetonAuthelia.class);
        service = new AiguillageService(serveurs, users, appartenances, validateur, MAITRE);

        serveur(APPELANT, "client-appelant", "https://appelant.exemple.test", LicenceServeur.LicenceServeurStatut.ACTIF);
        alice = user(1L, true);
        when(users.findAllByEmailIgnoreCase("alice@exemple.fr")).thenReturn(List.of(alice));
        when(validateur.valider("jeton", "client-appelant")).thenReturn(Optional.of("alice@exemple.fr"));
    }

    private LicenceServeur serveur(long id, String clientId, String url, LicenceServeur.LicenceServeurStatut statut) {
        LicenceServeur s = new LicenceServeur();
        s.setId(id);
        s.setOidcClientId(clientId);
        s.setUrlPublique(url);
        s.setStatut(statut);
        when(serveurs.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private static User user(long id, boolean actif) {
        User u = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("alice@exemple.fr");
        u.setActive(actif);
        return u;
    }

    private void appartenance(Long societeId, String nom, Long serveurLicenceId) {
        Tenant t = new Tenant();
        t.setName(nom);
        t.setServeurLicenceId(serveurLicenceId);
        Appartenance a = new Appartenance();
        a.setUserId(1L);
        a.setTenant(t);
        a.setSocieteId(societeId);
        List<Appartenance> existantes = new java.util.ArrayList<>(appartenances.findByUserIdOrderById(1L));
        existantes.add(a);
        when(appartenances.findByUserIdOrderById(1L)).thenReturn(existantes);
    }

    @Test
    void liste_les_societes_hors_serveur_appelant() {
        serveur(20L, "c20", "https://vps.exemple.test", LicenceServeur.LicenceServeurStatut.ACTIF);
        appartenance(100L, "Chez l'appelant", APPELANT);
        appartenance(200L, "Chez le VPS", 20L);

        List<AiguillageService.Destination> r = service.aiguiller(APPELANT, "jeton");

        assertThat(r).containsExactly(new AiguillageService.Destination(200L, "Chez le VPS", "https://vps.exemple.test"));
    }

    @Test
    void un_tenant_sans_serveur_d_accueil_pointe_vers_le_maitre() {
        appartenance(300L, "Societe du Maitre", null);

        assertThat(service.aiguiller(APPELANT, "jeton"))
                .containsExactly(new AiguillageService.Destination(300L, "Societe du Maitre", MAITRE));
    }

    @Test
    void un_tenant_sans_serveur_d_accueil_est_omis_si_l_adresse_du_maitre_n_est_pas_configuree() {
        AiguillageService sansMaitre = new AiguillageService(serveurs, users, appartenances, validateur, "");
        appartenance(300L, "Societe du Maitre", null);

        assertThat(sansMaitre.aiguiller(APPELANT, "jeton")).isEmpty();
    }

    @Test
    void l_adresse_du_maitre_est_validee_https_seulement() {
        appartenance(300L, "Societe du Maitre", null);
        for (String mauvaise : new String[] {"http://maitre.exemple.test", "javascript:alert(1)", "https://u@maitre.test", "https://m.test/chemin"}) {
            AiguillageService s = new AiguillageService(serveurs, users, appartenances, validateur, mauvaise);
            assertThat(s.aiguiller(APPELANT, "jeton")).as(mauvaise).isEmpty();
        }
        AiguillageService ok = new AiguillageService(serveurs, users, appartenances, validateur, " https://maitre.exemple.test/ ");
        assertThat(ok.aiguiller(APPELANT, "jeton")).extracting(AiguillageService.Destination::urlPublique)
                .containsExactly("https://maitre.exemple.test");
    }

    @Test
    void l_aiguillage_utilise_l_adresse_publique_du_maitre_en_base() {
        var cfg = com.logicielpourtous.site.features.commun.ConfigurationCompteUniqueFixe.vide();
        AiguillageService suivi = new AiguillageService(serveurs, users, appartenances, validateur, cfg);
        appartenance(300L, "Societe du Maitre", null);
        assertThat(suivi.aiguiller(APPELANT, "jeton")).isEmpty();

        cfg.maitre("https://maitre.exemple.test/");
        assertThat(suivi.aiguiller(APPELANT, "jeton")).extracting(AiguillageService.Destination::urlPublique)
                .containsExactly("https://maitre.exemple.test");

        cfg.maitre("https://autre-maitre.exemple.test");
        assertThat(suivi.aiguiller(APPELANT, "jeton")).extracting(AiguillageService.Destination::urlPublique)
                .containsExactly("https://autre-maitre.exemple.test");

        cfg.maitre("http://invalide.exemple.test"); // invalide : traitee comme non configuree
        assertThat(suivi.aiguiller(APPELANT, "jeton")).isEmpty();
    }

    @Test
    void serveur_revoque_ou_sans_adresse_est_omis() {
        serveur(21L, "c21", "https://revoque.exemple.test", LicenceServeur.LicenceServeurStatut.REVOQUE);
        serveur(22L, "c22", null, LicenceServeur.LicenceServeurStatut.ACTIF);
        serveur(23L, "c23", " ", LicenceServeur.LicenceServeurStatut.ACTIF);
        appartenance(1L, "A", 21L);
        appartenance(2L, "B", 22L);
        appartenance(3L, "C", 23L);
        appartenance(4L, "D", 999L); // serveur inexistant

        assertThat(service.aiguiller(APPELANT, "jeton")).isEmpty();
    }

    @Test
    void compte_inconnu_inactif_ou_sans_societe_donne_une_liste_vide() {
        serveur(20L, "c20", "https://vps.exemple.test", LicenceServeur.LicenceServeurStatut.ACTIF);
        appartenance(200L, "Chez le VPS", 20L);

        // inactif
        when(users.findAllByEmailIgnoreCase("alice@exemple.fr")).thenReturn(List.of(user(1L, false)));
        assertThat(service.aiguiller(APPELANT, "jeton")).isEmpty();
        // inconnu
        when(users.findAllByEmailIgnoreCase("alice@exemple.fr")).thenReturn(List.of());
        assertThat(service.aiguiller(APPELANT, "jeton")).isEmpty();
        // ambigu (deux comptes ne differant que par la casse) : jamais un compte au hasard
        when(users.findAllByEmailIgnoreCase("alice@exemple.fr")).thenReturn(List.of(user(1L, true), user(2L, true)));
        assertThat(service.aiguiller(APPELANT, "jeton")).isEmpty();
        // sans societe accessible
        when(users.findAllByEmailIgnoreCase("alice@exemple.fr")).thenReturn(List.of(alice));
        when(appartenances.findByUserIdOrderById(1L)).thenReturn(List.of());
        assertThat(service.aiguiller(APPELANT, "jeton")).isEmpty();
    }

    @Test
    void jeton_invalide_serveur_inconnu_revoque_ou_sans_client_id_donnent_une_liste_vide() {
        serveur(20L, "c20", "https://vps.exemple.test", LicenceServeur.LicenceServeurStatut.ACTIF);
        appartenance(200L, "Chez le VPS", 20L);
        serveur(30L, null, "https://x.exemple.test", LicenceServeur.LicenceServeurStatut.ACTIF);
        serveur(31L, "c31", "https://y.exemple.test", LicenceServeur.LicenceServeurStatut.REVOQUE);

        assertThat(service.aiguiller(APPELANT, "autre-jeton")).isEmpty();
        assertThat(service.aiguiller(30L, "jeton")).isEmpty();
        assertThat(service.aiguiller(31L, "jeton")).isEmpty();
        assertThat(service.aiguiller(404L, "jeton")).isEmpty();
        assertThat(service.aiguiller(null, "jeton")).isEmpty();
        // le serveur sans client id n'a meme pas declenche de validation
        verify(validateur, org.mockito.Mockito.never()).valider(anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void l_adresse_vient_uniquement_de_la_base() {
        serveur(20L, "c20", "https://vps.exemple.test", LicenceServeur.LicenceServeurStatut.ACTIF);
        appartenance(200L, "Chez le VPS", 20L);

        // la seule entree variable est le jeton : l'adresse renvoyee est celle de licence_serveurs
        assertThat(service.aiguiller(APPELANT, "jeton")).extracting(AiguillageService.Destination::urlPublique)
                .containsExactly("https://vps.exemple.test");
        verify(validateur).valider(any(), org.mockito.ArgumentMatchers.eq("client-appelant"));
    }
}
