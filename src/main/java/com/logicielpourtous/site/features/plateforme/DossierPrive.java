package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Dossier privé du processus, même règle que le dossier de travail de {@link PluginManager} : créé réservé au
 * propriétaire ; existant, il doit être un vrai dossier (ni lien symbolique, ni jonction Windows) appartenant à
 * l'utilisateur du processus, et ses droits sont (re)restreints à ce seul utilisateur (POSIX {@code rwx------} ;
 * Windows : une seule entrée ACL ALLOW propriétaire, héritée par les fichiers et sous-dossiers). Hypothèse : le
 * dossier parent n'est pas modifiable par d'autres.
 */
final class DossierPrive {

	private DossierPrive() {
	}

	/** Utilisateur propriétaire des fichiers créés par ce processus (fichier témoin temporaire). */
	static UserPrincipal utilisateurDuProcessus() throws IOException {
		Path temoin = Files.createTempFile("plateforme-proprietaire", ".tmp");
		try {
			return Files.getOwner(temoin, LinkOption.NOFOLLOW_LINKS);
		}
		finally {
			Files.deleteIfExists(temoin);
		}
	}

	/**
	 * Crée ou contrôle {@code dossier} puis restreint ses droits ; {@code libelle} commence les messages
	 * (par exemple « dépôt refusé »).
	 *
	 * @throws IOException lien, jonction, pas un dossier, autre propriétaire ou droits non restreignables
	 */
	static Path preparer(Path dossier, UserPrincipal attendu, String libelle) throws IOException {
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		if (!Files.exists(dossier, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dossier)) {
			Path parent = dossier.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (posix) {
				Files.createDirectory(dossier,
						PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			}
			else {
				Files.createDirectory(dossier);
			}
		}
		BasicFileAttributes attributs = Files.readAttributes(dossier, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		if (attributs.isSymbolicLink() || attributs.isOther() || !attributs.isDirectory()) {
			throw new IOException(libelle + " (lien, jonction ou pas un dossier) : " + dossier);
		}
		UserPrincipal proprietaire = Files.getOwner(dossier, LinkOption.NOFOLLOW_LINKS);
		if (!proprietaire.equals(attendu)) {
			throw new IOException(libelle + " (n'appartient pas à l'utilisateur du processus) : " + dossier);
		}
		if (posix) {
			Files.setPosixFilePermissions(dossier, PosixFilePermissions.fromString("rwx------"));
		}
		else {
			AclFileAttributeView vue = Files.getFileAttributeView(dossier, AclFileAttributeView.class,
					LinkOption.NOFOLLOW_LINKS);
			if (vue == null) {
				throw new IOException(libelle + " : droits non restreignables sur ce système : " + dossier);
			}
			List<AclEntry> proprietaireSeul = List.of(AclEntry.newBuilder()
				.setType(AclEntryType.ALLOW)
				.setPrincipal(proprietaire)
				.setPermissions(EnumSet.allOf(AclEntryPermission.class))
				.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
				.build());
			if (!vue.getAcl().equals(proprietaireSeul)) {
				vue.setAcl(proprietaireSeul);
			}
		}
		return dossier;
	}

}
