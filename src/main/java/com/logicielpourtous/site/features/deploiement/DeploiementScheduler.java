package com.logicielpourtous.site.features.deploiement;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.licence.LicenceServeur;
import com.logicielpourtous.site.features.licence.LicenceServeurRepository;

/**
 * Cote MAITRE uniquement : sonde periodiquement GitHub ({@link GitHubReleaseClient#dernierTag()})
 * pour detecter une nouvelle version a deployer, et reveille l'Agent de deploiement (Task 5+) de
 * chaque {@link LicenceServeur} {@code ACTIF} ({@code POST .../reveil}, sans corps).
 *
 * <p><b>Garde de role</b> : {@code @Component} INCONDITIONNEL (pas de {@code
 * @ConditionalOnProperty}, contrairement a {@code LicenceDecouverteScheduler} qui n'existe que si
 * {@code app.licence.master-url} est configure) — ce bean n'a besoin d'aucune propriete dediee pour
 * exister (seul {@link GitHubReleaseClient} en a une, {@code app.deploiement.github-repo}, et reste
 * inconditionnel lui aussi pour la MEME raison, voir sa javadoc). Defense en profondeur a
 * l'execution : {@link #verifierNouvelleVersion()} ne fait rien si {@code app.role != MASTER}, meme
 * patron que {@code LicenceDecouverteScheduler#decouvrir}.
 *
 * <p><b>Idempotence</b> : pas de nouvelle table/colonne {@code DernierOrdreTraite} — la derniere
 * {@link OrdreDeploiement} creee ({@link OrdreDeploiementRepository#findTopByOrderByCreeADescIdDesc()},
 * Task 3) porte deja la derniere version traitee. Si le tag GitHub courant est IDENTIQUE a cette
 * version, rien n'est fait (pas de nouvel ordre, aucun agent recontacte) ; sinon {@link
 * #reveillerTousLesAgents(String)} cree le nouvel ordre PUIS reveille chaque serveur — dans cet
 * ordre, pour qu'un {@code DeploiementController} interroge entre-temps serve deja la bonne version
 * au premier Agent qui sonderait avant meme la fin de la boucle de reveil.
 *
 * <p><b>Reveil au mieux-effort</b> : {@link #reveillerTousLesAgents(String)} boucle sur {@link
 * LicenceServeurRepository#findByStatut}{@code (ACTIF)} ; chaque appel HTTP est dans son PROPRE
 * {@code try/catch} (de {@code Exception}, PAS seulement {@code RestClientException} — voir la
 * revue de Task 4 : une adresse malformee passee la validation construirait sinon une {@code
 * IllegalArgumentException} capable d'echapper a un catch trop etroit et d'avorter le reste de la
 * boucle) — un echec ne doit JAMAIS empecher les suivants d'etre contactes, meme patron que {@code
 * LicenceDecouverteScheduler#decouvrir} (isolation par entree). Un serveur sans {@code
 * adresseDeclaree} connue (jamais encore auto-detectee), ou dont l'adresse est refusee par {@link
 * #extraireHoteAutorise} (voir sa javadoc), est ignore de la meme facon (log, pas d'exception)
 * plutot que de tenter une URL invalide ou dangereuse.
 *
 * <p><b>URL de reveil</b> : {@code http://<hote>:<app.agent.port>/reveil}, construite via {@link
 * UriComponentsBuilder} (jamais par concatenation de chaines) a partir de l'hote renvoye par {@link
 * #extraireHoteAutorise} — un LITTERAL IPv4 nu, jamais un nom d'hote. {@code adresseDeclaree} peut
 * etre soit directement un tel litteral (cas par defaut, {@code
 * InetAddress.getLocalHost().getHostAddress()}, voir {@code LicenceBootstrap} — et la forme que
 * Headscale, mesh VPN prive de ce plan, attribue a chaque noeud), soit une URL COMPLETE ({@code
 * http(s)://hote:port}, forme que {@code app.licence.adresse-publique} documente pour l'usage SANS
 * RAPPORT {@code /api/sync/enroll} d'un chantier anterieur) — {@link #extraireHoteAutorise} en
 * extrait alors l'hote et IGNORE le port/schema de cette URL (toujours remplace par {@code
 * app.agent.port}, un protocole totalement distinct). Dans les deux cas, l'hote retenu doit rester
 * un litteral IPv4 valide une fois extrait ; un nom d'hote (dans l'une ou l'autre forme) est refuse.
 */
@Component
public class DeploiementScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeploiementScheduler.class);

    /** Signal fire-and-forget (voir le plan) : un agent injoignable/lent ne doit jamais retarder le
     * reveil des suivants du meme cycle — delais volontairement courts. */
    private static final Duration TIMEOUT_RESEAU = Duration.ofSeconds(3);

    /** Un hote/IP nu (voir {@link #adresseAutorisee}) ne contient jamais ces caracteres. */
    private static final Pattern CARACTERES_INTERDITS = Pattern.compile("[\\s/?#@]");

    /** IPv4 litteral uniquement (revue de Task 4, round 2) — voir la javadoc de {@link
     * #adresseAutorisee} pour le rationale complet (empeche toute resolution DNS reelle, donc tout
     * delai non borne, dans la validation elle-meme). */
    private static final Pattern IPV4_LITTERAL = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    private final LicenceServeurRepository licenceServeurRepository;
    private final OrdreDeploiementRepository ordreDeploiementRepository;
    private final GitHubReleaseClient gitHubReleaseClient;
    private final ServerRoleProperties serverRoleProperties;
    private final RestClient restClient;
    private final int agentPort;

    /**
     * Constructeur Spring : impose TOUJOURS sa propre {@link JdkClientHttpRequestFactory} a delais
     * courts (voir {@link #construireRequestFactoryAvecTimeout()}) sur le {@link RestClient.Builder}
     * injecte (bean {@code prototype} de {@code AppConfig}, jamais partage avec {@code
     * GitHubReleaseClient} ou un futur consommateur — voir la javadoc de {@code AppConfig}) — PAS le
     * bean partage lui-meme, qui reste sans timeout dedie pour les autres consommateurs.
     */
    @Autowired
    public DeploiementScheduler(LicenceServeurRepository licenceServeurRepository,
                                OrdreDeploiementRepository ordreDeploiementRepository,
                                GitHubReleaseClient gitHubReleaseClient,
                                ServerRoleProperties serverRoleProperties,
                                RestClient.Builder restClientBuilder,
                                @Value("${app.agent.port:8090}") int agentPort) {
        this(licenceServeurRepository, ordreDeploiementRepository, gitHubReleaseClient, serverRoleProperties,
                // Pas de baseUrl ici : chaque LicenceServeur a sa propre adresseDeclaree, l'URI
                // complete est fournie a chaque appel (voir reveiller).
                restClientBuilder.requestFactory(construireRequestFactoryAvecTimeout()).build(),
                agentPort);
    }

    /**
     * Visible pour les tests UNIQUEMENT (meme package) : accepte un {@link RestClient} DEJA
     * construit (typiquement lie a un {@code MockRestServiceServer}) plutot qu'un {@link
     * RestClient.Builder} — le constructeur public ci-dessus imposerait sinon sa PROPRE {@code
     * ClientHttpRequestFactory} (delais courts) sur le MEME {@code RestClient.Builder} qu'un test
     * aurait deja instrumente via {@code MockRestServiceServer.bindTo(builder)} : un {@code
     * RestClient.Builder} n'a qu'UNE SEULE {@code ClientHttpRequestFactory} a la fois, la derniere
     * posee gagne, donc appeler {@code .requestFactory(...)} APRES {@code bindTo(...)} casserait
     * l'interception du mock (la requete partirait reellement sur le reseau).
     */
    DeploiementScheduler(LicenceServeurRepository licenceServeurRepository,
                         OrdreDeploiementRepository ordreDeploiementRepository,
                         GitHubReleaseClient gitHubReleaseClient,
                         ServerRoleProperties serverRoleProperties,
                         RestClient restClient,
                         int agentPort) {
        this.licenceServeurRepository = licenceServeurRepository;
        this.ordreDeploiementRepository = ordreDeploiementRepository;
        this.gitHubReleaseClient = gitHubReleaseClient;
        this.serverRoleProperties = serverRoleProperties;
        this.restClient = restClient;
        this.agentPort = agentPort;
    }

    /**
     * Factory HTTP DEDIEE a ce scheduler (revue de Task 4, finding MEDIUM) : delais de connexion ET
     * de lecture courts ({@link #TIMEOUT_RESEAU}) — un reveil est un signal fire-and-forget, jamais
     * un appel longue duree, et un agent injoignable ne doit jamais bloquer le reveil des suivants.
     * Package-visible (pas {@code private}) uniquement pour permettre une verification unitaire
     * directe de sa configuration (voir le test correspondant) sans passer par un vrai appel reseau.
     */
    static JdkClientHttpRequestFactory construireRequestFactoryAvecTimeout() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT_RESEAU).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIMEOUT_RESEAU);
        return factory;
    }

    @Scheduled(fixedDelayString = "${app.deploiement.intervalle-verification-ms:300000}")
    public synchronized void verifierNouvelleVersion() {
        if (serverRoleProperties.getRole() != ServerRole.MASTER) {
            // Defense en profondeur : ce bean ne devrait meme pas exister pour un role incorrect
            // (voir la javadoc de classe) ; ce garde protege quand meme contre une mauvaise config.
            return;
        }
        try {
            String dernierTag = gitHubReleaseClient.dernierTag();
            if (dernierTag == null) {
                // GitHub pas configure, aucun tag encore publie, ou appel GitHub en echec (deja
                // logue par GitHubReleaseClient lui-meme) : rien a faire, nouvel essai au prochain
                // cycle.
                return;
            }
            String derniereVersionTraitee = ordreDeploiementRepository.findTopByOrderByCreeADescIdDesc()
                    .map(OrdreDeploiement::getVersion)
                    .orElse(null);
            if (dernierTag.equals(derniereVersionTraitee)) {
                // Deja traitee : pas de nouvel OrdreDeploiement, aucun agent recontacte.
                return;
            }
            log.info("Deploiement : nouvelle version detectee sur GitHub ({} -> {}), reveil de tous "
                    + "les agents enroles", derniereVersionTraitee, dernierTag);
            reveillerTousLesAgents(dernierTag);
        } catch (RuntimeException e) {
            log.error("Deploiement : cycle de verification de version interrompu, nouvel essai au "
                    + "prochain cycle : {}", e.toString());
        }
    }

    /**
     * Cree un nouvel {@link OrdreDeploiement} pour {@code version} puis reveille chaque {@link
     * LicenceServeur} {@code ACTIF} (voir la javadoc de classe pour l'ordre et l'isolation par
     * entree). Public et independant de {@link #verifierNouvelleVersion()} : appelable directement
     * (tests, futur declenchement administratif manuel).
     */
    public void reveillerTousLesAgents(String version) {
        OrdreDeploiement ordre = new OrdreDeploiement();
        ordre.setVersion(version);
        // Resultat de save() volontairement pas reaffecte a `ordre` : seule la version loguee ici
        // compte, et reaffecter exposerait ce code a un save() mocke renvoyant null (tests) alors
        // que l'entite passee en argument est deja celle persistee par un vrai JpaRepository.
        ordreDeploiementRepository.save(ordre);
        log.info("Deploiement : nouvel OrdreDeploiement enregistre pour la version {}", version);

        for (LicenceServeur serveur : licenceServeurRepository.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF)) {
            reveiller(serveur);
        }
    }

    private void reveiller(LicenceServeur serveur) {
        String adresse = serveur.getAdresseDeclaree();
        if (adresse == null || adresse.isBlank()) {
            log.warn("Deploiement : reveil ignore pour le serveur licence {} (aucune adresseDeclaree connue)",
                    serveur.getId());
            return;
        }
        String hote = extraireHoteAutorise(adresse);
        if (hote == null) {
            log.warn("Deploiement : reveil ignore pour le serveur licence {} : adresseDeclaree '{}' "
                    + "invalide ou non autorisee (protection SSRF — voir extraireHoteAutorise)",
                    serveur.getId(), adresse);
            return;
        }
        URI uri = UriComponentsBuilder.newInstance()
                .scheme("http").host(hote).port(agentPort).path("/reveil")
                .build().toUri();
        try {
            restClient.post().uri(uri).retrieve().toBodilessEntity();
            log.info("Deploiement : agent reveille pour le serveur licence {} ({})", serveur.getId(), uri);
        } catch (Exception e) {
            // Isolation par entree (voir la javadoc de classe) : un echec ici ne doit JAMAIS
            // empecher le reveil des serveurs suivants de ce cycle. Catch large deliberement (pas
            // seulement RestClientException) : voir la javadoc de classe.
            log.warn("Deploiement : reveil en echec pour le serveur licence {} ({}) : {}",
                    serveur.getId(), uri, e.toString());
        }
    }

    /**
     * Protection SSRF (revue de Task 4, finding Important confirme exploitable) : {@code
     * adresseDeclaree} est ecrite VERBATIM depuis le corps brut de {@code POST
     * /api/licence/enregistrer} (voir {@code LicenceEnrolementController}), jamais validee a
     * l'ecriture — n'importe quel appelant authentifie (identifiants ou jeton d'installation a usage
     * unique) peut y placer {@code 169.254.169.254} (metadonnees cloud), une adresse de loopback, ou
     * une chaine malformee ; le Maitre y POSTerait alors inconditionnellement. Validee ICI, au point
     * d'usage (PAS dans {@code LicenceEnrolementController}, module distinct d'un chantier anterieur
     * deja livre — durcir aussi l'ecriture serait de la defense en profondeur legitime mais hors
     * perimetre de cette tache). Renvoie l'hote nu AUTORISE (jamais l'adresse d'origine telle que
     * fournie) a utiliser pour construire l'URL de reveil, ou {@code null} si {@code adresse} doit
     * etre refusee.
     * <p><b>Round 3 (revue finale whole-branch, finding 2) — extraction prealable si une URL COMPLETE
     * est fournie</b> : {@code app.licence.adresse-publique} (propriete d'un chantier ANTERIEUR, sans
     * rapport avec celui-ci) documente explicitement accepter une URL COMPLETE ({@code
     * http(s)://hote:port}, pour le protocole DISTINCT {@code /api/sync/enroll}) comme valeur de
     * {@code adresseDeclaree} — un round precedent de CETTE revue rejetait alors a tort TOUTE une
     * telle valeur (silencieusement, aucun reveil jamais envoye a ce serveur). Si {@code adresse}
     * contient {@code "://"}, elle est d'abord parsee comme une {@link URI} et seul {@link
     * URI#getHost()} est retenu comme candidat a valider ci-dessous — le port/schema EVENTUELLEMENT
     * present dans cette URL est TOUJOURS IGNORE pour la construction de l'URL de reveil elle-meme
     * (qui utilise exclusivement {@code app.agent.port}, un protocole totalement distinct). Un
     * {@code URI.create} qui leve, ou un hote absent de l'URI, est traite comme toute autre
     * validation en echec (refus, pas d'exception). Si {@code adresse} ne contient PAS {@code
     * "://"}, elle est validee TELLE QUELLE, chemin inchange depuis le round precedent.
     * <p>Le candidat retenu (l'adresse elle-meme, ou l'hote extrait ci-dessus) doit ensuite :
     * <ol>
     *   <li>ne contenir aucun des caracteres {@code espace / ? # @} ni caractere de controle, et
     *       etre un LITTERAL IPv4 (regex {@link #IPV4_LITTERAL}) — AUCUN NOM D'HOTE ni litteral IPv6
     *       n'est accepte ;</li>
     *   <li>via {@link InetAddress#getByName}, n'appartenir a AUCUNE des categories structurellement
     *       illegitimes pour un pair reseau : loopback, link-local (couvre AUSSI {@code
     *       169.254.0.0/16} — donc deja le cas des metadonnees cloud), any-local, multicast.</li>
     * </ol>
     * <p><b>Round 2 — pourquoi un litteral IPv4 STRICT, pas juste "pas de nom d'hote evident"</b> :
     * {@link InetAddress#getByName} ne fait AUCUNE E/S reseau pour un litteral IP (analyse pure),
     * mais declenche une VRAIE resolution DNS, SANS DELAI BORNE, pour tout autre texte — et cet
     * appel a lieu AVANT le {@code try} de {@link #reveiller}, donc HORS de la factory a delais
     * courts de {@link #construireRequestFactoryAvecTimeout()} (qui ne couvre que l'appel HTTP qui
     * suit). Un DNS lent ou en trou noir bloquerait alors {@code reveiller} indefiniment pour cette
     * seule entree — et comme {@link #verifierNouvelleVersion()} est {@code @Scheduled} en {@code
     * fixedDelay} (delai mesure APRES la fin de l'execution precedente, pas un {@code fixedRate}),
     * un tel blocage retarderait TOUS les cycles suivants, pas seulement le reste des serveurs du
     * cycle en cours. Exiger un litteral IPv4 avant tout appel a {@code getByName} (qu'il vienne
     * directement de {@code adresse} ou de l'hote extrait d'une URL complete) rend cet appel
     * INSTANTANE par construction, quelle que soit l'infrastructure DNS disponible.
     * <p>Les plages PRIVEES (RFC1918/CGNAT, ex. {@code 100.64.0.0/10}) sont DELIBEREMENT PAS
     * rejetees : ce plan relie le Maitre a chaque pair via un mesh VPN prive (Headscale), qui
     * attribue precisement ce genre d'adresses IP a chaque noeud enrole — c'est la forme REELLEMENT
     * attendue de {@code adresseDeclaree} une fois cablé (Task 5+) ; un rejet generique des plages
     * privees casserait ce cas legitime, pas seulement les payloads d'attaque.
     */
    private static String extraireHoteAutorise(String adresse) {
        String candidat = adresse;
        if (adresse.contains("://")) {
            URI uri;
            try {
                uri = URI.create(adresse);
            } catch (IllegalArgumentException e) {
                return null;
            }
            candidat = uri.getHost();
            if (candidat == null) {
                return null;
            }
        }
        if (CARACTERES_INTERDITS.matcher(candidat).find()) {
            return null;
        }
        for (int i = 0; i < candidat.length(); i++) {
            if (Character.isISOControl(candidat.charAt(i))) {
                return null;
            }
        }
        if (!IPV4_LITTERAL.matcher(candidat).matches()) {
            // Round 2 : aucun nom d'hote (resolution DNS non bornee) ni litteral IPv6 — voir la
            // javadoc. getByName() ci-dessous devient alors purement une analyse, jamais une E/S.
            return null;
        }
        try {
            InetAddress resolue = InetAddress.getByName(candidat);
            if (resolue.isLoopbackAddress() || resolue.isLinkLocalAddress()
                    || resolue.isAnyLocalAddress() || resolue.isMulticastAddress()) {
                return null;
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return candidat;
    }
}
