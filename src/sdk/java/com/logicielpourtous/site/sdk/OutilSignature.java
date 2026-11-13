package com.logicielpourtous.site.sdk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

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

	private static int generer(Path dossier, String keyId) throws Exception {
		if (keyId.isEmpty() || keyId.contains("/") || keyId.contains("\\") || keyId.contains("..")) {
			System.err.println("keyId invalide");
			return 1;
		}
		Files.createDirectories(dossier);
		Path prive = dossier.resolve(keyId + ".prive");
		Path publique = dossier.resolve(keyId + ".public");
		if (Files.exists(prive) || Files.exists(publique)) {
			System.err.println("Fichiers de clés déjà présents, rien n'est écrasé");
			return 1;
		}
		KeyPair paire = KeyPairGenerator.getInstance(SignatureJar.ALGO).generateKeyPair();
		Files.writeString(prive, Base64.getEncoder().encodeToString(paire.getPrivate().getEncoded()) + "\n",
				StandardCharsets.UTF_8);
		Files.writeString(publique, Base64.getEncoder().encodeToString(paire.getPublic().getEncoded()) + "\n",
				StandardCharsets.UTF_8);
		System.out.println("Clés écrites : " + prive.getFileName() + " (SECRÈTE, à ne jamais publier) et "
				+ publique.getFileName());
		return 0;
	}

	private static int signer(Path jar, Path fichierPrive, String keyId) throws Exception {
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
