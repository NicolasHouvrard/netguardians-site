package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/** Validation du jeton d'identite : cles RSA generees en memoire, aucun reseau, aucune cle reelle. */
class ValidateurJetonAutheliaTest {

    private static final String EMETTEUR = "https://auth.exemple.test";
    private static final String CLIENT = "serveur-vps";

    private static KeyPair cles;
    private static KeyPair autresCles;
    private static ValidateurJetonAuthelia validateur;

    @BeforeAll
    static void init() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        cles = g.generateKeyPair();
        autresCles = g.generateKeyPair();
        validateur = new ValidateurJetonAuthelia(EMETTEUR,
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) cles.getPublic()).build());
    }

    private static JWTClaimsSet.Builder valides() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(EMETTEUR).subject("u1").audience(List.of(CLIENT))
                .claim("email", "Alice@Exemple.FR").claim("email_verified", true)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)));
    }

    private static String signer(JWTClaimsSet claims, KeyPair paire) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(paire.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void jeton_valide_donne_l_email() throws Exception {
        assertThat(validateur.valider(signer(valides().build(), cles), CLIENT)).contains("alice@exemple.fr");
    }

    @Test
    void signature_invalide() throws Exception {
        assertThat(validateur.valider(signer(valides().build(), autresCles), CLIENT)).isEmpty();
    }

    @Test
    void emetteur_inconnu() throws Exception {
        assertThat(validateur.valider(signer(valides().issuer("https://pirate.test").build(), cles), CLIENT)).isEmpty();
    }

    @Test
    void destinataire_d_un_autre_serveur() throws Exception {
        assertThat(validateur.valider(signer(valides().build(), cles), "autre-serveur")).isEmpty();
        assertThat(validateur.valider(signer(valides().build(), cles), null)).isEmpty();
    }

    @Test
    void expire() throws Exception {
        Instant passe = Instant.now().minusSeconds(3600);
        JWTClaimsSet c = valides().issueTime(Date.from(passe.minusSeconds(300)))
                .expirationTime(Date.from(passe)).build();
        assertThat(validateur.valider(signer(c, cles), CLIENT)).isEmpty();
    }

    @Test
    void email_non_verifie() throws Exception {
        assertThat(validateur.valider(signer(valides().claim("email_verified", false).build(), cles), CLIENT)).isEmpty();
        assertThat(validateur.valider(signer(valides().claim("email_verified", null).build(), cles), CLIENT)).isEmpty();
        assertThat(validateur.valider(signer(valides().claim("email_verified", "true").build(), cles), CLIENT)).isEmpty();
    }

    @Test
    void algorithme_none_refuse() {
        assertThat(validateur.valider(new PlainJWT(valides().build()).serialize(), CLIENT)).isEmpty();
    }

    @Test
    void jeton_vide_ou_illisible_et_emetteur_non_configure_donnent_vide() throws Exception {
        assertThat(validateur.valider(null, CLIENT)).isEmpty();
        assertThat(validateur.valider("  ", CLIENT)).isEmpty();
        assertThat(validateur.valider("pas.un.jeton", CLIENT)).isEmpty();
        // emetteur vide : aiguillage desactive, aucun appel reseau
        assertThat(new ValidateurJetonAuthelia("").valider(signer(valides().build(), cles), CLIENT)).isEmpty();
    }
}
