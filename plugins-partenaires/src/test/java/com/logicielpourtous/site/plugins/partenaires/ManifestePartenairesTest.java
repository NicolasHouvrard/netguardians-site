package com.logicielpourtous.site.plugins.partenaires;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logicielpourtous.site.sdk.EntreeNavigation;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import com.logicielpourtous.site.sdk.ZoneNavigation;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Le plugin.properties livre est valide pour le lecteur de manifeste du SDK (celui du noyau). */
class ManifestePartenairesTest {

	@Test
	void manifesteValideAvecUneSeuleEntreeAdminEtAucunBloc() throws Exception {
		Properties p = new Properties();
		try (InputStream in = getClass().getResourceAsStream("/plugin.properties")) {
			assertNotNull(in, "plugin.properties absent du classpath");
			p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		ManifestePlugin m = ManifestePlugin.depuis(p);
		assertEquals("partenaires", m.nom());
		assertEquals("1.0.0", m.version());
		assertEquals(ModeMiseAJour.CHAUD, m.miseAJour());
		assertEquals("com.logicielpourtous.site.plugins.partenaires.PartenairesConfiguration", m.configuration());
		assertEquals(1, m.entrees().size());
		assertEquals(new EntreeNavigation(ZoneNavigation.ADMIN, "Partenaires", "apartment", "/partenaires",
				"partenaires.gerer"), m.entrees().get(0));
		assertTrue(m.blocs().isEmpty(), "aucun bloc : les blocs ne sont pas lies a une zone");
	}

}
