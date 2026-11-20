package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.logicielpourtous.site.features.plateforme.NavigationService.BlocAutorise;
import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.sdk.ZoneNavigation;

/** Validation des entrées et forme de la réponse (le 401 sans jeton est dans SecurityPlateformeTest). */
class NavigationControllerTest {

	private NavigationService service;

	private MockMvc mvc;

	@BeforeEach
	void preparer() {
		service = mock(NavigationService.class);
		mvc = MockMvcBuilders.standaloneSetup(new NavigationController(service)).build();
	}

	@Test
	void tuiles_forme_de_la_reponse() throws Exception {
		when(service.tuiles(ZoneNavigation.PARTENAIRE, 5L))
				.thenReturn(List.of(new Tuile("alpha", "A", "ico", "/a", ZoneNavigation.PARTENAIRE, "1.2.3")));

		mvc.perform(get("/api/navigation").param("zone", "PARTENAIRE").param("societeId", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].plugin").value("alpha"))
				.andExpect(jsonPath("$[0].libelle").value("A"))
				.andExpect(jsonPath("$[0].icone").value("ico"))
				.andExpect(jsonPath("$[0].chemin").value("/a"))
				.andExpect(jsonPath("$[0].zone").value("PARTENAIRE"))
				.andExpect(jsonPath("$[0].version").value("1.2.3"));
	}

	@Test
	void zone_inconnue_400_message_francais_sans_travail() throws Exception {
		var res = mvc.perform(get("/api/navigation").param("zone", "NIMPORTE")).andExpect(status().isBadRequest())
				.andReturn();
		assertThat(res.getResolvedException()).hasMessageContaining("Zone inconnue");
		verifyNoInteractions(service);
	}

	@Test
	void zone_absente_laisse_le_serveur_decider_et_chaque_tuile_porte_sa_zone() throws Exception {
		when(service.tuilesAutorisees(null))
				.thenReturn(List.of(new Tuile("alpha", "A", "ico", "/a", ZoneNavigation.PARTENAIRE, "1.0.0")));
		when(service.tuilesAutorisees(5L))
				.thenReturn(List.of(new Tuile("beta", "B", "ico", "/b", ZoneNavigation.CLIENT, "2.0.0")));

		mvc.perform(get("/api/navigation")).andExpect(status().isOk()).andExpect(jsonPath("$[0].plugin").value("alpha"))
				.andExpect(jsonPath("$[0].zone").value("PARTENAIRE"));
		mvc.perform(get("/api/navigation").param("societeId", "5")).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].zone").value("CLIENT"));
	}

	@Test
	void societe_id_non_numerique_400() throws Exception {
		mvc.perform(get("/api/navigation").param("zone", "CLIENT").param("societeId", "abc"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void blocs_forme_de_la_reponse() throws Exception {
		when(service.blocs(5L)).thenReturn(List.of(new BlocAutorise("alpha", "notes", "Notes", "/n", "1.2.3")));

		mvc.perform(get("/api/navigation/blocs").param("societeId", "5")).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].plugin").value("alpha"))
				.andExpect(jsonPath("$[0].id").value("notes"))
				.andExpect(jsonPath("$[0].chemin").value("/n"))
				.andExpect(jsonPath("$[0].version").value("1.2.3"));
	}

}
