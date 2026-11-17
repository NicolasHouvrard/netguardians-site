package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.features.plateforme.ResultatInstallation.Statut;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Source du tireur sur le Maître : son propre dépôt ({@link DepotPlugins}), sans HTTP. Seules les paires complètes et
 * vérifiées du dépôt sont servies ({@link DepotPlugins#catalogue()}, {@link DepotPlugins#ouvrir}), ouvertes sans suivre
 * de lien et copiées avec la même borne que par HTTP ; le tireur refait ensuite les mêmes contrôles. Les rapports ne
 * vont qu'au journal (le Maître n'a pas de {@code LicenceServeur} pour lui-même dans {@code plateforme_etat_serveur}).
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@Conditional(SurServeurMaitre.class)
public class SourceDepotLocal implements SourcePlugins {

	private static final Logger log = LoggerFactory.getLogger(SourceDepotLocal.class);

	private final DepotPlugins depot;

	public SourceDepotLocal(DepotPlugins depot) {
		this.depot = depot;
	}

	@Override
	public List<EntreeCatalogue> catalogue() {
		return depot.catalogue();
	}

	@Override
	public Path telecharger(EntreeCatalogue e, Path dossier) throws IOException {
		String base = e.nom() + "-" + e.version() + ".jar";
		Path jar = dossier.resolve(base);
		Path sig = dossier.resolve(base + ".sig");
		boolean fini = false;
		try {
			copier(depot.ouvrir(e.nom(), e.version(), "jar"), jar, e.taille());
			copier(depot.ouvrir(e.nom(), e.version(), "sig"), sig, PluginManager.TAILLE_MAX_SIG);
			fini = true;
			return jar;
		}
		catch (IllegalArgumentException ex) {
			throw new IOException("entrée du catalogue invalide : " + ex.getMessage());
		}
		finally {
			if (!fini) {
				Files.deleteIfExists(jar);
				Files.deleteIfExists(sig);
			}
		}
	}

	private static void copier(Path source, Path cible, long max) throws IOException {
		try (InputStream in = Channels.newInputStream(Files.newByteChannel(source, StandardOpenOption.READ,
				LinkOption.NOFOLLOW_LINKS));
				OutputStream out = Files.newOutputStream(cible, StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE)) {
			byte[] tampon = new byte[64 * 1024];
			long total = 0;
			int lus;
			while ((lus = in.read(tampon)) != -1) {
				total += lus;
				if (total > max) {
					throw new TelechargementRefuse(source.getFileName() + " : plus de " + max + " octets");
				}
				out.write(tampon, 0, lus);
			}
		}
	}

	@Override
	public void rapporter(String nom, String version, Statut statut, List<String> raisons) {
		if (statut == Statut.OK) {
			log.info("Tirage local (Maître) : {} {} -> OK", nom, version);
		}
		else {
			log.warn("Tirage local (Maître) : {} {} -> {} {}", nom, version, statut,
					raisons.stream().map(DepotPlugins::net).toList());
		}
	}

}
