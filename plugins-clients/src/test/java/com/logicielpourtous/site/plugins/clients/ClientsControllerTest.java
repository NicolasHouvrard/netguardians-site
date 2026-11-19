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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ClientResume;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
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
		return new ClientResume(id, "SIREN" + id, "TVA" + id, "MANUEL");
	}

	private static FicheClient fiche(long id, long partenaire) {
		return new FicheClient(resume(id), partenaire, List.of("CLIENT"));
	}

	@Test
	void listeContientSeulementCeQueLeContratRenvoiePourLaSocieteDeLAppelant() throws Exception {
		mvc.perform(get("/liste").param("societeId", String.valueOf(AUTRE_PARTENAIRE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].societeId").value(MON_CLIENT));
		verify(annuaire).clientsDuPartenaire(MOI);
		verify(annuaire, never()).clientsDuPartenaire(AUTRE_PARTENAIRE);
		verify(annuaire).autorise(USER, MOI, "clients.lire");
	}

	@Test
	void ficheDeMonClientRenvoyee() throws Exception {
		mvc.perform(get("/" + MON_CLIENT + "/fiche"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resume.societeId").value(MON_CLIENT))
				.andExpect(jsonPath("$.partenaireSocieteId").value(MOI));
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

}
