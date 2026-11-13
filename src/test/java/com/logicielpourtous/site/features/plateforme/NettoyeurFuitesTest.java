package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.util.ConcurrentLruCache;

/** Nettoyage des caches : robuste à un changement d'interne d'une bibliothèque (avertissement, jamais d'exception). */
class NettoyeurFuitesTest {

	private final Logger journal = (Logger) LoggerFactory.getLogger(NettoyeurFuites.class);

	private final ListAppender<ILoggingEvent> capture = new ListAppender<>();

	private StaticApplicationContext coeur;

	private NettoyeurFuites nettoyeur;

	/** Champs statiques purgeables, pour vérifier le chemin nominal du vidage par réflexion. */
	static final Map<Object, Object> MAP_DE_TEST = new HashMap<>();

	static final ConcurrentLruCache<String, String> LRU_DE_TEST = new ConcurrentLruCache<>(10, cle -> cle);

	@BeforeEach
	void preparer() {
		capture.start();
		journal.addAppender(capture);
		coeur = new StaticApplicationContext();
		coeur.refresh();
		nettoyeur = new NettoyeurFuites(coeur, new PlateformeProperties());
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
	void structure_interne_absente_ne_leve_pas() throws Exception {
		try (URLClassLoader chargeur = new URLClassLoader(new URL[0], null)) {
			assertThatCode(() -> nettoyeur.viderChampStatique(NettoyeurFuitesTest.class.getName(), "champInexistant",
					chargeur))
				.doesNotThrowAnyException();
			assertThatCode(() -> nettoyeur.viderChampStatique("org.exemple.ClasseDisparue", "cache", chargeur))
				.doesNotThrowAnyException();
			assertThatCode(() -> nettoyeur.retirerEntreesDuChamp(new Object(), "argumentResolverCache", chargeur))
				.doesNotThrowAnyException();
			assertThat(avertissements()).isEqualTo(3);
			assertThat(capture.list).filteredOn(e -> e.getLevel() == Level.WARN)
				.extracting(ILoggingEvent::getFormattedMessage)
				.anySatisfy(m -> assertThat(m).contains("champInexistant"))
				.anySatisfy(m -> assertThat(m).contains("org.exemple.ClasseDisparue"))
				.anySatisfy(m -> assertThat(m).contains("argumentResolverCache"));
		}
	}

	@Test
	void nettoyage_complet_sur_un_coeur_sans_mvc_ne_leve_pas() throws Exception {
		try (URLClassLoader chargeur = new URLClassLoader(new URL[0], null)) {
			assertThatCode(() -> nettoyeur.nettoyer(chargeur)).doesNotThrowAnyException();
		}
	}

	@Test
	void champ_statique_present_est_purge() throws Exception {
		MAP_DE_TEST.put("cle", "valeur");
		LRU_DE_TEST.get("cle");
		try (URLClassLoader chargeur = new URLClassLoader(new URL[0], null)) {
			// Map : seules les entrées qui désignent le chargeur partent ; cache LRU (non parcourable) : vidé.
			nettoyeur.viderChampStatique(NettoyeurFuitesTest.class.getName(), "MAP_DE_TEST", chargeur);
			nettoyeur.viderChampStatique(NettoyeurFuitesTest.class.getName(), "LRU_DE_TEST", chargeur);
		}
		assertThat(MAP_DE_TEST).containsEntry("cle", "valeur");
		assertThat(LRU_DE_TEST.size()).isZero();
		assertThat(avertissements()).isZero();
		MAP_DE_TEST.clear();
	}

}
