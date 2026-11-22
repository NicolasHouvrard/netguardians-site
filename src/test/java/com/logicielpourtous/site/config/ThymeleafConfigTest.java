package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

import com.logicielpourtous.site.features.plateforme.PluginManager;
import com.logicielpourtous.site.features.plateforme.ResolveurGabaritsPlugins;

/** Composition des résolveurs du moteur : plugins (ordre 1) puis cœur enveloppé, qui refuse l'espace plugins/. */
class ThymeleafConfigTest {

	@Test
	void le_moteur_a_le_resolveur_plugins_en_premier_et_le_noyau_enveloppe_ensuite() {
		ClassLoaderTemplateResolver noyau = new ClassLoaderTemplateResolver();
		noyau.setPrefix("templates/");
		noyau.setSuffix(".html");
		noyau.setTemplateMode(TemplateMode.HTML);
		ResolveurGabaritsPlugins plugins = new ResolveurGabaritsPlugins(Mockito.mock(PluginManager.class));

		// Ordre d'injection volontairement inverse : c'est l'ordre des résolveurs qui doit décider.
		SpringTemplateEngine moteur = new ThymeleafConfig().templateEngine(List.of(noyau, plugins));

		List<ITemplateResolver> resolveurs = new ArrayList<>(moteur.getConfiguration().getTemplateResolvers());
		assertThat(resolveurs).hasSize(2);
		assertThat(resolveurs.get(0)).isSameAs(plugins);
		assertThat(resolveurs.get(0).getOrder()).isEqualTo(1);
		assertThat(resolveurs.get(1)).isNotSameAs(noyau);
		assertThat(resolveurs.get(1).getOrder()).isEqualTo(100);
		// Le cœur brut résoudrait ce nom ; enveloppé, il le refuse. Les noms du cœur passent.
		assertThat(noyau.resolveTemplate(moteur.getConfiguration(), null, "plugins/x/y", Map.of())).isNotNull();
		assertThat(resolveurs.get(1).resolveTemplate(moteur.getConfiguration(), null, "plugins/x/y", Map.of()))
				.isNull();
		assertThat(resolveurs.get(1).resolveTemplate(moteur.getConfiguration(), null, "socle/layout", Map.of()))
				.isNotNull();
	}

	@Test
	void sans_plateforme_le_moteur_n_a_que_le_noyau() {
		ClassLoaderTemplateResolver noyau = new ClassLoaderTemplateResolver();
		SpringTemplateEngine moteur = new ThymeleafConfig().templateEngine(List.of(noyau));
		assertThat(moteur.getConfiguration().getTemplateResolvers()).hasSize(1);
	}

}
