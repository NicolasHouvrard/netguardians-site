package com.logicielpourtous.site.plugins.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ClientResume;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.PorteeAcces;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

class ClientsPagesControllerTest {

	private static final String V = "plugins/clients/";
	private static final long USER = 11L;
	private static final long MOI = 5L;

	private IdentiteContrat identite;
	private AnnuaireClientsContrat annuaire;
	private MockMvc mvc;

	@BeforeEach
	void preparer() {
		identite = mock(IdentiteContrat.class);
		annuaire = mock(AnnuaireClientsContrat.class);
		when(identite.courante()).thenReturn(new IdentiteCourante(USER, Set.of("PARTENAIRE"), MOI, 1L, null));
		when(annuaire.autorise(anyLong(), anyLong(), anyString())).thenReturn(true);
		// vue unique et vide : aucun rendu, le nom de vue reste verifiable
		View vide = (m, rq, rs) -> {
		};
		mvc = MockMvcBuilders.standaloneSetup(new ClientsPagesController(identite, annuaire))
			.setViewResolvers((nom, loc) -> nom.startsWith("redirect:") ? new RedirectView(nom.substring(9)) : vide)
			.build();
	}

	private void droitsLimites(String... droits) {
		Set<String> permis = Set.of(droits);
		when(annuaire.autorise(anyLong(), anyLong(), anyString())).thenAnswer(i -> permis.contains(i.getArgument(2)));
	}

	private static FicheClient fiche(long id, long partenaire) {
		return new FicheClient(new ClientResume(id, "732829320", "FR44732829320", "PARTENAIRE", "Acme"), partenaire,
				List.of("clients.lire"));
	}

	/** Creation valide par defaut ; les surcharges (nom, valeur) remplacent ou ajoutent un parametre (null = absent). */
	private static MockHttpServletRequestBuilder valide(String... surcharges) {
		Map<String, String> p = new LinkedHashMap<>(Map.of("siren", "732829320", "email", "c@d.fr", "nom", "Zoe"));
		for (int i = 0; i < surcharges.length; i += 2) {
			p.put(surcharges[i], surcharges[i + 1]);
		}
		MockHttpServletRequestBuilder r = post("/");
		p.forEach((k, v) -> {
			if (v != null) {
				r.param(k, v);
			}
		});
		return r;
	}

	// liste

	@Test
	void liste_affiche_seulement_les_clients_de_la_societe_de_l_appelant() throws Exception {
		List<ClientResume> l = List.of(new ClientResume(9L, "732829320", null, "PARTENAIRE", "Acme"));
		when(annuaire.clientsDuPartenaire(MOI)).thenReturn(l);
		mvc.perform(get("/liste"))
			.andExpect(status().isOk())
			.andExpect(view().name(V + "liste"))
			.andExpect(model().attribute("clients", l))
			.andExpect(model().attribute("moduleActif", "clients"))
			.andExpect(model().attributeExists("titre"));
		verify(annuaire).clientsDuPartenaire(MOI);
		verify(annuaire).autorise(USER, MOI, "clients.lire");
	}

	@Test
	void liste_sans_droit_lire_ou_sans_societe_ou_identite_indisponible_est_403_sans_lecture() throws Exception {
		droitsLimites("clients.creer");
		mvc.perform(get("/liste")).andExpect(status().isForbidden()).andExpect(view().name(V + "refuse"));

		when(identite.courante()).thenReturn(new IdentiteCourante(USER, Set.of(), null, 1L, null));
		mvc.perform(get("/liste")).andExpect(status().isForbidden());
		when(identite.courante()).thenReturn(new IdentiteCourante(null, Set.of(), MOI, 1L, null));
		mvc.perform(get("/liste")).andExpect(status().isForbidden());
		when(identite.courante()).thenReturn(null);
		mvc.perform(get("/liste")).andExpect(status().isForbidden());
		when(identite.courante()).thenThrow(new IllegalStateException("pas de session"));
		mvc.perform(get("/liste")).andExpect(status().isForbidden()).andExpect(view().name(V + "refuse"));

		verify(annuaire, never()).clientsDuPartenaire(any());
	}

	// fiche

	@Test
	void fiche_de_mon_client() throws Exception {
		FicheClient f = fiche(9L, MOI);
		when(annuaire.fiche(9L)).thenReturn(Optional.of(f));
		mvc.perform(get("/9/fiche"))
			.andExpect(status().isOk())
			.andExpect(view().name(V + "fiche"))
			.andExpect(model().attribute("fiche", f))
			.andExpect(model().attribute("moduleActif", "clients"));
		mvc.perform(get("/9/fiche").param("ok", "cree")).andExpect(model().attributeExists("succes"));
		mvc.perform(get("/9/fiche").param("ok", "<script>")).andExpect(model().attributeDoesNotExist("succes"));
	}

	@Test
	void fiche_d_un_autre_partenaire_et_fiche_inconnue_donnent_la_meme_reponse() throws Exception {
		when(annuaire.fiche(8L)).thenReturn(Optional.of(fiche(8L, 999L)));
		when(annuaire.fiche(7L)).thenReturn(Optional.empty());
		MvcResult autre = mvc.perform(get("/8/fiche")).andExpect(status().isNotFound()).andReturn();
		MvcResult inconnu = mvc.perform(get("/7/fiche")).andExpect(status().isNotFound()).andReturn();
		assertEquals(V + "introuvable", autre.getModelAndView().getViewName());
		assertEquals(autre.getModelAndView().getViewName(), inconnu.getModelAndView().getViewName());
		assertEquals(autre.getModelAndView().getModel().keySet(), inconnu.getModelAndView().getModel().keySet());
		assertEquals(autre.getModelAndView().getModel().get("titre"),
				inconnu.getModelAndView().getModel().get("titre"));
		for (String id : new String[] { "abc", "1234567890123456", "-1", "1.5" }) {
			MvcResult r = mvc.perform(get("/" + id + "/fiche")).andExpect(status().isNotFound()).andReturn();
			assertEquals(V + "introuvable", r.getModelAndView().getViewName());
		}
		verify(annuaire, never()).fiche(1234567890123456L);
	}

	@Test
	void fiche_sans_droit_lire_est_403_sans_lecture() throws Exception {
		droitsLimites("clients.creer");
		mvc.perform(get("/9/fiche")).andExpect(status().isForbidden());
		verify(annuaire, never()).fiche(any());
	}

	// creation

	@Test
	void formulaire_de_creation_exige_le_droit_creer() throws Exception {
		droitsLimites("clients.lire");
		mvc.perform(get("/nouveau")).andExpect(status().isForbidden());
		droitsLimites("clients.lire", "clients.creer");
		mvc.perform(get("/nouveau"))
			.andExpect(status().isOk())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attributeExists("saisie", "erreursChamps"));
	}

	@Test
	void creation_valide_redirige_vers_la_fiche_du_nouveau_client() throws Exception {
		when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("CREE", 42L));
		mvc.perform(valide("partenaireSocieteId", "999", "tva", "FR44732829320"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/p/clients/42/fiche?ok=cree"));
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(annuaire).creerClient(c.capture());
		assertEquals(new CreationClient(MOI, false, "732829320", "FR44732829320", false, "c@d.fr", "Zoe"),
				c.getValue());
	}

	@Test
	void creation_sans_droit_creer_est_403_et_ne_cree_rien() throws Exception {
		droitsLimites("clients.lire");
		mvc.perform(valide()).andExpect(status().isForbidden()).andExpect(view().name(V + "refuse"));
		verify(annuaire, never()).creerClient(any());
	}

	@Test
	void creation_particulier_avec_siren_et_tva_vides_est_acceptee() throws Exception {
		when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("CREE", 43L));
		mvc.perform(valide("particulier", "on", "siren", "", "tva", ""))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/p/clients/43/fiche?ok=cree"));
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(annuaire).creerClient(c.capture());
		assertTrue(c.getValue().particulier());
		assertFalse(c.getValue().etrangere());
		assertNull(c.getValue().siren());
		assertNull(c.getValue().tva());
	}

	@Test
	void creation_non_particulier_sans_siren_donne_une_erreur_sur_le_champ_siren() throws Exception {
		mvc.perform(valide("siren", ""))
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attribute("erreursChamps", Matchers.hasKey("siren")));
		verify(annuaire, never()).creerClient(any());
	}

	@Test
	void un_parametre_etrangere_envoye_est_ignore_le_contrat_recoit_false() throws Exception {
		when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("CREE", 44L));
		mvc.perform(valide("etrangere", "on")).andExpect(status().is3xxRedirection());
		ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
		verify(annuaire).creerClient(c.capture());
		assertFalse(c.getValue().etrangere());
	}

	@Test
	void creation_siren_invalide_tva_incoherente_email_invalide_nom_vide_donnent_une_erreur_par_champ()
			throws Exception {
		mvc.perform(valide("siren", "123456789"))
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attribute("erreursChamps", Matchers.hasKey("siren")))
			.andExpect(model().attribute("saisie", Matchers.hasEntry("siren", "123456789")));
		mvc.perform(valide("tva", "FR00732829320"))
			.andExpect(status().isBadRequest())
			.andExpect(model().attribute("erreursChamps", Matchers.hasKey("tva")));
		mvc.perform(valide("email", "pas un mail"))
			.andExpect(status().isBadRequest())
			.andExpect(model().attribute("erreursChamps", Matchers.hasKey("email")))
			.andExpect(model().attribute("saisie", Matchers.hasEntry("email", "pas un mail")));
		mvc.perform(valide("nom", "  "))
			.andExpect(status().isBadRequest())
			.andExpect(model().attribute("erreursChamps", Matchers.hasKey("nom")));
		mvc.perform(valide("siren", "", "email", "x", "nom", ""))
			.andExpect(status().isBadRequest())
			.andExpect(model().attribute("erreursChamps", Matchers.allOf(Matchers.hasKey("siren"),
					Matchers.hasKey("email"), Matchers.hasKey("nom"))));
		verify(annuaire, never()).creerClient(any());
	}

	@Test
	void deja_connue_et_email_deja_utilise_donnent_409_avec_message_neutre() throws Exception {
		when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("DEJA_CONNUE", 77L));
		mvc.perform(valide())
			.andExpect(status().isConflict())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attribute("erreur",
					"société déjà connue, une demande d'accès a été envoyée à son manager"))
			.andExpect(model().attribute("saisie", Matchers.hasEntry("nom", "Zoe")));
		when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("EMAIL_DEJA_UTILISE", null));
		mvc.perform(valide())
			.andExpect(status().isConflict())
			.andExpect(model().attribute("erreur", "cette adresse e-mail est déjà utilisée"));
	}

	@Test
	void illegal_argument_du_contrat_donne_400_avec_message_borne_a_200_caracteres_ou_generique() throws Exception {
		doThrow(new IllegalArgumentException("Le SIREN est invalide.")).when(annuaire).creerClient(any());
		mvc.perform(valide())
			.andExpect(status().isBadRequest())
			.andExpect(view().name(V + "client-form"))
			.andExpect(model().attribute("erreur", "Le SIREN est invalide."));
		doThrow(new IllegalArgumentException("x".repeat(201))).when(annuaire).creerClient(any());
		mvc.perform(valide())
			.andExpect(status().isBadRequest())
			.andExpect(model().attribute("erreur", "Données invalides : vérifiez les champs saisis."));
		doThrow(new IllegalArgumentException()).when(annuaire).creerClient(any());
		mvc.perform(valide())
			.andExpect(status().isBadRequest())
			.andExpect(model().attribute("erreur", "Données invalides : vérifiez les champs saisis."));
	}

	// entrer

	@Test
	void entrer_accorde_affiche_le_perimetre() throws Exception {
		when(annuaire.entrerEspaceClient(USER, 9L))
			.thenReturn(new PorteeAcces(9L, Set.of("devis.lire", "clients.lire")));
		mvc.perform(post("/9/entrer").param("userId", "1").param("societeId", "2"))
			.andExpect(status().isOk())
			.andExpect(view().name(V + "entree"))
			.andExpect(model().attribute("societeId", 9L))
			.andExpect(model().attribute("droits", List.of("clients.lire", "devis.lire")));
		verify(annuaire).entrerEspaceClient(USER, 9L);
	}

	@Test
	void entrer_refuse_donne_le_meme_404_qu_un_client_inconnu() throws Exception {
		when(annuaire.entrerEspaceClient(anyLong(), anyLong())).thenThrow(new SecurityException("autre partenaire"));
		MvcResult refuse = mvc.perform(post("/9/entrer")).andExpect(status().isNotFound()).andReturn();
		assertEquals(V + "introuvable", refuse.getModelAndView().getViewName());
		when(annuaire.fiche(7L)).thenReturn(Optional.empty());
		MvcResult inconnu = mvc.perform(get("/7/fiche")).andReturn();
		assertEquals(inconnu.getResponse().getStatus(), refuse.getResponse().getStatus());
		assertEquals(inconnu.getModelAndView().getViewName(), refuse.getModelAndView().getViewName());
		assertEquals(inconnu.getModelAndView().getModel().keySet(), refuse.getModelAndView().getModel().keySet());
		mvc.perform(post("/abc/entrer")).andExpect(status().isNotFound());
	}

	@Test
	void entrer_sans_droit_entrer_est_403_sans_appel_du_contrat() throws Exception {
		droitsLimites("clients.lire", "clients.creer");
		mvc.perform(post("/9/entrer")).andExpect(status().isForbidden()).andExpect(view().name(V + "refuse"));
		verify(annuaire, never()).entrerEspaceClient(any(), any());
	}

	// echec ferme

	@Test
	void erreur_inattendue_donne_500_a_page_fixe_sans_message() throws Exception {
		when(annuaire.clientsDuPartenaire(any())).thenThrow(new RuntimeException("secret interne"));
		mvc.perform(get("/liste"))
			.andExpect(status().isInternalServerError())
			.andExpect(view().name(V + "erreur"))
			.andExpect(model().attributeDoesNotExist("erreur", "message"));
		when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("INCONNUE", null));
		mvc.perform(valide()).andExpect(status().isInternalServerError()).andExpect(view().name(V + "erreur"));
	}

}
