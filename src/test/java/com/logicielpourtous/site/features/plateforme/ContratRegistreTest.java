package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
	void refuse_un_type_defini_par_un_plugin(@TempDir Path dossier) throws Exception {
		Path jar = JarDePluginDeTest.creer(dossier, "a", "1", Map.of(), Map.of(),
				Map.of("x.Cible", "package x; public interface Cible { }"));
		try (PluginClassLoader loader = new PluginClassLoader("a@1#1", jar.toUri().toURL(),
				getClass().getClassLoader())) {
			Class<?> type = loader.loadClass("x.Cible");
			assertThatThrownBy(() -> registre.declarer(type)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("x.Cible");
			assertThatThrownBy(() -> registre.obtenir(type)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> registre.publier(type, new Object(), generation("a")))
				.isInstanceOf(IllegalArgumentException.class);
			assertThat(registre.types()).isEmpty();
		}
	}

	@Test
	void aucun_appel_apres_le_drainage_d_une_generation() throws Exception {
		int generations = 300;
		int lecteurs = 8;
		AtomicBoolean arret = new AtomicBoolean();
		AtomicInteger violations = new AtomicInteger();
		List<PluginGeneration> toutes = new CopyOnWriteArrayList<>();
		Salut proxy = registre.obtenir(Salut.class);

		AtomicBoolean fermee0 = new AtomicBoolean();
		PluginGeneration gen0 = generation("g0");
		toutes.add(gen0);
		registre.publier(Salut.class, fournisseurSurveille(fermee0, violations), gen0);

		ExecutorService pool = Executors.newFixedThreadPool(lecteurs);
		List<Future<?>> futurs = new ArrayList<>();
		for (int i = 0; i < lecteurs; i++) {
			futurs.add(pool.submit(() -> {
				while (!arret.get()) {
					proxy.dire("x");
				}
			}));
		}
		try {
			PluginGeneration courante = gen0;
			AtomicBoolean fermeeCourante = fermee0;
			for (int i = 1; i <= generations; i++) {
				PluginGeneration suivante = generation("g" + i);
				toutes.add(suivante);
				AtomicBoolean fermeeSuivante = new AtomicBoolean();
				registre.publier(Salut.class, fournisseurSurveille(fermeeSuivante, violations), suivante);
				registre.retirer(courante);
				while (courante.enVol.get() != 0) {
					Thread.onSpinWait();
				}
				fermeeCourante.set(true); // drainage terminé : plus aucun appel ne doit arriver ici
				courante = suivante;
				fermeeCourante = fermeeSuivante;
			}
		}
		finally {
			arret.set(true);
			for (Future<?> f : futurs) {
				f.get(30, TimeUnit.SECONDS); // propage aussi toute exception d'un lecteur
			}
			pool.shutdown();
		}
		assertThat(violations).hasValue(0);
		assertThat(toutes).allSatisfy(g -> assertThat(g.enVol.get()).isZero());
	}

	private static Salut fournisseurSurveille(AtomicBoolean fermee, AtomicInteger violations) {
		return n -> {
			if (fermee.get()) {
				violations.incrementAndGet();
			}
			return "ok";
		};
	}

	/** Génération dont le contexte (démarré) contient un bean {@link Salut} répondant {@code prefixe + nom}. */
	private static PluginGeneration generationActive(String nom, String prefixe) {
		PluginGeneration gen = generation(nom);
		org.springframework.context.support.GenericApplicationContext ctx = new org.springframework.context.support.GenericApplicationContext();
		ctx.registerBean(Salut.class, () -> n -> prefixe + n);
		ctx.refresh();
		gen.contexte = ctx;
		return gen;
	}

	@Test
	void fournisseur_active_avant_toute_demande_du_type_est_resolu() {
		PluginGeneration gen = generationActive("a", "bonjour ");
		registre.activer(gen);

		Salut proxy = registre.obtenir(Salut.class);

		assertThat(proxy.dire("x")).isEqualTo("bonjour x");
		assertThat(gen.enVol.get()).isZero();
	}

	@Test
	void fournisseur_active_apres_la_demande_du_type_est_publie() {
		Salut proxy = registre.obtenir(Salut.class);
		registre.activer(generationActive("a", "bonjour "));

		assertThat(proxy.dire("x")).isEqualTo("bonjour x");
	}

	@Test
	void generation_activee_puis_retiree_n_est_plus_resolue() {
		PluginGeneration gen = generationActive("a", "bonjour ");
		registre.activer(gen);
		registre.retirer(gen);

		Salut proxy = registre.obtenir(Salut.class);

		assertThatThrownBy(() -> proxy.dire("x")).isInstanceOf(ContratIndisponibleException.class);
	}

	@Test
	void remplacement_d_une_generation_activee_avant_la_demande() {
		PluginGeneration ancienne = generationActive("a", "ancien ");
		registre.activer(ancienne);
		Salut proxy = registre.obtenir(Salut.class);
		assertThat(proxy.dire("x")).isEqualTo("ancien x");

		registre.retirer(ancienne);
		registre.activer(generationActive("b", "nouveau "));

		assertThat(proxy.dire("x")).isEqualTo("nouveau x");
	}

	/** Génération consommatrice : elle expose le proxy du registre comme bean du type contrat (cas fréquent). */
	private PluginGeneration generationConsommatrice(String nom) {
		PluginGeneration gen = generation(nom);
		org.springframework.context.support.GenericApplicationContext ctx = new org.springframework.context.support.GenericApplicationContext();
		ctx.registerBean(Salut.class, () -> registre.obtenir(Salut.class));
		ctx.refresh();
		gen.contexte = ctx;
		return gen;
	}

	@Test
	void le_proxy_du_registre_expose_par_un_consommateur_n_est_jamais_un_fournisseur() {
		PluginGeneration consommateur = generationConsommatrice("conso");
		registre.activer(consommateur);
		Salut proxy = registre.obtenir(Salut.class);

		assertThatThrownBy(() -> proxy.dire("x")).isInstanceOf(ContratIndisponibleException.class);

		registre.activer(generationActive("fourni", "bonjour "));

		assertThat(proxy.dire("x")).isEqualTo("bonjour x");
		assertThat(((Salut) consommateur.contexte.getBean(Salut.class)).dire("y")).isEqualTo("bonjour y");
	}

	@Test
	void consommateur_active_apres_le_fournisseur_ne_le_remplace_pas() {
		Salut proxy = registre.obtenir(Salut.class);
		registre.activer(generationActive("fourni", "bonjour "));
		registre.activer(generationConsommatrice("conso"));

		assertThat(proxy.dire("x")).isEqualTo("bonjour x");
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
