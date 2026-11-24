package com.logicielpourtous.site.plugins.partenaires;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Controles statiques des gabarits livres dans le jar du plugin. */
class GabaritsPartenairesTest {

	@ParameterizedTest
	@ValueSource(strings = { "liste", "partenaire-form", "client-form", "modele", "refuse", "erreur" })
	void gabaritConforme(String vue) throws Exception {
		String chemin = "/templates/plugins/partenaires/" + vue + ".html";
		String html;
		try (InputStream in = getClass().getResourceAsStream(chemin)) {
			assertNotNull(in, chemin + " absent");
			html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertTrue(html.contains("layout:decorate=\"~{socle/layout}\""), "doit decorer socle/layout");
		assertTrue(html.contains("layout:fragment=\"contenu\""), "doit remplir le fragment contenu");
		assertFalse(html.contains("th:utext"), "th:utext interdit");
		assertFalse(html.toLowerCase().contains("<script"), "pas de script");
		Matcher m = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
		while (m.find()) {
			String f = m.group();
			if (Pattern.compile("(?i)method\\s*=\\s*[\"']?post\\b").matcher(f).find()) {
				assertTrue(f.contains("th:action="), "formulaire POST sans th:action : " + f);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "partenaire-form", "client-form" })
	void champSirenAssezLongPourLesEspaces(String vue) throws Exception {
		try (InputStream in = getClass().getResourceAsStream("/templates/plugins/partenaires/" + vue + ".html")) {
			String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			Matcher m = Pattern.compile("<input[^>]*id=\"siren\"[^>]*>").matcher(html);
			assertTrue(m.find());
			assertFalse(m.group().contains("maxlength=\"9\""), "maxlength=9 tronque un SIREN saisi avec espaces");
		}
	}

}
