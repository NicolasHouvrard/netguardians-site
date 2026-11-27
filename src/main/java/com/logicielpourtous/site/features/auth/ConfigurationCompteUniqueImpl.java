package com.logicielpourtous.site.features.auth;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;
import com.logicielpourtous.site.features.mail.MailEncryptionConverter;

/**
 * Reglages du compte unique de CE serveur : la valeur en base prime, sinon la variable d'environnement historique
 * (memes noms qu'avant), sinon vide. Le secret est chiffre en base (meme mecanisme que les comptes mail) et n'apparait
 * dans aucun message d'erreur ni {@code toString}.
 * <p>
 * Un instantane est mis en cache et invalide a chaque enregistrement ; {@link #version()} change alors, ce qui permet
 * aux consommateurs de se reconstruire sans redemarrage.
 */
@Service
public class ConfigurationCompteUniqueImpl implements ConfigurationCompteUnique {

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
     * Valeurs saisies a l'enregistrement. Une adresse vide efface la valeur en base (l'environnement reprend la main) ;
     * un secret vide conserve le secret deja enregistre.
     */
    public record ReglagesModifies(String issuerUri, String clientId, String nouveauSecret, String urlPublique,
            String maitreUrlPublique, String retourDeconnexion) {

        @Override
        public String toString() {
            return "ReglagesModifies[secret=" + (nouveauSecret == null || nouveauSecret.isBlank() ? "non fourni" : "fourni")
                    + "]";
        }
    }

    /** Valeurs effectives resolues ; le secret y est en clair, d'ou l'absence de toString. */
    private record Instantane(long version, String issuer, String clientId, String secret, String urlPublique,
            String maitre, String retour) {

        @Override
        public String toString() {
            return "Instantane[version=" + version + "]";
        }
    }

    private final ReglagesCompteUniqueRepository repository;
    private final Environment env;
    private final MailEncryptionConverter chiffreur;
    private volatile Instantane cache;

    public ConfigurationCompteUniqueImpl(ReglagesCompteUniqueRepository repository, Environment env,
            MailEncryptionConverter chiffreur) {
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

    /** Oublie l'instantane (relu a la prochaine lecture). */
    public void invalider() {
        cache = null;
    }

    @Override
    public String toString() {
        return "ConfigurationCompteUniqueImpl";
    }

    private Instantane instantane() {
        Instantane c = cache;
        if (c == null) {
            c = construire();
            cache = c;
        }
        return c;
    }

    private Instantane construire() {
        Optional<ReglagesCompteUnique> ligne = repository.findById(ReglagesCompteUnique.ID_UNIQUE);
        ReglagesCompteUnique r = ligne.orElse(null);
        String issuer = choisir(r == null ? null : r.getIssuerUri(), P_ISSUER);
        String clientId = choisir(r == null ? null : r.getClientId(), P_CLIENT_ID);
        String secret = r != null && notBlank(r.getClientSecret())
                ? chiffreur.convertToEntityAttribute(r.getClientSecret())
                : propriete(P_CLIENT_SECRET);
        String url = r == null || !notBlank(r.getUrlPublique()) ? "" : r.getUrlPublique().trim();
        String maitre = choisir(r == null ? null : r.getMaitreUrlPublique(), P_MAITRE);
        String explicite = choisir(r == null ? null : r.getRetourDeconnexion(), P_RETOUR);
        String retour = "";
        if (adresseUtilisable(explicite, issuer)) {
            retour = explicite;
        } else if (adresseUtilisable(url, issuer)) {
            retour = sansBarreFinale(url) + SUFFIXE_RETOUR;
        }
        return new Instantane(r == null ? 0L : r.getVersion(), issuer, clientId, secret == null ? "" : secret, url,
                maitre, retour);
    }

    private String choisir(String enBase, String cleEnvironnement) {
        return notBlank(enBase) ? enBase.trim() : propriete(cleEnvironnement);
    }

    private String propriete(String cle) {
        String v = env.getProperty(cle);
        return v == null ? "" : v.trim();
    }

    // --- Ecriture ---

    /**
     * Valide, chiffre le secret, ecrit et incremente la version. Une valeur invalide leve une
     * {@link IllegalArgumentException} au message fixe (jamais la valeur) AVANT toute ecriture.
     */
    @Transactional
    public void enregistrer(ReglagesModifies m, String auteur) {
        if (m == null) {
            throw new IllegalArgumentException("Reglages absents.");
        }
        String issuer = vide(m.issuerUri());
        String clientId = vide(m.clientId());
        String url = vide(m.urlPublique());
        String maitre = vide(m.maitreUrlPublique());
        String retour = vide(m.retourDeconnexion());
        String secret = m.nouveauSecret() == null || m.nouveauSecret().isBlank() ? null : m.nouveauSecret();

        longueur(issuer, MAX_ADRESSE, "L'adresse d'Authelia est trop longue.");
        longueur(url, MAX_ADRESSE, "L'adresse publique est trop longue.");
        longueur(maitre, MAX_ADRESSE, "L'adresse publique du Maitre est trop longue.");
        longueur(retour, MAX_RETOUR, "L'adresse de retour est trop longue.");
        longueur(clientId, MAX_IDENTIFIANT, "L'identifiant du client est trop long.");
        longueur(secret, MAX_SECRET, "Le secret du client est trop long.");

        URI uriIssuer = issuer == null ? null : https(issuer, "L'adresse d'Authelia");
        URI uriUrl = url == null ? null : https(url, "L'adresse publique");
        if (maitre != null) {
            https(maitre, "L'adresse publique du Maitre");
        }
        URI uriRetour = retour == null ? null : https(retour, "L'adresse de retour");

        if (uriUrl != null || uriRetour != null) {
            // Le domaine parent se calcule sur l'emetteur saisi, a defaut sur celui qui s'appliquerait deja.
            String emetteur = issuer != null ? issuer : issuerUri();
            String parent = emetteur.isEmpty() ? null : domaineParent(URI.create(emetteur).getHost());
            if (parent == null) {
                throw new IllegalArgumentException(
                        "Le domaine du portail Authelia ne permet pas de verifier l'adresse publique et l'adresse de retour.");
            }
            if (uriUrl != null && !dansDomaine(uriUrl.getHost(), parent)) {
                throw new IllegalArgumentException("L'adresse publique doit etre dans le domaine parent du portail Authelia.");
            }
            if (uriRetour != null && !dansDomaine(uriRetour.getHost(), parent)) {
                throw new IllegalArgumentException("L'adresse de retour doit etre dans le domaine parent du portail Authelia.");
            }
        }
        if (uriIssuer != null && uriIssuer.getHost() == null) {
            throw new IllegalArgumentException("L'adresse d'Authelia est invalide.");
        }

        ReglagesCompteUnique r = repository.findById(ReglagesCompteUnique.ID_UNIQUE).orElseGet(ReglagesCompteUnique::new);
        r.setId(ReglagesCompteUnique.ID_UNIQUE);
        r.setIssuerUri(issuer);
        r.setClientId(clientId);
        if (secret != null) {
            r.setClientSecret(chiffreur.convertToDatabaseColumn(secret));
        }
        r.setUrlPublique(url);
        r.setMaitreUrlPublique(maitre);
        r.setRetourDeconnexion(retour);
        r.setVersion(r.getVersion() + 1);
        r.setModifiePar(auteur);
        r.setModifieA(Instant.now());
        repository.save(r);
        invalider();
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

    /** https obligatoire, hote present, ni identifiants integres ni fragment. Messages fixes, sans la valeur. */
    private static URI https(String valeur, String libelle) {
        URI u;
        try {
            u = new URI(valeur);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(libelle + " est invalide.");
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getHost().isBlank()) {
            throw new IllegalArgumentException(libelle + " doit etre une adresse https.");
        }
        if (u.getRawUserInfo() != null || u.getRawFragment() != null) {
            throw new IllegalArgumentException(libelle + " ne doit contenir ni identifiants ni fragment.");
        }
        return u;
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
        if (h.contains(":") || h.matches("[0-9.]+")) {
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
            String parent = domaineParent(URI.create(issuer).getHost());
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
