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

/** Service des fichiers {@code ui/} du plugin ACTIF : types admis, chemins stricts, version = celle du plugin actif. */
class PluginUiControllerTest {

	@TempDir
	Path dossier;

	private MockMvc mvc;

	private PluginGeneration v1;

	private PluginGeneration v2;

	@BeforeEach
	void preparer() throws Exception {
		mvc = MockMvcBuilders.standaloneSetup(new PluginUiController()).build();
		v1 = generation("1.0.0", "console.log('v1');");
		v2 = generation("2.0.0", "console.log('v2');");
	}

	@AfterEach
	void fermer() throws Exception {
		v1.loader.close();
		v2.loader.close();
	}

	private PluginGeneration generation(String version, String js) throws Exception {
		Path jar = dossier.resolve("alpha-" + version + ".jar");
		Map<String, String> entrees = new LinkedHashMap<>();
		entrees.put("ui/main.js", js);
		entrees.put("ui/style.css", "a{color:red}");
		entrees.put("ui/data.json", "{\"a\":1}");
		entrees.put("ui/note.txt", "interdit");
		entrees.put("ui/page.html", "<h1>x</h1>");
		entrees.put("secret.js", "SECRET");
		entrees.put("plugin.properties", "nom=alpha\nversion=" + version + "\n");
		try (OutputStream sortie = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(sortie)) {
			for (Map.Entry<String, String> e : entrees.entrySet()) {
				jos.putNextEntry(new JarEntry(e.getKey()));
				jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				jos.closeEntry();
			}
		}
		PluginGeneration gen = new PluginGeneration("alpha", version, jar);
		gen.loader = new PluginClassLoader(gen.etiquette(), jar.toUri().toURL(), getClass().getClassLoader());
		return gen;
	}

	private MockHttpServletRequestBuilder requete(String uri, Object generation) {
		return get(URI.create(uri)).requestAttr(RoutagePlugins.ATTRIBUT_GENERATION, generation);
	}

	@Test
	void sert_le_js_avec_type_et_en_tetes_de_cache() throws Exception {
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/main.js", v1))
				.andExpect(status().isOk())
				.andExpect(content().string("console.log('v1');"))
				.andExpect(header().string("Content-Type", "text/javascript"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));
	}

	@Test
	void sert_css_et_json_avec_le_bon_type() throws Exception {
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/style.css", v1)).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/css"));
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/data.json", v1)).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/json"));
	}

	@Test
	void extension_non_admise_refusee() throws Exception {
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/note.txt", v1)).andExpect(status().isNotFound());
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/page.html", v1)).andExpect(status().isNotFound());
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/main", v1)).andExpect(status().isNotFound());
	}

	@Test
	void traversee_de_chemin_refusee_sous_toutes_ses_formes() throws Exception {
		for (String fichier : new String[] { "..", "%2e%2e", "..%2fsecret.js", "%2e%2e%2fsecret.js", "..%5csecret.js",
				"%2e%2e%5csecret.js", "%2fsecret.js", "%00main.js", "main.js%00.css", "..%2f..%2fplugin.properties",
				"a%2fmain.js", "%5Csecret.js", ".main.js.", "ma%20in.js" }) {
			MvcResult res = mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/" + fichier, v1)).andReturn();
			assertThat(res.getResponse().getStatus()).as(fichier).isIn(400, 404);
			assertThat(res.getResponse().getContentAsString()).as(fichier).doesNotContain("SECRET");
		}
	}

	@Test
	void le_fichier_hors_du_dossier_ui_n_est_jamais_servi() throws Exception {
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/secret.js", v1)).andExpect(status().isNotFound());
	}

	@Test
	void fichier_inexistant_404() throws Exception {
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/absent.js", v1)).andExpect(status().isNotFound());
	}

	@Test
	void plugin_inconnu_404() throws Exception {
		mvc.perform(requete("/api/plugins/inconnu/ui/1.0.0/main.js", RoutagePlugins.AUCUNE_GENERATION))
				.andExpect(status().isNotFound());
		mvc.perform(get(URI.create("/api/plugins/inconnu/ui/1.0.0/main.js"))).andExpect(status().isNotFound());
	}

	@Test
	void generation_d_un_autre_plugin_404() throws Exception {
		mvc.perform(requete("/api/plugins/beta/ui/1.0.0/main.js", v1)).andExpect(status().isNotFound());
	}

	@Test
	void version_differente_de_celle_du_plugin_actif_404() throws Exception {
		mvc.perform(requete("/api/plugins/alpha/ui/0.9.0/main.js", v1)).andExpect(status().isNotFound());
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0-evil/main.js", v1)).andExpect(status().isNotFound());
	}

	@Test
	void mise_a_jour_a_chaud_ancienne_url_404_et_nouvelle_url_servie() throws Exception {
		mvc.perform(requete("/api/plugins/alpha/ui/1.0.0/main.js", v2)).andExpect(status().isNotFound());
		mvc.perform(requete("/api/plugins/alpha/ui/2.0.0/main.js", v2)).andExpect(status().isOk())
				.andExpect(content().string("console.log('v2');"));
	}

}
