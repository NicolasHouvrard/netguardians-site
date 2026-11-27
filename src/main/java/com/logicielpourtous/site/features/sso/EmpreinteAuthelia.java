package com.logicielpourtous.site.features.sso;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Secret client aleatoire et son empreinte au format d'Authelia : {@code $pbkdf2-sha512$310000$<sel>$<hash>}.
 * <p>
 * Encodage : sel de 16 octets et hash de 64 octets (PBKDF2-HMAC-SHA512, 310000 iterations), chacun en base64
 * « adapte » (celui de passlib, repris par Authelia) : alphabet standard avec {@code +} remplace par {@code .}, et SANS
 * remplissage {@code =}. Le secret en clair est compose de 72 caracteres du jeu RFC 3986 non reserve
 * ({@code A-Z a-z 0-9 - . _ ~}), tires uniformement avec {@link SecureRandom}.
 * <p>
 * Verification cote Authelia (syntaxe a confirmer avec la version installee) :
 * {@code authelia crypto hash validate --password '<mot de passe>' -- '<empreinte>'}.
 */
public final class EmpreinteAuthelia {

    static final int ITERATIONS = 310_000;
    static final int OCTETS_SEL = 16;
    static final int OCTETS_HASH = 64;
    static final int LONGUEUR_SECRET = 72;
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final SecureRandom ALEA = new SecureRandom();
    private static final Pattern FORMAT =
            Pattern.compile("^\\$pbkdf2-sha512\\$(\\d{1,9})\\$([A-Za-z0-9./]+)\\$([A-Za-z0-9./]+)$");

    private EmpreinteAuthelia() {
    }

    /** Secret en clair (a ne jamais afficher ni journaliser) et son empreinte (a coller dans Authelia). */
    public record ClientGenere(String secretClair, String empreinte) {

        @Override
        public String toString() {
            return "ClientGenere[secret=masque]";
        }
    }

    public static ClientGenere generer() {
        StringBuilder sb = new StringBuilder(LONGUEUR_SECRET);
        for (int i = 0; i < LONGUEUR_SECRET; i++) {
            sb.append(ALPHABET.charAt(ALEA.nextInt(ALPHABET.length())));
        }
        String secret = sb.toString();
        byte[] sel = new byte[OCTETS_SEL];
        ALEA.nextBytes(sel);
        return new ClientGenere(secret, empreinte(secret, sel));
    }

    static String empreinte(String secret, byte[] sel) {
        return "$pbkdf2-sha512$" + ITERATIONS + "$" + encoder(sel) + "$" + encoder(hash(secret, sel, ITERATIONS));
    }

    /** Vrai si l'empreinte (meme format) correspond au secret ; temps constant sur la comparaison des hashs. */
    public static boolean verifie(String secret, String empreinte) {
        if (secret == null || empreinte == null) {
            return false;
        }
        Matcher m = FORMAT.matcher(empreinte);
        if (!m.matches()) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(m.group(1));
            byte[] sel = decoder(m.group(2));
            byte[] attendu = decoder(m.group(3));
            return MessageDigest.isEqual(hash(secret, sel, iterations), attendu);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] hash(String secret, byte[] sel, int iterations) {
        char[] mdp = secret.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(mdp, sel, iterations, OCTETS_HASH * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2-SHA512 indisponible.");
        } finally {
            spec.clearPassword();
            Arrays.fill(mdp, '\0');
        }
    }

    private static String encoder(byte[] octets) {
        return Base64.getEncoder().withoutPadding().encodeToString(octets).replace('+', '.');
    }

    private static byte[] decoder(String ab64) {
        return Base64.getDecoder().decode(ab64.replace('.', '+').getBytes(StandardCharsets.US_ASCII));
    }
}
