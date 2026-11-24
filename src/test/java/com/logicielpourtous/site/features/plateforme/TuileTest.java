package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.sdk.ZoneNavigation;

class TuileTest {

	private static Tuile avec(String chemin) {
		return new Tuile("clients", "L", "people", chemin, ZoneNavigation.PARTENAIRE, "1");
	}

	@Test
	void url_est_p_plugin_chemin_et_couleur_par_defaut_primaire() {
		assertThat(avec("/clients").url()).isEqualTo("/p/clients/clients");
		assertThat(avec("/clients").couleur()).isEqualTo("primaire");
	}

	@ParameterizedTest
	@ValueSource(strings = { "//evil.com", "/../x", "/a/..", "/a\\b", "/a b", "/a\tb", "/a\nb", "javascript:alert(1)", "",
			"/a\u0000b", "/a?x=1#y" })
	void chemin_dangereux_donne_pas_d_url(String chemin) {
		assertThat(avec(chemin).url()).isNull();
	}

}
