package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.logicielpourtous.site.features.plateforme.AppsAdvice.Apps;
import com.logicielpourtous.site.features.plateforme.AppsAdvice.TuileApp;
import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.sdk.ZoneNavigation;

/** Tuile « Apparence » du noyau : réservée à ROLE_ADMIN (comme l'écran {@code /admin/apparence}), jamais pour les autres. */
class AppsAdviceTest {

	@AfterEach
	void nettoyer() {
		SecurityContextHolder.clearContext();
	}

	private static void connecte(String... roles) {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", "x",
				java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
	}

	private static Apps apps(NavigationService navigation) {
		return apps(navigation, new MockEnvironment());
	}

	private static Apps apps(NavigationService navigation, MockEnvironment environnement) {
		return new AppsAdvice(navigation, environnement).apps();
	}

	@Test
	void l_admin_recoit_la_tuile_apparence_apres_les_tuiles_des_plugins() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenReturn(
				List.of(new Tuile("clients", "Clients", "people", "/liste", ZoneNavigation.PARTENAIRE, "1.0.0")));
		connecte("ROLE_ADMIN");

		List<TuileApp> tuiles = apps(navigation).tuiles();

		assertThat(tuiles).extracting(TuileApp::libelle).containsExactly("Clients", "Apparence", "Plugins", "Serveurs", "Comptes licence", "Fédération", "Conflits", "Doublons sociétés");
		TuileApp apparence = tuiles.get(1);
		assertThat(apparence.url()).isEqualTo("/admin/apparence");
		assertThat(apparence.icone()).isEqualTo("bi-palette");
		assertThat(apparence.couleur()).isEqualTo("primaire");
	}

	@Test
	void un_utilisateur_sans_role_admin_ne_recoit_pas_la_tuile_apparence() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of());
		connecte("ROLE_GESTIONNAIRE", "ROLE_USER");

		assertThat(apps(navigation).tuiles()).isEmpty();
	}

	@Test
	void sans_authentification_aucune_tuile_apparence() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of());

		assertThat(apps(navigation).tuiles()).isEmpty();
	}

	@Test
	void la_tuile_apparence_reste_pour_l_admin_si_la_lecture_des_plugins_echoue() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenThrow(new IllegalStateException("panne"));
		connecte("ROLE_ADMIN");

		assertThat(apps(navigation).tuiles()).extracting(TuileApp::libelle).containsExactly("Apparence", "Plugins", "Serveurs", "Comptes licence", "Fédération", "Conflits", "Doublons sociétés");
	}

	@Test
	void la_tuile_plugins_est_pour_l_admin_du_maitre_seulement() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of());
		connecte("ROLE_ADMIN");

		TuileApp plugins = apps(navigation).tuiles().get(1);
		assertThat(plugins.libelle()).isEqualTo("Plugins");
		assertThat(plugins.url()).isEqualTo("/admin/plugins");
		assertThat(plugins.icone()).isEqualTo("bi-puzzle");
		assertThat(plugins.couleur()).isEqualTo("primaire");

		// Rôle MASTER explicite, casse indifférente : toujours le Maître.
		assertThat(apps(navigation, new MockEnvironment().withProperty("app.role", "master")).tuiles())
			.extracting(TuileApp::libelle).contains("Plugins");
		// Serveur annexe : Apparence reste, pas Plugins.
		assertThat(apps(navigation, new MockEnvironment().withProperty("app.role", "PARTNER")).tuiles())
			.extracting(TuileApp::libelle).containsExactly("Apparence");
	}

	@Test
	void les_tuiles_de_gestion_des_serveurs_pointent_vers_les_pages_existantes_et_restent_au_maitre() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of());
		connecte("ROLE_ADMIN");

		List<TuileApp> tuiles = apps(navigation).tuiles();
		assertThat(tuiles).extracting(TuileApp::url).contains("/intra/licence/serveurs", "/intra/licence/comptes",
				"/intra/licence/federation", "/intra/licence/conflits", "/intra/societes/doublons");
		assertThat(tuiles).allSatisfy(t -> assertThat(t.icone()).startsWith("bi-"));

		// serveur annexe : ni ces tuiles ni Plugins (seul Apparence reste, déjà testé plus haut)
		assertThat(apps(navigation, new MockEnvironment().withProperty("app.role", "PARTNER")).tuiles())
			.extracting(TuileApp::url).doesNotContain("/intra/licence/serveurs", "/intra/societes/doublons");
	}

	@Test
	void sans_role_admin_pas_de_tuile_plugins_meme_sur_le_maitre() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenReturn(List.of());
		connecte("ROLE_GESTIONNAIRE");

		assertThat(apps(navigation).tuiles()).isEmpty();
	}

}
