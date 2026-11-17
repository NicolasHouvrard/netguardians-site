package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.CleMaitre;
import com.logicielpourtous.site.features.plateforme.ResultatInstallation.Statut;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Source HTTP du tireur contre un vrai serveur HTTP local (JDK {@code HttpServer}). */
class SourceMaitreHttpTest {

	private static final String CLE = "CLE-SECRETE-7f3a9c41d2e8";

	@TempDir
	Path dossier;

	private HttpServer serveur;

	private String base;

	/** Requêtes reçues : méthode, chemin, en-têtes (noms en minuscules) et corps. */
	record Requete(String methode, String chemin, Map<String, List<String>> entetes, byte[] corps) {

		String entete(String nom) {
			List<String> v = entetes.get(nom.toLowerCase());
			return v == null || v.isEmpty() ? null : v.get(0);
		}

	}

	private final List<Requete> recues = new CopyOnWriteArrayList<>();

	private final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();

	private final List<HttpServer> autres = new ArrayList<>();

	@BeforeEach
	void demarrer() throws IOException {
		serveur = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		serveur.createContext("/", echange -> {
			Map<String, List<String>> entetes = new java.util.HashMap<>();
			echange.getRequestHeaders().forEach((k, v) -> entetes.put(k.toLowerCase(), List.copyOf(v)));
			recues.add(new Requete(echange.getRequestMethod(), echange.getRequestURI().getRawPath(), entetes,
					echange.getRequestBody().readAllBytes()));
			HttpHandler h = routes.get(echange.getRequestMethod() + " " + echange.getRequestURI().getRawPath());
			if (h == null) {
				repondre(echange, 404, "{\"erreur\":\"absent\"}".getBytes(StandardCharsets.UTF_8));
			}
			else {
				h.handle(echange);
			}
		});
		serveur.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		serveur.start();
		base = "http://127.0.0.1:" + serveur.getAddress().getPort();
	}

	@AfterEach
	void arreter() {
		serveur.stop(0);
		autres.forEach(s -> s.stop(0));
	}

	private static void repondre(HttpExchange echange, int statut, byte[] corps) throws IOException {
		echange.sendResponseHeaders(statut, corps.length == 0 ? -1 : corps.length);
		try (OutputStream out = echange.getResponseBody()) {
			out.write(corps);
		}
	}

	private static HttpHandler fixe(int statut, String corps) {
		return e -> repondre(e, statut, corps.getBytes(StandardCharsets.UTF_8));
	}

	private SourceMaitreHttp source() {
		return new SourceMaitreHttp(base, () -> Optional.of(CLE), Duration.ofMillis(500), Duration.ofMillis(500));
	}

	private static EntreeCatalogue entree(long taille) {
		return new EntreeCatalogue("alpha", "1.1.0", "chaud", "1.0.0", null, "a".repeat(64), taille);
	}

	private static final String CATALOGUE = """
			[{"nom":"alpha","version":"1.1.0","miseAJour":"chaud","coeurMin":"1.0.0","coeurMax":null,
			  "sha256":"%s","taille":42}]""".formatted("b".repeat(64));

	@Test
	void en_tete_X_Licence_Api_Key_envoye_avec_Accept_tout_et_sans_Range() throws IOException {
		routes.put("GET /api/licence/plugins/catalogue", fixe(200, CATALOGUE));

		List<EntreeCatalogue> catalogue = source().catalogue();

		assertThat(catalogue).containsExactly(
				new EntreeCatalogue("alpha", "1.1.0", "chaud", "1.0.0", null, "b".repeat(64), 42));
		Requete r = recues.get(0);
		assertThat(r.methode()).isEqualTo("GET");
		assertThat(r.entete("X-Licence-Api-Key")).isEqualTo(CLE);
		assertThat(r.entete("Accept")).isEqualTo("*/*");
		assertThat(r.entete("Range")).isNull();
	}

	@Test
	void url_du_maitre_avec_barre_finale_acceptee() throws IOException {
		routes.put("GET /api/licence/plugins/catalogue", fixe(200, "[]"));
		SourceMaitreHttp s = new SourceMaitreHttp(base + "/", () -> Optional.of(CLE), Duration.ofMillis(500),
				Duration.ofMillis(500));
		assertThat(s.catalogue()).isEmpty();
		assertThat(recues.get(0).chemin()).isEqualTo("/api/licence/plugins/catalogue");
	}

	@Test
	void telecharge_jar_et_sig_avec_la_cle() throws IOException {
		byte[] jar = "contenu-du-jar".getBytes(StandardCharsets.UTF_8);
		routes.put("GET /api/licence/plugins/alpha/1.1.0/jar", e -> repondre(e, 200, jar));
		routes.put("GET /api/licence/plugins/alpha/1.1.0/sig", fixe(200, "{\"keyId\":\"k\"}"));

		Path telecharge = source().telecharger(entree(jar.length), dossier);

		assertThat(telecharge).isEqualTo(dossier.resolve("alpha-1.1.0.jar"));
		assertThat(Files.readAllBytes(telecharge)).isEqualTo(jar);
		assertThat(Files.readString(dossier.resolve("alpha-1.1.0.jar.sig"))).isEqualTo("{\"keyId\":\"k\"}");
		assertThat(recues).hasSize(2).allSatisfy(r -> {
			assertThat(r.entete("X-Licence-Api-Key")).isEqualTo(CLE);
			assertThat(r.entete("Accept")).isEqualTo("*/*");
			assertThat(r.entete("Range")).isNull();
		});
	}

	@Test
	void telechargement_borne_a_la_taille_du_catalogue_et_rien_ne_reste() throws IOException {
		routes.put("GET /api/licence/plugins/alpha/1.1.0/jar", e -> repondre(e, 200, new byte[5000]));
		routes.put("GET /api/licence/plugins/alpha/1.1.0/sig", fixe(200, "{}"));

		assertThatThrownBy(() -> source().telecharger(entree(100), dossier)).isInstanceOf(IOException.class);
		assertThat(contenu(dossier)).isEmpty();

		// Signature démesurée : refusée aussi.
		routes.put("GET /api/licence/plugins/alpha/1.1.0/jar", e -> repondre(e, 200, new byte[100]));
		routes.put("GET /api/licence/plugins/alpha/1.1.0/sig", e -> repondre(e, 200, new byte[17 * 1024]));
		assertThatThrownBy(() -> source().telecharger(entree(100), dossier)).isInstanceOf(IOException.class);
		assertThat(contenu(dossier)).isEmpty();

		// Version absente (404) : IOException, rien d'écrit.
		routes.clear();
		assertThatThrownBy(() -> source().telecharger(entree(100), dossier)).isInstanceOf(IOException.class);
		assertThat(contenu(dossier)).isEmpty();
	}

	@Test
	void nom_ou_version_a_chemin_refuses_sans_requete() {
		for (EntreeCatalogue e : List.of(
				new EntreeCatalogue("../x", "1.0.0", "chaud", "1.0.0", null, "a".repeat(64), 1),
				new EntreeCatalogue("alpha", "1.0.0/../../catalogue", "chaud", "1.0.0", null, "a".repeat(64), 1))) {
			assertThatThrownBy(() -> source().telecharger(e, dossier)).isInstanceOf(IOException.class);
		}
		assertThat(recues).isEmpty();
	}

	@Test
	void delais_de_connexion_et_de_lecture_bornes() {
		SourceMaitreHttp parDefaut = new SourceMaitreHttp(base, (CleMaitre) () -> Optional.of(CLE));
		assertThat(parDefaut.delaiConnexion()).isEqualTo(Duration.ofSeconds(5));
		assertThat(parDefaut.delaiLecture()).isEqualTo(Duration.ofSeconds(60));

		// Un Maître qui accepte la connexion puis ne répond jamais : le délai de lecture coupe.
		routes.put("GET /api/licence/plugins/catalogue", e -> {
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException ignore) {
				Thread.currentThread().interrupt();
			}
		});
		assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> assertThatThrownBy(() -> source().catalogue()).isInstanceOf(IOException.class));

		// Un corps qui arrive au goutte-à-goutte : coupé aussi (lecture bornée).
		routes.put("GET /api/licence/plugins/alpha/1.1.0/jar", e -> {
			e.sendResponseHeaders(200, 100);
			OutputStream out = e.getResponseBody();
			out.write(new byte[10]);
			out.flush();
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException ignore) {
				Thread.currentThread().interrupt();
			}
		});
		assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> assertThatThrownBy(() -> source().telecharger(entree(100), dossier))
					.isInstanceOf(IOException.class));
	}

	@Test
	void redirection_vers_un_autre_hote_non_suivie() throws IOException {
		HttpServer autre = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		AtomicInteger visites = new AtomicInteger();
		autre.createContext("/", e -> {
			visites.incrementAndGet();
			repondre(e, 200, CATALOGUE.getBytes(StandardCharsets.UTF_8));
		});
		autre.start();
		autres.add(autre);
		routes.put("GET /api/licence/plugins/catalogue", e -> {
			e.getResponseHeaders().add("Location",
					"http://localhost:" + autre.getAddress().getPort() + "/api/licence/plugins/catalogue");
			repondre(e, 302, new byte[0]);
		});

		assertThatThrownBy(() -> source().catalogue()).isInstanceOf(IOException.class);
		assertThat(visites.get()).isZero();
	}

	@Test
	void rapport_envoye_en_json_et_404_n_est_pas_retente() throws IOException {
		routes.put("POST /api/licence/plugins/etat", fixe(204, ""));

		source().rapporter("alpha", "1.1.0", Statut.REFUSE, List.of("raison 1", "raison 2"));

		Requete r = recues.get(0);
		assertThat(r.entete("X-Licence-Api-Key")).isEqualTo(CLE);
		assertThat(r.entete("Content-Type")).startsWith("application/json");
		JsonNode corps = new ObjectMapper().readTree(r.corps());
		assertThat(corps.get("nom").asText()).isEqualTo("alpha");
		assertThat(corps.get("version").asText()).isEqualTo("1.1.0");
		assertThat(corps.get("statut").asText()).isEqualTo("REFUSE");
		assertThat(corps.get("raisons")).hasSize(2);

		// 404 (plugin absent du catalogue) : retour normal, pas de nouvelle tentative.
		routes.put("POST /api/licence/plugins/etat", fixe(404, "{\"erreur\":\"plugin absent\"}"));
		source().rapporter("alpha", "1.1.0", Statut.OK, List.of());
		assertThat(recues).hasSize(2);

		// 5xx : échec passager, signalé pour être retenté au tirage suivant.
		routes.put("POST /api/licence/plugins/etat", fixe(503, ""));
		assertThatThrownBy(() -> source().rapporter("alpha", "1.1.0", Statut.OK, List.of()))
			.isInstanceOf(IOException.class);
	}

	@Test
	void rapport_aux_raisons_demesurees_reste_sous_la_borne_du_maitre() throws IOException {
		routes.put("POST /api/licence/plugins/etat", fixe(204, ""));
		List<String> raisons = Collections.nCopies(500, "\u0001".repeat(2000));

		source().rapporter("alpha", "1.1.0", Statut.ECHEC, raisons);

		assertThat(recues.get(0).corps().length).isLessThan(DistributionController.TAILLE_MAX_RAPPORT);
	}

	@Test
	void catalogue_illisible_leve_IOException_et_elements_mal_formes_ignores() throws IOException {
		for (String corps : List.of("pas du json", "{\"nom\":\"alpha\"}", "[1,2", "null")) {
			routes.put("GET /api/licence/plugins/catalogue", fixe(200, corps));
			assertThatThrownBy(() -> source().catalogue()).as(corps).isInstanceOf(IOException.class);
		}
		routes.put("GET /api/licence/plugins/catalogue", e -> repondre(e, 200, new byte[2 * 1024 * 1024]));
		assertThatThrownBy(() -> source().catalogue()).isInstanceOf(IOException.class);

		routes.put("GET /api/licence/plugins/catalogue", fixe(200, "[1, \"x\", {\"nom\":[1]}, {\"nom\":\"alpha\","
				+ "\"version\":\"1.1.0\",\"miseAJour\":\"chaud\",\"coeurMin\":\"1.0.0\",\"sha256\":\"" + "c".repeat(64)
				+ "\",\"taille\":\"beaucoup\"}, " + CATALOGUE.substring(1)));
		assertThat(source().catalogue()).extracting(EntreeCatalogue::sha256).containsExactly("b".repeat(64));

		routes.put("GET /api/licence/plugins/catalogue", fixe(500, "{}"));
		assertThatThrownBy(() -> source().catalogue()).isInstanceOf(IOException.class);
	}

	@Test
	void sans_cle_ni_url_aucune_requete() {
		SourceMaitreHttp sansCle = new SourceMaitreHttp(base, Optional::empty, Duration.ofMillis(500),
				Duration.ofMillis(500));
		assertThatThrownBy(sansCle::catalogue).isInstanceOf(IOException.class);
		SourceMaitreHttp sansUrl = new SourceMaitreHttp("", () -> Optional.of(CLE), Duration.ofMillis(500),
				Duration.ofMillis(500));
		assertThatThrownBy(sansUrl::catalogue).isInstanceOf(IOException.class);
		SourceMaitreHttp urlBizarre = new SourceMaitreHttp("file:///etc/passwd", () -> Optional.of(CLE),
				Duration.ofMillis(500), Duration.ofMillis(500));
		assertThatThrownBy(urlBizarre::catalogue).isInstanceOf(IOException.class);
		assertThat(recues).isEmpty();
	}

	@Test
	void jamais_la_cle_dans_les_journaux_ni_les_erreurs() throws Exception {
		Logger racine = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ch.qos.logback.classic.Level niveau = racine.getLevel();
		racine.setLevel(ch.qos.logback.classic.Level.TRACE);
		ListAppender<ILoggingEvent> capture = new ListAppender<>();
		capture.start();
		racine.addAppender(capture);
		List<String> messages = new ArrayList<>();
		try {
			int portFerme;
			try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
				portFerme = s.getLocalPort();
			}
			// Réponses d'erreur qui renvoient la clé reçue (pire cas : le Maître ou un intermédiaire l'écho).
			HttpHandler echo = e -> repondre(e, 401,
					("{\"erreur\":\"cle " + e.getRequestHeaders().getFirst("X-Licence-Api-Key") + "\"}")
						.getBytes(StandardCharsets.UTF_8));
			routes.put("GET /api/licence/plugins/catalogue", echo);
			routes.put("POST /api/licence/plugins/etat", echo);
			routes.put("GET /api/licence/plugins/alpha/1.1.0/jar", echo);
			List<SourceMaitreHttp> sources = List.of(source(), new SourceMaitreHttp(
					"http://127.0.0.1:" + portFerme, () -> Optional.of(CLE), Duration.ofMillis(500),
					Duration.ofMillis(500)));
			for (SourceMaitreHttp s : sources) {
				messages.add(message(() -> s.catalogue()));
				messages.add(message(() -> s.telecharger(entree(10), dossier)));
				messages.add(message(() -> {
					s.rapporter("alpha", "1.1.0", Statut.OK, List.of());
					return null;
				}));
			}
			// Et à travers le tireur, qui journalise les pannes.
			for (SourceMaitreHttp s : sources) {
				PlateformeProperties p = new PlateformeProperties();
				p.setRepertoirePlugins(dossier.resolve("plugins").toString());
				new TireurPlugins(s, p, new ClesPubliques(JarDePluginDeTest.clesDeTest()),
						jar -> new ResultatInstallation(Statut.OK, "alpha", "1.1.0", List.of(), 0), Map::of)
					.tirer();
			}
		}
		finally {
			racine.detachAppender(capture);
			racine.setLevel(niveau);
		}
		assertThat(messages).noneMatch(m -> m != null && m.contains(CLE));
		for (ILoggingEvent e : capture.list) {
			assertThat(e.getFormattedMessage()).doesNotContain(CLE);
			for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
				assertThat(String.valueOf(t.getMessage())).doesNotContain(CLE);
			}
		}
	}

	private interface Appel {

		Object appeler() throws Exception;

	}

	/** Message complet (chaîne de causes) de l'exception levée par {@code appel}, ou {@code null}. */
	private static String message(Appel appel) {
		try {
			appel.appeler();
			return null;
		}
		catch (Exception e) {
			StringBuilder sb = new StringBuilder();
			for (Throwable t = e; t != null; t = t.getCause()) {
				sb.append(t).append(" | ");
			}
			return sb.toString();
		}
	}

	private static List<Path> contenu(Path d) throws IOException {
		try (Stream<Path> s = Files.list(d)) {
			return s.toList();
		}
	}

}
