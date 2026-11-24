package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.logicielpourtous.site.features.plateforme.DistributionController.EtatServeur;

/**
 * Écran {@code /admin/plugins} sur l'application complète (vraie chaîne de sécurité, rôle MASTER par défaut) ; les
 * services du dépôt, du gestionnaire et la lecture des états des serveurs sont simulés.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.plateforme.actif=true")
class PluginsEcranControllerTest {

	private static final String CONFLIT = "version déjà publiée avec un contenu différent : publier un numéro plus haut";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private DepotPlugins depot;

	@MockitoBean
	private PluginManager manager;

	@MockitoBean
	private LectureEtatsServeurs lecture;

	private static RequestPostProcessor admin() {
		return user("admin-ecran").roles("ADMIN");
	}

	private static RequestPostProcessor gestionnaire() {
		return user("gest-ecran").roles("GESTIONNAIRE");
	}

	private static EntreeCatalogue entree(String nom, String version) {
		return new EntreeCatalogue(nom, version, "coeur", "1.0.0", null, "ab".repeat(32), 1234);
	}

	private static MockMultipartHttpServletRequestBuilder envoi(boolean avecSig) {
		MockMultipartHttpServletRequestBuilder b = multipart("/admin/plugins/publier")
			.file(new MockMultipartFile("jar", "../../x.jar", "application/java-archive", new byte[] { 1, 2, 3 }));
		if (avecSig) {
			b.file(new MockMultipartFile("sig", "x.sig", "application/json", new byte[] { 4, 5 }));
		}
		return b;
	}

	@BeforeEach
	void defauts() {
		when(depot.catalogue()).thenReturn(List.of());
		when(manager.etats()).thenReturn(List.of());
		when(lecture.derniers(500)).thenReturn(List.of());
	}

	// ---- (a) accès ----------------------------------------------------------------------------------------------

	@Test
	void anonyme_est_renvoye_au_login_pour_le_get_et_les_post() throws Exception {
		mvc.perform(get("/admin/plugins")).andExpect(status().isFound())
			.andExpect(header().string("Location", endsWith("/login")));
		mvc.perform(envoi(true).with(csrf())).andExpect(status().isFound())
			.andExpect(header().string("Location", endsWith("/login")));
		mvc.perform(post("/admin/plugins/alpha/desinstaller").with(csrf())).andExpect(status().isFound())
			.andExpect(header().string("Location", endsWith("/login")));
		verify(manager, never()).desinstaller(anyString());
	}

	@Test
	void non_admin_403_partout_et_aucun_effet() throws Exception {
		mvc.perform(get("/admin/plugins").with(gestionnaire())).andExpect(status().isForbidden());
		mvc.perform(get("/admin/plugins").with(user("u").roles("USER"))).andExpect(status().isForbidden());
		mvc.perform(envoi(true).with(gestionnaire()).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/admin/plugins/alpha/desinstaller").with(gestionnaire()).with(csrf()))
			.andExpect(status().isForbidden());
		verify(depot, never()).publier(any(), any());
		verify(manager, never()).desinstaller(anyString());
	}

	@Test
	void post_sans_jeton_csrf_403_meme_pour_l_admin() throws Exception {
		mvc.perform(envoi(true).with(admin())).andExpect(status().isForbidden());
		mvc.perform(post("/admin/plugins/alpha/desinstaller").with(admin())).andExpect(status().isForbidden());
		verify(depot, never()).publier(any(), any());
		verify(manager, never()).desinstaller(anyString());
	}

	// ---- (b) page ---------------------------------------------------------------------------------------------

	@Test
	void get_admin_affiche_les_trois_sections_et_un_plugin() throws Exception {
		when(depot.catalogue()).thenReturn(List.of(entree("alpha", "1.2.3")));
		when(manager.etats()).thenReturn(List.of(new EtatPlugin("alpha", "1.2.3", EtatPlugin.Etat.ACTIF)));
		when(lecture.derniers(500)).thenReturn(List.of(new EtatServeur(7L, "alpha", "1.2.3", "OK", List.of("ok"),
				Instant.parse("2026-11-24T10:00:00Z"), null)));

		String html = mvc.perform(get("/admin/plugins").with(admin())).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();

		assertThat(html).contains("Dépôt du Maître").contains("Installés sur le Maître").contains("Serveurs annexes")
			.contains("alpha").contains("1.2.3").contains("enctype=\"multipart/form-data\"")
			.contains("action=\"/admin/plugins/publier\"").contains("name=\"_csrf\"");
		assertThat(html).contains("/admin/plugins/alpha/desinstaller").contains("hx-confirm");
	}

	@Test
	void texte_hostile_affiche_echappe_jamais_en_balise_brute() throws Exception {
		when(depot.catalogue()).thenReturn(List.of(entree("<script>x</script>", "1.0.0")));
		when(manager.etats()).thenReturn(List.of(new EtatPlugin("<script>x</script>", "<b>v</b>",
				EtatPlugin.Etat.ACTIF)));
		when(lecture.derniers(500)).thenReturn(List.of(new EtatServeur(1L, "<script>x</script>", "1", "ECHEC",
				List.of("\"><img src=x onerror=1>"), Instant.now(), null)));

		String html = mvc.perform(get("/admin/plugins").with(admin())).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();

		assertThat(html).doesNotContain("<script>x</script>").doesNotContain("<img src=x").doesNotContain("<b>v</b>");
		assertThat(html).contains("&lt;script&gt;x&lt;/script&gt;").contains("&lt;img src=x onerror=1&gt;");
	}

	@Test
	void le_message_de_desinstallation_dit_que_le_retrait_dure_jusqu_au_redemarrage() throws Exception {
		String html = mvc.perform(get("/admin/plugins").param("ok", "desinstalle").with(admin())).andReturn()
			.getResponse().getContentAsString();
		assertThat(html).contains("jusqu&#39;au prochain redémarrage").doesNotContain("ne sera pas réinstallé");
	}

	@Test
	void le_message_ok_vient_d_une_liste_fixe_jamais_du_parametre() throws Exception {
		String html = mvc.perform(get("/admin/plugins").param("ok", "publie").with(admin())).andReturn()
			.getResponse().getContentAsString();
		assertThat(html).contains("Plugin publié");
		html = mvc.perform(get("/admin/plugins").param("ok", "<script>boom</script>").with(admin())).andReturn()
			.getResponse().getContentAsString();
		assertThat(html).doesNotContain("boom").doesNotContain("Plugin publié");
	}

	// ---- (c) publication --------------------------------------------------------------------------------------

	@Test
	void publication_refusee_invalide_400_avec_le_message_du_refus_seulement() throws Exception {
		when(depot.publier(any(), any()))
			.thenThrow(new DepotPlugins.DepotRefuse(DepotPlugins.Motif.INVALIDE, "signature invalide : clé inconnue"));

		String html = mvc.perform(envoi(true).with(admin()).with(csrf())).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("signature invalide : clé inconnue").doesNotContain("DepotRefuse")
			.doesNotContain("Exception").doesNotContain("x.jar");
	}

	@Test
	void conflit_409_message_fixe() throws Exception {
		when(depot.publier(any(), any()))
			.thenThrow(new DepotPlugins.DepotRefuse(DepotPlugins.Motif.CONFLIT, "texte interne à ne pas afficher"));
		String html = mvc.perform(envoi(true).with(admin()).with(csrf())).andExpect(status().isConflict()).andReturn()
			.getResponse().getContentAsString();
		assertThat(html).contains(CONFLIT).doesNotContain("texte interne");
	}

	@Test
	void trop_volumineux_413() throws Exception {
		when(depot.publier(any(), any()))
			.thenThrow(new DepotPlugins.DepotRefuse(DepotPlugins.Motif.TROP_VOLUMINEUX, "interne"));
		mvc.perform(envoi(true).with(admin()).with(csrf())).andExpect(status().isPayloadTooLarge());
	}

	@Test
	void publication_nouvelle_redirige_vers_publie() throws Exception {
		when(depot.publier(any(), any())).thenReturn(entree("alpha", "1.0.0"));
		mvc.perform(envoi(true).with(admin()).with(csrf())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/admin/plugins?ok=publie"));
	}

	@Test
	void publication_identique_redirige_vers_identique_et_le_message_le_dit() throws Exception {
		EntreeCatalogue connue = entree("alpha", "1.0.0");
		when(depot.catalogue()).thenReturn(List.of(connue));
		when(depot.publier(any(), any())).thenReturn(connue);

		mvc.perform(envoi(true).with(admin()).with(csrf())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/admin/plugins?ok=identique"));
		String html = mvc.perform(get("/admin/plugins").param("ok", "identique").with(admin())).andReturn()
			.getResponse().getContentAsString();
		assertThat(html).contains("déjà publié (identique)");
	}

	@Test
	void partie_manquante_400_message_fixe_sans_appel_au_depot() throws Exception {
		String html = mvc.perform(envoi(false).with(admin()).with(csrf())).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("jar et signature obligatoires");
		verify(depot, never()).publier(any(), any());
	}

	// ---- (d) désinstallation ----------------------------------------------------------------------------------

	@Test
	void desinstaller_un_plugin_connu_redirige() throws Exception {
		when(manager.desinstaller("alpha")).thenReturn(true);
		mvc.perform(post("/admin/plugins/alpha/desinstaller").with(admin()).with(csrf()))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/admin/plugins?ok=desinstalle"));
		verify(manager).desinstaller("alpha");
	}

	@Test
	void desinstaller_un_plugin_inconnu_donne_un_message_sans_500() throws Exception {
		when(manager.desinstaller("inconnu")).thenReturn(false);
		String html = mvc.perform(post("/admin/plugins/inconnu/desinstaller").with(admin()).with(csrf()))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Plugin inconnu");
	}

	@Test
	void noms_pieges_refuses_sans_appel_a_desinstaller() throws Exception {
		for (String nom : List.of("..%2Fx", "Plugin", "a".repeat(65), "a;b", "-a", "a%5Cb")) {
			int s = mvc.perform(post("/admin/plugins/" + nom + "/desinstaller").with(admin()).with(csrf()))
				.andReturn().getResponse().getStatus();
			assertThat(s).as(nom).isIn(400, 404);
		}
		verify(manager, never()).desinstaller(anyString());
	}

}
