package com.logicielpourtous.site.features.plateforme;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Surveillance du Metaspace : chaque rechargement de plugin laisse des classes dans le Metaspace (fuite de
 * chargeur incluse), que seul un redémarrage libère. Au-dessus de {@code app.plateforme.metaspace-seuil-mo}, un repère
 * {@code <repertoire-donnees>/redemarrage-recommande} (JSON {@code metaspaceMo}, {@code seuilMo}, {@code depuis}) est
 * écrit UNE fois (atomiquement : fichier temporaire puis déplacement) et un avertissement journalisé ; sous le seuil,
 * le repère est supprimé. Rien ici ne redémarre quoi que ce soit : le repère n'est qu'un signal pour l'exploitation.
 * <p>
 * {@link #controler()} ne lève jamais : une erreur d'écriture ou de mesure est journalisée et réessayée au contrôle
 * suivant. Le JSON est produit par Jackson, jamais par concaténation.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class SurveillanceMetaspace {

	private static final Logger log = LoggerFactory.getLogger(SurveillanceMetaspace.class);

	/** Nom du repère dans le répertoire de données. */
	static final String NOM_REPERE = "redemarrage-recommande";

	private static final long MO = 1024L * 1024L;

	private static final ObjectMapper JSON = new ObjectMapper();

	private final PlateformeProperties proprietes;

	/** Utilisation du Metaspace en octets. */
	private final Supplier<Long> mesureOctets;

	private volatile long dernierMo;

	private volatile boolean recommande;

	/** Défaut : pool {@code Metaspace} de la JVM. */
	@Autowired
	public SurveillanceMetaspace(PlateformeProperties proprietes) {
		this(proprietes, SurveillanceMetaspace::metaspaceJvm);
	}

	/** Mesure injectable (tests). */
	SurveillanceMetaspace(PlateformeProperties proprietes, Supplier<Long> mesureOctets) {
		this.proprietes = proprietes;
		this.mesureOctets = mesureOctets;
	}

	private static long metaspaceJvm() {
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if ("Metaspace".equals(pool.getName())) {
				return pool.getUsage().getUsed();
			}
		}
		return 0L;
	}

	/** Dernière mesure, en Mo (0 avant le premier contrôle). */
	public long metaspaceMo() {
		return dernierMo;
	}

	public long seuilMo() {
		return proprietes.getMetaspaceSeuilMo();
	}

	/** Mesure rapportée au Maître avec l'état des plugins (spec §7). */
	public record Mesure(long metaspaceMo, long seuilMo, boolean redemarrageRecommande) {
	}

	/** Instantané {@code (metaspaceMo, seuilMo, redemarrageRecommande)} de la dernière mesure. */
	public Mesure mesure() {
		return new Mesure(metaspaceMo(), seuilMo(), redemarrageRecommande());
	}

	/** {@code true} si la dernière mesure dépasse le seuil. */
	public boolean redemarrageRecommande() {
		return recommande;
	}

	@Scheduled(fixedDelayString = "${app.plateforme.intervalle-surveillance-ms:60000}",
			initialDelayString = "${app.plateforme.intervalle-surveillance-ms:60000}")
	public synchronized void controler() {
		try {
			Long octets = mesureOctets.get();
			long mo = octets == null ? 0 : octets / MO;
			dernierMo = mo;
			long seuil = proprietes.getMetaspaceSeuilMo();
			recommande = mo > seuil;
			Path repere = Path.of(proprietes.getRepertoireDonnees()).toAbsolutePath().resolve(NOM_REPERE);
			if (recommande) {
				ecrireRepere(repere, mo, seuil);
			}
			else {
				Files.deleteIfExists(repere);
			}
		}
		catch (VirtualMachineError e) {
			throw e;
		}
		catch (Throwable e) {
			log.warn("Surveillance du Metaspace : contrôle impossible ({}: {})", e.getClass().getSimpleName(),
					DepotPlugins.net(e.getMessage()));
		}
	}

	/** Écrit le repère s'il est absent (donc une seule fois tant que la mesure reste au-dessus du seuil). */
	private void ecrireRepere(Path repere, long mo, long seuil) throws Exception {
		if (Files.exists(repere)) {
			return;
		}
		Path dossier = repere.getParent();
		Files.createDirectories(dossier);
		Map<String, Object> contenu = new LinkedHashMap<>();
		contenu.put("metaspaceMo", mo);
		contenu.put("seuilMo", seuil);
		contenu.put("depuis", Instant.now().toString());
		Path temporaire = Files.createTempFile(dossier, NOM_REPERE, ".tmp");
		try {
			Files.write(temporaire, JSON.writeValueAsBytes(contenu));
			Files.move(temporaire, repere, StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(temporaire);
		}
		log.warn("Metaspace à {} Mo, au-dessus du seuil de {} Mo : redémarrage recommandé (repère {} écrit)", mo, seuil,
				NOM_REPERE);
	}

}
