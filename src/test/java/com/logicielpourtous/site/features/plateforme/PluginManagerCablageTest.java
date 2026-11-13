package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Câblage Spring des beans de la plateforme : présents seulement avec {@code app.plateforme.actif=true}. */
class PluginManagerCablageTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:postgresql://localhost:1/inutilisee"))
		.withUserConfiguration(PlateformeConfiguration.class, PluginManager.class, DemarreurContexte.class,
				RoutagePlugins.class, ReferencesEntrantes.class, NettoyeurFuites.class);

	@Test
	void plateforme_active_cree_le_gestionnaire_et_lie_les_proprietes() {
		runner.withPropertyValues("app.plateforme.actif=true", "app.plateforme.delai-drainage-ms=1234")
			.run(contexte -> {
				assertThat(contexte).hasNotFailed();
				assertThat(contexte).hasSingleBean(PluginManager.class);
				assertThat(contexte).hasSingleBean(ContratRegistre.class);
				assertThat(contexte).hasSingleBean(NettoyeurFuites.class);
				assertThat(contexte.getBean(PlateformeProperties.class).isNettoyageActif()).isTrue();
				assertThat(contexte.getBean(PlateformeProperties.class).getDelaiDrainageMs()).isEqualTo(1234);
				assertThat(contexte.getBean(PluginManager.class).actifs()).isEmpty();
			});
	}

	@Test
	void plateforme_inactive_ne_cree_aucun_bean() {
		runner.run(contexte -> {
			assertThat(contexte).hasNotFailed();
			assertThat(contexte).doesNotHaveBean(PluginManager.class);
			assertThat(contexte).doesNotHaveBean(ContratRegistre.class);
			assertThat(contexte).doesNotHaveBean(PlateformeProperties.class);
			assertThat(contexte).doesNotHaveBean(ReferencesEntrantes.class);
			assertThat(contexte).doesNotHaveBean(NettoyeurFuites.class);
		});
	}

}
