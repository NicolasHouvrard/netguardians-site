package com.logicielpourtous.site.sdk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Outil en ligne de commande : {@code generer-cles <dossier> <keyId>}, {@code signer <jar> <cle-privee> <keyId>},
 * {@code verifier <jar> <cles-publiques.properties>}. Code retour 0 si succès, 1 sinon. La clé privée n'est
 * jamais affichée.
 */
public final class OutilSignature {

	private OutilSignature() {
	}

	public static void main(String[] args) {
		System.exit(executer(args));
	}

	/** Exécute une commande et renvoie le code retour (0 ou 1). */
	public static int executer(String[] args) {
		try {
			if (args == null || args.length == 0) {
				return usage();
			}
			switch (args[0]) {
				case "generer-cles":
					return args.length == 3 ? generer(Path.of(args[1]), args[2]) : usage();
				case "signer":
					return args.length == 4 ? signer(Path.of(args[1]), Path.of(args[2]), args[3]) : usage();
				case "verifier":
					return args.length == 3 ? verifier(Path.of(args[1]), Path.of(args[2])) : usage();
				default:
					return usage();
			}
		}
		catch (Exception e) {
			System.err.println("Échec : " + e.getClass().getSimpleName());
			return 1;
		}
	}

	private static int usage() {
		System.err.println("Usage :");
		System.err.println("  generer-cles <dossier> <keyId>");
		System.err.println("  signer <jar> <fichier-cle-privee> <keyId>");
		System.err.println("  verifier <jar> <fichier-cles-publiques.properties>");
		return 1;
	}

	private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private static boolean keyIdValide(String keyId) {
		return keyId != null && KEY_ID.matcher(keyId).matches();
	}

	private static int generer(Path dossier, String keyId) throws Exception {
		if (!keyIdValide(keyId)) {
			System.err.println("keyId invalide : attendu [A-Za-z0-9._-]{1,64}");
			return 1;
		}
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		if (!Files.exists(dossier)) {
			if (posix) {
				Files.createDirectories(dossier, PosixFilePermissions
						.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			}
			else {
				Files.createDirectories(dossier);
			}
		}
		Path prive = dossier.resolve(keyId + ".prive");
		Path publique = dossier.resolve(keyId + ".public");
		if (existe(prive) || existe(publique)) {
			System.err.println("Fichiers de clés déjà présents (ou lien symbolique), rien n'est écrasé");
			return 1;
		}
		KeyPair paire = KeyPairGenerator.getInstance(SignatureJar.ALGO).generateKeyPair();
		byte[] contenuPrive = (Base64.getEncoder().encodeToString(paire.getPrivate().getEncoded()) + "\n")
				.getBytes(StandardCharsets.UTF_8);
		ecrirePrive(prive, contenuPrive, posix);
		try {
			Files.write(publique, (Base64.getEncoder().encodeToString(paire.getPublic().getEncoded()) + "\n")
					.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		}
		catch (Exception e) {
			Files.deleteIfExists(prive);
			throw e;
		}
		System.out.println("Clés écrites : " + prive.getFileName() + " (SECRÈTE, à ne jamais publier) et "
				+ publique.getFileName());
		return 0;
	}

	private static boolean existe(Path p) {
		return Files.exists(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p);
	}

	/** Crée le fichier de clé privée (jamais écrasé) avec des droits réservés au propriétaire avant d'y écrire. */
	private static void ecrirePrive(Path prive, byte[] contenu, boolean posix) throws Exception {
		Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		try {
			if (posix) {
				try (SeekableByteChannel canal = Files.newByteChannel(prive, options,
						PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
					canal.write(ByteBuffer.wrap(contenu));
				}
				return;
			}
			// Fichier créé vide, restreint au propriétaire, PUIS rempli.
			Files.newByteChannel(prive, options).close();
			AclFileAttributeView vue = Files.getFileAttributeView(prive, AclFileAttributeView.class,
					LinkOption.NOFOLLOW_LINKS);
			if (vue == null) {
				throw new IOException("impossible de restreindre les droits du fichier de clé privée");
			}
			UserPrincipal proprietaire = vue.getOwner();
			vue.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(proprietaire)
					.setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
			try (SeekableByteChannel canal = Files.newByteChannel(prive, StandardOpenOption.WRITE,
					LinkOption.NOFOLLOW_LINKS)) {
				canal.write(ByteBuffer.wrap(contenu));
			}
		}
		catch (Exception e) {
			Files.deleteIfExists(prive);
			throw e;
		}
	}

	private static int signer(Path jar, Path fichierPrive, String keyId) throws Exception {
		if (!keyIdValide(keyId)) {
			System.err.println("keyId invalide : attendu [A-Za-z0-9._-]{1,64}");
			return 1;
		}
		byte[] der = Base64.getDecoder().decode(Files.readString(fichierPrive, StandardCharsets.UTF_8).trim());
		PrivateKey cle = KeyFactory.getInstance(SignatureJar.ALGO).generatePrivate(new PKCS8EncodedKeySpec(der));
		String json = SignatureJar.signer(jar, cle, keyId);
		Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
		Files.writeString(sig, json + "\n", StandardCharsets.UTF_8);
		System.out.println("Signature écrite : " + sig.getFileName());
		return 0;
	}

	private static int verifier(Path jar, Path fichierCles) throws Exception {
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(fichierCles, StandardCharsets.UTF_8)) {
			props.load(in);
		}
		Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
		SignatureJar.Verdict v = SignatureJar.verifier(jar, Files.readString(sig, StandardCharsets.UTF_8),
				SignatureJar.lireCles(props));
		if (v.valide()) {
			System.out.println("Signature valide (clé " + v.keyId() + ")");
			return 0;
		}
		System.err.println("Signature refusée : " + v.raison());
		return 1;
	}
}
