package com.logicielpourtous.site.features.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import java.util.Locale;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;

/** Rendu Thymeleaf autonome de la mise en page du socle (sans Spring, sans base). */
class SocleLayoutTest {

	private static final List<String> REFERENCES = List.of("/css/vendor/bootstrap.min.css",
			"/css/vendor/bootstrap-icons.min.css", "/css/socle.css", "/css/theme.css",
			"/js/vendor/bootstrap.bundle.min.js", "/js/vendor/htmx.min.js", "/js/socle.js");

	private SpringTemplateEngine moteur;

	@BeforeEach
	void init() {
		ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
		r.setPrefix("templates/");
		r.setSuffix(".html");
		r.setTemplateMode(TemplateMode.HTML);
		r.setCharacterEncoding("UTF-8");
		moteur = new SpringTemplateEngine();
		moteur.setTemplateResolver(r);
		moteur.addDialect(new LayoutDialect());
	}

	private String rendre(Map<String, Object> variables) {
		MockServletContext sc = new MockServletContext();
		JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(sc);
		IWebExchange echange = app.buildExchange(new MockHttpServletRequest(sc), new MockHttpServletResponse());
		WebContext ctx = new WebContext(echange, Locale.FRENCH);
		ctx.setVariables(variables);
		return moteur.process("testsocle/page", ctx);
	}

	@Test
	void sept_references_dans_l_ordre() {
		String html = rendre(Map.of("titre", "T"));
		int pos = -1;
		for (String ref : REFERENCES) {
			int i = html.indexOf(ref);
			assertThat(i).as(ref).isGreaterThan(pos);
			pos = i;
		}
		assertThat(html).contains("id=\"toasts\"").contains("id=\"corps\"");
	}

	@Test
	void aucune_url_absolue_dans_href_et_src() {
		String html = rendre(Map.of("titre", "T"));
		Matcher m = Pattern.compile("(?:href|src)=\"([^\"]*)\"").matcher(html);
		int n = 0;
		while (m.find()) {
			n++;
			assertThat(m.group(1)).doesNotStartWith("http://").doesNotStartWith("https://").doesNotStartWith("//");
		}
		assertThat(n).isGreaterThan(6);
	}

	@Test
	void chaque_fichier_reference_existe() {
		for (String ref : REFERENCES) {
			if (ref.equals("/css/theme.css")) {
				continue; // fourni par la tache 5
			}
			assertThat(getClass().getResource("/static" + ref)).as(ref).isNotNull();
		}
		assertThat(getClass().getResource("/static/css/vendor/fonts/bootstrap-icons.woff2")).isNotNull();
	}


	/** Imite CsrfToken (accesseurs getXxx, comme le vrai jeton Spring Security). */
	public static class Jeton {

		private final String token;

		private final String headerName;

		public Jeton(String token, String headerName) {
			this.token = token;
			this.headerName = headerName;
		}

		public String getToken() {
			return token;
		}

		public String getHeaderName() {
			return headerName;
		}
	}
	@Test
	void meta_csrf_presentes_puis_absentes_sans_erreur() {
		String avec = rendre(Map.of("titre", "T", "_csrf", new Jeton("abc123", "X-CSRF-TOKEN")));
		assertThat(avec).contains("name=\"_csrf\"").contains("content=\"abc123\"")
				.contains("name=\"_csrf_header\"").contains("content=\"X-CSRF-TOKEN\"");
		String sans = rendre(Map.of("titre", "T"));
		assertThat(sans).doesNotContain("name=\"_csrf\"").doesNotContain("name=\"_csrf_header\"");
	}

	@Test
	void tuiles_en_liens_et_libelle_echappe() {
		List<Tuile> tuiles = List.of(new Tuile("p1", "Clients", "bi-people", "/clients", null, "1"),
				new Tuile("p2", "<b>x</b>", "bi-box", "/autre", null, "1"));
		String html = rendre(Map.of("titre", "T", "tuiles", tuiles));
		assertThat(html).contains("href=\"/clients\"").contains("Clients").contains("href=\"/autre\"")
				.contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>");
	}

	@Test
	void configuration_htmx_sans_cache_d_historique_et_sans_requete_hors_site() {
		String html = rendre(Map.of("titre", "T"));
		assertThat(html).contains("<meta name=\"htmx-config\"")
				.contains("\"historyCacheSize\":0").contains("\"selfRequestsOnly\":true");
	}

	@Test
	void titre_sert_de_title() {
		String html = rendre(Map.of("titre", "Mon titre"));
		assertThat(html).containsPattern("<title>[^<]*Mon titre[^<]*</title>");
	}
}
