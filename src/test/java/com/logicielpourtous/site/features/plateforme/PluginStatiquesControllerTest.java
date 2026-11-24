package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Service des fichiers {@code static/} du plugin ACTIF sous {@code /p/<nom>/static/<fichier>}. */
class PluginStatiquesControllerTest {

	@TempDir
	Path dossier;

	private MockMvc mvc;

	private PluginGeneration gen;

	@BeforeEach
	void preparer() throws Exception {
		mvc = MockMvcBuilders.standaloneSetup(new PluginStatiquesController()).build();
		Path jar = dossier.resolve("alpha-1.0.0.jar");
		Map<String, String> entrees = new LinkedHashMap<>();
		entrees.put("static/app.css", "a{color:red}");
		entrees.put("static/app.js", "console.log(1);");
		entrees.put("static/logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>");
		entrees.put("static/img.png", "PNG");
		entrees.put("static/photo.jpg", "JPG");
		entrees.put("static/police.woff2", "WOFF");
		entrees.put("static/note.txt", "interdit");
		entrees.put("static/page.html", "<h1>x</h1>");
		entrees.put("static/data.json", "{}");
		entrees.put("static/sans-extension", "x");
		entrees.put("secret.js", "SECRET");
		entrees.put("ui/main.js", "ui");
		entrees.put("templates/plugins/alpha/page.html", "<p>gabarit</p>");
		entrees.put("plugin.properties", "nom=alpha\nversion=1.0.0\n");
		try (OutputStream sortie = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(sortie)) {
			for (Map.Entry<String, String> e : entrees.entrySet()) {
				jos.putNextEntry(new JarEntry(e.getKey()));
				jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				jos.closeEntry();
			}
		}
		gen = new PluginGeneration("alpha", "1.0.0", jar);
		gen.loader = new PluginClassLoader(gen.etiquette(), jar.toUri().toURL(), getClass().getClassLoader());
	}

	@AfterEach
	void fermer() throws Exception {
		gen.loader.close();
	}

	private MockHttpServletRequestBuilder requete(String uri, Object generation) {
		return get(URI.create(uri)).requestAttr(RoutagePlugins.ATTRIBUT_GENERATION, generation);
	}

	@Test
	void sert_chaque_type_autorise_avec_son_content_type_et_nosniff() throws Exception {
		Map<String, String> types = Map.of("app.css", "text/css", "app.js", "text/javascript", "logo.svg",
				"image/svg+xml", "img.png", "image/png", "photo.jpg", "image/jpeg", "police.woff2", "font/woff2");
		for (Map.Entry<String, String> t : types.entrySet()) {
			mvc.perform(requete("/p/alpha/static/" + t.getKey(), gen)).andExpect(status().isOk())
					.andExpect(header().string("Content-Type", t.getValue()))
					.andExpect(header().string("X-Content-Type-Options", "nosniff"));
		}
		mvc.perform(requete("/p/alpha/static/app.css", gen)).andExpect(content().string("a{color:red}"));
	}

	@Test
	void le_svg_est_neutralise_par_une_politique_de_securite() throws Exception {
		MvcResult r = mvc.perform(requete("/p/alpha/static/logo.svg", gen)).andExpect(status().isOk()).andReturn();
		assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'none'")
				.contains("sandbox");
		// Les autres types ne portent pas cette politique.
		assertThat(mvc.perform(requete("/p/alpha/static/app.css", gen)).andReturn().getResponse()
				.getHeader("Content-Security-Policy")).isNull();
	}

	@Test
	void tout_refus_est_le_meme_404_sans_corps() throws Exception {
		String[] refus = { "/p/alpha/static/note.txt", "/p/alpha/static/page.html", "/p/alpha/static/data.json",
				"/p/alpha/static/sans-extension", "/p/alpha/static/absent.css", "/p/alpha/static/..%2fsecret.js",
				"/p/alpha/static/%2e%2e%2fsecret.js", "/p/alpha/static/..%5csecret.js", "/p/alpha/static/a..b.css",
				"/p/alpha/static/.css", "/p/alpha/static/app.css.", "/p/alpha/static/ap%00p.css",
				"/p/alpha/static/ap%20p.css", "/p/alpha/static/app.css.txt" };
		for (String uri : refus) {
			MvcResult r = mvc.perform(requete(uri, gen)).andReturn();
			assertThat(r.getResponse().getStatus()).as(uri).isEqualTo(404);
			assertThat(r.getResponse().getContentAsByteArray()).as(uri).isEmpty();
			assertThat(r.getResponse().getHeader("Content-Type")).as(uri).isNull();
		}
	}

	@Test
	void plugin_inconnu_ou_generation_absente_donnent_le_meme_404() throws Exception {
		mvc.perform(requete("/p/inconnu/static/app.css", gen)).andExpect(status().isNotFound())
				.andExpect(content().string(""));
		mvc.perform(requete("/p/alpha/static/app.css", RoutagePlugins.AUCUNE_GENERATION))
				.andExpect(status().isNotFound()).andExpect(content().string(""));
		mvc.perform(get("/p/alpha/static/app.css")).andExpect(status().isNotFound()).andExpect(content().string(""));
	}

	@Test
	void ne_sert_que_le_dossier_static_du_jar() throws Exception {
		// secret.js, ui/main.js et templates/ sont dans le jar mais hors de static/.
		mvc.perform(requete("/p/alpha/static/secret.js", gen)).andExpect(status().isNotFound());
		mvc.perform(requete("/p/alpha/static/main.js", gen)).andExpect(status().isNotFound());
	}

	@Test
	void ne_retombe_pas_sur_le_chargeur_parent() throws Exception {
		// Un fichier static/ du cœur n'est jamais servi pour un plugin qui n'a pas le sien.
		Path jarVide = dossier.resolve("beta-1.0.0.jar");
		try (OutputStream sortie = Files.newOutputStream(jarVide); JarOutputStream jos = new JarOutputStream(sortie)) {
			jos.putNextEntry(new JarEntry("plugin.properties"));
			jos.write("nom=beta\nversion=1.0.0\n".getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		Path racineParent = dossier.resolve("parent");
		Files.createDirectories(racineParent.resolve("static"));
		Files.writeString(racineParent.resolve("static/coeur.css"), "COEUR");
		PluginGeneration beta = new PluginGeneration("beta", "1.0.0", jarVide);
		try (java.net.URLClassLoader parent = new java.net.URLClassLoader(
				new java.net.URL[] { racineParent.toUri().toURL() }, getClass().getClassLoader())) {
			beta.loader = new PluginClassLoader(beta.etiquette(), jarVide.toUri().toURL(), parent);
			try {
				mvc.perform(requete("/p/beta/static/coeur.css", beta)).andExpect(status().isNotFound());
			}
			finally {
				beta.loader.close();
			}
		}
	}

	@Test
	void generation_sans_chargeur_donne_404() throws Exception {
		PluginGeneration sansChargeur = new PluginGeneration("alpha", "1.0.0", gen.jarCopie);
		mvc.perform(requete("/p/alpha/static/app.css", sansChargeur)).andExpect(status().isNotFound());
	}

}
