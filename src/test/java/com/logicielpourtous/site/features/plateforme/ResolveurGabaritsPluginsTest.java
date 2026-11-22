package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;

/**
 * Résolution des gabarits livrés par les plugins : uniquement {@code plugins/<nom>/<chemin>}, uniquement dans le jar
 * de la génération ACTIVE du plugin, jamais dans le cœur (ni l'inverse).
 */
class ResolveurGabaritsPluginsTest {

	@TempDir
	Path dossier;

	private final Map<String, PluginGeneration> actifs = new ConcurrentHashMap<>();

	private ResolveurGabaritsPlugins resolveur;

	private TemplateEngine moteur;

	private final List<PluginGeneration> ouvertes = new ArrayList<>();

	private URLClassLoader parent;

	@BeforeEach
	void preparer() throws Exception {
		// Le « cœur » : un dossier avec une mise en page et un gabarit SOUS plugins/ (piège).
		Path coeur = dossier.resolve("coeur");
		Files.createDirectories(coeur.resolve("socle"));
		Files.createDirectories(coeur.resolve("plugins/demo"));
		Files.writeString(coeur.resolve("socle/layout.html"),
				"<html xmlns:layout=\"http://www.ultraq.net.nz/thymeleaf/layout\"><body>CADRE-COEUR["
						+ "<div layout:fragment=\"contenu\"></div>]</body></html>");
		Files.writeString(coeur.resolve("layout.html"), "<p>LAYOUT-COEUR</p>");
		Files.writeString(coeur.resolve("plugins/demo/coeur.html"), "<p>SECRET-DU-COEUR</p>");
		FileTemplateResolver noyau = new FileTemplateResolver();
		noyau.setPrefix(coeur + "/");
		noyau.setSuffix(".html");
		noyau.setTemplateMode(TemplateMode.HTML);
		noyau.setCharacterEncoding("UTF-8");
		noyau.setOrder(100);
		resolveur = new ResolveurGabaritsPlugins(actifs::get);
		moteur = new TemplateEngine();
		moteur.addTemplateResolver(resolveur);
		moteur.addTemplateResolver(ResolveurGabaritsPlugins.sansEspacePlugins(noyau));
		moteur.addDialect(new LayoutDialect());
		// Le chargeur parent d'un plugin porte des gabarits sous plugins/demo : ils ne doivent jamais être servis.
		Path racineParent = dossier.resolve("parent");
		Files.createDirectories(racineParent.resolve("templates/plugins/demo"));
		Files.writeString(racineParent.resolve("templates/plugins/demo/parent.html"), "<p>PARENT</p>");
		parent = new URLClassLoader(new java.net.URL[] { racineParent.toUri().toURL() }, getClass().getClassLoader());
	}

	@AfterEach
	void fermer() throws Exception {
		for (PluginGeneration g : ouvertes) {
			g.loader.close();
		}
		parent.close();
	}

	private PluginGeneration generation(String nom, String version, Map<String, String> entrees) throws Exception {
		Path jar = dossier.resolve(nom + "-" + version + "-" + ouvertes.size() + ".jar");
		Map<String, String> toutes = new LinkedHashMap<>(entrees);
		toutes.put("plugin.properties", "nom=" + nom + "\nversion=" + version + "\n");
		try (OutputStream sortie = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(sortie)) {
			for (Map.Entry<String, String> e : toutes.entrySet()) {
				jos.putNextEntry(new JarEntry(e.getKey()));
				jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				jos.closeEntry();
			}
		}
		PluginGeneration gen = new PluginGeneration(nom, version, jar);
		gen.loader = new PluginClassLoader(gen.etiquette(), jar.toUri().toURL(), parent);
		ouvertes.add(gen);
		return gen;
	}

	private PluginGeneration demo(String texte) throws Exception {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("templates/plugins/demo/page.html", "<p th:text=\"${x}\">" + texte + "</p>");
		m.put("templates/plugins/demo/sous/dossier/fiche.html", "<p>fiche " + texte + "</p>");
		m.put("templates/plugins/demo/decore.html",
				"<html xmlns:layout=\"http://www.ultraq.net.nz/thymeleaf/layout\" layout:decorate=\"~{socle/layout}\">"
						+ "<body><div layout:fragment=\"contenu\">" + texte + "</div></body></html>");
		// Pièges : des gabarits du plugin portant le nom de gabarits du cœur, et un chemin avec remontée.
		m.put("templates/layout.html", "<p>LAYOUT-PIRATE</p>");
		m.put("templates/socle/layout.html", "<p>SOCLE-PIRATE</p>");
		m.put("templates/plugins/demo/../../layout.html", "<p>REMONTEE-PIRATE</p>");
		m.put("page.txt", "texte");
		return generation("demo", "1.0.0", m);
	}

	private TemplateResolution resoudre(String nom) {
		return resolveur.resolveTemplate(moteur.getConfiguration(), null, nom, Map.of());
	}

	private String rendre(String nom) {
		Context c = new Context();
		c.setVariable("x", "<b>&valeur</b>");
		return moteur.process(nom, c);
	}

	@Test
	void resout_un_gabarit_du_plugin_actif() throws Exception {
		actifs.put("demo", demo("v1"));
		TemplateResolution r = resoudre("plugins/demo/page");
		assertThat(r).isNotNull();
		assertThat(r.getTemplateMode()).isEqualTo(TemplateMode.HTML);
		assertThat(r.getValidity().isCacheable()).as("jamais de cache pour un gabarit de plugin").isFalse();
		assertThat(resoudre("plugins/demo/sous/dossier/fiche")).isNotNull();
	}

	@Test
	void le_texte_du_modele_est_echappe() throws Exception {
		actifs.put("demo", demo("v1"));
		assertThat(rendre("plugins/demo/page")).isEqualTo("<p>&lt;b&gt;&amp;valeur&lt;/b&gt;</p>");
	}

	@Test
	void ne_resout_pas_les_noms_hors_forme() throws Exception {
		actifs.put("demo", demo("v1"));
		String[] noms = { "plugins/demo/../../layout", "plugins/demo/..\\x", "plugins/demo/../page",
				"plugins/inconnu/page", "plugins/demo/page.txt", "plugins/demo/page.html", "layout", "plugins/demo/",
				"plugins/demo", "plugins/", "plugins", "socle/layout", "/plugins/demo/page", "plugins//demo/page",
				"plugins/demo//page", "plugins/demo/Page", "plugins/Demo/page", "plugins/demo/pa ge",
				"plugins/demo/pa\u0000ge", "plugins/demo/pa\nge", "plugins/demo/./page", "plugins/demo/page/",
				"plugins/demo/pagé", "plugins/de.mo/page", "plugins/demo/%2e%2e/layout",
				"plugins/demo/page::frag", "Plugins/demo/page", "", " ", "plugins/../layout",
				"plugins/demo/" + "a".repeat(400) };
		for (String nom : noms) {
			assertThat(resoudre(nom)).as("[%s]", nom).isNull();
		}
		assertThat(resolveur.resolveTemplate(moteur.getConfiguration(), null, null, Map.of())).isNull();
	}

	@Test
	void plugin_inconnu_ou_retire_n_est_plus_resolu() throws Exception {
		PluginGeneration gen = demo("v1");
		assertThat(resoudre("plugins/demo/page")).as("pas encore actif").isNull();
		actifs.put("demo", gen);
		assertThat(resoudre("plugins/demo/page")).isNotNull();
		actifs.remove("demo");
		assertThat(resoudre("plugins/demo/page")).isNull();
		assertThatThrownBy(() -> rendre("plugins/demo/page")).isInstanceOf(TemplateInputException.class);
	}

	@Test
	void la_mise_a_jour_a_chaud_rend_le_nouveau_contenu_jamais_l_ancien() throws Exception {
		actifs.put("demo", demo("v1"));
		assertThat(rendre("plugins/demo/sous/dossier/fiche")).contains("fiche v1");
		actifs.put("demo", demo("v2"));
		assertThat(rendre("plugins/demo/sous/dossier/fiche")).contains("fiche v2").doesNotContain("v1");
		actifs.remove("demo");
		assertThatThrownBy(() -> rendre("plugins/demo/sous/dossier/fiche")).isInstanceOf(TemplateInputException.class);
	}

	@Test
	void ne_retombe_jamais_sur_le_chargeur_parent() throws Exception {
		actifs.put("demo", demo("v1"));
		assertThat(resoudre("plugins/demo/parent")).as("gabarit du seul parent").isNull();
		assertThatThrownBy(() -> rendre("plugins/demo/parent")).isInstanceOf(TemplateInputException.class);
	}

	@Test
	void un_plugin_ne_masque_aucun_gabarit_du_coeur() throws Exception {
		actifs.put("demo", demo("v1"));
		// Le jar du plugin contient templates/layout.html et templates/socle/layout.html : jamais servis.
		assertThat(rendre("layout")).isEqualTo("<p>LAYOUT-COEUR</p>");
		assertThat(resoudre("layout")).isNull();
		assertThat(resoudre("socle/layout")).isNull();
		// Et le plugin décore bien avec la mise en page du cœur.
		String html = rendre("plugins/demo/decore");
		assertThat(html).contains("CADRE-COEUR[").contains("v1").doesNotContain("PIRATE");
	}

	@Test
	void un_gabarit_du_coeur_sous_plugins_n_est_jamais_servi_pour_un_nom_de_plugin() throws Exception {
		actifs.put("demo", demo("v1"));
		// Le cœur a un fichier plugins/demo/coeur.html : le plugin « demo » ne l'a pas, rien ne doit le servir.
		assertThatThrownBy(() -> rendre("plugins/demo/coeur")).isInstanceOf(TemplateInputException.class);
		// Même pour un plugin inconnu.
		actifs.clear();
		assertThatThrownBy(() -> rendre("plugins/demo/coeur")).isInstanceOf(TemplateInputException.class);
	}

	@Test
	void le_garde_laisse_passer_les_noms_du_noyau_et_bloque_tout_plugins() throws Exception {
		FileTemplateResolver brut = new FileTemplateResolver();
		brut.setPrefix(dossier.resolve("coeur") + "/");
		brut.setSuffix(".html");
		ITemplateResolver garde = ResolveurGabaritsPlugins.sansEspacePlugins(brut);
		assertThat(garde.getOrder()).isNull();
		assertThat(garde.resolveTemplate(moteur.getConfiguration(), null, "plugins/x/y", Map.of())).isNull();
		assertThat(garde.resolveTemplate(moteur.getConfiguration(), null, "plugins/", Map.of())).isNull();
		for (String nom : new String[] { "Plugins/x/y", "/plugins/x/y", "PLUGINS/x", "plugins", "plugins\\x\\y",
				"\\plugins/x" }) {
			assertThat(garde.resolveTemplate(moteur.getConfiguration(), null, nom, Map.of())).as(nom).isNull();
		}
		assertThat(garde.resolveTemplate(moteur.getConfiguration(), null, "pluginsx/y", Map.of())).isNotNull();
		assertThat(garde.resolveTemplate(moteur.getConfiguration(), null, null, Map.of())).isNull();
		assertThat(garde.resolveTemplate(moteur.getConfiguration(), null, "socle/layout", Map.of())).isNotNull();
	}

}
