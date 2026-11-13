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
				RoutagePlugins.class, ReferencesEntrantes.class, NettoyeurFuites.class, ContratIndisponibleAdvice.class);

	@Test
	void plateforme_active_cree_le_gestionnaire_et_lie_les_proprietes() {
		runner.withPropertyValues("app.plateforme.actif=true", "app.plateforme.delai-drainage-ms=1234")
			.run(contexte -> {
				assertThat(contexte).hasNotFailed();
				assertThat(contexte).hasSingleBean(PluginManager.class);
				assertThat(contexte).hasSingleBean(ContratRegistre.class);
				assertThat(contexte).hasSingleBean(NettoyeurFuites.class);
				assertThat(contexte.getBean(PlateformeProperties.class).isNettoyageActif()).isTrue();
				assertThat(contexte.getBean(PlateformeProperties.class).isDiagnosticFuites()).isFalse();
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
			assertThat(contexte).doesNotHaveBean(ContratIndisponibleAdvice.class);
		});
	}

	@Test
	void plateforme_active_avertit_que_les_jars_ne_sont_pas_signes() {
		ch.qos.logback.classic.Logger journal = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger(PluginManager.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture = new ch.qos.logback.core.read.ListAppender<>();
		capture.start();
		journal.addAppender(capture);
		try {
			runner.withPropertyValues("app.plateforme.actif=true").run(contexte -> {
				assertThat(contexte).hasNotFailed();
				assertThat(contexte).hasSingleBean(ContratIndisponibleAdvice.class);
			});
		}
		finally {
			journal.detachAppender(capture);
		}
		assertThat(capture.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
				&& e.getFormattedMessage().contains("non signés")
				&& e.getFormattedMessage().contains("sous-projet 2"));
	}

}
