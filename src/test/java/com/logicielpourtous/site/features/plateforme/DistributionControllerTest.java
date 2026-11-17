package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.licence.LicenceApiKeyAuthFilter;
import com.logicielpourtous.site.features.licence.LicenceCles;
import com.logicielpourtous.site.features.licence.LicenceServeur;
import com.logicielpourtous.site.features.licence.LicenceServeurRepository;
import com.logicielpourtous.site.features.sync.SecondaryServer;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;
import com.logicielpourtous.site.features.sync.SyncApiKeyAuthFilter;
import com.logicielpourtous.site.features.sync.SyncApiKeys;

/**
 * API des serveurs vers le Maître ({@code /api/licence/plugins/**}, clé {@code X-Licence-Api-Key}) et état rapporté
 * ({@code GET /api/plateforme/serveurs-etat}, ADMIN), sur l'application complète : vraie chaîne de sécurité,
 * plateforme active, rôle MASTER (valeur par défaut de {@code app.role}). Tests transactionnels (annulés en fin de
 * test), comme {@code DeploiementControllerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(DistributionControllerTest.ClesDeTest.class)
class DistributionControllerTest {

	private static final Path RACINE;

	static {
		try {
			RACINE = Files.createTempDirectory("distribution-controller-test");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static final Path DEPOT = RACINE.resolve("depot");

	@DynamicPropertySource
	static void proprietes(DynamicPropertyRegistry registre) {
		registre.add("app.plateforme.actif", () -> "true");
		registre.add("app.plateforme.repertoire-plugins", () -> RACINE.resolve("plugins").toString());
		registre.add("app.plateforme.depot-maitre", DEPOT::toString);
	}

	@TestConfiguration
	static class ClesDeTest {

		@Bean
		@Primary
		ClesPubliques clesPubliquesDeTest() {
			return new ClesPubliques(JarDePluginDeTest.clesDeTest());
		}

	}

	@AfterAll
	static void supprimerDossiers() throws IOException {
		try (Stream<Path> s = Files.walk(RACINE)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	@Autowired
	private MockMvc mvc;

	/** Fournisseur : le contexte imbriqué (non MASTER) n'a pas de dépôt, et injecte aussi l'instance englobante. */
	@Autowired
	private ObjectProvider<DepotPlugins> depot;

	@Autowired
	private LicenceServeurRepository licenceServeurRepository;

	@Autowired
	private ObjectProvider<DistributionController> controleur;

	@Autowired
	private SecondaryServerRepository secondaryServerRepository;

	private final ObjectMapper json = new ObjectMapper();

	private static int compteur;

	@BeforeEach
	void viderLeDepot() throws IOException {
		DepotControllerTest.viderSousDossier(DEPOT);
	}

	private static Path jarSigne(String nom, String version) {
		Map<String, String> props = Map.of("coeur-min", "1.0.0", "mise-a-jour", "chaud", "paquet", nom,
				"configuration", nom + ".Config");
		return JarDePluginDeTest.signer(
				JarDePluginDeTest.creer(RACINE.resolve("construction-" + (compteur++)), nom, version, props, Map.of()));
	}

	private EntreeCatalogue publier(Path jar) throws IOException {
		try (var j = Files.newInputStream(jar); var s = Files.newInputStream(JarDePluginDeTest.sig(jar))) {
			return depot.getObject().publier(j, s);
		}
	}

	private LicenceServeur serveurActif(String cleApi) {
		LicenceServeur serveur = new LicenceServeur();
		serveur.setCompteId(1L);
		serveur.setRole(ServerRole.PARTNER);
		serveur.setIdentifiantInstallationStable(UUID.randomUUID().toString());
		serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
		serveur.setCleApiHash(LicenceCles.hash(cleApi));
		return licenceServeurRepository.save(serveur);
	}

	private static MockHttpServletRequestBuilder avecCle(MockHttpServletRequestBuilder requete, String cle) {
		return requete.header(LicenceApiKeyAuthFilter.HEADER, cle);
	}

	private static MockHttpServletRequestBuilder rapport(String cle, String corps) {
		return avecCle(post("/api/licence/plugins/etat"), cle).contentType(MediaType.APPLICATION_JSON).content(corps);
	}

	private static String sha256(byte[] octets) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(octets));
	}

	private JsonNode etats() throws Exception {
		return etats(get("/api/plateforme/serveurs-etat").param("limite",
				String.valueOf(DistributionController.LISTE_MAX)));
	}

	private JsonNode etats(MockHttpServletRequestBuilder requete) throws Exception {
		String corps = mvc.perform(requete.with(user("admin-etat").roles("ADMIN")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return json.readTree(corps);
	}

	private static JsonNode etatDe(JsonNode etats, long serveurId, String nom) {
		for (JsonNode e : etats) {
			if (e.path("licenceServeurId").asLong() == serveurId && nom.equals(e.path("nom").asText())) {
				return e;
			}
		}
		return null;
	}

	@Test
	void sans_cle_401() throws Exception {
		mvc.perform(get("/api/licence/plugins/catalogue")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/licence/plugins/alpha/1.0.0/jar")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/licence/plugins/alpha/1.0.0/sig")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/licence/plugins/etat").contentType(MediaType.APPLICATION_JSON)
			.content("{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(avecCle(get("/api/licence/plugins/catalogue"), "cle-inconnue")).andExpect(status().isUnauthorized());
	}

	@Test
	void cle_valide_catalogue_jar_et_sig_identiques_au_depot() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		Path jar = jarSigne("alpha", "1.2.3");
		EntreeCatalogue entree = publier(jar);
		byte[] octetsJar = Files.readAllBytes(jar);
		byte[] octetsSig = Files.readAllBytes(JarDePluginDeTest.sig(jar));

		mvc.perform(avecCle(get("/api/licence/plugins/catalogue"), cle))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].nom").value("alpha"))
			.andExpect(jsonPath("$[0].version").value("1.2.3"))
			.andExpect(jsonPath("$[0].miseAJour").value("chaud"))
			.andExpect(jsonPath("$[0].sha256").value(entree.sha256()))
			.andExpect(jsonPath("$[0].taille").value(entree.taille()));

		byte[] recuJar = mvc.perform(avecCle(get("/api/licence/plugins/alpha/1.2.3/jar"), cle))
			.andExpect(status().isOk())
			.andExpect(header().longValue("Content-Length", octetsJar.length))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(recuJar).isEqualTo(octetsJar);
		assertThat(sha256(recuJar)).isEqualTo(entree.sha256());

		byte[] recuSig = mvc.perform(avecCle(get("/api/licence/plugins/alpha/1.2.3/sig"), cle))
			.andExpect(status().isOk())
			.andExpect(header().longValue("Content-Length", octetsSig.length))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(recuSig).isEqualTo(octetsSig);
	}

	@Test
	void version_absente_404_et_paire_incomplete_jamais_servie() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));

		mvc.perform(avecCle(get("/api/licence/plugins/alpha/9.9.9/jar"), cle)).andExpect(status().isNotFound());
		mvc.perform(avecCle(get("/api/licence/plugins/beta/1.0.0/sig"), cle)).andExpect(status().isNotFound());

		// Jar déposé à la main sans .sig : ni catalogue, ni téléchargement.
		Path nonPublie = jarSigne("gamma", "1.0.0");
		Files.copy(nonPublie, DEPOT.resolve("gamma-1.0.0.jar"));
		mvc.perform(avecCle(get("/api/licence/plugins/gamma/1.0.0/jar"), cle)).andExpect(status().isNotFound());
		mvc.perform(avecCle(get("/api/licence/plugins/catalogue"), cle))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void nom_ou_version_a_chemin_400() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));

		for (String[] nv : new String[][] { { "..", "1.0.0" }, { "alpha", ".." }, { "Alpha", "1.0.0" },
				{ "alpha", "1.0" }, { "a.b", "1.0.0" }, { "alpha", "1.0.0..1" }, { "alpha", "1.0.0-..%2F" },
				{ "alpha%2F..", "1.0.0" } }) {
			int statut = mvc.perform(avecCle(get("/api/licence/plugins/" + nv[0] + "/" + nv[1] + "/jar"), cle))
				.andReturn()
				.getResponse()
				.getStatus();
			assertThat(statut).as(nv[0] + " / " + nv[1]).isEqualTo(400);
		}
	}

	@Test
	void post_etat_enregistre_puis_remplace_le_dernier_etat_du_meme_serveur_et_plugin() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		String autreCle = LicenceCles.generate();
		LicenceServeur autre = serveurActif(autreCle);
		publier(jarSigne("alpha", "1.0.0"));

		mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"REFUSE\","
				+ "\"raisons\":[\"signature invalide\"]}"))
			.andExpect(status().isNoContent());
		mvc.perform(rapport(autreCle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]}"))
			.andExpect(status().isNoContent());

		JsonNode e = etatDe(etats(), serveur.getId(), "alpha");
		assertThat(e).isNotNull();
		assertThat(e.path("version").asText()).isEqualTo("1.0.0");
		assertThat(e.path("statut").asText()).isEqualTo("REFUSE");
		assertThat(e.path("raisons").get(0).asText()).isEqualTo("signature invalide");
		assertThat(e.path("rapporteLe").isMissingNode()).isFalse();

		// Même serveur, même plugin : remplacé (une seule ligne) ; l'autre serveur est intact.
		mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.1.0\",\"statut\":\"ANNULE\","
				+ "\"raisons\":[\"démarrage en échec\",\"retour arrière\"]}"))
			.andExpect(status().isNoContent());
		JsonNode tous = etats();
		int lignes = 0;
		for (JsonNode x : tous) {
			if (x.path("licenceServeurId").asLong() == serveur.getId() && "alpha".equals(x.path("nom").asText())) {
				lignes++;
			}
		}
		assertThat(lignes).isEqualTo(1);
		JsonNode apres = etatDe(tous, serveur.getId(), "alpha");
		assertThat(apres.path("version").asText()).isEqualTo("1.1.0");
		assertThat(apres.path("statut").asText()).isEqualTo("ANNULE");
		assertThat(apres.path("raisons").size()).isEqualTo(2);
		assertThat(etatDe(tous, autre.getId(), "alpha").path("statut").asText()).isEqualTo("OK");
	}

	@Test
	void identite_du_serveur_vient_de_la_cle_jamais_du_corps() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		long usurpe = serveur.getId() + 100_000;
		publier(jarSigne("delta", "2.0.0"));

		mvc.perform(rapport(cle, "{\"licenceServeurId\":" + usurpe + ",\"nom\":\"delta\",\"version\":\"2.0.0\","
				+ "\"statut\":\"ECHEC\",\"raisons\":[]}"))
			.andExpect(status().isNoContent());

		JsonNode tous = etats();
		assertThat(etatDe(tous, serveur.getId(), "delta")).isNotNull();
		assertThat(etatDe(tous, usurpe, "delta")).isNull();
	}

	@Test
	void post_etat_invalide_400() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));

		for (String corps : new String[] {
				"{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"INSTALLE_PEUT_ETRE\",\"raisons\":[]}",
				"{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"ok\",\"raisons\":[]}",
				"{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"raisons\":[]}",
				"{\"nom\":\"../alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]}",
				"{\"nom\":\"alpha\",\"version\":\"1.0.0/../x\",\"statut\":\"OK\",\"raisons\":[]}",
				"{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[1,2]}",
				"{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":\"texte\"}",
				"{\"nom\":[\"alpha\"],\"version\":\"1.0.0\",\"statut\":\"OK\"}",
				"pas du json",
				"[]" }) {
			mvc.perform(rapport(cle, corps)).andExpect(status().isBadRequest());
		}
		JsonNode tous = etats();
		for (JsonNode x : tous) {
			assertThat(x.path("statut").asText()).isIn("OK", "REFUSE", "ECHEC", "ANNULE");
		}
	}

	@Test
	void post_etat_corps_trop_gros_413_et_raisons_bornees_et_nettoyees() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		publier(jarSigne("epsilon", "1.0.0"));

		String enorme = "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[\""
				+ "x".repeat(DistributionController.TAILLE_MAX_RAPPORT) + "\"]}";
		mvc.perform(rapport(cle, enorme)).andExpect(status().isPayloadTooLarge());

		StringBuilder raisons = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			raisons.append(i == 0 ? "" : ",").append("\"r").append(i).append("\"");
		}
		// Échappements JSON (un contrôle brut serait un JSON invalide) ; décodés par le serveur.
		String hostile = "<script>\\u202e\\u0007" + "z".repeat(1000);
		mvc.perform(rapport(cle, "{\"nom\":\"epsilon\",\"version\":\"1.0.0\",\"statut\":\"ECHEC\",\"raisons\":[\""
				+ hostile + "\"," + raisons + "]}"))
			.andExpect(status().isNoContent());

		JsonNode e = etatDe(etats(), serveur.getId(), "epsilon");
		assertThat(e).isNotNull();
		assertThat(e.path("raisons").size()).isLessThanOrEqualTo(DistributionController.RAISONS_MAX);
		String premiere = e.path("raisons").get(0).asText();
		assertThat(premiere).doesNotContain("<script>").doesNotContain("‮").doesNotContain("\u0007");
		assertThat(premiere.length()).isLessThanOrEqualTo(200);
	}

	@Test
	void serveurs_etat_admin_seulement() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		mvc.perform(get("/api/plateforme/serveurs-etat")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plateforme/serveurs-etat").with(user("commercial").roles("COMMERCIAL", "ESN_USER")))
			.andExpect(status().isForbidden());
		// La clé d'un serveur n'ouvre pas l'administration.
		mvc.perform(avecCle(get("/api/plateforme/serveurs-etat"), cle)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/plateforme/serveurs-etat").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
	}

	@Test
	void cle_d_un_autre_module_n_ouvre_rien_et_reciproquement() throws Exception {
		String cleSync = SyncApiKeys.generate();
		SecondaryServer s = new SecondaryServer();
		s.setNom("secondaire-" + UUID.randomUUID());
		s.setRole(ServerRole.CLIENT);
		s.setApiKeyHash(SyncApiKeys.hash(cleSync));
		s.setStatut(SecondaryServer.SecondaryServerStatut.ACTIF);
		secondaryServerRepository.save(s);
		publier(jarSigne("alpha", "1.0.0"));

		// Clé /api/sync, dans son propre en-tête ou dans celui de la licence : rien.
		for (String entete : new String[] { SyncApiKeyAuthFilter.HEADER, LicenceApiKeyAuthFilter.HEADER }) {
			mvc.perform(get("/api/licence/plugins/catalogue").header(entete, cleSync)).andExpect(status().isUnauthorized());
			mvc.perform(get("/api/licence/plugins/alpha/1.0.0/jar").header(entete, cleSync))
				.andExpect(status().isUnauthorized());
			mvc.perform(post("/api/licence/plugins/etat").header(entete, cleSync)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]}"))
				.andExpect(status().isUnauthorized());
		}

		// Clé de licence valide sur /api/sync : rien non plus.
		String cle = LicenceCles.generate();
		serveurActif(cle);
		mvc.perform(get("/api/sync/events").header(LicenceApiKeyAuthFilter.HEADER, cle))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/sync/events").header(SyncApiKeyAuthFilter.HEADER, cle))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void route_non_declaree_sous_plugins_refusee_meme_avec_cle() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));
		mvc.perform(avecCle(get("/api/licence/plugins/alpha/1.0.0/exe"), cle)).andExpect(status().isForbidden());
		mvc.perform(avecCle(post("/api/licence/plugins/catalogue"), cle)).andExpect(status().isForbidden());
		mvc.perform(avecCle(get("/api/licence/plugins/etat"), cle)).andExpect(status().isForbidden());
	}

	// ---- Correctifs de revue (tour 1) ----

	private static final String OK_100 = "\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]}";

	@Test
	void post_etat_d_un_plugin_absent_du_depot_404_sans_ligne() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));

		mvc.perform(rapport(cle, "{\"nom\":\"inconnu" + OK_100))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.erreur").exists());
		assertThat(etatDe(etats(), serveur.getId(), "inconnu")).isNull();
	}

	@Test
	void plafond_de_lignes_par_serveur_refuse_un_nouveau_nom_mais_pas_la_mise_a_jour() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		String autreCle = LicenceCles.generate();
		LicenceServeur autre = serveurActif(autreCle);
		for (String nom : new String[] { "alpha", "beta", "gamma" }) {
			publier(jarSigne(nom, "1.0.0"));
		}
		DistributionController c = controleur.getObject();
		int avant = c.etatsMaxParServeur();
		c.etatsMaxParServeur(2);
		try {
			mvc.perform(rapport(cle, "{\"nom\":\"alpha" + OK_100)).andExpect(status().isNoContent());
			mvc.perform(rapport(cle, "{\"nom\":\"beta" + OK_100)).andExpect(status().isNoContent());
			mvc.perform(rapport(cle, "{\"nom\":\"gamma" + OK_100))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.erreur").exists());
			// Mise à jour d'un nom déjà présent : toujours admise au plafond.
			mvc.perform(rapport(cle, "{\"nom\":\"beta\",\"version\":\"1.0.0\",\"statut\":\"ECHEC\",\"raisons\":[]}"))
				.andExpect(status().isNoContent());
			// Plafond par serveur : un autre serveur n'est pas gêné.
			mvc.perform(rapport(autreCle, "{\"nom\":\"gamma" + OK_100)).andExpect(status().isNoContent());

			JsonNode tous = etats();
			assertThat(etatDe(tous, serveur.getId(), "gamma")).isNull();
			assertThat(etatDe(tous, serveur.getId(), "beta").path("statut").asText()).isEqualTo("ECHEC");
			assertThat(etatDe(tous, autre.getId(), "gamma")).isNotNull();
		}
		finally {
			c.etatsMaxParServeur(avant);
		}
	}

	@Test
	void post_etat_avec_metaspace_le_stocke_et_l_expose() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));

		mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[],"
				+ "\"metaspace\":{\"metaspaceMo\":600,\"seuilMo\":512,\"redemarrageRecommande\":true}}"))
			.andExpect(status().isNoContent());
		JsonNode m = etatDe(etats(), serveur.getId(), "alpha").path("metaspace");
		assertThat(m.path("metaspaceMo").asLong()).isEqualTo(600);
		assertThat(m.path("seuilMo").asLong()).isEqualTo(512);
		assertThat(m.path("redemarrageRecommande").asBoolean()).isTrue();

		// Un rapport suivant remplace la mesure ; sans mesure (ancien serveur), le champ est absent (null).
		mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[],"
				+ "\"metaspace\":{\"metaspaceMo\":100,\"seuilMo\":512,\"redemarrageRecommande\":false}}"))
			.andExpect(status().isNoContent());
		assertThat(etatDe(etats(), serveur.getId(), "alpha").path("metaspace").path("redemarrageRecommande")
			.asBoolean(true)).isFalse();
		mvc.perform(rapport(cle, "{\"nom\":\"alpha" + OK_100)).andExpect(status().isNoContent());
		assertThat(etatDe(etats(), serveur.getId(), "alpha").path("metaspace").isNull()).isTrue();
	}

	@Test
	void post_etat_metaspace_non_fiable_est_ignore_sans_refuser_le_rapport() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));
		String debut = "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[],\"metaspace\":";

		for (String m : new String[] { "\"texte\"", "[1,2]", "{\"metaspaceMo\":-1,\"seuilMo\":512,\"redemarrageRecommande\":true}",
				"{\"metaspaceMo\":9999999999,\"seuilMo\":512,\"redemarrageRecommande\":true}",
				"{\"metaspaceMo\":1.5,\"seuilMo\":512,\"redemarrageRecommande\":true}",
				"{\"metaspaceMo\":\"600\",\"seuilMo\":512,\"redemarrageRecommande\":true}",
				"{\"metaspaceMo\":600,\"seuilMo\":512,\"redemarrageRecommande\":\"oui\"}",
				"{\"metaspaceMo\":600,\"seuilMo\":512}", "{}" }) {
			mvc.perform(rapport(cle, debut + m + "}")).andExpect(status().isNoContent());
			assertThat(etatDe(etats(), serveur.getId(), "alpha").path("metaspace").isNull()).as(m).isTrue();
		}
	}

	@Test
	void post_etat_avec_metaspace_respecte_le_plafond_de_lignes_par_serveur() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));
		publier(jarSigne("beta", "1.0.0"));
		DistributionController c = controleur.getObject();
		int avant = c.etatsMaxParServeur();
		c.etatsMaxParServeur(1);
		try {
			String m = "\"metaspace\":{\"metaspaceMo\":600,\"seuilMo\":512,\"redemarrageRecommande\":true}}";
			mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]," + m))
				.andExpect(status().isNoContent());
			mvc.perform(rapport(cle, "{\"nom\":\"beta\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]," + m))
				.andExpect(status().isConflict());
		}
		finally {
			c.etatsMaxParServeur(avant);
		}
	}

	@Test
	void serveurs_etat_borne_par_limite() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));
		publier(jarSigne("beta", "1.0.0"));
		mvc.perform(rapport(cle, "{\"nom\":\"alpha" + OK_100)).andExpect(status().isNoContent());
		mvc.perform(rapport(cle, "{\"nom\":\"beta" + OK_100)).andExpect(status().isNoContent());

		assertThat(etats(get("/api/plateforme/serveurs-etat").param("limite", "1")).size()).isEqualTo(1);
		assertThat(etats(get("/api/plateforme/serveurs-etat")).size())
			.isLessThanOrEqualTo(DistributionController.LISTE_DEFAUT);
		// Au-delà du maximum : ramené au maximum, pas d'erreur.
		assertThat(etats(get("/api/plateforme/serveurs-etat").param("limite", "1000000")).size())
			.isLessThanOrEqualTo(DistributionController.LISTE_MAX);
		for (String limite : new String[] { "0", "-1", "abc" }) {
			mvc.perform(get("/api/plateforme/serveurs-etat").param("limite", limite).with(user("admin").roles("ADMIN")))
				.andExpect(status().isBadRequest());
		}
	}

	@Test
	void erreurs_jar_sig_gardent_leur_statut_quel_que_soit_accept() throws Exception {
		String cle = LicenceCles.generate();
		serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));
		for (String accept : new String[] { "application/octet-stream", "application/java-archive" }) {
			mvc.perform(avecCle(get("/api/licence/plugins/alpha/9.9.9/jar"), cle).header("Accept", accept))
				.andExpect(status().isNotFound());
			mvc.perform(avecCle(get("/api/licence/plugins/alpha/9.9.9/sig"), cle).header("Accept", accept))
				.andExpect(status().isNotFound());
			mvc.perform(avecCle(get("/api/licence/plugins/Alpha/1.0.0/jar"), cle).header("Accept", accept))
				.andExpect(status().isBadRequest());
			mvc.perform(avecCle(get("/api/licence/plugins/alpha/1.0/sig"), cle).header("Accept", accept))
				.andExpect(status().isBadRequest());
		}
		// Rapport : 400, 404 et 413 aussi, avec un Accept qui exclut JSON.
		mvc.perform(rapport(cle, "pas du json").header("Accept", "application/octet-stream"))
			.andExpect(status().isBadRequest());
		mvc.perform(rapport(cle, "{\"nom\":\"inconnu" + OK_100).header("Accept", "application/octet-stream"))
			.andExpect(status().isNotFound());
		mvc.perform(rapport(cle, "{\"x\":\"" + "y".repeat(DistributionController.TAILLE_MAX_RAPPORT) + "\"}")
			.header("Accept", "application/octet-stream")).andExpect(status().isPayloadTooLarge());
	}

	@Test
	void raisons_vides_ecartees_a_l_ecriture() throws Exception {
		String cle = LicenceCles.generate();
		LicenceServeur serveur = serveurActif(cle);
		publier(jarSigne("alpha", "1.0.0"));
		mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"REFUSE\","
				+ "\"raisons\":[\"\",\"a\",\"  \",\"b\",\"\"]}"))
			.andExpect(status().isNoContent());
		JsonNode e = etatDe(etats(), serveur.getId(), "alpha");
		assertThat(e.path("raisons").size()).isEqualTo(2);
		assertThat(e.path("raisons").get(0).asText()).isEqualTo("a");
		assertThat(e.path("raisons").get(1).asText()).isEqualTo("b");

		mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[\"\",\"\"]}"))
			.andExpect(status().isNoContent());
		assertThat(etatDe(etats(), serveur.getId(), "alpha").path("raisons").size()).isZero();
	}

	/** Rôle de serveur autre que MASTER : aucune route de distribution (404 même avec une clé valide). */
	@Nested
	@NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
	@SpringBootTest
	@AutoConfigureMockMvc
	@Transactional
	@TestPropertySource(properties = "app.role=partner")
	class RoleNonMaitre {

		@DynamicPropertySource
		static void proprietes(DynamicPropertyRegistry registre) {
			registre.add("app.plateforme.actif", () -> "true");
			registre.add("app.plateforme.repertoire-plugins", () -> RACINE.resolve("plugins-partenaire").toString());
			registre.add("app.plateforme.signature-obligatoire", () -> "false");
			registre.add("app.plateforme.depot-maitre", () -> RACINE.resolve("depot-partenaire").toString());
		}

		@Autowired
		private ApplicationContext contexte;

		@Autowired
		private MockMvc mvc;

		@Autowired
		private LicenceServeurRepository licenceServeurRepository;

		@Test
		void absent_quand_le_role_n_est_pas_master() throws Exception {
			assertThat(contexte.getBeansOfType(DistributionController.class)).isEmpty();
			String cle = LicenceCles.generate();
			LicenceServeur serveur = new LicenceServeur();
			serveur.setCompteId(1L);
			serveur.setRole(ServerRole.CLIENT);
			serveur.setIdentifiantInstallationStable(UUID.randomUUID().toString());
			serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
			serveur.setCleApiHash(LicenceCles.hash(cle));
			licenceServeurRepository.save(serveur);

			mvc.perform(avecCle(get("/api/licence/plugins/catalogue"), cle)).andExpect(status().isNotFound());
			mvc.perform(rapport(cle, "{\"nom\":\"alpha\",\"version\":\"1.0.0\",\"statut\":\"OK\",\"raisons\":[]}"))
				.andExpect(status().isNotFound());
			mvc.perform(get("/api/plateforme/serveurs-etat").with(user("admin").roles("ADMIN")))
				.andExpect(status().isNotFound());
		}

	}

}
