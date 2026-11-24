package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
		return new AppsAdvice(navigation).apps();
	}

	@Test
	void l_admin_recoit_la_tuile_apparence_apres_les_tuiles_des_plugins() {
		NavigationService navigation = mock(NavigationService.class);
		when(navigation.tuilesAutorisees(null)).thenReturn(
				List.of(new Tuile("clients", "Clients", "people", "/liste", ZoneNavigation.PARTENAIRE, "1.0.0")));
		connecte("ROLE_ADMIN");

		List<TuileApp> tuiles = apps(navigation).tuiles();

		assertThat(tuiles).extracting(TuileApp::libelle).containsExactly("Clients", "Apparence");
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

		assertThat(apps(navigation).tuiles()).extracting(TuileApp::libelle).containsExactly("Apparence");
	}

}
