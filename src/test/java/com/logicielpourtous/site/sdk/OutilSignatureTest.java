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
}
