package com.logicielpourtous.site.features.plateforme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Le jar du plugin « clients » (construit par {@code mvn -f plugins-clients/pom.xml package}) est lu par le vrai
 * {@link LecteurManifeste} et accepté par la vraie vérification de compatibilité du noyau. Jamais ignoré : si le jar
 * n'a pas été construit, le test échoue avec la commande à lancer ({@link JarClientsReel#localiser()}).
 */
class PluginClientsCompatibiliteTest {

	@Test
	void leJarEstLuEtAccepteParLeNoyau() {
		ManifestePlugin m = LecteurManifeste.lire(JarClientsReel.localiser());
		assertEquals("clients", m.nom());
		assertEquals(1, m.entrees().size());
		assertEquals("1.1.0", m.version());
		assertEquals(0, m.blocs().size());
		for (String coeur : new String[] { "0.0.0", "0.0.1-SNAPSHOT", "1.2.3" }) {
			var verdict = VerificateurMiseAJour.verifier(m, Map.of(), Set.of(), coeur, null);
			assertTrue(verdict.accepte(), "refusé pour le coeur " + coeur + " : " + verdict.raisons());
		}
		assertTrue(VerificateurMiseAJour.verifierPaquets(m).isEmpty(), VerificateurMiseAJour.verifierPaquets(m).toString());
	}

}
