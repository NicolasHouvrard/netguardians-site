package com.logicielpourtous.site.plugins.partenaires;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.CreationPartenaire;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.ResultatPartenaire;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creation de partenaires et de leurs clients. Servi sous {@code /api/plugins/partenaires} par le noyau. Le plugin ne
 * verifie aucun role et ne lit aucune identite : le contrat {@link GestionPartenairesContrat} refuse tout appel qui ne
 * vient pas de l'administrateur du serveur maitre ({@link SecurityException}), traduit ici en 403 a corps vide. Toute
 * autre erreur inattendue donne un 500 a corps vide (echec ferme) ; une demande invalide, un 400 generique.
 */
@RestController
public class PartenairesController {

	private static final Pattern IDENTIFIANT = Pattern.compile("^[0-9]{1,15}$");

	/** Corps de la creation d'un client : le partenaire cible vient du chemin, jamais du corps. */
	record DemandeClient(boolean particulier, String siren, String tva, String email, String nom) {
	}

	/** Corps du remplacement du modele de droits. */
	record DemandeModele(Set<String> droits) {
	}

	private final GestionPartenairesContrat contrat;

	public PartenairesController(GestionPartenairesContrat contrat) {
		this.contrat = contrat;
	}

	@GetMapping("/liste")
	public ResponseEntity<Object> liste() {
		return proteger(() -> ResponseEntity.ok(contrat.listerPartenaires()));
	}

	@PostMapping
	public ResponseEntity<Object> creerPartenaire(@RequestBody CreationPartenaire demande) {
		return proteger(() -> {
			ResultatPartenaire r = contrat.creerPartenaire(demande);
			String issue = r == null ? null : r.issue();
			if ("CREE".equals(issue) && r.societeId() != null) {
				return ResponseEntity.status(201).body(Map.of("societeId", r.societeId()));
			}
			if ("SIREN_DEJA_CONNU".equals(issue)) {
				return ResponseEntity.status(409)
					.body(Map.of("issue", "SIREN_DEJA_CONNU", "message", "Ce SIREN est déjà enregistré"));
			}
			if ("EMAIL_DEJA_UTILISE".equals(issue)) {
				return ResponseEntity.status(409)
					.body(Map.of("issue", "EMAIL_DEJA_UTILISE", "message", "Cette adresse e-mail est déjà utilisée"));
			}
			throw new IllegalStateException("Issue de creation inattendue.");
		});
	}

	@PostMapping("/{partenaireId}/clients")
	public ResponseEntity<Object> creerClient(@PathVariable("partenaireId") String partenaireId,
			@RequestBody DemandeClient demande) {
		if (partenaireId == null || !IDENTIFIANT.matcher(partenaireId).matches()) {
			return mauvaiseDemande();
		}
		long partenaire = Long.parseLong(partenaireId);
		return proteger(() -> {
			ResultatCreation r = contrat.creerClientPourPartenaire(new CreationClient(partenaire,
					demande.particulier(), demande.siren(), demande.tva(), false, demande.email(), demande.nom()));
			String issue = r == null ? null : r.issue();
			if ("CREE".equals(issue) && r.societeId() != null) {
				return ResponseEntity.status(201).body(Map.of("issue", "CREE", "societeId", r.societeId()));
			}
			if ("DEJA_CONNUE".equals(issue)) {
				return ResponseEntity.status(409)
					.body(Map.of("issue", "DEJA_CONNUE", "message",
							"société déjà connue, une demande d'accès a été envoyée à son manager"));
			}
			if ("EMAIL_DEJA_UTILISE".equals(issue)) {
				return ResponseEntity.status(409)
					.body(Map.of("issue", "EMAIL_DEJA_UTILISE", "message", "cette adresse e-mail est déjà utilisée"));
			}
			throw new IllegalStateException("Issue de creation inattendue.");
		});
	}

	@GetMapping("/modele-droits")
	public ResponseEntity<Object> modeleDroits() {
		return proteger(() -> ResponseEntity.ok(contrat.modeleDroits()));
	}

	@PutMapping("/modele-droits")
	public ResponseEntity<Object> remplacerModeleDroits(@RequestBody DemandeModele demande) {
		if (demande == null || demande.droits() == null) {
			return mauvaiseDemande();
		}
		Set<String> droits = new HashSet<>(demande.droits());
		return proteger(() -> {
			contrat.remplacerModeleDroits(droits);
			return ResponseEntity.noContent().build();
		});
	}

	private static ResponseEntity<Object> mauvaiseDemande() {
		return ResponseEntity.status(400).body(Map.of("message", "Données invalides."));
	}

	/** Traduit les refus du contrat : 403 vide, 400 generique, 500 vide (echec ferme). */
	private static ResponseEntity<Object> proteger(Supplier<ResponseEntity<Object>> action) {
		try {
			return action.get();
		}
		catch (SecurityException e) {
			return ResponseEntity.status(403).build();
		}
		catch (IllegalArgumentException e) {
			return mauvaiseDemande();
		}
		catch (RuntimeException e) {
			return ResponseEntity.status(500).build();
		}
	}

}
