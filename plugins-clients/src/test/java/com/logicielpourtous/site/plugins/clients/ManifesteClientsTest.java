package com.logicielpourtous.site.plugins.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.logicielpourtous.site.sdk.EntreeNavigation;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ModeMiseAJour;
import com.logicielpourtous.site.sdk.ZoneNavigation;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Le plugin.properties livré est valide pour le lecteur de manifeste du SDK (celui du noyau). */
class ManifesteClientsTest {

	@Test
	void manifesteValideAvecSonEntreeEtSansBloc() throws Exception {
		Properties p = new Properties();
		try (InputStream in = getClass().getResourceAsStream("/plugin.properties")) {
			assertNotNull(in, "plugin.properties absent du classpath");
			p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		ManifestePlugin m = ManifestePlugin.depuis(p);
		assertEquals("clients", m.nom());
		assertEquals("1.1.0", m.version());
		assertEquals(ModeMiseAJour.CHAUD, m.miseAJour());
		assertEquals("com.logicielpourtous.site.plugins.clients.ClientsConfiguration", m.configuration());
		assertEquals(1, m.entrees().size());
		assertEquals(new EntreeNavigation(ZoneNavigation.PARTENAIRE, "Clients", "people", "/liste", "clients.lire"),
				m.entrees().get(0));
		assertEquals(0, m.blocs().size());
	}

}
