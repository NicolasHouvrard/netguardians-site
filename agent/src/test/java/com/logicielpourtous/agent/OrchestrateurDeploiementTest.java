package com.logicielpourtous.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Verifie {@link OrchestrateurDeploiement#traiterReveil()} (Task 9) : le chemin du succes (bascule
 * Nginx puis arret de l'ancienne instance) et le chemin de l'echec/annulation (arret de la
 * nouvelle instance seule, Nginx jamais touche) — les 2 tests du brief — plus les cas limites
 * explicitement laisses au jugement de l'implementeur, les findings du security scan (fix round
 * 1 : commit d'etat/validation de version) et, surtout, ceux du fix round 2 (CRITIQUE) : cette
 * classe ne garde plus AUCUN etat en memoire entre deux cycles — {@link
 * BasculeNginx#portActifActuel()} est relu a chaque cycle (jamais mis en cache) et l'ancienne
 * instance est arretee par son nom DETERMINISTE ({@link DockerControl#nomConteneur(int)}), jamais
 * un id memorise — ce qui elimine entierement la classe de bug "le noeud se coince apres son
 * premier deploiement reussi" identifiee par la revue finale.
 *
 * <p>Instanciation directe avec les 4 dependances mockees (meme discipline que {@link
 * DockerControlTest}/{@link ControleSanteTest}/{@link BasculeNginxTest}) : pas de contexte Spring.
 * Un seul constructeur package-visible (5 arguments) suffit desormais — plus besoin de fournir un
 * etat initial, voir la javadoc de classe de {@link OrchestrateurDeploiement}.
 */
class OrchestrateurDeploiementTest {

    private static final Duration FENETRE = Duration.ofSeconds(30);
    private static final String OWNER = "owner";

    private final MaitreClient maitreClient = mock(MaitreClient.class);
    private final DockerControl dockerControl = mock(DockerControl.class);
    private final ControleSante controleSante = mock(ControleSante.class);
    private final BasculeNginx basculeNginx = mock(BasculeNginx.class);
    private final OrchestrateurDeploiement orchestrateur = new OrchestrateurDeploiement(
            maitreClient, dockerControl, controleSante, basculeNginx, OWNER);

    @Test
    void basculeVersLaNouvelleVersionQuandLeControleDeSanteReussit() {
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081)).thenReturn("abc123");
        when(controleSante.attendreSante(8081, FENETRE)).thenReturn(true);

        orchestrateur.traiterReveil();

        verify(basculeNginx).basculerVers(8081);
        // L'ancienne instance est arretee par son nom DETERMINISTE (le port qui etait actif),
        // jamais un id memorise : "site-8080".
        verify(dockerControl).arreterConteneur(DockerControl.nomConteneur(8080));
    }

    @Test
    void annuleSansToucherNginxQuandLeControleDeSanteEchoue() {
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion(any(), eq(8081))).thenReturn("abc123");
        when(controleSante.attendreSante(8081, FENETRE)).thenReturn(false);

        orchestrateur.traiterReveil();

        verify(dockerControl).arreterConteneur("abc123");
        verify(basculeNginx, never()).basculerVers(anyInt());
        // L'ancienne version (encore active) ne doit JAMAIS etre touchee sur le chemin d'echec.
        verify(dockerControl, never()).arreterConteneur(DockerControl.nomConteneur(8080));
    }

    @Test
    void neFaitRienQuandAucunOrdreNEstEnAttente() {
        // Securite scan, fix round 3, finding B : portActifActuel() est lu AVANT recupererOrdre()
        // (voir la javadoc de classe) — il est donc normal qu'il soit invoque meme quand aucun
        // ordre n'est en attente ; seul basculerVers() (et tout Docker) ne doit jamais etre atteint.
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.empty());

        orchestrateur.traiterReveil();

        verifyNoInteractions(dockerControl, controleSante);
        verify(basculeNginx, never()).basculerVers(anyInt());
    }

    // Cas limite laisse au jugement de l'implementeur (brief) : si demarrerVersion lui-meme leve
    // (Docker n'arrive meme pas a creer le conteneur), c'est traite comme un echec de controle de
    // sante — logue, jamais une exception qui remonte, Nginx et le controle de sante ne sont
    // jamais atteints, l'ancienne version n'est jamais touchee.
    @Test
    void annuleSansCrasherQuandLeDemarrageDockerLuiMemeEchoue() {
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion(any(), eq(8081)))
                .thenThrow(new IllegalStateException("docker run a echoue"));

        orchestrateur.traiterReveil();

        verifyNoInteractions(controleSante);
        verify(basculeNginx, never()).basculerVers(anyInt());
        verify(dockerControl, never()).arreterConteneur(any());
    }

    // Securite scan, fix round 1, finding 2 : une version malformee (caracteres interdits) ne doit
    // jamais servir a construire une reference d'image Docker — demarrerVersion n'est jamais
    // appelee, et l'absence de validation ne doit jamais faire planter traiterReveil().
    @Test
    void annuleSansAppelerDockerQuandLaVersionEstMalformee() {
        // portActifActuel() est lu AVANT recupererOrdre() (fix round 3, finding B) : normal qu'il
        // soit invoque ici, seul basculerVers() ne doit jamais etre atteint.
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("../etc/passwd v1"));

        assertThatCode(orchestrateur::traiterReveil).doesNotThrowAnyException();

        verifyNoInteractions(dockerControl, controleSante);
        verify(basculeNginx, never()).basculerVers(anyInt());
    }

    // Securite scan, fix round 1, finding 1 : si la bascule Nginx elle-meme echoue, la nouvelle
    // instance (saine mais jamais atteinte par le trafic) doit etre arretee, l'ancienne instance
    // n'est jamais touchee, et l'exception ne doit jamais remonter hors de traiterReveil().
    @Test
    void arreteLaNouvelleInstanceSeuleQuandLaBasculeNginxEchoue() {
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081)).thenReturn("nouveau1");
        when(controleSante.attendreSante(8081, FENETRE)).thenReturn(true);
        doThrow(new IllegalStateException("nginx -s reload a echoue"))
                .when(basculeNginx).basculerVers(8081);

        assertThatCode(orchestrateur::traiterReveil).doesNotThrowAnyException();

        verify(dockerControl).arreterConteneur("nouveau1");
        verify(dockerControl, never()).arreterConteneur(DockerControl.nomConteneur(8080));
    }

    // Securite scan, fix round 2, finding 1 (CRITIQUE) : l'arret de l'ancienne instance par son nom
    // deterministe ne doit jamais faire planter le cycle, meme si ce nom ne correspond a AUCUN
    // conteneur reellement en cours (ex. tout premier cycle de l'Agent : le port actif par defaut
    // n'a jamais ete demarre PAR l'Agent sous ce nom). La bascule elle-meme doit rester un succes.
    @Test
    void neCrashePasQuandAucunConteneurNeTourneSousLeNomDeLAncienneInstance() {
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081)).thenReturn("nouveau1");
        when(controleSante.attendreSante(8081, FENETRE)).thenReturn(true);
        doThrow(new IllegalStateException("docker stop a echoue (No such container: site-8080)"))
                .when(dockerControl).arreterConteneur(DockerControl.nomConteneur(8080));

        assertThatCode(orchestrateur::traiterReveil).doesNotThrowAnyException();

        verify(basculeNginx).basculerVers(8081);
    }

    // Securite scan, fix round 2, finding 1 (CRITIQUE) : le port actif est relu A CHAQUE cycle
    // depuis BasculeNginx.portActifActuel() (source de verite), jamais memorise/mis en cache entre
    // deux appels a traiterReveil() — 2 cycles avec des valeurs DIFFERENTES de portActifActuel()
    // doivent chacun viser le port inactif CORRESPONDANT, pas un port reste en cache du cycle
    // precedent.
    @Test
    void relitLePortActifAChaqueCycleJamaisEnCache() {
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(basculeNginx.portActifActuel()).thenReturn(8080);
        when(dockerControl.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081)).thenReturn("nouveau1");
        when(controleSante.attendreSante(8081, FENETRE)).thenReturn(true);

        orchestrateur.traiterReveil();

        verify(basculeNginx).basculerVers(8081);
        verify(dockerControl).arreterConteneur(DockerControl.nomConteneur(8080));

        // Second cycle : portActifActuel() renvoie maintenant 8081 (la bascule precedente a
        // reellement eu lieu, cote Nginx) — un etat en memoire fige au cycle precedent viserait a
        // nouveau 8081 comme port INACTIF (faux) au lieu de 8080.
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v2.0.0"));
        when(basculeNginx.portActifActuel()).thenReturn(8081);
        when(dockerControl.demarrerVersion("ghcr.io/owner/site:v2.0.0", 8080)).thenReturn("nouveau2");
        when(controleSante.attendreSante(8080, FENETRE)).thenReturn(true);

        orchestrateur.traiterReveil();

        verify(dockerControl).demarrerVersion("ghcr.io/owner/site:v2.0.0", 8080);
        verify(basculeNginx).basculerVers(8080);
        verify(dockerControl).arreterConteneur(DockerControl.nomConteneur(8081));
    }

    // Securite scan, fix round 2, finding 1 (CRITIQUE) : si la lecture du port actif echoue (config
    // upstream corrompue/inattendue, IllegalStateException de BasculeNginx.portActifActuel()), le
    // cycle doit etre annule proprement — jamais une hypothese devinee, jamais une exception qui
    // remonte, et aucun appel Docker (le port inactif n'a jamais pu etre determine).
    //
    // Securite scan, fix round 3, finding B : et surtout, recupererOrdre() ne doit JAMAIS etre
    // appelee dans ce cas — elle consommerait l'ordre a usage unique DU COTE DU MAITRE pour un
    // probleme qui n'a rien a voir avec le deploiement (un fichier local illisible SUR CET AGENT),
    // sans aucune possibilite de retry avant le prochain tag GitHub. Aucun stub sur
    // maitreClient.recupererOrdre() ci-dessous : s'il etait jamais appele sans stub, Mockito
    // renverrait null (pas Optional.empty()), qui aurait fait planter traiterReveil() avec une
    // NullPointerException — cela aurait suffi a faire echouer ce test de toute facon, mais
    // l'assertion verifyNoInteractions(maitreClient) rend l'intention explicite.
    @Test
    void annuleSansCrasherEtNeConsommeJamaisLOrdreDuMaitreQuandLaLectureDuPortActifEchoue() {
        when(basculeNginx.portActifActuel())
                .thenThrow(new IllegalStateException("config upstream inattendue"));

        assertThatCode(orchestrateur::traiterReveil).doesNotThrowAnyException();

        verifyNoInteractions(maitreClient, dockerControl, controleSante);
        verify(basculeNginx, never()).basculerVers(anyInt());
    }

    // Paquet Synology (Task 1) : les ports bleu/vert doivent etre configurables (valeurs non
    // standard 18080/18081 pour eviter toute collision avec d'autres services sur un NAS), sans
    // changer le raisonnement (le port inactif reste "l'autre" port configure). Construit
    // l'orchestrateur avec le constructeur de test etendu (portBleu=18080, portVert=18081) et
    // verifie que demarrerVersion vise bien 18081 (le port inactif quand portActifActuel()
    // renvoie 18080), jamais la valeur par defaut 8081.
    @Test
    void utiliseLesPortsConfiguresAuLieuDesValeursParDefaut() {
        OrchestrateurDeploiement orchestrateurAvecPortsConfigures = new OrchestrateurDeploiement(
                maitreClient, dockerControl, controleSante, basculeNginx, OWNER, 18080, 18081);

        when(basculeNginx.portActifActuel()).thenReturn(18080);
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion("ghcr.io/owner/site:v1.2.3", 18081)).thenReturn("abc123");
        when(controleSante.attendreSante(18081, FENETRE)).thenReturn(true);

        orchestrateurAvecPortsConfigures.traiterReveil();

        verify(dockerControl).demarrerVersion("ghcr.io/owner/site:v1.2.3", 18081);
        verify(controleSante).attendreSante(18081, FENETRE);
        verify(basculeNginx).basculerVers(18081);
        verify(dockerControl, never()).demarrerVersion(any(), eq(8081));
    }
}
