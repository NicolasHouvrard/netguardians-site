package com.logicielpourtous.site.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Signature Ed25519 d'un jar de plugin. La signature porte sur les octets exacts du jar ;
 * le fichier {@code <jar>.sig} contient {@code {"keyId":...,"algo":"Ed25519","signature":<base64>}}.
 * Sans aucune dépendance : le JSON est écrit et lu à la main (analyse stricte).
 */
public final class SignatureJar {

	public static final String ALGO = "Ed25519";

	/** Résultat d'une vérification : {@code keyId} n'est renseigné que si la signature est valide. */
	public record Verdict(boolean valide, String raison, String keyId) {
	}

	private SignatureJar() {
	}

	/** Signe le jar et renvoie le JSON de la {@code .sig}. */
	public static String signer(Path jar, PrivateKey cle, String keyId) throws Exception {
		if (keyId == null || keyId.isEmpty()) {
			throw new IllegalArgumentException("keyId vide");
		}
		Signature s = Signature.getInstance(ALGO);
		s.initSign(cle);
		s.update(Files.readAllBytes(jar));
		String b64 = Base64.getEncoder().encodeToString(s.sign());
		return "{\"keyId\":\"" + echapper(keyId) + "\",\"algo\":\"" + ALGO + "\",\"signature\":\"" + b64 + "\"}";
	}

	/** Vérifie la signature ; ne lève jamais : toute anomalie donne {@code valide=false} et une raison. */
	public static Verdict verifier(Path jar, String jsonSig, List<ClePublique> cles) {
		try {
			if (jar == null) {
				return refus("jar absent");
			}
			if (jsonSig == null) {
				return refus("signature absente");
			}
			if (cles == null || cles.isEmpty()) {
				return refus("aucune clé publique de confiance");
			}
			Map<String, String> champs = analyser(jsonSig);
			String keyId = champs.get("keyId");
			String algo = champs.get("algo");
			String b64 = champs.get("signature");
			if (keyId == null || algo == null || b64 == null) {
				return refus("champ manquant dans la signature");
			}
			if (!ALGO.equals(algo)) {
				return refus("algorithme non supporté : " + net(algo));
			}
			if (keyId.isEmpty() || b64.isEmpty()) {
				return refus("champ vide dans la signature");
			}
			PublicKey publique = null;
			for (ClePublique c : cles) {
				if (c != null && keyId.equals(c.keyId())) {
					publique = c.cle();
					break;
				}
			}
			if (publique == null) {
				return refus("clé inconnue : " + net(keyId));
			}
			byte[] sig;
			try {
				sig = Base64.getDecoder().decode(b64);
			}
			catch (IllegalArgumentException e) {
				return refus("signature illisible (base64 invalide)");
			}
			byte[] octets = Files.readAllBytes(jar);
			Signature s = Signature.getInstance(ALGO);
			s.initVerify(publique);
			s.update(octets);
			if (!s.verify(sig)) {
				return refus("signature invalide");
			}
			return new Verdict(true, "signature valide", keyId);
		}
		catch (IOException e) {
			return refus("jar illisible");
		}
		catch (IllegalArgumentException e) {
			return refus("JSON invalide : " + e.getMessage());
		}
		catch (Exception | LinkageError e) {
			return refus("vérification impossible : " + e.getClass().getSimpleName());
		}
	}

	/**
	 * Lit les clés publiques d'un fichier de propriétés : {@code <keyId>=<base64 X.509>}. Les entrées
	 * illisibles sont ignorées.
	 */
	public static List<ClePublique> lireCles(Properties props) {
		List<ClePublique> res = new ArrayList<>();
		if (props == null) {
			return res;
		}
		for (String nom : props.stringPropertyNames()) {
			try {
				byte[] der = Base64.getDecoder().decode(props.getProperty(nom).trim());
				PublicKey k = KeyFactory.getInstance(ALGO).generatePublic(new X509EncodedKeySpec(der));
				res.add(new ClePublique(nom, k));
			}
			catch (Exception e) {
				// entrée ignorée
			}
		}
		return res;
	}

	private static final int LONGUEUR_MAX_ECHO = 64;

	/** Texte venant de la signature : ASCII imprimable seulement et tronqué, avant d'entrer dans une raison. */
	static String net(String texte) {
		if (texte == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < texte.length() && sb.length() < LONGUEUR_MAX_ECHO; i++) {
			char c = texte.charAt(i);
			// liste blanche : ASCII imprimable uniquement (le reste, y compris contrôles C1, bidi, substituts, devient ?)
			sb.append(c >= 0x20 && c <= 0x7e ? c : '?');
		}
		return texte.length() > LONGUEUR_MAX_ECHO ? sb + "..." : sb.toString();
	}

	private static Verdict refus(String raison) {
		return new Verdict(false, raison, null);
	}

	private static String echapper(String s) {
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') {
				sb.append('\\').append(c);
			}
			else if (c < 0x20) {
				sb.append(String.format("\\u%04x", (int) c));
			}
			else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** Analyseur strict d'un objet JSON plat dont les trois champs (keyId, algo, signature) sont des chaînes. */
	static Map<String, String> analyser(String json) {
		int[] pos = { 0 };
		Map<String, String> res = new HashMap<>();
		blancs(json, pos);
		attendre(json, pos, '{');
		boolean premier = true;
		while (true) {
			blancs(json, pos);
			if (!premier) {
				if (courant(json, pos) != ',') {
					break;
				}
				pos[0]++;
				blancs(json, pos);
			}
			premier = false;
			String nom = chaine(json, pos);
			blancs(json, pos);
			attendre(json, pos, ':');
			blancs(json, pos);
			String valeur = chaine(json, pos);
			if (!nom.equals("keyId") && !nom.equals("algo") && !nom.equals("signature")) {
				throw new IllegalArgumentException("champ inattendu : " + net(nom));
			}
			if (res.put(nom, valeur) != null) {
				throw new IllegalArgumentException("champ dupliqué : " + net(nom));
			}
		}
		attendre(json, pos, '}');
		blancs(json, pos);
		if (pos[0] != json.length()) {
			throw new IllegalArgumentException("contenu après l'objet JSON");
		}
		return res;
	}

	private static char courant(String s, int[] pos) {
		if (pos[0] >= s.length()) {
			throw new IllegalArgumentException("JSON tronqué");
		}
		return s.charAt(pos[0]);
	}

	private static void attendre(String s, int[] pos, char c) {
		if (courant(s, pos) != c) {
			throw new IllegalArgumentException("JSON invalide : '" + c + "' attendu");
		}
		pos[0]++;
	}

	private static void blancs(String s, int[] pos) {
		while (pos[0] < s.length() && " \t\r\n".indexOf(s.charAt(pos[0])) >= 0) {
			pos[0]++;
		}
	}

	private static String chaine(String s, int[] pos) {
		attendre(s, pos, '"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = courant(s, pos);
			pos[0]++;
			if (c == '"') {
				return sb.toString();
			}
			if (c < 0x20) {
				throw new IllegalArgumentException("caractère de contrôle dans une chaîne");
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			char e = courant(s, pos);
			pos[0]++;
			switch (e) {
				case '"', '\\', '/' -> sb.append(e);
				case 'n' -> sb.append('\n');
				case 't' -> sb.append('\t');
				case 'r' -> sb.append('\r');
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'u' -> {
					if (pos[0] + 4 > s.length()) {
						throw new IllegalArgumentException("échappement unicode tronqué");
					}
					int code = 0;
					for (int k = 0; k < 4; k++) {
						int d = Character.digit(s.charAt(pos[0] + k), 16);
						if (d < 0 || s.charAt(pos[0] + k) > 'f') {
							throw new IllegalArgumentException("échappement unicode invalide");
						}
						code = code * 16 + d;
					}
					sb.append((char) code);
					pos[0] += 4;
				}
				default -> throw new IllegalArgumentException("échappement inconnu");
			}
		}
	}
}
