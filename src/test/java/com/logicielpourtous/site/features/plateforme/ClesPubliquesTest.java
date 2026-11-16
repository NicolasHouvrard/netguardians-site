package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Clés publiques de confiance du cœur : lecture de la ressource, comptage des entrées illisibles, avertissement sous
 * deux clés, et refus de démarrer sans aucune clé quand la signature est obligatoire (jamais de cœur qui accepte tout).
 */
class ClesPubliquesTest {

	private final Logger journal = (Logger) LoggerFactory.getLogger(ClesPubliques.class);

	private final ListAppender<ILoggingEvent> capture = new ListAppender<>();

	@BeforeEach
	void brancher() {
		capture.start();
		journal.addAppender(capture);
	}

	@AfterEach
	void debrancher() {
		journal.detachAppender(capture);
	}

	private static String publiqueB64() {
		return Base64.getEncoder().encodeToString(JarDePluginDeTest.nouvellePaire().getPublic().getEncoded());
	}

	private static InputStream flux(String contenu) {
		return new ByteArrayInputStream(contenu.getBytes(StandardCharsets.ISO_8859_1));
	}

	private boolean avertit(String fragment) {
		return capture.list.stream()
			.anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(fragment));
	}

	@Test
	void la_ressource_du_coeur_contient_au_moins_deux_cles_de_developpement_lisibles() {
		ClesPubliques cles = ClesPubliques.depuisRessource(true);

		assertThat(cles.cles()).hasSizeGreaterThanOrEqualTo(2);
		assertThat(cles.cles()).allMatch(c -> c.keyId().startsWith("dev-"));
		assertThat(cles.ignorees()).isZero();
		assertThat(avertit("moins de deux")).isFalse();
	}

	@Test
	void deux_cles_lisibles_sans_avertissement() {
		ClesPubliques cles = ClesPubliques.lire(flux("a=" + publiqueB64() + "\nb=" + publiqueB64() + "\n"), "test",
				true);

		assertThat(cles.cles()).extracting(c -> c.keyId()).containsExactlyInAnyOrder("a", "b");
		assertThat(cles.ignorees()).isZero();
		assertThat(capture.list).noneMatch(e -> e.getLevel() == Level.WARN);
	}

	@Test
	void moins_de_deux_cles_avertit() {
		ClesPubliques cles = ClesPubliques.lire(flux("seule=" + publiqueB64() + "\n"), "test", true);

		assertThat(cles.cles()).hasSize(1);
		assertThat(avertit("moins de deux")).isTrue();
	}

	@Test
	void une_entree_illisible_est_comptee_et_avertie() {
		ClesPubliques cles = ClesPubliques.lire(
				flux("a=" + publiqueB64() + "\nb=" + publiqueB64() + "\ncassee=pas-du-base64!!\n"), "test", true);

		assertThat(cles.cles()).hasSize(2);
		assertThat(cles.ignorees()).isEqualTo(1);
		assertThat(avertit("ignorée")).isTrue();
	}

	@Test
	void ressource_absente_et_signature_obligatoire_refuse_de_demarrer() {
		assertThatThrownBy(() -> ClesPubliques.lire(null, "absente", true)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("absente");
		assertThatThrownBy(() -> ClesPubliques.depuisRessource("plateforme/inexistante.properties", true))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void ressource_vide_et_signature_obligatoire_refuse_de_demarrer() {
		assertThatThrownBy(() -> ClesPubliques.lire(flux("# rien\n"), "vide", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("aucune clé");
	}

	@Test
	void aucune_cle_lisible_et_signature_obligatoire_refuse_de_demarrer() {
		assertThatThrownBy(() -> ClesPubliques.lire(flux("x=AAAA\ny=zzz\n"), "illisibles", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("aucune clé");
	}

	@Test
	void ressource_absente_sans_signature_obligatoire_avertit_seulement() {
		ClesPubliques cles = ClesPubliques.lire(null, "absente", false);

		assertThat(cles.cles()).isEmpty();
		assertThat(avertit("absente")).isTrue();
	}

	@Test
	void le_gestionnaire_refuse_de_demarrer_sans_cle_quand_la_signature_est_obligatoire() {
		StaticApplicationContext coeur = new StaticApplicationContext();
		coeur.refresh();
		try {
			DataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:1/inutilisee");
			PlateformeProperties proprietes = new PlateformeProperties();
			assertThat(proprietes.isSignatureObligatoire()).isTrue(); // vrai par défaut

			assertThatThrownBy(() -> new PluginManager(proprietes, new ClesPubliques(List.of()),
					new DemarreurContexte(ds, coeur), new RoutagePlugins(), new ContratRegistre(),
					new ReferencesEntrantes(ds), ds, chargeur -> {
					}))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("aucune clé");
		}
		finally {
			coeur.close();
		}
	}

}
