package com.logicielpourtous.site.features.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation et empreinte des cles API de synchronisation.
 *
 * <p><b>Choix de l'empreinte : SHA-256 simple (hex), sans sel ni hachage lent.</b>
 * <ul>
 *   <li>Pas {@code CryptoConfig}/{@code EncryptionConverter} : c'est un chiffrement REVERSIBLE
 *       (la cle en clair serait recuperable cote maitre), contraire a la regle "seule une empreinte
 *       est stockee".</li>
 *   <li>Pas BCrypt ({@code PasswordEncoder} de {@code SecurityConfig}) : BCrypt est sale, donc non
 *       deterministe — impossible de retrouver le {@link SecondaryServer} par son empreinte, il
 *       faudrait tester la cle contre CHAQUE serveur enrole a chaque requete. Le sel et la lenteur
 *       de BCrypt protegent des mots de passe humains a faible entropie ; ici la cle est 256 bits
 *       aleatoires ({@link SecureRandom}), une attaque par dictionnaire/force brute sur l'empreinte
 *       est hors de portee, un hachage rapide deterministe suffit (patron classique des jetons d'API).</li>
 * </ul>
 */
public final class SyncApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32; // 256 bits

    private SyncApiKeys() {}

    /** Nouvelle cle API en clair (base64url sans padding, 43 caracteres). */
    public static String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Empreinte SHA-256 (hex minuscule) de la cle en clair : seule valeur stockee cote maitre. */
    public static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e); // garanti par la JVM
        }
    }
}
