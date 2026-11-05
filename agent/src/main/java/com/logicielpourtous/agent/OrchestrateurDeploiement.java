package com.logicielpourtous.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Orchestrateur du deploiement bleu-vert cote Agent (Task 9) : relie {@link MaitreClient} (Task
 * 5), {@link DockerControl} (Task 6), {@link ControleSante} (Task 7) et {@link BasculeNginx}
 * (Task 8) pour appliquer un ordre de deploiement recu du Maitre, SANS jamais interrompre le
 * trafic si la nouvelle version ne demarre pas correctement (garantie centrale du Review Focus du
 * plan).
 *
 * <p><b>Deroule de {@link #traiterReveil()}</b> : lit D'ABORD le port REELLEMENT actif ({@link
 * BasculeNginx#portActifActuel()}, voir ci-dessous — securite scan, fix round 3, finding B : AVANT
 * {@link MaitreClient#recupererOrdre()}, voir pourquoi plus bas) ; si {@link
 * MaitreClient#recupererOrdre()} ne renvoie aucune version, ne fait rien (etat normal). Sinon :
 * <ol>
 *   <li>en deduit le port INACTIF (entre {@link #PORT_BLEU}/{@link #PORT_VERT}) ;</li>
 *   <li>demarre l'image correspondante ({@code ghcr.io/<owner>/site:<tag>}, convention du plan)
 *       sur ce port inactif ;</li>
 *   <li>attend jusqu'a {@link #FENETRE} (30s, Global Constraints du plan) que cette nouvelle
 *       instance devienne saine ;</li>
 *   <li>si saine : bascule Nginx vers ce port, puis arrete l'ancienne instance (par son nom
 *       deterministe, voir ci-dessous) ;</li>
 *   <li>si PAS saine dans la fenetre : arrete la nouvelle instance qui vient d'etre demarree,
 *       n'appelle JAMAIS {@link BasculeNginx#basculerVers(int)} — l'ancienne version continue de
 *       repondre sans interruption.</li>
 * </ol>
 *
 * <p><b>Aucun etat en memoire (securite scan, fix round 2, finding 1 — CRITIQUE)</b> : les
 * versions precedentes de cette classe gardaient "le port actif" et "l'id de l'ancienne instance"
 * dans des champs en memoire. La revue finale a demontre, par un cheminement concret et
 * deterministe, que ce choix coinçait DEFINITIVEMENT tout nœud apres son premier deploiement
 * reussi : au tout premier cycle, rien n'est connu sur l'AUTRE port (celui qui n'est pas la cible
 * de ce cycle) — un conteneur peut y tourner sans que l'Agent le sache (demarre hors de sa
 * connaissance, ex. par {@code compose.yaml}) — donc il n'etait jamais arrete ; au cycle SUIVANT,
 * {@code docker run} sur ce port (desormais cru "inactif") echouait avec "port already allocated"
 * puisque ce conteneur non suivi l'occupait encore — et ce, a chaque cycle suivant, indefiniment.
 * Le meme probleme resurgissait apres un redemarrage de l'Agent (l'hypothese par defaut peut ne
 * pas correspondre a la realite) et apres un echec d'arret de l'ancienne instance (qui deplaçait
 * juste le coincement au cycle suivant plutot que de le resorber).
 *
 * <p>Plutot que de rapiecer ce cas par cas, Task 6 et Task 8 ont chacune ajoute un mecanisme qui
 * elimine la classe de bug entiere, et cette classe n'a plus BESOIN de retenir quoi que ce soit
 * entre deux appels a {@link #traiterReveil()} :
 * <ul>
 *   <li>{@link BasculeNginx#portActifActuel()} lit le port actif REEL depuis le fichier de config
 *       upstream (source de verite qui survit a un redemarrage de l'Agent), jamais une hypothese
 *       en memoire ;</li>
 *   <li>{@link DockerControl#demarrerVersion} nomme chaque conteneur de facon deterministe par
 *       port ({@code site-<port>}, voir {@link DockerControl#nomConteneur(int)}) et execute
 *       {@code docker rm -f} sur ce nom AVANT tout {@code docker run} — le port cible est donc
 *       TOUJOURS libre avant de l'utiliser, quel que soit ce qui l'occupait (un conteneur de
 *       l'Agent, un conteneur {@code compose.yaml}, ou rien) ;</li>
 *   <li>l'arret de l'ancienne instance utilise donc {@link DockerControl#nomConteneur(int)} sur le
 *       port qui ETAIT actif (lu au debut du cycle), jamais un id memorise.</li>
 * </ul>
 * Si {@link BasculeNginx#portActifActuel()} leve (config upstream corrompue/inattendue) : logue et
 * annule le cycle, jamais une exception qui remonte — meme philosophie que le reste de cette
 * classe.
 *
 * <p><b>Ordre des deux appels : etat local AVANT ordre du Maitre</b> (securite scan, fix round 3,
 * finding B) : {@link MaitreClient#recupererOrdre()} consomme l'ordre a USAGE UNIQUE du cote du
 * Maitre a l'instant meme ou il est servi (voir {@code DeploiementController}, appli principale) —
 * sans accuse de reception explicite (fonctionnalite plus large, hors perimetre de ce plan), un
 * ordre consomme ne sera JAMAIS reservi a ce serveur avant le prochain tag GitHub. Appeler {@link
 * BasculeNginx#portActifActuel()} EN PREMIER evite de bruler cet ordre pour un probleme qui n'a
 * RIEN A VOIR avec le deploiement lui-meme (un fichier de config local illisible/corrompu SUR CET
 * AGENT) : si l'etat local n'est meme pas lisible, inutile de consommer l'ordre, autant le laisser
 * disponible pour une tentative ulterieure (ex. apres correction manuelle du fichier, ou sur un
 * autre reveil une fois le probleme local resolu).
 *
 * <p><b>Echec de {@link DockerControl#demarrerVersion}</b> (Docker n'arrive meme pas a creer le
 * conteneur) : traite exactement comme un echec de controle de sante — logue, ne relance jamais
 * l'exception, n'atteint jamais le controle de sante ni {@link BasculeNginx}.
 *
 * <p><b>Echec de {@code basculerVers}</b> (securite scan, fix round 1, finding 1) : attrape
 * localement, PAS laisse remonter. Nginx n'ayant pas bascule, l'ancienne instance reste active et
 * inchangee ; la nouvelle instance, saine mais jamais atteinte par le trafic, est arretee
 * (elle-meme dans son propre try/catch, voir ci-dessous) pour ne pas fuiter un conteneur inutile.
 *
 * <p><b>Echec de {@code arreterConteneur} (l'ANCIENNE instance, apres une bascule REUSSIE)</b>
 * (securite scan, fix round 1, finding 1) : attrape localement, PAS laisse remonter — la bascule
 * elle-meme a reussi, donc Nginx pointe deja vers la nouvelle instance independamment du succes de
 * cet arret. Depuis la suppression de l'etat en memoire (fix round 2 ci-dessus), cet echec n'a
 * plus AUCUN effet sur les cycles suivants : {@link DockerControl#demarrerVersion} relira de toute
 * facon le port reel et supprimera ce qui s'y trouve avant de redemarrer. Seul un nettoyage manuel
 * de ce conteneur precis peut rester necessaire si l'arret echoue, jamais un blocage.
 *
 * <p><b>Validation de la version recue</b> (securite scan, fix round 1, finding 2) : {@code
 * version} vient du Maitre (donc, en pratique, d'un tag GitHub — moderement fiable, pas une entree
 * totalement adverse, mais pas garantie non plus) et sert a construire la reference d'image Docker
 * passee a {@link DockerControl#demarrerVersion}. Meme si cet appel passe par la liste d'arguments
 * d'un {@link ProcessBuilder} (pas une chaine shell — aucune injection shell possible), une version
 * inattendue (caracteres exotiques, longueur excessive) pourrait tout de meme produire une
 * reference d'image surprenante. {@link #VERSION_AUTORISEE} (ancre, charset restreint, longueur
 * bornee) est verifiee AVANT tout appel a {@link DockerControl#demarrerVersion} ; une version qui
 * ne correspond pas est loguee et le cycle annule, meme philosophie "jamais planter, sauter ce
 * cycle" que le reste de cette classe. Hors perimetre (volontairement) : validation HTTPS de l'URL
 * du Maitre, verification de signature/cosign de l'image — fonctionnalites plus larges, pas de ce
 * plan.
 *
 * <p><b>Bean Spring</b> : {@link BasculeNginx} (Task 8) n'est pas encore un bean Spring (pas de
 * {@code @Component}, chemin de fichier config non devinable sans configuration) — le constructeur
 * Spring de cette classe le construit lui-meme a partir du chemin configure et du {@link
 * CommandeExecuteur} (bean existant, Task 6), meme discipline que le double constructeur public
 * {@code @Autowired}/package-visible de {@link MaitreClient} (un pour Spring, un pour les tests qui
 * injecte directement un {@link BasculeNginx} mocke).
 */
@Component
public class OrchestrateurDeploiement {

    private static final Logger log = LoggerFactory.getLogger(OrchestrateurDeploiement.class);

    /** Valeurs par defaut historiques des ports bleu/vert (Global Constraints du plan). Rendues
     * configurables (paquet Synology, Task 1) via {@code app.deploiement.port-bleu}/{@code
     * app.deploiement.port-vert} pour eviter toute collision avec d'autres services sur un NAS
     * (ex. 18080/18081) ; ces constantes ne servent plus qu'a fournir ces memes valeurs par
     * defaut aux constructeurs de test qui ne les precisent pas (aucune regression). */
    static final int PORT_BLEU = 8080;
    static final int PORT_VERT = 8081;

    /** Fenetre de controle de sante avant abandon (Global Constraints du plan) : constante ICI,
     * pas injectee depuis ailleurs. */
    static final Duration FENETRE = Duration.ofSeconds(30);

    private static final String IMAGE_DOCKER_TEMPLATE = "ghcr.io/%s/site:%s";

    /** Securite scan, fix round 1, finding 2 : charset restreint (alphanumerique, {@code . _ -}),
     * doit commencer par un caractere alphanumerique, longueur bornee (128) — rejette toute
     * version contenant un {@code /}, des espaces, des caracteres de controle, etc. avant qu'elle
     * ne serve a construire une reference d'image Docker. Voir la javadoc de classe. */
    private static final Pattern VERSION_AUTORISEE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final MaitreClient maitreClient;
    private final DockerControl dockerControl;
    private final ControleSante controleSante;
    private final BasculeNginx basculeNginx;
    private final String dockerImageOwner;
    private final int portBleu;
    private final int portVert;

    /**
     * Constructeur Spring : construit lui-meme le {@link BasculeNginx} (pas encore un bean, voir
     * javadoc de classe) a partir du chemin de config upstream configure et du {@link
     * CommandeExecuteur} existant (Task 6).
     *
     * <p>{@code app.deploiement.docker-image-owner} : proprietaire GHCR utilise pour construire
     * l'image a deployer ({@code ghcr.io/<owner>/site:<tag>}, convention du plan). Aucune valeur
     * par defaut devinee (meme discipline que {@code app.deploiement.github-repo} de l'appli
     * principale, {@code GitHubReleaseClient}, pour la meme raison : deviner un proprietaire par
     * defaut risquerait de pointer vers l'image de quelqu'un d'autre silencieusement).
     *
     * <p>{@code app.deploiement.port-bleu}/{@code app.deploiement.port-vert} (paquet Synology,
     * Task 1) : ports bleu/vert, par defaut {@value #PORT_BLEU}/{@value #PORT_VERT} si absents
     * (aucune regression sur le comportement deja livre) — le paquet Synology les redefinit en
     * 18080/18081 pour eviter toute collision avec d'autres services pouvant deja tourner sur un
     * NAS. Le raisonnement (port inactif = "l'autre" port configure) ne change pas, seule la
     * lecture de ces deux valeurs change.
     */
    @Autowired
    public OrchestrateurDeploiement(MaitreClient maitreClient,
                                     DockerControl dockerControl,
                                     ControleSante controleSante,
                                     CommandeExecuteur executeur,
                                     @Value("${app.deploiement.docker-image-owner:}") String dockerImageOwner,
                                     @Value("${app.nginx.fichier-config-upstream:}") String cheminFichierConfigNginx,
                                     @Value("${app.deploiement.port-bleu:8080}") int portBleu,
                                     @Value("${app.deploiement.port-vert:8081}") int portVert) {
        this(maitreClient, dockerControl, controleSante,
                new BasculeNginx(Path.of(blankToEmpty(cheminFichierConfigNginx)), executeur),
                dockerImageOwner, portBleu, portVert);
    }

    /** Visible pour les tests UNIQUEMENT (meme package) : surcharge historique a 5 arguments,
     * conservee pour ne pas casser les tests existants qui ne precisent pas les ports — delegue
     * aux valeurs par defaut {@link #PORT_BLEU}/{@link #PORT_VERT} (paquet Synology, Task 1). */
    OrchestrateurDeploiement(MaitreClient maitreClient, DockerControl dockerControl,
                             ControleSante controleSante, BasculeNginx basculeNginx,
                             String dockerImageOwner) {
        this(maitreClient, dockerControl, controleSante, basculeNginx, dockerImageOwner,
                PORT_BLEU, PORT_VERT);
    }

    /** Visible pour les tests UNIQUEMENT (meme package) : plus aucun etat initial a fournir
     * (securite scan, fix round 2) — {@link BasculeNginx#portActifActuel()} et les noms de
     * conteneurs deterministes de {@link DockerControl} remplacent tout etat en memoire. Ports
     * bleu/vert configurables (paquet Synology, Task 1) pour les tests qui veulent verifier un
     * comportement avec des valeurs non standard. */
    OrchestrateurDeploiement(MaitreClient maitreClient, DockerControl dockerControl,
                             ControleSante controleSante, BasculeNginx basculeNginx,
                             String dockerImageOwner, int portBleu, int portVert) {
        this.maitreClient = maitreClient;
        this.dockerControl = dockerControl;
        this.controleSante = controleSante;
        this.basculeNginx = basculeNginx;
        this.dockerImageOwner = dockerImageOwner;
        this.portBleu = portBleu;
        this.portVert = portVert;
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Appelee par {@link ReveilController} a chaque {@code POST /reveil} (voir sa propre javadoc
     * sur la soumission asynchrone, le dedoublonnage, et la relance coalescee d'un reveil arrive
     * pendant un cycle deja en cours). Voir le deroule complet dans la javadoc de classe.
     */
    void traiterReveil() {
        // Securite scan, fix round 3, finding B : lu AVANT d'appeler recupererOrdre() — ce dernier
        // consomme l'ordre a usage unique DU COTE DU MAITRE des l'instant ou il est servi. Si l'etat
        // local (purement un probleme DE CET AGENT, sans rapport avec le deploiement lui-meme)
        // n'est meme pas lisible, inutile de bruler cet ordre pour rien : voir la javadoc de classe.
        int portActifActuel;
        try {
            portActifActuel = basculeNginx.portActifActuel();
        } catch (RuntimeException e) {
            // Securite scan, fix round 2 : config upstream corrompue/inattendue — jamais une
            // hypothese devinee, voir la javadoc de classe.
            log.warn("Agent : lecture du port actif Nginx impossible, annulation (ordre du Maitre "
                    + "PAS consomme) : {}", e.toString());
            return;
        }

        Optional<String> version = maitreClient.recupererOrdre();
        if (version.isEmpty()) {
            log.debug("Agent : aucun ordre de deploiement en attente, rien a faire");
            return;
        }

        String versionRecue = version.get();
        if (!VERSION_AUTORISEE.matcher(versionRecue).matches()) {
            // Securite scan, fix round 1, finding 2 : jamais utilisee pour construire une
            // reference d'image Docker si elle ne respecte pas le charset/longueur attendus, voir
            // la javadoc de classe. demarrerVersion n'est jamais appelee dans ce cas.
            log.warn("Agent : version recue du Maitre invalide ({}), annulation — jamais utilisee "
                    + "dans une commande Docker", versionRecue);
            return;
        }

        int portInactif = (portActifActuel == portBleu) ? portVert : portBleu;
        String image = IMAGE_DOCKER_TEMPLATE.formatted(dockerImageOwner, versionRecue);

        String idNouvelleVersion;
        try {
            idNouvelleVersion = dockerControl.demarrerVersion(image, portInactif);
        } catch (RuntimeException e) {
            // Docker n'a meme pas reussi a creer le conteneur : traite comme un echec de sante
            // (voir la javadoc de classe). L'ancienne version n'est jamais touchee.
            log.warn("Agent : demarrage de la nouvelle version ({}) sur le port {} impossible, "
                    + "annulation : {}", image, portInactif, e.toString());
            return;
        }

        boolean sain = controleSante.attendreSante(portInactif, FENETRE);
        if (!sain) {
            dockerControl.arreterConteneur(idNouvelleVersion);
            log.warn("Agent : controle de sante en echec pour la version {} (port {}, fenetre {}), "
                    + "annulation — Nginx n'est jamais touche, l'ancienne version continue de repondre",
                    versionRecue, portInactif, FENETRE);
            return;
        }

        try {
            basculeNginx.basculerVers(portInactif);
        } catch (RuntimeException e) {
            // Securite scan, fix round 1, finding 1 : attrapee ICI (pas laissee remonter) — Nginx
            // n'a pas bascule, l'ancienne instance reste active et inchangee. La nouvelle instance,
            // saine mais jamais atteinte par le trafic, est arretee pour ne pas la fuiter ; un
            // echec de CET arret est lui-meme avale (deja orpheline de toute facon).
            log.warn("Agent : bascule Nginx vers le port {} en echec, l'ancienne instance (port {}) "
                    + "reste active — le conteneur {} (sain mais jamais atteint par le trafic) va "
                    + "etre arrete : {}", portInactif, portActifActuel, idNouvelleVersion, e.toString());
            try {
                dockerControl.arreterConteneur(idNouvelleVersion);
            } catch (RuntimeException e2) {
                log.warn("Agent : arret du conteneur orphelin {} (bascule Nginx en echec) lui-meme "
                        + "en echec, nettoyage manuel necessaire : {}", idNouvelleVersion, e2.toString());
            }
            return;
        }

        log.info("Agent : bascule reussie vers la version {} (port {})", versionRecue, portInactif);

        // Securite scan, fix round 2 : arret de l'ANCIENNE instance par son nom DETERMINISTE (le
        // port qui etait actif en debut de cycle), jamais un id memorise — voir la javadoc de
        // classe. Best-effort : la bascule a deja reussi, un echec ici n'a plus aucun effet sur les
        // cycles suivants (demarrerVersion supprime de toute facon ce qui occupe un port avant de
        // redemarrer dessus).
        try {
            dockerControl.arreterConteneur(DockerControl.nomConteneur(portActifActuel));
        } catch (RuntimeException e) {
            log.warn("Agent : l'ancienne instance (port {}, conteneur {}) n'a pas pu etre arretee, "
                    + "nettoyage manuel peut-etre necessaire — la bascule elle-meme a reussi : {}",
                    portActifActuel, DockerControl.nomConteneur(portActifActuel), e.toString());
        }
    }
}
