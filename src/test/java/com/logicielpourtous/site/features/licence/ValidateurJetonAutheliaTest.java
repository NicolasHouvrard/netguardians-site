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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void hs256_signe_avec_la_cle_publique_est_refuse() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), valides().build());
        jwt.sign(new MACSigner(cles.getPublic().getEncoded()));
        assertThat(validateur.valider(jwt.serialize(), CLIENT)).isEmpty();
    }

    @Test
    void nbf_dans_le_futur_est_refuse() throws Exception {
        JWTClaimsSet c = valides().notBeforeTime(Date.from(Instant.now().plusSeconds(3600))).build();
        assertThat(validateur.valider(signer(c, cles), CLIENT)).isEmpty();
    }

    @Test
    void aud_multiple_est_gere() throws Exception {
        // destinataire attendu parmi plusieurs : azp obligatoire et egal au client attendu
        assertThat(validateur.valider(signer(valides().audience(List.of("autre", CLIENT)).claim("azp", CLIENT).build(), cles), CLIENT))
                .contains("alice@exemple.fr");
        assertThat(validateur.valider(signer(valides().audience(List.of("autre", CLIENT)).build(), cles), CLIENT)).isEmpty();
        assertThat(validateur.valider(signer(valides().audience(List.of("autre", CLIENT)).claim("azp", "autre").build(), cles), CLIENT)).isEmpty();
        assertThat(validateur.valider(signer(valides().audience(List.of("autre", "encore")).claim("azp", CLIENT).build(), cles), CLIENT)).isEmpty();
    }

    @Test
    void emetteur_injoignable_ne_bloque_pas_le_demarrage_et_donne_une_reponse_vide() throws Exception {
        long debut = System.nanoTime();
        ValidateurJetonAuthelia v = new ValidateurJetonAuthelia("https://127.0.0.1:1");
        assertThat((System.nanoTime() - debut) / 1_000_000).as("construction sans reseau (ms)").isLessThan(500);
        assertThat(v.valider(signer(valides().build(), cles), CLIENT)).isEmpty();
        // deuxieme appel pendant la pause de 30 s : refuse sans nouvel essai reseau
        debut = System.nanoTime();
        assertThat(v.valider(signer(valides().build(), cles), CLIENT)).isEmpty();
        assertThat((System.nanoTime() - debut) / 1_000_000).isLessThan(200);
    }

    @Test
    void pause_de_30_secondes_apres_un_echec_puis_nouvel_essai() throws Exception {
        AtomicInteger essais = new AtomicInteger();
        long[] t = {1_000L};
        ValidateurJetonAuthelia v = new ValidateurJetonAuthelia(EMETTEUR, () -> {
            if (essais.incrementAndGet() == 1) {
                throw new IllegalStateException("injoignable");
            }
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) cles.getPublic()).build();
        }, () -> t[0]);
        String jeton = signer(valides().build(), cles);
        v.valider(jeton, CLIENT);
        v.valider(jeton, CLIENT);
        assertThat(essais.get()).isEqualTo(1);
        t[0] += ValidateurJetonAuthelia.PAUSE_ENTRE_ESSAIS_NANOS + 1;
        assertThat(v.valider(jeton, CLIENT)).contains("alice@exemple.fr");
        assertThat(essais.get()).isEqualTo(2);
    }

    @Test
    void un_seul_essai_a_la_fois() throws Exception {
        AtomicInteger essais = new AtomicInteger();
        CountDownLatch entre = new CountDownLatch(1);
        CountDownLatch libere = new CountDownLatch(1);
        ValidateurJetonAuthelia v = new ValidateurJetonAuthelia(EMETTEUR, () -> {
            essais.incrementAndGet();
            entre.countDown();
            try {
                libere.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) cles.getPublic()).build();
        }, System::nanoTime);
        String jeton = signer(valides().build(), cles);
        Thread premier = new Thread(() -> v.valider(jeton, CLIENT));
        premier.start();
        entre.await();
        // pendant que le premier essai est en cours, les autres requetes ne bloquent pas et sont refusees
        long debut = System.nanoTime();
        assertThat(v.valider(jeton, CLIENT)).isEmpty();
        assertThat((System.nanoTime() - debut) / 1_000_000).isLessThan(500);
        libere.countDown();
        premier.join();
        assertThat(essais.get()).isEqualTo(1);
    }

    @Test
    void decodeur_de_production_paresseux_avec_jwks_servi_en_http_local() throws Exception {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) cles.getPublic()).keyID("k1").build();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        AtomicInteger appels = new AtomicInteger();
        http.createContext("/.well-known/openid-configuration", ex -> {
            appels.incrementAndGet();
            byte[] b = ("{\"issuer\":\"" + base + "\",\"jwks_uri\":\"" + base + "/jwks\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        http.createContext("/jwks", ex -> {
            byte[] b = new JWKSet(jwk).toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        http.start();
        try {
            ValidateurJetonAuthelia v = new ValidateurJetonAuthelia(base);
            assertThat(appels.get()).as("aucun appel a la construction").isZero();
            JWTClaimsSet c = valides().issuer(base).build();
            // typ "JWT" (Authelia) puis typ absent : tous deux acceptes
            SignedJWT avecTyp = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                    .type(com.nimbusds.jose.JOSEObjectType.JWT).build(), c);
            avecTyp.sign(new RSASSASigner(cles.getPrivate()));
            assertThat(v.valider(avecTyp.serialize(), CLIENT)).contains("alice@exemple.fr");
            SignedJWT sansTyp = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), c);
            sansTyp.sign(new RSASSASigner(cles.getPrivate()));
            assertThat(v.valider(sansTyp.serialize(), CLIENT)).contains("alice@exemple.fr");
            // mauvaise cle de signature : refuse
            SignedJWT faux = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), c);
            faux.sign(new RSASSASigner(autresCles.getPrivate()));
            assertThat(v.valider(faux.serialize(), CLIENT)).isEmpty();
        } finally {
            http.stop(0);
        }
    }

    @Test
    void un_jeton_forge_ne_met_pas_l_aiguillage_en_pause() throws Exception {
        AtomicInteger essais = new AtomicInteger();
        // le decodeur leve une exception inattendue (sans cause d'E/S) pour le jeton "piege"
        ValidateurJetonAuthelia v = new ValidateurJetonAuthelia(EMETTEUR, () -> {
            essais.incrementAndGet();
            JwtDecoder reel = NimbusJwtDecoder.withPublicKey((RSAPublicKey) cles.getPublic()).build();
            return jeton -> {
                if ("piege".equals(jeton)) {
                    throw new IllegalStateException("inattendue");
                }
                return reel.decode(jeton);
            };
        }, System::nanoTime);
        String bon = signer(valides().build(), cles);

        assertThat(v.valider("pas.un.jeton", CLIENT)).isEmpty();
        assertThat(v.valider("piege", CLIENT)).isEmpty();
        // kid inconnu / signature fausse
        assertThat(v.valider(signer(valides().build(), autresCles), CLIENT)).isEmpty();
        // le jeton valide suivant est accepte immediatement, sans nouveau decodeur
        assertThat(v.valider(bon, CLIENT)).contains("alice@exemple.fr");
        assertThat(essais.get()).isEqualTo(1);
    }

    @Test
    void une_panne_du_jwks_met_en_pause_et_les_jetons_valides_sont_refuses_30_s() throws Exception {
        long[] t = {1_000L};
        AtomicInteger decodages = new AtomicInteger();
        JwtDecoder reel = NimbusJwtDecoder.withPublicKey((RSAPublicKey) cles.getPublic()).build();
        boolean[] panne = {true};
        ValidateurJetonAuthelia v = new ValidateurJetonAuthelia(EMETTEUR, () -> jeton -> {
            decodages.incrementAndGet();
            if (panne[0]) {
                throw new org.springframework.security.oauth2.jwt.JwtException("jwks",
                        new RuntimeException(new java.io.IOException("injoignable")));
            }
            return reel.decode(jeton);
        }, () -> t[0]);
        String bon = signer(valides().build(), cles);

        assertThat(v.valider(bon, CLIENT)).isEmpty();
        panne[0] = false;
        assertThat(v.valider(bon, CLIENT)).as("en pause").isEmpty();
        assertThat(decodages.get()).isEqualTo(1);
        t[0] += ValidateurJetonAuthelia.PAUSE_ENTRE_ESSAIS_NANOS + 1;
        assertThat(v.valider(bon, CLIENT)).contains("alice@exemple.fr");
    }
}
