package com.logicielpourtous.site.features.commun;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.stereotype.Component;

/**
 * Chiffrement des secrets stockes en base : AES-256-GCM authentifie (nonce aleatoire a chaque chiffrement), cle derivee
 * de {@code app.crypto.key} et {@code app.crypto.salt} (jamais dans le depot en production : secrets montes). ECHEC
 * FERME : un texte modifie, tronque ou chiffre avec une autre cle leve une exception au message fixe, sans jamais
 * renvoyer la donnee brute ni la valeur.
 */
@Component
public class ChiffrementSecrets {

    private final BytesEncryptor chiffreur;

    public ChiffrementSecrets(@Value("${app.crypto.key}") String cle, @Value("${app.crypto.salt}") String selHex) {
        this.chiffreur = Encryptors.stronger(cle, selHex);
    }

    /** Texte chiffre (hexadecimal) ; deux appels sur le meme clair donnent deux textes differents. */
    public String chiffrer(String clair) {
        if (clair == null) {
            throw new IllegalArgumentException("Rien a chiffrer.");
        }
        try {
            return new String(Hex.encode(chiffreur.encrypt(clair.getBytes(StandardCharsets.UTF_8))));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Chiffrement impossible.");
        }
    }

    /** Clair ; leve {@link IllegalStateException} (message fixe) si le texte est invalide, modifie ou d'une autre cle. */
    public String dechiffrer(String chiffre) {
        try {
            return new String(chiffreur.decrypt(Hex.decode(chiffre)), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Dechiffrement impossible.");
        }
    }

    @Override
    public String toString() {
        return "ChiffrementSecrets";
    }
}
