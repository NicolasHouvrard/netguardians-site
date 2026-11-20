package com.logicielpourtous.site.plugins.clients;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ClientResume;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Le partenaire ne voit que ses clients : droit sur SA société, 404 identique pour tout ce qui n'est pas à lui. */
class ClientsControllerTest {

	private static final long USER = 7L;
	private static final long MOI = 100L;
	private static final long AUTRE_PARTENAIRE = 200L;
	private static final long MON_CLIENT = 11L;
	private static final long CLIENT_D_UN_AUTRE = 22L;
	private static final long SANS_LIEN = 33L;
	private static final long INCONNU = 999L;

	private IdentiteContrat identite;
	private AnnuaireClientsContrat annuaire;
	private MockMvc mvc;

	@BeforeEach
	void preparer() {
		identite = mock(IdentiteContrat.class);
		annuaire = mock(AnnuaireClientsContrat.class);
		mvc = MockMvcBuilders.standaloneSetup(new ClientsController(identite, annuaire)).build();
		quand(MOI);
		when(annuaire.autorise(USER, MOI, "clients.lire")).thenReturn(true);
		when(annuaire.clientsDuPartenaire(MOI)).thenReturn(List.of(resume(MON_CLIENT)));
		when(annuaire.fiche(MON_CLIENT)).thenReturn(Optional.of(fiche(MON_CLIENT, MOI)));
		when(annuaire.fiche(CLIENT_D_UN_AUTRE)).thenReturn(Optional.of(fiche(CLIENT_D_UN_AUTRE, AUTRE_PARTENAIRE)));
		when(annuaire.fiche(SANS_LIEN)).thenReturn(Optional.empty());
		when(annuaire.fiche(INCONNU)).thenReturn(Optional.empty());
	}

	private void quand(Long societeId) {
		when(identite.courante()).thenReturn(new IdentiteCourante(USER, Set.of("ROLE_PARTNER"), societeId, 1L, "PARTNER"));
	}

	private static ClientResume resume(long id) {
		return new ClientResume(id, "SIREN" + id, "TVA" + id, "MANUEL", "Nom " + id);
	}

	private static FicheClient fiche(long id, long partenaire) {
		return new FicheClient(resume(id), partenaire, List.of("CLIENT"));
	}

	@Test
	void listeContientSeulementCeQueLeContratRenvoiePourLaSocieteDeLAppelant() throws Exception {
		mvc.perform(get("/liste").param("societeId", String.valueOf(AUTRE_PARTENAIRE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].societeId").value(MON_CLIENT))
				.andExpect(jsonPath("$[0].nom").value("Nom " + MON_CLIENT));
		verify(annuaire).clientsDuPartenaire(MOI);
		verify(annuaire, never()).clientsDuPartenaire(AUTRE_PARTENAIRE);
		verify(annuaire).autorise(USER, MOI, "clients.lire");
	}

	@Test
	void ficheDeMonClientRenvoyee() throws Exception {
		mvc.perform(get("/" + MON_CLIENT + "/fiche"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resume.societeId").value(MON_CLIENT))
				.andExpect(jsonPath("$.partenaireSocieteId").value(MOI))
				.andExpect(jsonPath("$.resume.nom").value("Nom " + MON_CLIENT));
	}

	@Test
	void ficheDUnClientDUnAutrePartenaireEst404() throws Exception {
		mvc.perform(get("/" + CLIENT_D_UN_AUTRE + "/fiche")).andExpect(status().isNotFound());
	}

	@Test
	void ficheNonClienteOuInconnueEst404IdentiqueAuClientDUnAutre() throws Exception {
		String autre = mvc.perform(get("/" + CLIENT_D_UN_AUTRE + "/fiche")).andReturn().getResponse()
				.getContentAsString();
		mvc.perform(get("/" + SANS_LIEN + "/fiche")).andExpect(status().isNotFound()).andExpect(content().string(autre));
		mvc.perform(get("/" + INCONNU + "/fiche")).andExpect(status().isNotFound()).andExpect(content().string(autre));
	}

	@Test
	void droitRefuseEst403SansAucuneDonnee() throws Exception {
		when(annuaire.autorise(USER, MOI, "clients.lire")).thenReturn(false);
		mvc.perform(get("/liste")).andExpect(status().isForbidden()).andExpect(content().string(""));
		mvc.perform(get("/" + MON_CLIENT + "/fiche")).andExpect(status().isForbidden()).andExpect(content().string(""));
		verify(annuaire, never()).clientsDuPartenaire(anyLong());
		verify(annuaire, never()).fiche(anyLong());
	}

	@Test
	void societeNulleEst403EtLeContratNEstJamaisInterroge() throws Exception {
		quand(null);
		mvc.perform(get("/liste")).andExpect(status().isForbidden());
		mvc.perform(get("/" + MON_CLIENT + "/fiche")).andExpect(status().isForbidden());
		verifyNoInteractions(annuaire);
	}

	@Test
	void identiteIndisponibleEst403() throws Exception {
		when(identite.courante()).thenThrow(new IllegalStateException("non authentifié"));
		mvc.perform(get("/liste")).andExpect(status().isForbidden());
		verify(annuaire, never()).autorise(any(), any(), anyString());
		verify(annuaire, never()).clientsDuPartenaire(any());
	}


	// ---- Bloc resume de la fiche ----

	@Test
	void blocResumeDeMonClientRenvoyeLeResume() throws Exception {
		mvc.perform(get("/" + MON_CLIENT + "/blocs/resume"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.societeId").value(MON_CLIENT))
				.andExpect(jsonPath("$.siren").value("SIREN" + MON_CLIENT))
				.andExpect(jsonPath("$.origine").value("MANUEL"));
		verify(annuaire).autorise(USER, MOI, "clients.lire");
	}

	@Test
	void blocResumeD_unAutrePartenaireOuInconnuEst404IdentiqueALaFiche() throws Exception {
		String attendu = mvc.perform(get("/" + CLIENT_D_UN_AUTRE + "/fiche")).andReturn().getResponse()
				.getContentAsString();
		mvc.perform(get("/" + CLIENT_D_UN_AUTRE + "/blocs/resume")).andExpect(status().isNotFound())
				.andExpect(content().string(attendu));
		mvc.perform(get("/" + SANS_LIEN + "/blocs/resume")).andExpect(status().isNotFound())
				.andExpect(content().string(attendu));
		mvc.perform(get("/" + INCONNU + "/blocs/resume")).andExpect(status().isNotFound())
				.andExpect(content().string(attendu));
	}

	@Test
	void blocResumeSansDroitOuSansSocieteEst403SansLectureDeDonnees() throws Exception {
		when(annuaire.autorise(USER, MOI, "clients.lire")).thenReturn(false);
		mvc.perform(get("/" + MON_CLIENT + "/blocs/resume")).andExpect(status().isForbidden())
				.andExpect(content().string(""));
		verify(annuaire, never()).fiche(anyLong());
		quand(null);
		mvc.perform(get("/" + MON_CLIENT + "/blocs/resume")).andExpect(status().isForbidden());
		when(identite.courante()).thenThrow(new IllegalStateException("non authentifié"));
		mvc.perform(get("/" + MON_CLIENT + "/blocs/resume")).andExpect(status().isForbidden());
		verify(annuaire, never()).fiche(anyLong());
	}


    // ---- Creation ----

    private static final String CORPS = "{\"particulier\":false,\"siren\":\"123456782\",\"tva\":null,\"etrangere\":false,"
            + "\"email\":\"a@b.test\",\"nom\":\"Alice\"}";

    private org.springframework.test.web.servlet.ResultActions creer(String corps) throws Exception {
        return mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(corps));
    }

    @Test
    void creationRenvoie201AvecLIdentifiantEtUtiliseLaSocieteDeLAppelant() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.creer")).thenReturn(true);
        when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("CREE", 55L));

        // Un partenaireSocieteId glisse dans le corps doit etre ignore.
        creer(CORPS.replace("{", "{\"partenaireSocieteId\":" + AUTRE_PARTENAIRE + ","))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.societeId").value(55))
                .andExpect(jsonPath("$.issue").value("CREE"));

        ArgumentCaptor<CreationClient> c = ArgumentCaptor.forClass(CreationClient.class);
        verify(annuaire).creerClient(c.capture());
        org.junit.jupiter.api.Assertions.assertEquals(MOI, c.getValue().partenaireSocieteId());
        org.junit.jupiter.api.Assertions.assertEquals("123456782", c.getValue().siren());
        org.junit.jupiter.api.Assertions.assertEquals("a@b.test", c.getValue().email());
        verify(annuaire).autorise(USER, MOI, "clients.creer");
    }

    @Test
    void societeDejaConnueRenvoie409NeutreSansRevelerLAutreSociete() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.creer")).thenReturn(true);
        when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("DEJA_CONNUE", null));

        String corps = creer(CORPS).andExpect(status().isConflict())
                .andExpect(jsonPath("$.issue").value("DEJA_CONNUE"))
                .andExpect(jsonPath("$.message")
                        .value("société déjà connue, une demande d'accès a été envoyée à son manager"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(corps.contains("societeId"));
    }

    @Test
    void emailDejaUtiliseRenvoie409() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.creer")).thenReturn(true);
        when(annuaire.creerClient(any())).thenReturn(new ResultatCreation("EMAIL_DEJA_UTILISE", null));

        creer(CORPS).andExpect(status().isConflict())
                .andExpect(jsonPath("$.issue").value("EMAIL_DEJA_UTILISE"))
                .andExpect(jsonPath("$.message").value("cette adresse e-mail est déjà utilisée"));
    }

    @Test
    void creationSansLeDroitEst403SansEcriture() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.creer")).thenReturn(false);

        creer(CORPS).andExpect(status().isForbidden()).andExpect(content().string(""));
        verify(annuaire, never()).creerClient(any());
    }

    @Test
    void creationAvecLeSeulDroitLireEst403() throws Exception {
        // clients.lire accorde (setUp), clients.creer non (mock par defaut : faux).
        creer(CORPS).andExpect(status().isForbidden());
        verify(annuaire, never()).creerClient(any());
    }

    @Test
    void creationSocieteNulleOuIdentiteIndisponibleEst403() throws Exception {
        quand(null);
        creer(CORPS).andExpect(status().isForbidden());
        when(identite.courante()).thenThrow(new IllegalStateException("non authentifié"));
        creer(CORPS).andExpect(status().isForbidden());
        verify(annuaire, never()).creerClient(any());
    }

    @Test
    void creationDonneesInvalidesEst400EtErreurInattendueEchoueFerme() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.creer")).thenReturn(true);
        when(annuaire.creerClient(any())).thenThrow(new IllegalArgumentException("détail interne SIREN invalide"));
        String corps = creer(CORPS).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(corps.contains("interne"));

        doThrow(new RuntimeException("contrat indisponible")).when(annuaire).creerClient(any());
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class, () -> creer(CORPS));
    }

    @Test
    void listeAvecUserIdNulEtSocieteNonNulleEst403() throws Exception {
        when(identite.courante()).thenReturn(new IdentiteCourante(null, Set.of("ROLE_PARTNER"), MOI, 1L, "PARTNER"));
        mvc.perform(get("/liste")).andExpect(status().isForbidden());
        verify(annuaire, never()).autorise(any(), any(), anyString());
        verify(annuaire, never()).clientsDuPartenaire(any());
    }

    // ---- Entree dans l'espace d'un client ----

    @Test
    void entrerRenvoie200AvecLaPorteeEtNUtiliseQueLIdentiteDeLAppelant() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.entrer")).thenReturn(true);
        when(annuaire.entrerEspaceClient(USER, MON_CLIENT))
                .thenReturn(new AnnuaireClientsContrat.PorteeAcces(MON_CLIENT, Set.of("clients.lire")));

        mvc.perform(post("/" + MON_CLIENT + "/entrer").param("societeId", "1").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.societeId").value((int) MON_CLIENT))
                .andExpect(jsonPath("$.droitsEffectifs[0]").value("clients.lire"));
        verify(annuaire).entrerEspaceClient(USER, MON_CLIENT);
    }

    @Test
    void entrerSansLeDroitEst403SansAucuneEntree() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.entrer")).thenReturn(false);

        mvc.perform(post("/" + MON_CLIENT + "/entrer")).andExpect(status().isForbidden())
                .andExpect(content().string(""));
        verify(annuaire, never()).entrerEspaceClient(any(), any());
    }

    @Test
    void entrerAvecLeSeulDroitLireEst403() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.lire")).thenReturn(true);

        mvc.perform(post("/" + MON_CLIENT + "/entrer")).andExpect(status().isForbidden());
        verify(annuaire, never()).entrerEspaceClient(any(), any());
    }

    @Test
    void entrerSansSocieteOuSansIdentiteEst403() throws Exception {
        when(annuaire.autorise(anyLong(), anyLong(), anyString())).thenReturn(true);
        quand(null);
        mvc.perform(post("/" + MON_CLIENT + "/entrer")).andExpect(status().isForbidden());
        when(identite.courante()).thenThrow(new IllegalStateException("non authentifie"));
        mvc.perform(post("/" + MON_CLIENT + "/entrer")).andExpect(status().isForbidden());
        verify(annuaire, never()).entrerEspaceClient(any(), any());
    }

    @Test
    void entrerClientInconnuEtClientDUnAutreDonnentLeMeme404Vide() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.entrer")).thenReturn(true);
        when(annuaire.entrerEspaceClient(USER, INCONNU)).thenThrow(new SecurityException("Accès refusé."));
        when(annuaire.entrerEspaceClient(USER, CLIENT_D_UN_AUTRE)).thenThrow(new SecurityException("Accès refusé."));

        String a = mvc.perform(post("/" + INCONNU + "/entrer")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String b = mvc.perform(post("/" + CLIENT_D_UN_AUTRE + "/entrer")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals("", a);
        org.junit.jupiter.api.Assertions.assertEquals(a, b);
    }

    @Test
    void entrerErreurInattendueEchoueFerme() throws Exception {
        when(annuaire.autorise(USER, MOI, "clients.entrer")).thenReturn(true);
        when(annuaire.entrerEspaceClient(USER, MON_CLIENT)).thenThrow(new IllegalStateException("journal"));

        org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class,
                () -> mvc.perform(post("/" + MON_CLIENT + "/entrer")));
    }
}
