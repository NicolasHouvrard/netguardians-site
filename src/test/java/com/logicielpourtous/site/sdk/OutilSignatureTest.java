package com.logicielpourtous.site.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutilSignatureTest {

	@TempDir
	Path dossier;

	private int lancer(StringBuilder sortie, String... args) {
		PrintStream ancienneOut = System.out;
		PrintStream ancienneErr = System.err;
		ByteArrayOutputStream tampon = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(tampon, true, StandardCharsets.UTF_8);
		System.setOut(ps);
		System.setErr(ps);
		try {
			return OutilSignature.executer(args);
		}
		finally {
			System.setOut(ancienneOut);
			System.setErr(ancienneErr);
			sortie.append(tampon.toString(StandardCharsets.UTF_8));
		}
	}

	@Test
	void cycle_complet_generer_signer_verifier() throws Exception {
		StringBuilder sortie = new StringBuilder();
		assertThat(lancer(sortie, "generer-cles", dossier.toString(), "cle-a")).isZero();
		Path prive = dossier.resolve("cle-a.prive");
		Path publique = dossier.resolve("cle-a.public");
		assertThat(prive).exists();
		assertThat(publique).exists();

		Path jar = dossier.resolve("p.jar");
		Files.write(jar, new byte[] { 1, 2, 3, 4 });
		assertThat(lancer(sortie, "signer", jar.toString(), prive.toString(), "cle-a")).isZero();
		assertThat(dossier.resolve("p.jar.sig")).exists();

		Path props = dossier.resolve("cles.properties");
		Files.writeString(props, "cle-a=" + Files.readString(publique).trim() + "\n");
		assertThat(lancer(sortie, "verifier", jar.toString(), props.toString())).isZero();

		byte[] octets = Files.readAllBytes(jar);
		octets[0] ^= 1;
		Files.write(jar, octets);
		assertThat(lancer(sortie, "verifier", jar.toString(), props.toString())).isEqualTo(1);

		String secret = Files.readString(prive).trim();
		assertThat(sortie.toString()).doesNotContain(secret);
	}

	@Test
	void usage_incorrect_donne_le_code_1() {
		assertThat(lancer(new StringBuilder())).isEqualTo(1);
		assertThat(lancer(new StringBuilder(), "inconnue")).isEqualTo(1);
		assertThat(lancer(new StringBuilder(), "verifier", dossier.resolve("x.jar").toString(),
				dossier.resolve("y.properties").toString())).isEqualTo(1);
	}

	@Test
	void keyId_hors_liste_blanche_est_refuse_par_generer_cles_et_signer() throws Exception {
		Path jar = dossier.resolve("p.jar");
		Files.write(jar, new byte[] { 1 });
		Path cle = dossier.resolve("k.prive");
		assertThat(lancer(new StringBuilder(), "generer-cles", dossier.toString(), "k")).isZero();
		Files.move(dossier.resolve("k.prive"), cle, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		for (String mauvais : new String[] { "D:x", "a b", "a=b", "a/b", "a\\b", "", "a\nb", "x".repeat(65) }) {
			assertThat(lancer(new StringBuilder(), "generer-cles", dossier.toString(), mauvais)).as("generer %s", mauvais)
					.isEqualTo(1);
			assertThat(lancer(new StringBuilder(), "signer", jar.toString(), cle.toString(), mauvais))
					.as("signer %s", mauvais).isEqualTo(1);
		}
		assertThat(dossier.resolve("p.jar.sig")).doesNotExist();
		assertThat(lancer(new StringBuilder(), "generer-cles", dossier.toString(), "Ok._-9")).isZero();
	}

	@Test
	void cle_privee_existante_n_est_pas_ecrasee() throws Exception {
		Path prive = dossier.resolve("k.prive");
		Files.writeString(prive, "ORIGINAL");
		assertThat(lancer(new StringBuilder(), "generer-cles", dossier.toString(), "k")).isEqualTo(1);
		assertThat(Files.readString(prive)).isEqualTo("ORIGINAL");
		assertThat(dossier.resolve("k.public")).doesNotExist();
	}

	@Test
	void lien_symbolique_pendant_est_refuse() throws Exception {
		Path cible = dossier.resolve("n-existe-pas");
		try {
			Files.createSymbolicLink(dossier.resolve("k.prive"), cible);
		}
		catch (Exception | Error e) {
			org.junit.jupiter.api.Assumptions.abort("liens symboliques non disponibles : " + e);
		}
		assertThat(lancer(new StringBuilder(), "generer-cles", dossier.toString(), "k")).isEqualTo(1);
		assertThat(cible).doesNotExist();
		assertThat(dossier.resolve("k.public")).doesNotExist();
	}

	@Test
	void cle_privee_posix_est_en_rw_seulement() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(
				dossier.getFileSystem().supportedFileAttributeViews().contains("posix"));
		Path sous = dossier.resolve("neuf");
		assertThat(lancer(new StringBuilder(), "generer-cles", sous.toString(), "k")).isZero();
		assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(sous.resolve("k.prive"))))
				.isEqualTo("rw-------");
		assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(sous)))
				.isEqualTo("rwx------");
	}

	@Test
	void cle_privee_windows_est_reservee_au_proprietaire() throws Exception {
		java.nio.file.attribute.AclFileAttributeView vue = null;
		org.junit.jupiter.api.Assumptions.assumeTrue(
				dossier.getFileSystem().supportedFileAttributeViews().contains("acl")
						&& !dossier.getFileSystem().supportedFileAttributeViews().contains("posix"));
		assertThat(lancer(new StringBuilder(), "generer-cles", dossier.toString(), "k")).isZero();
		vue = Files.getFileAttributeView(dossier.resolve("k.prive"), java.nio.file.attribute.AclFileAttributeView.class);
		java.nio.file.attribute.UserPrincipal proprietaire = vue.getOwner();
		assertThat(vue.getAcl()).isNotEmpty();
		assertThat(vue.getAcl()).allSatisfy(e -> assertThat(e.principal()).isEqualTo(proprietaire));
	}
}
