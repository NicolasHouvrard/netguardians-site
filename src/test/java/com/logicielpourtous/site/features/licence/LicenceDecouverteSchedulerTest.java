package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.sync.SecondaryServer;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;

/**
 * {@link LicenceDecouverteScheduler} : sondage cote PARTNER du MAITRE (Task 6), materialisation
 * locale d'un {@code SecondaryServer}, push-back du code local, propagation d'une revocation.
 *
 * <p>Contre les VRAIS endpoints du maitre ({@code GET /api/licence/nouveaux-serveurs}, {@code POST
 * /api/licence/code-partenaire}), sur un vrai port HTTP (aucun mock pour {@link LicenceClient}) —
 * meme patron que {@code LicenceClientIntegrationTest} pour {@code genererJeton} : ce contexte joue
 * le maitre, {@link LicenceDecouverteScheduler} et son {@link LicenceClient} sont instancies a la
 * main vers {@code localhost:<port>}. Pas de {@code @Transactional} (vraie requete HTTP, propre
 * thread serveur) : nettoyage explicite en {@link #nettoyer}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LicenceDecouverteSchedulerTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompteLicenceRepository compteLicenceRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private EtatLicenceLocaleRepository etatLicenceLocaleRepository;
    @Autowired private SecondaryServerRepository secondaryServerRepository;

    private CompteLicence compte;
    private LicenceServeur partenaire;
    private EtatLicenceLocale etatLocal;
    private final List<Long> licenceServeurIdsACleaner = new ArrayList<>();
    private final List<Long> secondaryServerIdsACleaner = new ArrayList<>();

    @AfterEach
    void nettoyer() {
        secondaryServerIdsACleaner.forEach(secondaryServerRepository::deleteById);
        licenceServeurIdsACleaner.forEach(licenceServeurRepository::deleteById);
        if (etatLocal != null) {
            etatLicenceLocaleRepository.deleteById(etatLocal.getId());
        }
        if (partenaire != null) {
            licenceServeurRepository.deleteById(partenaire.getId());
        }
        if (compte != null) {
            compteLicenceRepository.deleteById(compte.getId());
        }
    }

    @Test
    void decouverte_newClientLicenceServeur_createsLocalSecondaryServerWithCorrectCustomerId() {
        enrolerPartenaireEtEtatLocal();
        LicenceServeur client = creerLicenceServeurClient(888L, LicenceServeur.LicenceServeurStatut.ACTIF);

        nouveauScheduler().decouvrir();

        SecondaryServer serveur = trouverSecondaryServer(888L);
        assertThat(serveur).isNotNull();
        assertThat(serveur.getRole()).isEqualTo(ServerRole.CLIENT);
        assertThat(serveur.getCustomerId()).isEqualTo(888L);
        assertThat(serveur.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);
        assertThat(serveur.getEnrollmentCode()).isNotBlank();

        licenceServeurIdsACleaner.add(client.getId());
        secondaryServerIdsACleaner.add(serveur.getId());
    }

    @Test
    void decouverte_pushesLocalCodeBackToMp() {
        enrolerPartenaireEtEtatLocal();
        LicenceServeur client = creerLicenceServeurClient(889L, LicenceServeur.LicenceServeurStatut.ACTIF);

        nouveauScheduler().decouvrir();

        SecondaryServer serveur = trouverSecondaryServer(889L);
        LicenceServeur clientApres = licenceServeurRepository.findById(client.getId()).orElseThrow();
        assertThat(clientApres.isNotifiePartenaire()).isTrue();
        assertThat(clientApres.getCodeLocalPartenaire())
                .isNotBlank()
                .isEqualTo(serveur.getEnrollmentCode());

        licenceServeurIdsACleaner.add(client.getId());
        secondaryServerIdsACleaner.add(serveur.getId());
    }

    @Test
    void decouverte_revokedLicenceServeur_revokesLocalSecondaryServer() {
        enrolerPartenaireEtEtatLocal();
        LicenceServeur client = creerLicenceServeurClient(890L, LicenceServeur.LicenceServeurStatut.ACTIF);
        LicenceDecouverteScheduler scheduler = nouveauScheduler();

        scheduler.decouvrir(); // 1er cycle : materialisation ACTIF + notification.
        SecondaryServer serveurApresCreation = trouverSecondaryServer(890L);
        assertThat(serveurApresCreation.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);

        // Cote maitre : un admin revoque (voir CompteLicenceAdminController#revokeLicenceServeur, qui
        // remet aussi notifiePartenaire a false pour que cette revocation soit re-sondee).
        LicenceServeur clientARevoquer = licenceServeurRepository.findById(client.getId()).orElseThrow();
        clientARevoquer.setStatut(LicenceServeur.LicenceServeurStatut.REVOQUE);
        clientARevoquer.setNotifiePartenaire(false);
        licenceServeurRepository.save(clientARevoquer);

        scheduler.decouvrir(); // 2e cycle : doit propager la revocation localement.

        SecondaryServer serveurApresRevocation = trouverSecondaryServer(890L);
        assertThat(serveurApresRevocation.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.REVOQUE);
        assertThat(serveurApresRevocation.getId()).isEqualTo(serveurApresCreation.getId()); // meme ligne, pas un doublon

        licenceServeurIdsACleaner.add(client.getId());
        secondaryServerIdsACleaner.add(serveurApresRevocation.getId());
    }

    // Revue finale, C2 : forme REALISTE du bug — une fois le client inscrit sync (POST /api/sync/enroll,
    // EnrollmentController efface alors l'enrollmentCode du SecondaryServer local), sa revocation
    // poussait un codeLocal null, refuse 400 par /code-partenaire : notifiePartenaire ne passait jamais
    // a true (re-sondage infini), et l'exception avortait tout le reste du cycle. Un 2e client,
    // GENUINEMENT nouveau, decouvert dans le MEME cycle, doit lui aussi etre materialise.
    @Test
    void decouverte_revokedClientAfterSyncEnrollment_nullCode_isAcknowledgedAndCycleContinues() {
        enrolerPartenaireEtEtatLocal();
        LicenceServeur client = creerLicenceServeurClient(893L, LicenceServeur.LicenceServeurStatut.ACTIF);
        licenceServeurIdsACleaner.add(client.getId()); // enregistre tot : nettoye meme si une assertion echoue
        LicenceDecouverteScheduler scheduler = nouveauScheduler();

        scheduler.decouvrir(); // 1er cycle : materialisation ACTIF + notification.
        SecondaryServer serveurMaterialise = trouverSecondaryServer(893L);
        secondaryServerIdsACleaner.add(serveurMaterialise.getId());
        assertThat(serveurMaterialise.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);

        // Inscription sync terminee cote client -> enrollmentCode local efface (comme EnrollmentController).
        serveurMaterialise.setEnrollmentCode(null);
        serveurMaterialise.setEnrollmentCodeExpiresAt(null);
        secondaryServerRepository.save(serveurMaterialise);

        // Cote MP : revocation (meme effet que CompteLicenceAdminController#revokeLicenceServeur).
        LicenceServeur clientARevoquer = licenceServeurRepository.findById(client.getId()).orElseThrow();
        clientARevoquer.setStatut(LicenceServeur.LicenceServeurStatut.REVOQUE);
        clientARevoquer.setNotifiePartenaire(false);
        licenceServeurRepository.save(clientARevoquer);

        // Un AUTRE client, tout nouveau, apparait dans le MEME cycle que la revocation.
        LicenceServeur autreClient = creerLicenceServeurClient(894L, LicenceServeur.LicenceServeurStatut.ACTIF);
        licenceServeurIdsACleaner.add(autreClient.getId());

        assertThatCode(scheduler::decouvrir).doesNotThrowAnyException(); // 2e cycle
        SecondaryServer autreMaterialise = trouverSecondaryServer(894L);
        if (autreMaterialise != null) {
            secondaryServerIdsACleaner.add(autreMaterialise.getId());
        }

        SecondaryServer serveurRevoque = secondaryServerRepository.findById(serveurMaterialise.getId()).orElseThrow();
        assertThat(serveurRevoque.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.REVOQUE);
        assertThat(licenceServeurRepository.findById(client.getId()).orElseThrow().isNotifiePartenaire())
                .as("revocation acquittee cote MP (pas de re-sondage infini)").isTrue();

        SecondaryServer serveurAutre = trouverSecondaryServer(894L);
        assertThat(serveurAutre).as("2e client du meme cycle materialise").isNotNull();
        assertThat(serveurAutre.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);
        assertThat(licenceServeurRepository.findById(autreClient.getId()).orElseThrow().isNotifiePartenaire()).isTrue();
    }

    // Revue finale, C2 (isolation par entree) : quelle que soit la cause (ce bug ou un futur), UNE entree
    // en echec ne doit jamais affamer les suivantes du meme cycle. LicenceClient mocke ici pour forcer
    // un echec deterministe sur la 1ere entree, dans un ordre controle ; vrai SecondaryServerRepository.
    @Test
    void decouverte_oneEntryFails_otherEntriesOfSameCycleStillProcessed() {
        LicenceClient licenceClientMock = mock(LicenceClient.class);
        String identifiantEmpoisonne = UUID.randomUUID().toString();
        String identifiantSain = UUID.randomUUID().toString();
        when(licenceClientMock.listerNouveauxServeurs()).thenReturn(List.of(
                new LicenceEnrolementController.NouveauServeurDto(identifiantEmpoisonne, 895L, LicenceServeur.LicenceServeurStatut.ACTIF),
                new LicenceEnrolementController.NouveauServeurDto(identifiantSain, 896L, LicenceServeur.LicenceServeurStatut.ACTIF)));
        doThrow(new IllegalStateException("echec simule pour l'entree empoisonnee"))
                .when(licenceClientMock).pousserCodePartenaire(eq(identifiantEmpoisonne), any());

        LicenceDecouverteScheduler scheduler =
                new LicenceDecouverteScheduler(licenceClientMock, secondaryServerRepository, role("PARTNER"));
        assertThatCode(scheduler::decouvrir).doesNotThrowAnyException();

        SecondaryServer serveurEmpoisonne = trouverSecondaryServerParIdentifiant(identifiantEmpoisonne);
        SecondaryServer serveurSain = trouverSecondaryServerParIdentifiant(identifiantSain);
        if (serveurEmpoisonne != null) {
            secondaryServerIdsACleaner.add(serveurEmpoisonne.getId());
        }
        if (serveurSain != null) {
            secondaryServerIdsACleaner.add(serveurSain.getId());
        }
        assertThat(serveurSain).as("entree saine traitee malgre l'echec de la precedente").isNotNull();
        assertThat(serveurSain.getCustomerId()).isEqualTo(896L);
        verify(licenceClientMock).pousserCodePartenaire(eq(identifiantSain), eq(serveurSain.getEnrollmentCode()));
    }

    // Fix round 1, finding Critical : customerId seul ne suffit pas a identifier une installation —
    // une machine remplacee (2e jeton genere pour le MEME customerId) cree un LicenceServeur CLIENT
    // DISTINCT (identifiantInstallationStable different) sous ce customerId. Le SecondaryServer
    // REVOQUE de l'ANCIENNE installation ne doit JAMAIS etre reactive par la decouverte de la NOUVELLE ;
    // la nouvelle doit obtenir SA PROPRE ligne.
    @Test
    void decouverte_sameCustomerIdDifferentIdentifiant_newInstallGetsOwnRowOldStaysRevoked() {
        enrolerPartenaireEtEtatLocal();
        LicenceServeur ancienneInstallation = creerLicenceServeurClient(891L, LicenceServeur.LicenceServeurStatut.ACTIF);
        LicenceDecouverteScheduler scheduler = nouveauScheduler();

        scheduler.decouvrir(); // 1er cycle : materialise la 1ere installation (ACTIF).
        SecondaryServer serveurAncien = trouverSecondaryServerParIdentifiant(ancienneInstallation.getIdentifiantInstallationStable());
        assertThat(serveurAncien).isNotNull();
        assertThat(serveurAncien.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);

        // L'ancienne machine est revoquee cote maitre (admin, meme comportement que
        // CompteLicenceAdminController#revokeLicenceServeur : notifiePartenaire remis a false).
        LicenceServeur ancienneARevoquer = licenceServeurRepository.findById(ancienneInstallation.getId()).orElseThrow();
        ancienneARevoquer.setStatut(LicenceServeur.LicenceServeurStatut.REVOQUE);
        ancienneARevoquer.setNotifiePartenaire(false);
        licenceServeurRepository.save(ancienneARevoquer);
        scheduler.decouvrir(); // 2e cycle : propage la revocation de l'ancienne.

        SecondaryServer serveurAncienApresRevocation =
                trouverSecondaryServerParIdentifiant(ancienneInstallation.getIdentifiantInstallationStable());
        assertThat(serveurAncienApresRevocation.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.REVOQUE);
        assertThat(serveurAncienApresRevocation.getId()).isEqualTo(serveurAncien.getId());

        // Machine de remplacement : MEME customerId (891L), NOUVEL identifiantInstallationStable,
        // nouveau LicenceServeur ACTIF (2e jeton genere par le partenaire pour ce client).
        LicenceServeur nouvelleInstallation = creerLicenceServeurClient(891L, LicenceServeur.LicenceServeurStatut.ACTIF);
        scheduler.decouvrir(); // 3e cycle : doit materialiser une NOUVELLE ligne, PAS reactiver l'ancienne.

        SecondaryServer serveurNouveau =
                trouverSecondaryServerParIdentifiant(nouvelleInstallation.getIdentifiantInstallationStable());
        assertThat(serveurNouveau).isNotNull();
        assertThat(serveurNouveau.getCustomerId()).isEqualTo(891L);
        assertThat(serveurNouveau.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);
        assertThat(serveurNouveau.getId()).isNotEqualTo(serveurAncien.getId()); // ligne DISTINCTE, pas reutilisee

        // L'ancienne ligne (REVOQUE) reste INCHANGEE : pas reactivee par la decouverte de la nouvelle.
        SecondaryServer serveurAncienFinal =
                trouverSecondaryServerParIdentifiant(ancienneInstallation.getIdentifiantInstallationStable());
        assertThat(serveurAncienFinal.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.REVOQUE);

        licenceServeurIdsACleaner.add(ancienneInstallation.getId());
        licenceServeurIdsACleaner.add(nouvelleInstallation.getId());
        secondaryServerIdsACleaner.add(serveurAncien.getId());
        secondaryServerIdsACleaner.add(serveurNouveau.getId());
    }

    // Fix round 2, Finding Critical : le repli customerId du fix round 1 (seulement "sans ambiguite")
    // ne suffisait pas — une ligne SANS identifiant ET REVOQUE peut venir d'ailleurs que d'un residu
    // pre-fix a revendiquer : ecran admin GestionServeursController (Task 5, cree+revoque des
    // SecondaryServer hors du flux de decouverte, ne renseigne jamais ce champ), ou une revocation
    // deja propagee AVANT ce fix (notifiePartenaire=true cote maitre -> ne reapparaitra plus jamais
    // dans /nouveaux-serveurs, donc jamais retro-remplie). Construite ICI DIRECTEMENT (pas via
    // materialiser(), qui stamperait toujours l'identifiant) pour simuler exactement ce cas.
    @Test
    void decouverte_onlyRevokedUnidentifiedCandidateForCustomerId_isNeverReactivated_newRowCreatedInstead() {
        enrolerPartenaireEtEtatLocal();
        SecondaryServer ligneAdminRevoquee = new SecondaryServer();
        ligneAdminRevoquee.setNom("Cree par un admin, puis revoque");
        ligneAdminRevoquee.setRole(ServerRole.CLIENT);
        ligneAdminRevoquee.setCustomerId(892L);
        ligneAdminRevoquee.setStatut(SecondaryServer.SecondaryServerStatut.REVOQUE);
        ligneAdminRevoquee.setEnrollmentCode("code-admin-jamais-touche");
        // identifiantInstallationStable volontairement JAMAIS renseigne (ni ici, ni par
        // GestionServeursController en pratique).
        ligneAdminRevoquee = secondaryServerRepository.save(ligneAdminRevoquee);

        LicenceServeur nouvelleInstallation = creerLicenceServeurClient(892L, LicenceServeur.LicenceServeurStatut.ACTIF);
        nouveauScheduler().decouvrir();

        // La ligne admin REVOQUE est INCHANGEE : pas reactivee, pas supprimee, meme code.
        SecondaryServer ligneAdminApres = secondaryServerRepository.findById(ligneAdminRevoquee.getId()).orElseThrow();
        assertThat(ligneAdminApres.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.REVOQUE);
        assertThat(ligneAdminApres.getEnrollmentCode()).isEqualTo("code-admin-jamais-touche");
        assertThat(ligneAdminApres.getIdentifiantInstallationStable()).isNull();

        // La nouvelle installation obtient sa PROPRE ligne, distincte, ACTIF, avec son identifiant.
        SecondaryServer serveurNouveau =
                trouverSecondaryServerParIdentifiant(nouvelleInstallation.getIdentifiantInstallationStable());
        assertThat(serveurNouveau).isNotNull();
        assertThat(serveurNouveau.getId()).isNotEqualTo(ligneAdminApres.getId());
        assertThat(serveurNouveau.getCustomerId()).isEqualTo(892L);
        assertThat(serveurNouveau.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);

        licenceServeurIdsACleaner.add(nouvelleInstallation.getId());
        secondaryServerIdsACleaner.add(ligneAdminApres.getId());
        secondaryServerIdsACleaner.add(serveurNouveau.getId());
    }

    @Test
    void decouverte_onlyRunsOnPartnerRole() {
        LicenceClient licenceClientMock = mock(LicenceClient.class);

        new LicenceDecouverteScheduler(licenceClientMock, secondaryServerRepository, role("CLIENT")).decouvrir();
        new LicenceDecouverteScheduler(licenceClientMock, secondaryServerRepository, role("MASTER")).decouvrir();

        verifyNoInteractions(licenceClientMock);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private LicenceDecouverteScheduler nouveauScheduler() {
        LicenceClient client = new LicenceClient("http://localhost:" + port, etatLicenceLocaleRepository, objectMapper);
        return new LicenceDecouverteScheduler(client, secondaryServerRepository, role("PARTNER"));
    }

    private void enrolerPartenaireEtEtatLocal() {
        compte = new CompteLicence();
        compte.setLogin("compte-licence-decouverte-" + UUID.randomUUID());
        compte.setMotDePasseHash("non-utilise-dans-ce-test");
        compte.setTypeAbonnement(CompteLicence.TypeAbonnement.ILLIMITE);
        compte.setFrequence(CompteLicence.Frequence.MENSUEL);
        compte.setNombreServeursAutorises(20);
        compte.setActif(true);
        compte = compteLicenceRepository.save(compte);

        String cleApiEnClair = LicenceCles.generate();
        partenaire = new LicenceServeur();
        partenaire.setCompteId(compte.getId());
        partenaire.setRole(ServerRole.PARTNER);
        partenaire.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        partenaire.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        partenaire.setCleApiHash(LicenceCles.hash(cleApiEnClair));
        partenaire = licenceServeurRepository.save(partenaire);

        etatLocal = new EtatLicenceLocale();
        etatLocal.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        etatLocal.setCleApiLicence(cleApiEnClair);
        etatLocal.setEnregistrementTermine(true);
        etatLocal = etatLicenceLocaleRepository.save(etatLocal);
    }

    private LicenceServeur creerLicenceServeurClient(Long customerId, LicenceServeur.LicenceServeurStatut statut) {
        LicenceServeur client = new LicenceServeur();
        client.setCompteId(partenaire.getCompteId());
        client.setRole(ServerRole.CLIENT);
        client.setCustomerId(customerId);
        client.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        client.setStatut(statut);
        return licenceServeurRepository.save(client);
    }

    private SecondaryServer trouverSecondaryServer(Long customerId) {
        return secondaryServerRepository.findAll().stream()
                .filter(s -> s.getRole() == ServerRole.CLIENT && customerId.equals(s.getCustomerId()))
                .findFirst()
                .orElse(null);
    }

    /** Pour les scenarios a plusieurs {@code SecondaryServer} sous le MEME {@code customerId} (fix
     * round 1) : {@link #trouverSecondaryServer} serait ambigu, on cible par identifiant exact. */
    private SecondaryServer trouverSecondaryServerParIdentifiant(String identifiantInstallationStable) {
        return secondaryServerRepository.findAll().stream()
                .filter(s -> identifiantInstallationStable.equals(s.getIdentifiantInstallationStable()))
                .findFirst()
                .orElse(null);
    }

    private static ServerRoleProperties role(String role) {
        ServerRoleProperties properties = new ServerRoleProperties();
        properties.setRole(role);
        return properties;
    }
}
