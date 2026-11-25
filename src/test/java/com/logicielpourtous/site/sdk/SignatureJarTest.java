package com.logicielpourtous.site.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
		String b64 = extraireSignature(bonne);
		String ok = "\"keyId\":\"cle-1\",\"algo\":\"Ed25519\"";
		Map<String, String> cas = new LinkedHashMap<>();
		cas.put("", "JSON tronqué");
		cas.put("   ", "JSON tronqué");
		cas.put("{", "JSON tronqué");
		cas.put("pas du json", "JSON invalide");
		cas.put("[]", "JSON invalide");
		cas.put("null", "JSON invalide");
		cas.put("{}", "JSON invalide");
		cas.put("{" + ok + "}", "champ manquant");
		cas.put("{\"keyId\":\"cle-1\",\"signature\":\"" + b64 + "\"}", "champ manquant");
		cas.put("{\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}", "champ manquant");
		cas.put("{" + ok + ",\"signature\":\"@@pas base64@@\"}", "base64 invalide");
		cas.put("{" + ok + ",\"signature\":\"\"}", "champ vide");
		cas.put("{" + ok + ",\"signature\":\"" + b64 + "\"} xx", "contenu après");
		cas.put("{\"keyId\":\"cle-1\",\"keyId\":\"cle-1\",\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}",
				"champ dupliqué");
		cas.put("{" + ok + ",\"signature\":\"" + b64 + "\",\"extra\":\"1\"}", "champ inattendu");
		cas.put("{\"keyId\":1,\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}", "JSON invalide");
		cas.put("{" + ok + ",\"signature\":\"" + b64 + "\"", "JSON tronqué");
		cas.put("{\"keyId\":\"cle-1\",\"algo\":\"RSA\",\"signature\":\"" + b64 + "\"}", "algorithme non supporté");
		for (Map.Entry<String, String> c : cas.entrySet()) {
			SignatureJar.Verdict v = SignatureJar.verifier(jar, c.getKey(), cles());
			assertThat(v.valide()).as("cas : %s", c.getKey()).isFalse();
			assertThat(v.keyId()).isNull();
			assertThat(v.raison()).as("raison : %s", c.getKey()).contains(c.getValue());
		}
		assertThat(SignatureJar.verifier(jar, null, cles()).valide()).isFalse();
		assertThat(SignatureJar.verifier(jar, bonne, null).valide()).isFalse();
		assertThat(SignatureJar.verifier(dossier.resolve("absent.jar"), bonne, cles()).valide()).isFalse();
		assertThat(SignatureJar.verifier(null, bonne, cles()).valide()).isFalse();
		assertThat(SignatureJar.verifier(jar, bonne, cles()).valide()).isTrue();
	}

	@Test
	void un_fichier_sig_illisible_donne_un_message_actionnable_sans_repetition() {
		for (String illisible : new String[] { "pas du json", "{", "[]", "\u0000\u0001 binaire" }) {
			String raison = SignatureJar.verifier(jar, illisible, cles()).raison();
			assertThat(raison).as("raison : %s", illisible)
				.contains("fichier de signature illisible")
				.contains("outil de signature")
				.doesNotContain("JSON invalide : JSON");
		}
	}

	private static String extraireSignature(String json) {
		return json.replaceAll(".*\"signature\"\s*:\s*\"([^\"]*)\".*", "$1");
	}

	@Test
	void echappement_unicode_exige_exactement_quatre_chiffres_hexadecimaux() throws Exception {
		String b64 = extraireSignature(SignatureJar.signer(jar, paire.getPrivate(), "cle-1"));
		String fin = ",\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}";
		String u = "\\" + "u"; // barre oblique inverse + u, sans échappement Java
		// u0065 = 'e' : accepté
		assertThat(SignatureJar.verifier(jar, "{\"keyId\":\"cl" + u + "0065-1\"" + fin, cles()).valide()).isTrue();
		for (String mauvais : List.of(u + "+065", u + "-065", u + " 065", u + "00zz", u + "006")) {
			SignatureJar.Verdict v = SignatureJar.verifier(jar, "{\"keyId\":\"cl" + mauvais + "-1\"" + fin, cles());
			assertThat(v.valide()).as(mauvais).isFalse();
		}
	}

	@Test
	void raison_ne_contient_ni_controle_ni_texte_demesure() throws Exception {
		String b64 = extraireSignature(SignatureJar.signer(jar, paire.getPrivate(), "cle-1"));
		String bs = "\\";
		// échappements JSON : saut de ligne et ESC dans la valeur lue
		String vilain = "x" + bs + "ny" + bs + "u001b[31m" + "z".repeat(500);
		SignatureJar.Verdict v = SignatureJar.verifier(jar,
				"{\"keyId\":\"" + vilain + "\",\"algo\":\"Ed25519\",\"signature\":\"" + b64 + "\"}", cles());
		assertThat(v.valide()).isFalse();
		assertThat(v.raison()).doesNotContain("\n").doesNotContain(String.valueOf((char) 27));
		assertThat(v.raison().length()).isLessThan(120);
		SignatureJar.Verdict w = SignatureJar.verifier(jar,
				"{\"keyId\":\"cle-1\",\"algo\":\"" + vilain + "\",\"signature\":\"" + b64 + "\"}", cles());
		assertThat(w.raison()).doesNotContain("\n").doesNotContain(String.valueOf((char) 27));
		assertThat(w.raison().length()).isLessThan(120);
	}

	@Test
	void raison_ne_garde_que_l_ascii_imprimable() throws Exception {
		String b64 = extraireSignature(SignatureJar.signer(jar, paire.getPrivate(), "cle-1"));
		String bs = "\\";
		// RLO, espace de largeur nulle, NEL (C1), ESC, substitut isolé, lettre accentuée (hors ASCII)
		for (String hex : List.of("202e", "200b", "0085", "001b", "d800", "00e9")) {
			String echappe = bs + "u" + hex;
			SignatureJar.Verdict v = SignatureJar.verifier(jar, "{\"keyId\":\"a" + echappe + "b\",\"algo\":\"Ed25519\","
					+ "\"signature\":\"" + b64 + "\"}", cles());
			assertThat(v.valide()).isFalse();
			assertThat(v.raison()).as("keyId %s", hex).endsWith("clé inconnue : a?b");
			SignatureJar.Verdict w = SignatureJar.verifier(jar, "{\"keyId\":\"cle-1\",\"algo\":\"a" + echappe
					+ "b\",\"signature\":\"" + b64 + "\"}", cles());
			assertThat(w.raison()).as("algo %s", hex).endsWith("algorithme non supporté : a?b");
		}
	}

	@Test
	void signature_d_une_autre_cle_sous_un_keyId_de_confiance_est_refusee() throws Exception {
		KeyPair attaquant = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String sig = SignatureJar.signer(jar, attaquant.getPrivate(), "cle-1");
		SignatureJar.Verdict v = SignatureJar.verifier(jar, sig, cles());
		assertThat(v.valide()).isFalse();
		assertThat(v.keyId()).isNull();
	}

	@Test
	void jar_zip_reel_modifie_au_premier_ou_au_dernier_octet_est_refuse() throws Exception {
		Path zip = dossier.resolve("reel.jar");
		try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
			z.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
			z.write("bonjour".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			z.closeEntry();
		}
		String sig = SignatureJar.signer(zip, paire.getPrivate(), "cle-1");
		assertThat(SignatureJar.verifier(zip, sig, cles()).valide()).isTrue();
		byte[] original = Files.readAllBytes(zip);
		for (int index : new int[] { 0, original.length - 1 }) {
			byte[] copie = original.clone();
			copie[index] ^= 1;
			Files.write(zip, copie);
			assertThat(SignatureJar.verifier(zip, sig, cles()).valide()).as("octet %d", index).isFalse();
		}
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
