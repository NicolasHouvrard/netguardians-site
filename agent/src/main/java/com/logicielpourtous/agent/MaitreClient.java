package com.logicielpourtous.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client HTTP sortant de l'Agent (Task 5) vers le MAITRE : lit la cle API licence de CETTE
 * installation depuis un fichier LOCAL (jamais une route HTTP — voir {@code
 * AgentCleApiFileWriter} de l'appli principale, qui ecrit ce fichier, et la javadoc de classe de
 * {@code DeploiementScheduler} cote Maitre), puis interroge {@code GET
 * /api/licence/ordre-deploiement} ({@code DeploiementController}, deja construit) authentifie
 * avec cette cle.
 *
 * <p><b>Pourquoi un fichier et pas un appel HTTP vers l'appli locale</b> : l'ancienne route
 * {@code GET /internal/agent/cle-api} a ete SUPPRIMEE pendant la revue de securite de Task 3 —
 * c'etait un vecteur SSRF/fuite de cle reel et exploitable une fois Nginx monte en frontal (Task
 * 8 de ce meme plan) sur {@code 127.0.0.1} : tout le trafic, meme externe, relaye par Nginx arrive
 * alors a l'appli comme venant du loopback, rendant une garde "remoteAddr == 127.0.0.1"
 * completement contournable. La cle n'est donc plus JAMAIS exposee sur le reseau, meme en
 * loopback — les deux processus (appli principale et cet Agent), qui tournent sur le MEME hote,
 * s'accordent sur un chemin de fichier partage via leur configuration respective (voir {@code
 * app.maitre.cle-api-file-path} ci-dessous et {@code app.agent.cle-api-file-path} cote appli
 * principale).
 *
 * <p><b>Fichier absent ou vide = PAS une erreur</b> (Review Focus du plan) : {@link
 * #recupererOrdre()} renvoie alors {@link Optional#empty()} immediatement, SANS lever
 * d'exception et SANS appeler le Maitre — c'est l'etat NORMAL d'un hote pas encore enrole (voir
 * aussi la javadoc de {@code AgentCleApiFileWriter}, qui ne cree jamais ce fichier tant que
 * l'enrolement n'est pas termine).
 *
 * <p>(De)serialisation JSON explicite via un {@link ObjectMapper} dedie (corps en {@code
 * String}), meme discipline que {@code LicenceClient}/{@code RestSyncClient} cote appli
 * principale — pas les convertisseurs implicites de {@code RestClient}.
 */
@Component
public class MaitreClient {

    private static final Logger log = LoggerFactory.getLogger(MaitreClient.class);

    private static final String ORDRE_DEPLOIEMENT_PATH = "/api/licence/ordre-deploiement";
    private static final String HEADER_CLE_API = "X-Licence-Api-Key";
    /** Une cle API reelle fait quelques dizaines de caracteres : au-dela, le fichier est suspect. */
    private static final long TAILLE_MAX_CLE_API = 4096;
    private static final Pattern FORMAT_CLE_API = Pattern.compile("^[A-Za-z0-9._~+/=-]{1,512}$");

    /** Securite scan, fix round 1 : sans delai de connexion dedie, un Maitre INJOIGNABLE
     * bloquerait indefiniment le thread de l'executor qui execute {@code
     * ReveilController.traiterReveil} (thread potentiellement partage avec d'autres travaux).
     * Meme valeur (3s) que {@code DeploiementScheduler.TIMEOUT_RESEAU} cote appli principale,
     * pour la MEME raison — un Maitre injoignable doit echouer VITE, peu importe la nature de
     * l'appel. */
    private static final Duration TIMEOUT_CONNEXION = Duration.ofSeconds(3);

    /** Securite scan, fix round 3 : delai de LECTURE volontairement plus genereux que {@link
     * #TIMEOUT_CONNEXION} (contrairement au reveil fire-and-forget cote Maitre, voir {@code
     * DeploiementScheduler}) — {@code DeploiementController.ordreDeploiement()} (Task 3, deja
     * livre) marque l'ordre comme CONSOMME pour ce serveur DANS LA MEME transaction qui renvoie la
     * reponse (lecture-puis-marque-puis-reponse, cote Maitre). Si un delai de lecture TROP COURT
     * expire alors que le Maitre a bien repondu (juste lentement) mais que l'Agent n'a jamais reçu
     * le corps, le Maitre a deja persiste "consomme" alors que CET agent n'a jamais vu la version —
     * le serveur manquerait alors SILENCIEUSEMENT ce deploiement jusqu'au PROCHAIN tag GitHub (le
     * Maitre ne re-propose jamais un ordre deja consomme). Corriger cette fenetre completement
     * demanderait un accuse de reception explicite cote Maitre (toucherait {@code
     * DeploiementController}, deja livre et deja revu 2 fois — hors perimetre de cette tache) ;
     * elargir CE SEUL delai de lecture (10s, pas 3s) reduit juste la PROBABILITE qu'une reponse
     * simplement lente (Maitre reellement vivant) soit prise a tort pour un echec — un cout nul
     * (cet appel tourne deja sur un thread d'executor dedie, jamais partage avec {@code
     * ReveilController.reveil()} qui a deja repondu 202, et n'a lieu qu'une fois par reveil). Le
     * delai de CONNEXION, lui, reste court ({@link #TIMEOUT_CONNEXION}) : un Maitre reellement
     * injoignable (pas seulement lent) doit toujours echouer vite. */
    private static final Duration TIMEOUT_LECTURE = Duration.ofSeconds(10);

    private final Path cheminCleApiLocale;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Constructeur Spring : {@code @Autowired} EXPLICITE (meme patron que {@code
     * DeploiementScheduler} cote appli principale) — avec 2 constructeurs sur cette classe (voir
     * le constructeur package-visible ci-dessous, reserve aux tests), Spring ne peut pas deviner
     * lequel utiliser sans cette annotation.
     */
    @Autowired
    public MaitreClient(@Value("${app.maitre.cle-api-file-path:}") String cheminCleApiLocale,
                         @Value("${app.maitre.url:}") String urlMaitre) {
        this(isBlank(cheminCleApiLocale) ? null : Path.of(cheminCleApiLocale), urlMaitre);
    }

    /** Visible pour les tests UNIQUEMENT (meme package) : accepte un {@link Path} deja resolu. */
    MaitreClient(Path cheminCleApiLocale, String urlMaitre) {
        this.cheminCleApiLocale = cheminCleApiLocale;
        this.restClient = RestClient.builder().baseUrl(urlMaitre)
                .requestFactory(construireRequestFactoryAvecTimeout())
                .build();
    }

    /**
     * Factory HTTP DEDIEE (securite scan, fix round 1 ; delais asymetriques depuis le fix round
     * 3) : connexion COURTE ({@link #TIMEOUT_CONNEXION}, un Maitre injoignable echoue vite) mais
     * lecture plus genereuse ({@link #TIMEOUT_LECTURE}, voir sa javadoc sur le risque de "lost
     * update" si ce delai coupe une reponse simplement lente d'un Maitre par ailleurs vivant) —
     * meme patron de base que {@code DeploiementScheduler.construireRequestFactoryAvecTimeout()}
     * cote appli principale, qui lui n'a pas besoin de cette asymetrie (reveil fire-and-forget,
     * aucun etat consequent en jeu). Package-visible (pas {@code private}) uniquement pour
     * permettre une verification unitaire directe de sa configuration (voir le test
     * correspondant) sans passer par un vrai appel reseau.
     */
    static JdkClientHttpRequestFactory construireRequestFactoryAvecTimeout() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT_CONNEXION).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIMEOUT_LECTURE);
        return factory;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Reponse du Maitre ({@code DeploiementController.OrdreDeploiementReponse}, meme forme). */
    private record OrdreDeploiementReponse(String version) {}

    /**
     * Lit la cle API licence locale (fichier, voir la javadoc de classe) ; si elle est absente ou
     * vide, renvoie {@link Optional#empty()} sans appeler le Maitre (etat normal, pas encore
     * enrole). Sinon, interroge le Maitre et renvoie la version a deployer, ou {@link
     * Optional#empty()} si aucun ordre n'est en attente.
     */
    public Optional<String> recupererOrdre() {
        String cleApiLicence = lireCleApiLocale();
        if (cleApiLicence == null) {
            return Optional.empty();
        }

        String reponse = restClient.get()
                .uri(ORDRE_DEPLOIEMENT_PATH)
                .header(HEADER_CLE_API, cleApiLicence)
                .retrieve()
                .body(String.class);
        OrdreDeploiementReponse ordre = read(reponse);
        return ordre == null ? Optional.empty() : Optional.ofNullable(ordre.version());
    }

    /**
     * Renvoie {@code null} (pas une exception) si le chemin n'est pas configure, si le fichier est
     * absent, illisible, ou contient uniquement des blancs — voir la javadoc de classe sur le
     * statut NORMAL de cet etat.
     */
    private String lireCleApiLocale() {
        if (cheminCleApiLocale == null || !Files.exists(cheminCleApiLocale, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            // Le fichier est ecrit par l'application (conteneur, utilisateur non privilegie) et lu ici
            // par l'Agent : il n'est donc PAS de confiance. Refus d'un lien symbolique ou d'un fichier
            // special (lecture en root d'un fichier arbitraire), d'une taille anormale, et de tout
            // contenu qui ne ressemble pas a une cle (le contenu part dans un en-tete HTTP : un saut
            // de ligne y serait une injection d'en-tete).
            BasicFileAttributes attributs = Files.readAttributes(cheminCleApiLocale,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributs.isRegularFile()) {
                log.warn("Agent : fichier de cle API locale {} ignore (n'est pas un fichier ordinaire)",
                        cheminCleApiLocale);
                return null;
            }
            // UNE SEULE ouverture, sans suivre de lien, puis lecture BORNEE sur ce descripteur : le
            // chemin n'est jamais re-resolu apres la verification (un lien pose entre-temps ferait
            // echouer l'ouverture), et la taille se controle sur ce qui est reellement lu.
            byte[] octets;
            try (InputStream flux = Files.newInputStream(cheminCleApiLocale, LinkOption.NOFOLLOW_LINKS)) {
                octets = flux.readNBytes((int) TAILLE_MAX_CLE_API + 1);
            }
            if (octets.length > TAILLE_MAX_CLE_API) {
                log.warn("Agent : fichier de cle API locale {} ignore (taille anormale)", cheminCleApiLocale);
                return null;
            }
            String contenu = new String(octets, StandardCharsets.UTF_8).trim();
            if (contenu.isEmpty()) {
                return null;
            }
            if (!FORMAT_CLE_API.matcher(contenu).matches()) {
                log.warn("Agent : contenu du fichier de cle API locale {} ignore (caracteres inattendus)",
                        cheminCleApiLocale);
                return null;
            }
            return contenu;
        } catch (IOException e) {
            log.warn("Agent : lecture du fichier de cle API locale {} impossible, nouvel essai au "
                    + "prochain reveil/sondage : {}", cheminCleApiLocale, e.toString());
            return null;
        }
    }

    private OrdreDeploiementReponse read(String json) {
        try {
            return objectMapper.readValue(json == null ? "null" : json, OrdreDeploiementReponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Reponse JSON du maitre illisible", e);
        }
    }
}
