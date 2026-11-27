package com.logicielpourtous.site.features.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.logicielpourtous.site.features.commun.ChiffrementSecrets;
import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;

/**
 * Reglages du compte unique de CE serveur : la valeur en base prime, sinon la variable d'environnement historique
 * (memes noms qu'avant), sinon vide. Le secret est chiffre en base (AES-256-GCM, {@link ChiffrementSecrets}) et
 * n'apparait dans aucun message d'erreur ni {@code toString} ; un secret illisible est traite comme vide.
 * <p>
 * Domaine parent : calcule en retirant le premier label de l'hote de l'emetteur. Simple controle de coherence d'une
 * saisie d'administrateur, PAS une frontiere de securite : avec un suffixe public (ex. emetteur {@code auth.co.uk},
 * parent {@code co.uk}) tout {@code *.co.uk} serait accepte.
 * <p>
 * Un instantane est mis en cache, invalide APRES le commit de chaque ecriture et protege par un compteur de generation
 * (un lecteur parti avant l'ecriture ne republie pas de valeurs perimees) ; {@link #version()} change a chaque ecriture,
 * ce qui permet aux consommateurs de se reconstruire sans redemarrage. Les ecritures sont serialisees par un verrou
 * pessimiste sur la ligne unique (version strictement croissante).
 */
@Service
public class ConfigurationCompteUniqueImpl implements ConfigurationCompteUnique {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationCompteUniqueImpl.class);

    static final String P_ISSUER = "spring.security.oauth2.client.provider.authelia.issuer-uri";
    static final String P_CLIENT_ID = "spring.security.oauth2.client.registration.authelia.client-id";
    static final String P_CLIENT_SECRET = "spring.security.oauth2.client.registration.authelia.client-secret";
    static final String P_MAITRE = "app.auth.authelia.maitre-url-publique";
    static final String P_RETOUR = "app.auth.authelia.retour-deconnexion";

    private static final int MAX_ADRESSE = 300;
    private static final int MAX_RETOUR = 500;
    private static final int MAX_IDENTIFIANT = 200;
    private static final int MAX_SECRET = 500;
    private static final String SUFFIXE_RETOUR = "/login?logout";

    /**
     * Valeurs saisies a l'enregistrement, champ par champ : {@code null} = inchange, chaine vide = efface la valeur en
     * base (l'environnement reprend la main). Le secret est en ecriture seule : {@code null} ou vide = inchange (pour
     * l'effacer : {@link #effacerSecret}). Si l'emetteur ET l'identifiant sont tous deux effaces, le secret enregistre
     * est efface aussi (sinon : couple incoherent, secret de la base avec emetteur/identifiant de l'environnement).
     */
    public record ReglagesModifies(String issuerUri, String clientId, String nouveauSecret, String urlPublique,
            String maitreUrlPublique, String retourDeconnexion) {

        @Override
        public String toString() {
            return "ReglagesModifies[secret="
                    + (nouveauSecret == null || nouveauSecret.isBlank() ? "non fourni" : "fourni") + "]";
        }
    }

    /** Valeurs BRUTES de la base (sans le secret : seulement s'il est defini) ; chaine vide = pas de valeur en base. */
    public record ReglagesEnBase(String issuerUri, String clientId, boolean secretDefini, String urlPublique,
            String maitreUrlPublique, String retourDeconnexion, long version, String modifiePar, Instant modifieA) {
    }

    /** Valeurs effectives resolues ; le secret y est en clair, d'ou le toString neutre. */
    private record Instantane(long version, String issuer, String clientId, String secret, String urlPublique,
            String maitre, String retour) {

        @Override
        public String toString() {
            return "Instantane[version=" + version + "]";
        }
    }

    private final ReglagesCompteUniqueRepository repository;
    private final Environment env;
    private final ChiffrementSecrets chiffreur;
    private final AtomicLong generation = new AtomicLong();
    private volatile Instantane cache;

    public ConfigurationCompteUniqueImpl(ReglagesCompteUniqueRepository repository, Environment env,
            ChiffrementSecrets chiffreur) {
        this.repository = repository;
        this.env = env;
        this.chiffreur = chiffreur;
    }

    // --- Lecture ---

    @Override
    public boolean configure() {
        Instantane i = instantane();
        return !i.issuer().isEmpty() && !i.clientId().isEmpty() && !i.secret().isEmpty();
    }

    @Override
    public String issuerUri() {
        return instantane().issuer();
    }

    @Override
    public String clientId() {
        return instantane().clientId();
    }

    @Override
    public String clientSecret() {
        return instantane().secret();
    }

    @Override
    public String urlPublique() {
        return instantane().urlPublique();
    }

    @Override
    public String retourDeconnexion() {
        return instantane().retour();
    }

    @Override
    public String maitreUrlPublique() {
        return instantane().maitre();
    }

    @Override
    public long version() {
        return instantane().version();
    }

    /** Oublie l'instantane (relu a la prochaine lecture) et empeche de publier toute lecture deja en cours. */
    public synchronized void invalider() {
        generation.incrementAndGet();
        cache = null;
    }

    @Override
    public String toString() {
        return "ConfigurationCompteUniqueImpl";
    }

    private Instantane instantane() {
        Instantane c = cache;
        if (c == null) {
            long g = generation.get();
            c = construire();
            synchronized (this) {
                // Une ecriture survenue pendant la lecture rend ce resultat peut-etre perime : on ne le publie pas.
                if (generation.get() == g) {
                    cache = c;
                }
            }
        }
        return c;
    }

    private Instantane construire() {
        ReglagesCompteUnique r = repository.findById(ReglagesCompteUnique.ID_UNIQUE).orElse(null);
        String issuer = choisir(r == null ? null : r.getIssuerUri(), P_ISSUER);
        String clientId = choisir(r == null ? null : r.getClientId(), P_CLIENT_ID);
        String secret;
        if (r != null && notBlank(r.getClientSecret())) {
            try {
                secret = chiffreur.dechiffrer(r.getClientSecret());
            } catch (RuntimeException e) {
                LOG.warn("Compte unique : le secret enregistre est illisible (cle changee ou donnee alteree) ; "
                        + "il est ignore et le compte unique est considere comme non configure.");
                secret = "";
            }
        } else {
            secret = propriete(P_CLIENT_SECRET);
        }
        String url = r == null || !notBlank(r.getUrlPublique()) ? "" : r.getUrlPublique().trim();
        String maitre = choisir(r == null ? null : r.getMaitreUrlPublique(), P_MAITRE);
        String explicite = choisir(r == null ? null : r.getRetourDeconnexion(), P_RETOUR);
        String retour = "";
        if (adresseUtilisable(explicite, issuer)) {
            retour = explicite;
        } else if (adresseUtilisable(url, issuer)) {
            retour = sansBarreFinale(url) + SUFFIXE_RETOUR;
        }
        return new Instantane(r == null ? 0L : r.getVersion(), issuer, clientId, secret, url, maitre, retour);
    }

    private String choisir(String enBase, String cleEnvironnement) {
        return notBlank(enBase) ? enBase.trim() : propriete(cleEnvironnement);
    }

    private String propriete(String cle) {
        String v = env.getProperty(cle);
        return v == null ? "" : v.trim();
    }

    // --- Lecture brute (ecran d'administration) ---

    /** Valeurs de la base, sans le secret. */
    @Transactional(readOnly = true)
    public ReglagesEnBase lireReglagesEnBase() {
        ReglagesCompteUnique r = repository.findById(ReglagesCompteUnique.ID_UNIQUE).orElse(null);
        if (r == null) {
            return new ReglagesEnBase("", "", false, "", "", "", 0L, "", null);
        }
        return new ReglagesEnBase(nn(r.getIssuerUri()), nn(r.getClientId()), notBlank(r.getClientSecret()),
                nn(r.getUrlPublique()), nn(r.getMaitreUrlPublique()), nn(r.getRetourDeconnexion()), r.getVersion(),
                nn(r.getModifiePar()), r.getModifieA());
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }

    // --- Ecriture ---

    /**
     * Valide, ecrit sous verrou et incremente la version. Une valeur invalide leve une
     * {@link IllegalArgumentException} au message fixe (jamais la valeur) AVANT toute ecriture. Semantique par champ :
     * voir {@link ReglagesModifies}.
     */
    @Transactional
    public void enregistrer(ReglagesModifies m, String auteur) {
        if (m == null) {
            throw new IllegalArgumentException("Reglages absents.");
        }
        ReglagesCompteUnique existante = repository.verrouiller().orElse(null);
        String issuer = fusion(m.issuerUri(), existante == null ? null : existante.getIssuerUri());
        String clientId = fusion(m.clientId(), existante == null ? null : existante.getClientId());
        String url = fusion(m.urlPublique(), existante == null ? null : existante.getUrlPublique());
        String maitre = fusion(m.maitreUrlPublique(), existante == null ? null : existante.getMaitreUrlPublique());
        String retour = fusion(m.retourDeconnexion(), existante == null ? null : existante.getRetourDeconnexion());
        String secret = m.nouveauSecret() == null || m.nouveauSecret().isBlank() ? null : m.nouveauSecret();

        longueur(issuer, MAX_ADRESSE, "L'adresse d'Authelia est trop longue.");
        longueur(url, MAX_ADRESSE, "L'adresse publique est trop longue.");
        longueur(maitre, MAX_ADRESSE, "L'adresse publique du Maitre est trop longue.");
        longueur(retour, MAX_RETOUR, "L'adresse de retour est trop longue.");
        longueur(clientId, MAX_IDENTIFIANT, "L'identifiant du client est trop long.");
        longueur(secret, MAX_SECRET, "Le secret du client est trop long.");

        if (issuer != null) {
            https(issuer, "L'adresse d'Authelia");
        }
        URI uriUrl = url == null ? null : https(url, "L'adresse publique");
        if (maitre != null) {
            https(maitre, "L'adresse publique du Maitre");
        }
        URI uriRetour = retour == null ? null : https(retour, "L'adresse de retour");

        if (uriUrl != null || uriRetour != null) {
            // Domaine parent calcule sur l'emetteur EFFECTIF apres modification (base, sinon environnement).
            String emetteur = issuer != null ? issuer : propriete(P_ISSUER);
            String parent = emetteur.isEmpty() ? null : domaineParent(hoteDe(emetteur));
            if (parent == null) {
                throw new IllegalArgumentException("Le domaine du portail Authelia ne permet pas de verifier "
                        + "l'adresse publique et l'adresse de retour.");
            }
            if (uriUrl != null && !dansDomaine(uriUrl.getHost(), parent)) {
                throw new IllegalArgumentException(
                        "L'adresse publique doit etre dans le domaine parent du portail Authelia.");
            }
            if (uriRetour != null && !dansDomaine(uriRetour.getHost(), parent)) {
                throw new IllegalArgumentException(
                        "L'adresse de retour doit etre dans le domaine parent du portail Authelia.");
            }
        }

        // Effacement couple : seulement si l'emetteur ou l'identifiant etaient definis EN BASE et que les deux sont vides.
        final boolean etaitDefiniEnBase = existante != null
                && (existante.getIssuerUri() != null || existante.getClientId() != null);
        final String fIssuer = issuer;
        final String fClientId = clientId;
        final String fUrl = url;
        final String fMaitre = maitre;
        final String fRetour = retour;
        final String secretChiffre = secret == null ? null : chiffreur.chiffrer(secret);
        modifier(r -> {
            r.setIssuerUri(fIssuer);
            r.setClientId(fClientId);
            r.setUrlPublique(fUrl);
            r.setMaitreUrlPublique(fMaitre);
            r.setRetourDeconnexion(fRetour);
            if (secretChiffre != null) {
                r.setClientSecret(secretChiffre);
            } else if (etaitDefiniEnBase && fIssuer == null && fClientId == null) {
                r.setClientSecret(null);
            }
        }, auteur);
    }

    /** Remplace SEULEMENT le secret (les autres champs ne sont pas touches). */
    @Transactional
    public void remplacerSecret(String secretClair, String auteur) {
        if (secretClair == null || secretClair.isBlank()) {
            throw new IllegalArgumentException("Le secret du client est vide.");
        }
        longueur(secretClair, MAX_SECRET, "Le secret du client est trop long.");
        String chiffre = chiffreur.chiffrer(secretClair);
        modifier(r -> r.setClientSecret(chiffre), auteur);
    }

    /** Efface SEULEMENT le secret enregistre. */
    @Transactional
    public void effacerSecret(String auteur) {
        modifier(r -> r.setClientSecret(null), auteur);
    }

    /** Cree la ligne si besoin (idempotent), la verrouille, applique, incremente la version, invalide apres commit. */
    private void modifier(Consumer<ReglagesCompteUnique> changement, String auteur) {
        repository.creerSiAbsente();
        ReglagesCompteUnique r = repository.verrouiller()
                .orElseThrow(() -> new IllegalStateException("Reglages du compte unique introuvables."));
        changement.accept(r);
        r.setVersion(r.getVersion() + 1);
        r.setModifiePar(auteur == null || auteur.length() <= MAX_IDENTIFIANT ? auteur
                : auteur.substring(0, MAX_IDENTIFIANT));
        r.setModifieA(Instant.now());
        repository.save(r);
        invalidationApresCommit();
    }

    private void invalidationApresCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalider();
                }
            });
        } else {
            invalider();
        }
    }

    /** null = inchange (valeur actuelle), vide = efface, sinon la nouvelle valeur. */
    private static String fusion(String saisi, String actuel) {
        return vide(saisi == null ? actuel : saisi);
    }

    private static String hoteDe(String adresse) {
        try {
            return new URI(adresse).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // --- Validation ---

    private static String vide(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static void longueur(String v, int max, String message) {
        if (v != null && v.length() > max) {
            throw new IllegalArgumentException(message);
        }
    }

    /** https obligatoire, hote present et non IP, ni identifiants integres ni fragment. Messages fixes. */
    private static URI https(String valeur, String libelle) {
        URI u;
        try {
            u = new URI(valeur);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(libelle + " est invalide.");
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getHost().isBlank()) {
            throw new IllegalArgumentException(libelle + " doit etre une adresse https.");
        }
        if (estAdresseIp(u.getHost())) {
            throw new IllegalArgumentException(libelle + " ne doit pas etre une adresse IP.");
        }
        if (u.getRawUserInfo() != null || u.getRawFragment() != null) {
            throw new IllegalArgumentException(libelle + " ne doit contenir ni identifiants ni fragment.");
        }
        return u;
    }

    static boolean estAdresseIp(String hote) {
        return hote != null && (hote.contains(":") || hote.matches("[0-9.]+"));
    }

    /** Hote prive de son premier label ; null s'il ne reste qu'un label ou si l'hote est une adresse IP. */
    static String domaineParent(String hote) {
        if (hote == null) {
            return null;
        }
        String h = hote.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (estAdresseIp(h)) {
            return null;
        }
        int point = h.indexOf('.');
        if (point <= 0) {
            return null;
        }
        String parent = h.substring(point + 1);
        return parent.isEmpty() || parent.indexOf('.') < 0 ? null : parent;
    }

    static boolean dansDomaine(String hote, String parent) {
        if (hote == null || parent == null) {
            return false;
        }
        String h = hote.toLowerCase(Locale.ROOT);
        return h.equals(parent) || h.endsWith("." + parent);
    }

    /** Lecture : valeur https exploitable et, si l'emetteur est connu, dans le domaine parent du portail. */
    private static boolean adresseUtilisable(String valeur, String issuer) {
        if (!notBlank(valeur) || valeur.length() > MAX_RETOUR) {
            return false;
        }
        try {
            URI u = https(valeur, "x");
            if (!notBlank(issuer)) {
                return true;
            }
            String parent = domaineParent(hoteDe(issuer));
            return parent != null && dansDomaine(u.getHost(), parent);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String sansBarreFinale(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
