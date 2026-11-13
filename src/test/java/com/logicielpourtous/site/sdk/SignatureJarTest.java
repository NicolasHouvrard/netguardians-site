package com.logicielpourtous.site.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignatureJarTest {

	@TempDir
	Path dossier;

	KeyPair paire;
	Path jar;

	@BeforeEach
	void preparer() throws Exception {
		paire = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		jar = dossier.resolve("plugin.jar");
		Files.write(jar, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
	}

	private List<ClePublique> cles() {
		return List.of(new ClePublique("cle-1", paire.getPublic()));
	}

	@Test
	void signature_valide_est_acceptee() throws Exception {
		String sig = SignatureJar.signer(jar, paire.getPrivate(), "cle-1");
		SignatureJar.Verdict v = SignatureJar.verifier(jar, sig, cles());
		assertThat(v.valide()).isTrue();
		assertThat(v.keyId()).isEqualTo("cle-1");
	}

	@Test
	void un_octet_modifie_est_refuse() throws Exception {
		String sig = SignatureJar.signer(jar, paire.getPrivate(), "cle-1");
		byte[] octets = Files.readAllBytes(jar);
		octets[3] ^= 1;
		Files.write(jar, octets);
		SignatureJar.Verdict v = SignatureJar.verifier(jar, sig, cles());
		assertThat(v.valide()).isFalse();
		assertThat(v.raison()).isNotBlank();
	}

	@Test
	void cle_inconnue_est_refusee() throws Exception {
		String sig = SignatureJar.signer(jar, paire.getPrivate(), "autre");
		assertThat(SignatureJar.verifier(jar, sig, cles()).valide()).isFalse();
		// comparaison exacte : la casse compte
		String sigCasse = SignatureJar.signer(jar, paire.getPrivate(), "CLE-1");
		assertThat(SignatureJar.verifier(jar, sigCasse, cles()).valide()).isFalse();
	}

	@Test
	void algo_inconnu_est_refuse() throws Exception {
		String sig = SignatureJar.signer(jar, paire.getPrivate(), "cle-1").replace("Ed25519", "RSA");
		assertThat(SignatureJar.verifier(jar, sig, cles()).valide()).isFalse();
	}

	@Test
	void json_malforme_champs_manquants_ou_base64_invalide_sont_refuses_sans_exception() throws Exception {
		String bonne = SignatureJar.signer(jar, paire.getPrivate(), "cle-1");
		String b64 = bonne.replaceAll(".*\"signature\"\s*:\s*\"([^\"]*)\".*", "$1");
		List<String> cas = List.of(
				"", "   ", "{", "pas du json", "[]", "null", "{}",
				"{\"keyId\":\"cle-1\",\"algo\":\"Ed25519\"}",
				"{\"keyId\":\"cle-1\",\"signature\":\"" + b64 + "\"}",
				"{\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}",
				"{\"keyId\":\"cle-1\",\"algo\":\"Ed25519\",\"signature\":\"@@pas base64@@\"}",
				"{\"keyId\":\"cle-1\",\"algo\":\"Ed25519\",\"signature\":\"\"}",
				"{\"keyId\":\"cle-1\",\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"} xx",
				"{\"keyId\":\"cle-1\",\"keyId\":\"cle-1\",\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}",
				"{\"keyId\":\"cle-1\",\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\",\"extra\":1}",
				"{\"keyId\":1,\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}",
				"{\"keyId\":\"cle-1\",\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"");
		for (String c : cas) {
			SignatureJar.Verdict v = SignatureJar.verifier(jar, c, cles());
			assertThat(v.valide()).as("cas : %s", c).isFalse();
			assertThat(v.raison()).as("raison : %s", c).isNotBlank();
		}
		assertThat(SignatureJar.verifier(jar, null, cles()).valide()).isFalse();
		assertThat(SignatureJar.verifier(jar, bonne, null).valide()).isFalse();
		assertThat(SignatureJar.verifier(dossier.resolve("absent.jar"), bonne, cles()).valide()).isFalse();
		assertThat(SignatureJar.verifier(null, bonne, cles()).valide()).isFalse();
		// le cas valide reste valide
		assertThat(SignatureJar.verifier(jar, bonne, cles()).valide()).isTrue();
	}

	@Test
	void signature_d_un_autre_jar_est_refusee() throws Exception {
		Path autre = dossier.resolve("autre.jar");
		Files.write(autre, new byte[] { 9, 9, 9 });
		String sigAutre = SignatureJar.signer(autre, paire.getPrivate(), "cle-1");
		assertThat(SignatureJar.verifier(jar, sigAutre, cles()).valide()).isFalse();
	}

	@Test
	void lireCles_lit_les_cles_publiques_et_ignore_les_entrees_invalides() throws Exception {
		java.util.Properties p = new java.util.Properties();
		p.setProperty("cle-1", java.util.Base64.getEncoder().encodeToString(paire.getPublic().getEncoded()));
		p.setProperty("cassee", "@@@");
		List<ClePublique> lues = SignatureJar.lireCles(p);
		assertThat(lues).hasSize(1);
		assertThat(lues.get(0).keyId()).isEqualTo("cle-1");
		String sig = SignatureJar.signer(jar, paire.getPrivate(), "cle-1");
		assertThat(SignatureJar.verifier(jar, sig, lues).valide()).isTrue();
	}
}
