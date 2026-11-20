package com.logicielpourtous.site.plugins.partenaires;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.CreationPartenaire;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.PartenaireResume;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.ResultatPartenaire;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Le plugin ne verifie aucun role : il traduit les issues du contrat (qui refuse tout sauf l'admin maitre) en HTTP. */
class PartenairesControllerTest {

	private static final String CORPS_PARTENAIRE = "{\"siren\":\"123456789\",\"tva\":\"FR1\",\"raisonSociale\":\"Acme\","
			+ "\"email\":\"a@b.fr\",\"nom\":\"Ann\"}";
	private static final String CORPS_CLIENT = "{\"particulier\":false,\"siren\":\"987654321\",\"tva\":\"FR2\","
			+ "\"email\":\"c@d.fr\",\"nom\":\"Cli\"}";

	private GestionPartenairesContrat contrat;
	private MockMvc mvc;

	@BeforeEach
	void preparer() {
		contrat = mock(GestionPartenairesContrat.class);
		mvc = MockMvcBuilders.standaloneSetup(new PartenairesController(contrat)).build();
	}

	private static SecurityException refus() {
		return new SecurityException("reserve");
	}

	// --- liste ---

	@Test
	void listeRenvoieLesPartenaires() throws Exception {
		when(contrat.listerPartenaires()).thenReturn(List.of(new PartenaireResume(5L, "123", "Acme", "ACTIF")));
		mvc.perform(get("/liste")).andExpect(status().isOk()).andExpect(jsonPath("$[0].societeId").value(5))
				.andExpect(jsonPath("$[0].raisonSociale").value("Acme"));
	}

	@Test
	void listeRefuseParLeContratDonne403Vide() throws Exception {
		when(contrat.listerPartenaires()).thenThrow(refus());
		mvc.perform(get("/liste")).andExpect(status().isForbidden()).andExpect(content().string(""));
	}

	@Test
	void listeErreurInattendueDonne500Vide() throws Exception {
		when(contrat.listerPartenaires()).thenThrow(new IllegalStateException("secret interne"));
		mvc.perform(get("/liste")).andExpect(status().isInternalServerError()).andExpect(content().string(""));
	}

	// --- creation partenaire ---

	@Test
	void creationPartenaireCreeDonne201AvecSocieteId() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("CREE", 42L));
		mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(CORPS_PARTENAIRE))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.societeId").value(42));
		verify(contrat).creerPartenaire(new CreationPartenaire("123456789", "FR1", "Acme", "a@b.fr", "Ann"));
	}

	@Test
	void creationPartenaireSirenDejaConnuDonne409() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("SIREN_DEJA_CONNU", null));
		mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(CORPS_PARTENAIRE))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.issue").value("SIREN_DEJA_CONNU"))
				.andExpect(jsonPath("$.message").value("Ce SIREN est déjà enregistré"));
	}

	@Test
	void creationPartenaireEmailDejaUtiliseDonne409() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("EMAIL_DEJA_UTILISE", null));
		mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(CORPS_PARTENAIRE))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.issue").value("EMAIL_DEJA_UTILISE"))
				.andExpect(jsonPath("$.message").value("Cette adresse e-mail est déjà utilisée"));
	}

	@Test
	void creationPartenaireInvalideDonne400SansLeMessageDOrigine() throws Exception {
		when(contrat.creerPartenaire(any())).thenThrow(new IllegalArgumentException("detail-interne-xyz"));
		String corps = mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(CORPS_PARTENAIRE))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertEquals(false, corps.contains("detail-interne-xyz"));
	}

	@Test
	void creationPartenaireRefuseParLeContratDonne403Vide() throws Exception {
		when(contrat.creerPartenaire(any())).thenThrow(refus());
		mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(CORPS_PARTENAIRE))
				.andExpect(status().isForbidden()).andExpect(content().string(""));
	}

	@Test
	void creationPartenaireIssueInattendueDonne500Vide() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("AUTRE", null));
		mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(CORPS_PARTENAIRE))
				.andExpect(status().isInternalServerError()).andExpect(content().string(""));
	}

	@Test
	void creationPartenaireCreeSansIdentifiantDonne500Vide() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("CREE", null));
		mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(CORPS_PARTENAIRE))
				.andExpect(status().isInternalServerError()).andExpect(content().string(""));
	}

	// --- creation client pour partenaire ---

	@Test
	void creationClientTransmetLePartenaireDuCheminEtIgnoreCeluiDuCorps() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("CREE", 77L));
		String corps = "{\"partenaireSocieteId\":999,\"particulier\":true,\"siren\":\"987654321\",\"tva\":\"FR2\","
				+ "\"email\":\"c@d.fr\",\"nom\":\"Cli\"}";
		mvc.perform(post("/123/clients").contentType(MediaType.APPLICATION_JSON).content(corps))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.societeId").value(77));
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(contrat).creerClientPourPartenaire(c.capture());
		assertEquals(new CreationClient(123L, true, "987654321", "FR2", false, "c@d.fr", "Cli"), c.getValue());
	}

	@Test
	void creationClientDejaConnueDonne409Neutre() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("DEJA_CONNUE", null));
		mvc.perform(post("/123/clients").contentType(MediaType.APPLICATION_JSON).content(CORPS_CLIENT))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.issue").value("DEJA_CONNUE"))
				.andExpect(jsonPath("$.message")
						.value("société déjà connue, une demande d'accès a été envoyée à son manager"));
	}

	@Test
	void creationClientEmailDejaUtiliseDonne409Neutre() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("EMAIL_DEJA_UTILISE", null));
		mvc.perform(post("/123/clients").contentType(MediaType.APPLICATION_JSON).content(CORPS_CLIENT))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.issue").value("EMAIL_DEJA_UTILISE"))
				.andExpect(jsonPath("$.message").value("cette adresse e-mail est déjà utilisée"));
	}

	@Test
	void creationClientInvalideDonne400SansLeMessageDOrigine() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenThrow(new IllegalArgumentException("detail-interne-xyz"));
		String corps = mvc.perform(post("/123/clients").contentType(MediaType.APPLICATION_JSON).content(CORPS_CLIENT))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertEquals(false, corps.contains("detail-interne-xyz"));
	}

	@Test
	void creationClientRefuseParLeContratDonne403Vide() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenThrow(refus());
		mvc.perform(post("/123/clients").contentType(MediaType.APPLICATION_JSON).content(CORPS_CLIENT))
				.andExpect(status().isForbidden()).andExpect(content().string(""));
	}

	@Test
	void creationClientErreurInattendueDonne500Vide() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenThrow(new IllegalStateException("secret interne"));
		mvc.perform(post("/123/clients").contentType(MediaType.APPLICATION_JSON).content(CORPS_CLIENT))
				.andExpect(status().isInternalServerError()).andExpect(content().string(""));
	}

	@Test
	void creationClientIdentifiantNonNumeriqueDonne400SansAppelerLeContrat() throws Exception {
		for (String id : new String[] { "abc", "12a", "-1", "1.5", "1234567890123456", "%20" }) {
			mvc.perform(post("/" + id + "/clients").contentType(MediaType.APPLICATION_JSON).content(CORPS_CLIENT))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(contrat);
	}

	// --- modele de droits ---

	@Test
	void modeleDroitsRenvoieLaListe() throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("clients.lire"));
		mvc.perform(get("/modele-droits")).andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("clients.lire"));
	}

	@Test
	void modeleDroitsRefuseParLeContratDonne403Vide() throws Exception {
		when(contrat.modeleDroits()).thenThrow(refus());
		mvc.perform(get("/modele-droits")).andExpect(status().isForbidden()).andExpect(content().string(""));
	}

	@Test
	void remplacerModeleTransmetLEnsembleRecuEtDonne204() throws Exception {
		mvc.perform(put("/modele-droits").contentType(MediaType.APPLICATION_JSON)
				.content("{\"droits\":[\"clients.lire\",\"clients.creer\"]}")).andExpect(status().isNoContent());
		verify(contrat).remplacerModeleDroits(Set.of("clients.lire", "clients.creer"));
	}

	@Test
	void remplacerModeleInvalideDonne400SansLeMessageDOrigine() throws Exception {
		doThrow(new IllegalArgumentException("detail-interne-xyz")).when(contrat).remplacerModeleDroits(any());
		String corps = mvc
			.perform(put("/modele-droits").contentType(MediaType.APPLICATION_JSON).content("{\"droits\":[\"x\"]}"))
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertEquals(false, corps.contains("detail-interne-xyz"));
	}

	@Test
	void remplacerModeleRefuseParLeContratDonne403Vide() throws Exception {
		doThrow(refus()).when(contrat).remplacerModeleDroits(any());
		mvc.perform(put("/modele-droits").contentType(MediaType.APPLICATION_JSON).content("{\"droits\":[\"x\"]}"))
				.andExpect(status().isForbidden()).andExpect(content().string(""));
	}

	@Test
	void remplacerModeleSansDroitsDonne400SansAppelerLeContrat() throws Exception {
		mvc.perform(put("/modele-droits").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(contrat);
	}

}
