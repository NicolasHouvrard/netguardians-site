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
				assertThat(contexte.getBean(PlateformeProperties.class).isSignatureObligatoire()).isTrue();
				assertThat(contexte.getBean(PlateformeProperties.class).getTailleMaxJarMo()).isEqualTo(100);
				// Clés de confiance lues dans la ressource du cœur (clés de développement « dev- »).
				assertThat(contexte.getBean(ClesPubliques.class).cles()).hasSizeGreaterThanOrEqualTo(2);
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
			assertThat(contexte).doesNotHaveBean(ClesPubliques.class);
		});
	}

	@Test
	void taille_max_jar_hors_bornes_empeche_le_demarrage_avec_un_message_clair() {
		for (String valeur : java.util.List.of("0", "-5", "1025", "9223372036854775807")) {
			runner.withPropertyValues("app.plateforme.actif=true", "app.plateforme.taille-max-jar-mo=" + valeur)
				.run(contexte -> {
					assertThat(contexte).hasFailed();
					Throwable cause = contexte.getStartupFailure();
					while (cause.getCause() != null) {
						cause = cause.getCause();
					}
					assertThat(cause).isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("app.plateforme.taille-max-jar-mo doit être compris entre 1 et 1024");
				});
		}
		runner.withPropertyValues("app.plateforme.actif=true", "app.plateforme.taille-max-jar-mo=1024")
			.run(contexte -> assertThat(contexte).hasNotFailed());
	}

	/** Journal du gestionnaire capturé pendant le démarrage d'un contexte avec les propriétés données. */
	private java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> journalAuDemarrage(String... proprietes) {
		ch.qos.logback.classic.Logger journal = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger(PluginManager.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture = new ch.qos.logback.core.read.ListAppender<>();
		capture.start();
		journal.addAppender(capture);
		try {
			runner.withPropertyValues(proprietes).run(contexte -> {
				assertThat(contexte).hasNotFailed();
				assertThat(contexte).hasSingleBean(ContratIndisponibleAdvice.class);
			});
		}
		finally {
			journal.detachAppender(capture);
		}
		return capture.list;
	}

	@Test
	void signature_non_obligatoire_avertit_que_les_jars_ne_sont_pas_verifies() {
		assertThat(journalAuDemarrage("app.plateforme.actif=true", "app.plateforme.signature-obligatoire=false"))
			.anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
					&& e.getFormattedMessage().contains("non signés")
					&& e.getFormattedMessage().contains("signature-obligatoire=false"));
	}

	@Test
	void signature_obligatoire_par_defaut_n_avertit_pas_de_jars_non_signes() {
		assertThat(journalAuDemarrage("app.plateforme.actif=true"))
			.noneMatch(e -> e.getFormattedMessage().contains("non signés"));
	}

	/** T4-M6 : dépôt actif par repli (app.role absent ou invalide = MASTER) : avertissement au démarrage. */
	@Test
	void depot_actif_par_repli_du_role_avertit_au_demarrage() {
		ch.qos.logback.classic.Logger journal = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger(DepotPlugins.class);
		ApplicationContextRunner avecDepot = runner.withUserConfiguration(DepotPlugins.class);
		java.util.Map<String, Boolean> attendu = new java.util.LinkedHashMap<>();
		attendu.put("", true);
		attendu.put("app.role=", true);
		attendu.put("app.role=maitre", true);
		attendu.put("app.role=MASTER", false);
		attendu.put("app.role=master", false);
		attendu.forEach((role, avertit) -> {
			ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture = new ch.qos.logback.core.read.ListAppender<>();
			capture.start();
			journal.addAppender(capture);
			try {
				ApplicationContextRunner r = avecDepot.withPropertyValues("app.plateforme.actif=true");
				if (!role.isEmpty()) {
					r = r.withPropertyValues(role);
				}
				r.run(contexte -> assertThat(contexte).hasSingleBean(DepotPlugins.class));
			}
			finally {
				journal.detachAppender(capture);
			}
			boolean vu = capture.list.stream()
				.anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
						&& e.getFormattedMessage().contains("app.role")
						&& e.getFormattedMessage().contains("MASTER"));
			assertThat(vu).as("avertissement pour '%s'", role).isEqualTo(avertit);
		});
	}

	/** Tireur : dépôt local (sans HTTP) sur le Maître, Maître par HTTP ailleurs ; rien sans la plateforme. */
	@Test
	void tireur_tire_du_depot_local_sur_le_maitre_et_du_maitre_par_http_ailleurs() {
		ApplicationContextRunner avecTireur = runner.withUserConfiguration(TireurPlugins.class, SourceDepotLocal.class,
				DepotPlugins.class);
		for (String role : java.util.List.of("app.role=MASTER", "app.role=master")) {
			avecTireur.withPropertyValues("app.plateforme.actif=true", role).run(contexte -> {
				assertThat(contexte).hasNotFailed();
				assertThat(contexte).hasSingleBean(TireurPlugins.class);
				assertThat(contexte.getBean(SourcePlugins.class)).isInstanceOf(SourceDepotLocal.class);
				assertThat(contexte).doesNotHaveBean(SourceMaitreHttp.class);
				assertThat(contexte.getBean(PlateformeProperties.class).getIntervalleTirageMs()).isEqualTo(600_000);
			});
		}
		for (String role : java.util.List.of("partner", "CLIENT", "public")) {
			avecTireur
				.withPropertyValues("app.plateforme.actif=true", "app.role=" + role,
						"app.licence.master-url=http://maitre.exemple:8091")
				.run(contexte -> {
					assertThat(contexte).hasNotFailed();
					assertThat(contexte).hasSingleBean(TireurPlugins.class);
					assertThat(contexte.getBean(SourcePlugins.class)).isInstanceOf(SourceMaitreHttp.class);
					assertThat(contexte).doesNotHaveBean(SourceDepotLocal.class);
					assertThat(contexte.getBean(SourceMaitreHttp.class).urlMaitre())
						.isEqualTo("http://maitre.exemple:8091");
				});
		}
		// URL propre à la plateforme : prioritaire sur celle de la licence.
		avecTireur
			.withPropertyValues("app.plateforme.actif=true", "app.role=CLIENT",
					"app.licence.master-url=http://maitre.exemple:8091",
					"app.plateforme.master-url=https://autre.exemple/")
			.run(contexte -> assertThat(contexte.getBean(SourceMaitreHttp.class).urlMaitre())
				.isEqualTo("https://autre.exemple"));
		avecTireur.withPropertyValues("app.role=CLIENT").run(contexte -> {
			assertThat(contexte).hasNotFailed();
			assertThat(contexte).doesNotHaveBean(TireurPlugins.class);
			assertThat(contexte).doesNotHaveBean(SourcePlugins.class);
		});
		avecTireur.withPropertyValues("app.plateforme.actif=true", "app.role=CLIENT",
				"app.plateforme.intervalle-tirage-ms=5000").run(contexte -> {
					assertThat(contexte).hasFailed();
					Throwable cause = contexte.getStartupFailure();
					while (cause.getCause() != null) {
						cause = cause.getCause();
					}
					assertThat(cause).hasMessageContaining("app.plateforme.intervalle-tirage-ms doit valoir au moins");
				});
	}

	/** Dépôt du Maître : plateforme active ET rôle MASTER (absent ou invalide = MASTER, comme ServerRoleProperties). */
	@Test
	void depot_seulement_si_plateforme_active_et_role_master() {
		ApplicationContextRunner avecDepot = runner.withUserConfiguration(DepotPlugins.class);
		avecDepot.withPropertyValues("app.plateforme.actif=true").run(contexte -> {
			assertThat(contexte).hasNotFailed();
			assertThat(contexte).hasSingleBean(DepotPlugins.class);
			assertThat(contexte.getBean(PlateformeProperties.class).getDepotMaitre()).isEqualTo("depot-plugins");
		});
		avecDepot.withPropertyValues("app.plateforme.actif=true", "app.role=master")
			.run(contexte -> assertThat(contexte).hasSingleBean(DepotPlugins.class));
		for (String role : java.util.List.of("partner", "CLIENT", "public")) {
			avecDepot.withPropertyValues("app.plateforme.actif=true", "app.role=" + role).run(contexte -> {
				assertThat(contexte).hasNotFailed();
				assertThat(contexte).hasSingleBean(PluginManager.class);
				assertThat(contexte).doesNotHaveBean(DepotPlugins.class);
			});
		}
		avecDepot.withPropertyValues("app.role=MASTER").run(contexte -> {
			assertThat(contexte).hasNotFailed();
			assertThat(contexte).doesNotHaveBean(DepotPlugins.class);
		});
	}

}
