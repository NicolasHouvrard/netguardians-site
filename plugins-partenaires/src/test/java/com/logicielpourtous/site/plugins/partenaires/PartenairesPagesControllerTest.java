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

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pOk(String... surcharges) {
		java.util.Map<String, String> p = new java.util.LinkedHashMap<>(java.util.Map.of("siren", "732829320", "tva", "FR44732829320",
				"raisonSociale", "Acme", "email", "a@b.fr", "nom", "Jean"));
		for (int i = 0; i < surcharges.length; i += 2) {
			p.put(surcharges[i], surcharges[i + 1]);
		}
		var r = post("/");
		p.forEach((k, v) -> {
			if (v != null) {
				r.param(k, v);
			}
		});
		return r;
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cOk(long id,
			String... surcharges) {
		java.util.Map<String, String> p = new java.util.LinkedHashMap<>(
				java.util.Map.of("siren", "732829320", "email", "c@d.fr", "nom", "Zoe"));
		for (int i = 0; i < surcharges.length; i += 2) {
			p.put(surcharges[i], surcharges[i + 1]);
		}
		var r = post("/" + id + "/clients");
		p.forEach((k, v) -> {
			if (v != null) {
				r.param(k, v);
			}
		});
		return r;
	}

	// (a) liste

	@Test
	void listeRenvoieLaVueEtLesPartenaires() throws Exception {
		List<PartenaireResume> l = List.of(new PartenaireResume(7L, "732829320", "Acme", "ACTIF"));
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
		mvc.perform(post("/").param("siren", "732829320")
			.param("tva", "FR44732829320")
			.param("raisonSociale", "Acme")
			.param("email", "a@b.fr")
			.param("nom", "Jean"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/p/partenaires/liste?ok=partenaire"));
		ArgumentCaptor<CreationPartenaire> c = ArgumentCaptor.forClass(CreationPartenaire.class);
		verify(contrat).creerPartenaire(c.capture());
		assertEquals(new CreationPartenaire("732829320", "FR44732829320", "Acme", "a@b.fr", "Jean"), c.getValue());
	}

	@ParameterizedTest
	@ValueSource(strings = { "SIREN_DEJA_CONNU", "EMAIL_DEJA_UTILISE" })
	void doublonsDonnent409AvecSaisieEtMessageFixe(String issue) throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire(issue, null));
		mvc.perform(pOk("raisonSociale", "<b>X</b>"))
			.andExpect(status().isConflict())
			.andExpect(view().name(V + "partenaire-form"))
			.andExpect(model().attributeExists("erreur"))
			.andExpect(model().attribute("saisie", org.hamcrest.Matchers.hasEntry("siren", "732829320")))
			.andExpect(model().attribute("saisie", org.hamcrest.Matchers.hasEntry("raisonSociale", "<b>X</b>")));
	}

	@Test
	void demandeInvalideDonne400SansDetailInterne() throws Exception {
		when(contrat.creerPartenaire(any())).thenThrow(new IllegalArgumentException("Le SIREN est invalide."));
		var r = mvc.perform(pOk())
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "partenaire-form"))
			.andReturn();
		assertEquals("Le SIREN est invalide.", r.getModelAndView().getModel().get("erreur"));
	}

	@Test
	void issueInattendueDonne500() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("???", null));
		mvc.perform(pOk()).andExpect(status().isInternalServerError()).andExpect(view().name(V + "erreur"));
	}

	@Test
	void nouveauAfficheLeFormulaire() throws Exception {
		mvc.perform(get("/nouveau")).andExpect(status().isOk()).andExpect(view().name(V + "partenaire-form"));
		verifyNoInteractions(contrat);
	}

	// (c) refus et erreurs

	private ResultActions[] toutesLesRoutes() throws Exception {
		return new ResultActions[] { mvc.perform(get("/liste")), mvc.perform(pOk()),
				mvc.perform(cOk(12)), mvc.perform(get("/modele")),
				mvc.perform(post("/modele/ajouter").param("droit", "clients.exporter")),
				mvc.perform(post("/modele/retirer").param("droit", "clients.exporter")), mvc.perform(get("/12/clients")) };
	}

	private void toutLeContratLeve(RuntimeException e) {
		when(contrat.listerPartenaires()).thenThrow(e);
		when(contrat.creerPartenaire(any())).thenThrow(e);
		when(contrat.creerClientPourPartenaire(any())).thenThrow(e);
		when(contrat.modeleDroits()).thenThrow(e);
		when(contrat.listerClientsDuPartenaire(org.mockito.ArgumentMatchers.anyLong())).thenThrow(e);
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
			.param("siren", "732829320")
			.param("tva", "FR44732829320")
			.param("email", "c@d.fr")
			.param("nom", "Zoe")).andExpect(redirectedUrl("/p/partenaires/liste?ok=client"));
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(contrat).creerClientPourPartenaire(c.capture());
		assertEquals(new CreationClient(42L, true, "732829320", "FR44732829320", false, "c@d.fr", "Zoe"), c.getValue());
	}

	@Test
	void particulierAbsentVautFaux() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("CREE", 9L));
		mvc.perform(cOk(42, "particulier", null)).andExpect(status().is3xxRedirection());
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(contrat).creerClientPourPartenaire(c.capture());
		assertFalse(c.getValue().particulier());
	}

	@Test
	void clientDoublonsMessagesNeutres409() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("DEJA_CONNUE", null));
		mvc.perform(cOk(42))
			.andExpect(status().isConflict())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attribute("erreur",
					"société déjà connue, une demande d'accès a été envoyée à son manager"));
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("EMAIL_DEJA_UTILISE", null));
		mvc.perform(cOk(42))
			.andExpect(status().isConflict())
			.andExpect(model().attribute("erreur", "cette adresse e-mail est déjà utilisée"));
	}

	@Test
	void clientDemandeInvalide400() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenThrow(new IllegalArgumentException("Message noyau."));
		var r = mvc.perform(cOk(42))
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "client-form"))
			.andReturn();
		assertEquals("Message noyau.", r.getModelAndView().getModel().get("erreur"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "1;2", "..%2Fx", "7328293200123456", "-1", "1.5" })
	void identifiantInvalideDonne404SansContrat(String id) throws Exception {
		mvc.perform(get("/" + id + "/clients/nouveau")).andExpect(status().isNotFound()).andExpect(view().name(V + "erreur"));
		mvc.perform(post("/" + id + "/clients").param("siren", "1")).andExpect(status().isNotFound()).andExpect(view().name(V + "erreur"));
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
	@ValueSource(strings = { "Mauvais Droit", "" })
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
	@Test
	void droitDe61CaracteresRefuseEtDe60Accepte() throws Exception {
		when(contrat.modeleDroits()).thenReturn(Set.of("clients.lire"));
		String trop = "a".repeat(61);
		String limite = "a".repeat(60);
		mvc.perform(post("/modele/ajouter").param("droit", trop)).andExpect(status().isBadRequest());
		verify(contrat, never()).remplacerModeleDroits(any());
		mvc.perform(post("/modele/ajouter").param("droit", limite))
			.andExpect(redirectedUrl("/p/partenaires/modele?ok=modele"));
		verify(contrat).remplacerModeleDroits(Set.of("clients.lire", limite));
	}

	@Test
	void exceptionInattendueEstJournaliseeMaisAbsenteDeLaReponse() throws Exception {
		java.util.logging.Logger journal = java.util.logging.Logger.getLogger(PartenairesPagesController.class.getName());
		java.util.List<java.util.logging.LogRecord> enregistrements = new java.util.ArrayList<>();
		java.util.logging.Handler capteur = new java.util.logging.Handler() {
			@Override
			public void publish(java.util.logging.LogRecord r) {
				enregistrements.add(r);
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		journal.addHandler(capteur);
		try {
			IllegalStateException cause = new IllegalStateException("detail-secret");
			when(contrat.listerPartenaires()).thenThrow(cause);
			var res = mvc.perform(get("/liste")).andExpect(status().isInternalServerError()).andReturn();
			assertFalse(res.getModelAndView().getModel().toString().contains("detail-secret"));
			assertFalse(res.getResponse().getContentAsString().contains("detail-secret"));
			assertEquals(1, enregistrements.size());
			assertEquals(java.util.logging.Level.SEVERE, enregistrements.get(0).getLevel());
			assertEquals(cause, enregistrements.get(0).getThrown());
		}
		finally {
			journal.removeHandler(capteur);
		}
	}

	@Test
	void particulierAvecChampsVidesTransmetNull() throws Exception {
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("CREE", 9L));
		mvc.perform(post("/42/clients").param("particulier", "on")
			.param("siren", "")
			.param("tva", "  ")
			.param("email", "c@d.fr")
			.param("nom", "Zoe")).andExpect(redirectedUrl("/p/partenaires/liste?ok=client"));
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(contrat).creerClientPourPartenaire(c.capture());
		assertEquals(new CreationClient(42L, true, null, null, false, "c@d.fr", "Zoe"), c.getValue());
	}

	@Test
	void tvaPartenaireVideTransmetNull() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("CREE", 5L));
		mvc.perform(post("/").param("siren", "732829320")
			.param("tva", "")
			.param("raisonSociale", "Acme")
			.param("email", "a@b.fr")
			.param("nom", "Jean")).andExpect(redirectedUrl("/p/partenaires/liste?ok=partenaire"));
		ArgumentCaptor<CreationPartenaire> c = ArgumentCaptor.forClass(CreationPartenaire.class);
		verify(contrat).creerPartenaire(c.capture());
		assertEquals(new CreationPartenaire("732829320", null, "Acme", "a@b.fr", "Jean"), c.getValue());
	}

	// validation par champ dans le plugin : le contrat n'est pas appele

	private void refuseChampPartenaire(String champ, String valeur) throws Exception {
		var r = mvc.perform(pOk(champ, valeur))
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "partenaire-form"))
			.andReturn();
		@SuppressWarnings("unchecked")
		java.util.Map<String, String> e = (java.util.Map<String, String>) r.getModelAndView().getModel().get("erreursChamps");
		org.junit.jupiter.api.Assertions.assertNotNull(e, champ + "=" + valeur);
		org.junit.jupiter.api.Assertions.assertTrue(e.containsKey(champ), champ + "=" + valeur + " -> " + e);
		org.junit.jupiter.api.Assertions.assertEquals(1, e.size(), e.toString());
		org.junit.jupiter.api.Assertions.assertTrue(r.getModelAndView().getModel().containsKey("saisie"));
		verifyNoInteractions(contrat);
	}

	@ParameterizedTest
	@ValueSource(strings = { "123456789", "12", "abcdefghi", "7328293201", "", "   " })
	void sirenPartenaireInvalide(String v) throws Exception {
		refuseChampPartenaire("siren", v);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "  ", "a@@b.fr", "a b@c.fr", "@b.fr", "a@", "ab.fr" })
	void emailPartenaireInvalide(String v) throws Exception {
		refuseChampPartenaire("email", v);
	}

	@Test
	void emailPartenaireTropLong() throws Exception {
		refuseChampPartenaire("email", "a".repeat(251) + "@b.fr");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void nomEtRaisonSocialePartenaireVides(String v) throws Exception {
		refuseChampPartenaire("nom", v);
		org.mockito.Mockito.clearInvocations(contrat);
		refuseChampPartenaire("raisonSociale", v);
	}

	@Test
	void nomEtRaisonSocialePartenaireTropLongs() throws Exception {
		refuseChampPartenaire("nom", "a".repeat(256));
		refuseChampPartenaire("raisonSociale", "a".repeat(256));
	}

	@Test
	void sirenAvecEspacesAccepteEtTransmisSansEspaces() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("CREE", 5L));
		mvc.perform(pOk("siren", "732 829 320", "nom", "a".repeat(255)))
			.andExpect(redirectedUrl("/p/partenaires/liste?ok=partenaire"));
		ArgumentCaptor<CreationPartenaire> c = ArgumentCaptor.forClass(CreationPartenaire.class);
		verify(contrat).creerPartenaire(c.capture());
		assertEquals("732829320", c.getValue().siren());
	}

	@Test
	void plusieursChampsInvalidesSontTousSignales() throws Exception {
		var r = mvc.perform(pOk("siren", "1", "email", "x", "nom", "")).andExpect(status().isBadRequest()).andReturn();
		java.util.Map<?, ?> e = (java.util.Map<?, ?>) r.getModelAndView().getModel().get("erreursChamps");
		assertEquals(Set.of("siren", "email", "nom"), e.keySet());
		assertEquals("SIREN invalide : 9 chiffres avec clé de contrôle correcte", e.get("siren"));
		verifyNoInteractions(contrat);
	}

	private void refuseChampClient(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
			String champ) throws Exception {
		var r = mvc.perform(req).andExpect(status().isBadRequest()).andExpect(view().name(V + "client-form")).andReturn();
		java.util.Map<?, ?> e = (java.util.Map<?, ?>) r.getModelAndView().getModel().get("erreursChamps");
		org.junit.jupiter.api.Assertions.assertNotNull(e);
		assertEquals(Set.of(champ), e.keySet());
		assertEquals(42L, r.getModelAndView().getModel().get("partenaireId"));
		verifyNoInteractions(contrat);
	}

	@ParameterizedTest
	@ValueSource(strings = { "123456789", "12", "", "   " })
	void sirenClientSocieteInvalideOuAbsent(String v) throws Exception {
		refuseChampClient(cOk(42, "siren", v), "siren");
	}

	@Test
	void sirenClientParticulierInvalideRefuseMaisVideAccepte() throws Exception {
		refuseChampClient(cOk(42, "particulier", "on", "siren", "123456789"), "siren");
		when(contrat.creerClientPourPartenaire(any())).thenReturn(new ResultatCreation("CREE", 9L));
		mvc.perform(cOk(42, "particulier", "on", "siren", "", "tva", "")).andExpect(status().is3xxRedirection());
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "a@@b.fr", "a b@c.fr", "@b.fr", "a@" })
	void emailClientInvalide(String v) throws Exception {
		refuseChampClient(cOk(42, "email", v), "email");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void nomClientVide(String v) throws Exception {
		refuseChampClient(cOk(42, "nom", v), "nom");
	}

	@Test
	void nomClientTropLong() throws Exception {
		refuseChampClient(cOk(42, "nom", "a".repeat(256)), "nom");
	}

	@Test
	void messageNoyauVideOuTropLongRepliSurLeTexteGenerique() throws Exception {
		for (IllegalArgumentException e : List.of(new IllegalArgumentException((String) null),
				new IllegalArgumentException("  "), new IllegalArgumentException("x".repeat(201)))) {
			org.mockito.Mockito.doThrow(e).when(contrat).creerPartenaire(any());
			var r = mvc.perform(pOk()).andExpect(status().isBadRequest()).andReturn();
			assertEquals("Données invalides : vérifiez les champs saisis.", r.getModelAndView().getModel().get("erreur"));
		}
	}

	@Test
	void tvaIncoherenteAvecLeSirenRefusee() throws Exception {
		refuseChampPartenaire("tva", "FR00732829320");
		var r = mvc.perform(pOk("tva", "DE123456789")).andExpect(status().isBadRequest()).andReturn();
		assertEquals("Le numéro de TVA ne correspond pas au SIREN.",
				((java.util.Map<?, ?>) r.getModelAndView().getModel().get("erreursChamps")).get("tva"));
		verifyNoInteractions(contrat);
	}

	@Test
	void tvaNormaliseeEtVideDonneNull() throws Exception {
		when(contrat.creerPartenaire(any())).thenReturn(new ResultatPartenaire("CREE", 5L));
		mvc.perform(pOk("tva", "fr 44.732-829-320")).andExpect(status().is3xxRedirection());
		mvc.perform(pOk("tva", " ")).andExpect(status().is3xxRedirection());
		ArgumentCaptor<CreationPartenaire> c = ArgumentCaptor.forClass(CreationPartenaire.class);
		verify(contrat, org.mockito.Mockito.times(2)).creerPartenaire(c.capture());
		assertEquals("FR44732829320", c.getAllValues().get(0).tva());
		assertEquals(null, c.getAllValues().get(1).tva());
	}

	@Test
	void tvaClientSocieteIncoherenteRefusee() throws Exception {
		refuseChampClient(cOk(42, "tva", "FR00732829320"), "tva");
	}

	// liste des clients d'un partenaire

	@Test
	void clientsDuPartenaireAfficheLaListe() throws Exception {
		var l = List.of(new GestionPartenairesContrat.ClientResume(3L, "732829320", "Acme", java.time.Instant.EPOCH, "PARTENAIRE"));
		when(contrat.listerClientsDuPartenaire(42L)).thenReturn(l);
		mvc.perform(get("/42/clients"))
			.andExpect(status().isOk())
			.andExpect(view().name(V + "clients"))
			.andExpect(model().attribute("clients", l))
			.andExpect(model().attribute("partenaireId", 42L))
			.andExpect(model().attribute("moduleActif", "partenaires"))
			.andExpect(model().attributeExists("titre"));
	}

	@Test
	void clientsDuPartenaireInconnuDonne404PageErreur() throws Exception {
		when(contrat.listerClientsDuPartenaire(42L)).thenThrow(new IllegalArgumentException("detail-interne"));
		var r = mvc.perform(get("/42/clients")).andExpect(status().isNotFound()).andExpect(view().name(V + "erreur")).andReturn();
		assertFalse(r.getModelAndView().getModel().toString().contains("detail-interne"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "1;2", "..%2Fx", "1234567890123456", "-1", "1.5" })
	void clientsIdentifiantInvalide404SansContrat(String id) throws Exception {
		mvc.perform(get("/" + id + "/clients")).andExpect(status().isNotFound());
		verifyNoInteractions(contrat);
	}

}
