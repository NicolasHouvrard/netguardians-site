package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.logicielpourtous.site.sdk.ContratIndisponibleException;

class ContratRegistreTest {

	public interface Salut {

		String dire(String nom);

	}

	private final ContratRegistre registre = new ContratRegistre();

	private static PluginGeneration generation(String nom) {
		return new PluginGeneration(nom, "1", Path.of(nom + ".jar"));
	}

	@Test
	void proxy_stable_apres_remplacement() {
		Salut proxy = registre.obtenir(Salut.class);
		registre.publier(Salut.class, (Salut) n -> "bonjour " + n, generation("a"));
		assertThat(proxy.dire("x")).isEqualTo("bonjour x");
		registre.publier(Salut.class, (Salut) n -> "salut " + n, generation("b"));
		assertThat(registre.obtenir(Salut.class)).isSameAs(proxy);
		assertThat(proxy.dire("x")).isEqualTo("salut x");
	}

	@Test
	void aucun_fournisseur_leve_contrat_indisponible() {
		Salut proxy = registre.obtenir(Salut.class);
		assertThatThrownBy(() -> proxy.dire("x")).isInstanceOf(ContratIndisponibleException.class);
		assertThat(registre.types()).contains(Salut.class);
	}

	@Test
	void retirer_rend_le_contrat_indisponible() {
		PluginGeneration gen = generation("a");
		Salut proxy = registre.obtenir(Salut.class);
		registre.publier(Salut.class, (Salut) n -> "bonjour", gen);
		registre.retirer(gen);
		assertThatThrownBy(() -> proxy.dire("x")).isInstanceOf(ContratIndisponibleException.class);
	}

	@Test
	void retirer_une_ancienne_generation_ne_touche_pas_le_fournisseur_courant() {
		PluginGeneration ancienne = generation("a");
		PluginGeneration courante = generation("b");
		registre.publier(Salut.class, (Salut) n -> "ancien", ancienne);
		registre.publier(Salut.class, (Salut) n -> "courant", courante);
		registre.retirer(ancienne);
		assertThat(registre.obtenir(Salut.class).dire("x")).isEqualTo("courant");
	}

	@Test
	void compteur_en_vol_revient_a_zero_apres_appel_qui_leve() {
		PluginGeneration gen = generation("a");
		registre.publier(Salut.class, (Salut) n -> {
			assertThat(gen.enVol.get()).isEqualTo(1);
			throw new IllegalStateException("boom");
		}, gen);
		Salut proxy = registre.obtenir(Salut.class);
		assertThatThrownBy(() -> proxy.dire("x")).isInstanceOf(IllegalStateException.class).hasMessage("boom");
		assertThat(gen.enVol.get()).isZero();
	}

}
