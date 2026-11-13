package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.ref.SoftReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * La pression mémoire de {@code rapportFuites(true)} (remplir le tas jusqu'à {@code OutOfMemoryError}) n'a lieu que si
 * {@code app.plateforme.diagnostic-fuites} est activée ; sinon elle est ignorée avec un avertissement.
 */
class RapportFuitesTest {

	private final Logger journal = (Logger) LoggerFactory.getLogger(PluginManager.class);

	private final ListAppender<ILoggingEvent> capture = new ListAppender<>();

	private StaticApplicationContext coeur;

	private PlateformeProperties proprietes;

	private PluginManager manager;

	@BeforeEach
	void preparer() {
		capture.start();
		journal.addAppender(capture);
		coeur = new StaticApplicationContext();
		coeur.refresh();
		DataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:1/inutilisee");
		proprietes = new PlateformeProperties();
		manager = new PluginManager(proprietes, new DemarreurContexte(ds, coeur), new RoutagePlugins(),
				new ContratRegistre(), new ReferencesEntrantes(ds), ds, chargeur -> {
				});
	}

	@AfterEach
	void apres() {
		journal.detachAppender(capture);
		coeur.close();
	}

	private long avertissements() {
		return capture.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
	}

	@Test
	void pression_demandee_sans_diagnostic_est_ignoree_avec_avertissement() {
		SoftReference<byte[]> souple = new SoftReference<>(new byte[1024 * 1024]);

		RapportFuites rapport = manager.rapportFuites(true);

		assertThat(souple.get()).as("le tas ne doit pas avoir été rempli").isNotNull();
		assertThat(avertissements()).isEqualTo(1);
		assertThat(capture.list).filteredOn(e -> e.getLevel() == Level.WARN)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anySatisfy(m -> assertThat(m).contains("diagnostic-fuites"));
		assertThat(rapport.retires()).isZero();
	}

	@Test
	void pression_avec_diagnostic_efface_les_references_souples() {
		proprietes.setDiagnosticFuites(true);
		SoftReference<byte[]> souple = new SoftReference<>(new byte[1024 * 1024]);

		manager.rapportFuites(true);

		assertThat(souple.get()).as("la pression mémoire efface les références souples").isNull();
		assertThat(avertissements()).isZero();
	}

}
