package com.logicielpourtous.site.features.licence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation et empreinte des cles API du module {@code licence}.
 *
 * <p>Duplique volontairement (et non reutilise) le mecanisme equivalent de {@code features.sync} :
 * une cle API de ce module ne doit structurellement JAMAIS pouvoir authentifier une requete sur
 * {@code /api/sync/**} (qui expose de vraies donnees metier), et reciproquement une cle de
 * synchronisation ne doit jamais authentifier {@code /api/licence/**}. Des classes de cles, des
 * colonnes d'empreinte et des filtres Spring Security separes rendent cela structurellement
 * impossible plutot que de reposer sur une verification scrupuleuse a chaque appel.
 *
 * <p><b>Choix de l'empreinte : SHA-256 simple (hex), sans sel ni hachage lent.</b>
 * <ul>
 *   <li>Pas {@code CryptoConfig}/{@code EncryptionConverter} : c'est un chiffrement REVERSIBLE
 *       (la cle en clair serait recuperable cote maitre), contraire a la regle "seule une empreinte
 *       est stockee".</li>
 *   <li>Pas BCrypt ({@code PasswordEncoder} de {@code SecurityConfig}) : BCrypt est sale, donc non
 *       deterministe — impossible de retrouver le {@link LicenceServeur} par son empreinte, il
 *       faudrait tester la cle contre CHAQUE serveur enrole a chaque requete. Le sel et la lenteur
 *       de BCrypt protegent des mots de passe humains a faible entropie ; ici la cle est 256 bits
 *       aleatoires ({@link SecureRandom}), une attaque par dictionnaire/force brute sur l'empreinte
 *       est hors de portee, un hachage rapide deterministe suffit (patron classique des jetons d'API).</li>
 * </ul>
 */
public final class LicenceCles {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32; // 256 bits

    private LicenceCles() {}

    /** Nouvelle cle en clair (base64url sans padding, 43 caracteres). Sert aussi bien de cle API que
     * de code d'enrolement a usage unique (meme entropie, usages distincts). */
    public static String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Empreinte SHA-256 (hex minuscule) de la cle en clair : seule valeur stockee cote maitre. */
    public static String hash(String cle) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(cle.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e); // garanti par la JVM
        }
    }
}
