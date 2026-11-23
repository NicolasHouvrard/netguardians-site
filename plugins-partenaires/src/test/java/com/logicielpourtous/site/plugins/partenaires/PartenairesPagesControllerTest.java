package com.logicielpourtous.site.plugins.partenaires;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;

class PartenairesPagesControllerTest {

	private static final String V = "plugins/partenaires/";

	private GestionPartenairesContrat contrat;
	private MockMvc mvc;

	@BeforeEach
	void preparer() {
		contrat = mock(GestionPartenairesContrat.class);
		// vue unique et vide : aucun rendu, le nom de vue reste verifiable
		View vide = (m, rq, rs) -> {
		};
		mvc = MockMvcBuilders.standaloneSetup(new PartenairesPagesController(contrat)).setViewResolvers((nom, loc) -> nom.startsWith("redirect:") ? new org.springframework.web.servlet.view.RedirectView(nom.substring(9)) : vide).build();
	}

	// (a) liste

	@Test
	void listeRenvoieLaVueEtLesPartenaires() throws Exception {
		List<PartenaireResume> l = List.of(new PartenaireResume(7L, "123456789", "Acme", "ACTIF"));
		when(contrat.listerPartenaires()).thenReturn(l);
		mvc.perform(get("/liste"))
			.andExpect(status().isOk())
			.andExpect(view().name(V + "liste"))
			.andExpect(model().attribute("partenaires", l))
			.andExpect(model().attribute("moduleActif", "partenaires"))
			.andExpect(model().attributeExists("titre"));
	}

	@Test
	void okConnuDonneUnMessageFixeEtOkInconnuAucun() throws Exception {
		when(contrat.listerPartenaires()).thenReturn(List.of());
		mvc.perform(get("/liste").param("ok", "client")).andExpect(model().attributeExists("succes"));
		mvc.perform(get("/liste").param("ok", "partenaire")).andExpect(model().attributeExists("succes"));
		mvc.perform(get("/liste").param("ok", "<script>")).andExpect(model().attributeDoesNotExist("succes"));
		mvc.perform(get("/liste")).andExpect(model().attributeDoesNotExist("succes"));
	}

	// (b) creation partenaire

	@Test
	void creerPartenaireCreeRedirige() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("CREE", 5L));
		mvc.perform(post("/").param("siren", "123456789")
			.param("tva", "FR1")
			.param("raisonSociale", "Acme")
			.param("email", "a@b.fr")
			.param("nom", "Jean"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/p/partenaires/liste?ok=partenaire"));
		ArgumentCaptor<CreationPartenaire> c = ArgumentCaptor.forClass(CreationPartenaire.class);
		verify(contrat).creerPartenaire(c.capture());
		assertEquals(new CreationPartenaire("123456789", "FR1", "Acme", "a@b.fr", "Jean"), c.getValue());
	}

	@ParameterizedTest
	@ValueSource(strings = { "SIREN_DEJA_CONNU", "EMAIL_DEJA_UTILISE" })
	void doublonsDonnent409AvecSaisieEtMessageFixe(String issue) throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire(issue, null));
		mvc.perform(post("/").param("siren", "123456789").param("raisonSociale", "<b>X</b>"))
			.andExpect(status().isConflict())
			.andExpect(view().name(V + "partenaire-form"))
			.andExpect(model().attributeExists("erreur"))
			.andExpect(model().attributeExists("saisie"));
	}

	@Test
	void demandeInvalideDonne400SansDetailInterne() throws Exception {
		when(contrat.creerPartenaire(any())).thenThrow(new IllegalArgumentException("detail-interne"));
		var r = mvc.perform(post("/").param("siren", "x"))
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "partenaire-form"))
			.andReturn();
		assertFalse(r.getModelAndView().getModel().toString().contains("detail-interne"));
	}

	@Test
	void issueInattendueDonne500() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("???", null));
		mvc.perform(post("/")).andExpect(status().isInternalServerError()).andExpect(view().name(V + "erreur"));
	}

	@Test
	void nouveauAfficheLeFormulaire() throws Exception {
		mvc.perform(get("/nouveau")).andExpect(status().isOk()).andExpect(view().name(V + "partenaire-form"));
		verifyNoInteractions(contrat);
	}

	// (c) refus et erreurs

	private ResultActions[] toutesLesRoutes() throws Exception {
		return new ResultActions[] { mvc.perform(get("/liste")), mvc.perform(post("/").param("siren", "1")),
				mvc.perform(post("/12/clients").param("siren", "1")), mvc.perform(get("/modele")),
				mvc.perform(post("/modele/ajouter").param("droit", "clients.exporter")),
				mvc.perform(post("/modele/retirer").param("droit", "clients.exporter")) };
	}

	private void toutLeContratLeve(RuntimeException e) {
		when(contrat.listerPartenaires()).thenThrow(e);
		when(contrat.creerPartenaire(any())).thenThrow(e);
		when(contrat.creerClientPourPartenaire(any())).thenThrow(e);
		when(contrat.modeleDroits()).thenThrow(e);
	}

	@Test
	void securityExceptionDonne403VueRefuseSansDetail() throws Exception {
		toutLeContratLeve(new SecurityException("detail-secret"));
		for (ResultActions r : toutesLesRoutes()) {
			var res = r.andExpect(status().isForbidden()).andExpect(view().name(V + "refuse")).andReturn();
			assertFalse(res.getModelAndView().getModel().toString().contains("detail-secret"));
			assertFalse(res.getResponse().getContentAsString().contains("detail-secret"));
		}
		verify(contrat, never()).remplacerModeleDroits(any());
	}

	@Test
	void runtimeExceptionDonne500VueErreurSansDetail() throws Exception {
		toutLeContratLeve(new IllegalStateException("detail-secret"));
		for (ResultActions r : toutesLesRoutes()) {
			var res = r.andReturn();
			assertEquals(500, res.getResponse().getStatus());
			assertEquals(V + "erreur", res.getModelAndView().getViewName());
			assertFalse(res.getModelAndView().getModel().toString().contains("detail-secret"));
		}
	}

	// (d) clients

	@Test
	void clientUtiliseLIdentifiantDuCheminJamaisLeCorps() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("CREE", 9L));
		mvc.perform(post("/42/clients").param("partenaireSocieteId", "999")
			.param("particulier", "on")
			.param("siren", "123456789")
			.param("tva", "FR1")
			.param("email", "c@d.fr")
			.param("nom", "Zoe")).andExpect(redirectedUrl("/p/partenaires/liste?ok=client"));
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(contrat).creerClientPourPartenaire(c.capture());
		assertEquals(new CreationClient(42L, true, "123456789", "FR1", false, "c@d.fr", "Zoe"), c.getValue());
	}

	@Test
	void particulierAbsentVautFaux() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("CREE", 9L));
		mvc.perform(post("/42/clients").param("siren", "123456789")).andExpect(status().is3xxRedirection());
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(contrat).creerClientPourPartenaire(c.capture());
		assertFalse(c.getValue().particulier());
	}

	@Test
	void clientDoublonsMessagesNeutres409() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("DEJA_CONNUE", null));
		mvc.perform(post("/42/clients").param("siren", "1"))
			.andExpect(status().isConflict())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attribute("erreur",
					"société déjà connue, une demande d'accès a été envoyée à son manager"));
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("EMAIL_DEJA_UTILISE", null));
		mvc.perform(post("/42/clients").param("siren", "1"))
			.andExpect(status().isConflict())
			.andExpect(model().attribute("erreur", "cette adresse e-mail est déjà utilisée"));
	}

	@Test
	void clientDemandeInvalide400() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenThrow(new IllegalArgumentException("detail-interne"));
		var r = mvc.perform(post("/42/clients").param("siren", "1"))
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "client-form"))
			.andReturn();
		assertFalse(r.getModelAndView().getModel().toString().contains("detail-interne"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "1;2", "..%2Fx", "1234567890123456", "-1", "1.5" })
	void identifiantInvalideDonne404SansContrat(String id) throws Exception {
		mvc.perform(get("/" + id + "/clients/nouveau")).andExpect(status().isNotFound());
		mvc.perform(post("/" + id + "/clients").param("siren", "1")).andExpect(status().isNotFound());
		verifyNoInteractions(contrat);
	}

	@Test
	void formulaireClientAvecIdentifiantValide() throws Exception {
		mvc.perform(get("/42/clients/nouveau"))
			.andExpect(status().isOk())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attribute("partenaireId", 42L));
	}

	// (e) modele de droits

	@Test
	void modeleAfficheLesDroitsTries() throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("b.x", "a.x"));
		mvc.perform(get("/modele"))
			.andExpect(view().name(V + "modele"))
			.andExpect(model().attribute("droits", List.of("a.x", "b.x")));
		mvc.perform(get("/modele").param("ok", "modele")).andExpect(model().attributeExists("succes"));
	}

	@Test
	void ajouterFaitLUnion() throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("clients.lire"));
		mvc.perform(post("/modele/ajouter").param("droit", "clients.exporter"))
			.andExpect(redirectedUrl("/p/partenaires/modele?ok=modele"));
		verify(contrat).remplacerModeleDroits(Set.of("clients.lire", "clients.exporter"));
	}

	@Test
	void retirerEnleveLeDroit() throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("clients.lire", "clients.exporter"));
		mvc.perform(post("/modele/retirer").param("droit", "clients.exporter"))
			.andExpect(redirectedUrl("/p/partenaires/modele?ok=modele"));
		verify(contrat).remplacerModeleDroits(Set.of("clients.lire"));
	}

	@Test
	void retirerLeDernierDroitRefuse() throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("clients.lire"));
		mvc.perform(post("/modele/retirer").param("droit", "clients.lire"))
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "modele"))
			.andExpect(model().attributeExists("erreur"));
		verify(contrat, never()).remplacerModeleDroits(any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "Mauvais Droit", "", "a1234567890123456789012345678901234567890123456789012345678901" })
	void droitInvalideRefuse(String droit) throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("clients.lire"));
		mvc.perform(post("/modele/ajouter").param("droit", droit)).andExpect(status().isBadRequest());
		mvc.perform(post("/modele/retirer").param("droit", droit)).andExpect(status().isBadRequest());
		mvc.perform(post("/modele/ajouter")).andExpect(status().isBadRequest());
		verify(contrat, never()).remplacerModeleDroits(any());
	}

	@Test
	void retirerUnDroitAbsentEstIdempotentSansAppel() throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("clients.lire"));
		mvc.perform(post("/modele/retirer").param("droit", "clients.exporter"))
			.andExpect(redirectedUrl("/p/partenaires/modele?ok=modele"));
		verify(contrat, never()).remplacerModeleDroits(any());
	}

}
