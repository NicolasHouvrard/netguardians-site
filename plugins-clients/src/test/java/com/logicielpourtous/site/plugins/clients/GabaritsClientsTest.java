package com.logicielpourtous.site.plugins.clients;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ClientResume;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Controles des gabarits livres dans le jar du plugin : lecture statique et rendu par un moteur Thymeleaf autonome. */
class GabaritsClientsTest {

	private static final String[] VUES = { "liste", "client-form", "fiche", "entree", "refuse", "introuvable", "erreur" };
	private static final String XSS = "<script>x</script>";

	private static String lire(String vue) throws Exception {
		String chemin = "/templates/plugins/clients/" + vue + ".html";
		try (InputStream in = GabaritsClientsTest.class.getResourceAsStream(chemin)) {
			assertNotNull(in, chemin + " absent");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "liste", "client-form", "fiche", "entree", "refuse", "introuvable", "erreur" })
	void gabaritConforme(String vue) throws Exception {
		String html = lire(vue);
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

	@Test
	void formulaireDeCreation() throws Exception {
		String html = lire("client-form");
		Matcher m = Pattern.compile("<input[^>]*id=\"siren\"[^>]*>").matcher(html);
		assertTrue(m.find());
		assertTrue(m.group().contains("maxlength=\"14\""), "SIREN : maxlength 14 pour les espaces saisis");
		assertTrue(html.contains("name=\"particulier\""));
		assertTrue(html.contains("name=\"etrangere\""));
		assertTrue(html.contains("@{/p/clients/}"));
		assertTrue(html.contains("invalid-feedback") && html.contains("is-invalid"));
		for (String champ : new String[] { "siren", "tva", "email", "nom" }) {
			assertTrue(html.contains("erreursChamps['" + champ + "']"), champ);
		}
		assertFalse(html.contains("partenaireSocieteId"), "le partenaire ne vient jamais du formulaire");
	}

	@Test
	void ficheAUnBoutonEntrerEnPost() throws Exception {
		String html = lire("fiche");
		assertTrue(html.contains("Entrer dans l'espace"));
		assertTrue(Pattern.compile("(?i)<form[^>]*method=\"post\"[^>]*th:action=\"@\\{/p/clients/\\{id\\}/entrer").matcher(html)
			.find());
	}

	// ---- rendu

	private static TemplateEngine moteur() {
		ClassLoaderTemplateResolver reel = new ClassLoaderTemplateResolver();
		reel.setPrefix("templates/");
		reel.setSuffix(".html");
		reel.setCharacterEncoding("UTF-8");
		reel.setCheckExistence(true);
		reel.setOrder(2);
		// Mise en page du noyau remplacee par un gabarit minimal de test (le vrai vit dans le noyau).
		ClassLoaderTemplateResolver socle = new ClassLoaderTemplateResolver();
		socle.setPrefix("gabarits-test/");
		socle.setSuffix(".html");
		socle.setCharacterEncoding("UTF-8");
		socle.setResolvablePatterns(java.util.Set.of("socle/*"));
		socle.setOrder(1);
		TemplateEngine moteur = new TemplateEngine();
		// Sans contexte web : les liens relatifs au contexte (@{/...}) sont rendus tels quels.
		moteur.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
			@Override
			protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base,
					Map<String, Object> parameters) {
				return "";
			}
		});
		moteur.addDialect(new LayoutDialect());
		moteur.addTemplateResolver(socle);
		moteur.addTemplateResolver(reel);
		return moteur;
	}

	private static String rendre(String vue, Map<String, Object> variables) {
		Context ctx = new Context(java.util.Locale.FRANCE);
		ctx.setVariables(variables);
		return moteur().process("plugins/clients/" + vue, ctx);
	}

	private static void assertEchappe(String html) {
		assertFalse(html.contains(XSS), "balise non echappee");
		assertTrue(html.contains("&lt;script&gt;"), "nom echappe attendu");
	}

	@Test
	void rendDesNomsHostilesEchappes() {
		FicheClient fiche = new FicheClient(new ClientResume(9L, "732829320", "FR44732829320", "PARTENAIRE", XSS), 5L,
				List.of("clients.lire", XSS));
		assertEchappe(rendre("liste", Map.of("titre", "Clients", "clients", List.of(
				new ClientResume(9L, "732829320", null, "PARTENAIRE", XSS)))));
		assertEchappe(rendre("fiche", Map.of("titre", "Fiche", "fiche", fiche, "succes", XSS)));
		assertEchappe(rendre("client-form", Map.of("titre", "Nouveau", "saisie", Map.of("nom", XSS),
				"erreursChamps", Map.of("nom", XSS), "erreur", XSS)));
		assertEchappe(rendre("entree", Map.of("titre", "Entree", "societeId", 9L, "droits", List.of(XSS))));
	}

	@Test
	void rendLesPagesFixesEtListeVide() {
		assertTrue(rendre("liste", Map.of("titre", "Clients", "clients", List.of())).contains("Aucun client"));
		assertTrue(rendre("refuse", Map.of("titre", "Refus")).contains("Accès refusé"));
		assertTrue(rendre("introuvable", Map.of("titre", "x")).contains("introuvable"));
		assertTrue(rendre("erreur", Map.of("titre", "x")).contains("erreur"));
		String entree = rendre("entree", Map.of("titre", "x", "societeId", 9L, "droits", List.of("a.b")));
		assertTrue(entree.contains("L'espace client n'est pas encore disponible : voici le périmètre qui vous est accordé."));
		assertFalse(entree.contains("<a "), "aucun lien sur la page d'entree");
	}

}
