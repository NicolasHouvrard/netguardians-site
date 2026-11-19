package com.logicielpourtous.site.features.plateforme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Le jar du plugin « clients » (construit par {@code mvn -f plugins-clients/pom.xml package}) est lu par le vrai
 * {@link LecteurManifeste} et accepté par la vraie vérification de compatibilité du noyau. Ignoré si le jar n'a pas
 * été construit.
 */
class PluginClientsCompatibiliteTest {

	private static final Path JAR = Path.of("plugins-clients", "target", "clients-1.0.0.jar");

	@Test
	void leJarEstLuEtAccepteParLeNoyau() {
		assumeTrue(Files.isRegularFile(JAR), "jar du plugin clients non construit");
		ManifestePlugin m = LecteurManifeste.lire(JAR);
		assertEquals("clients", m.nom());
		assertEquals(2, m.entrees().size());
		assertEquals(1, m.blocs().size());
		for (String coeur : new String[] { "0.0.0", "0.0.1-SNAPSHOT", "1.2.3" }) {
			var verdict = VerificateurMiseAJour.verifier(m, Map.of(), Set.of(), coeur, null);
			assertTrue(verdict.accepte(), "refusé pour le coeur " + coeur + " : " + verdict.raisons());
		}
		assertTrue(VerificateurMiseAJour.verifierPaquets(m).isEmpty(), VerificateurMiseAJour.verifierPaquets(m).toString());
	}

}
