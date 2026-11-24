package com.logicielpourtous.site.plugins.clients;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liste et fiche des clients du partenaire courant. Servi sous {@code /api/plugins/clients} par le noyau (le préfixe
 * est ajouté par le routage des plugins).
 *
 * <p>Règles de sécurité (échec fermé) : le périmètre du droit {@value #DROIT} est la société du partenaire lui-même
 * (jamais une valeur de la requête) ; sans société ou sans droit : 403, sans aucune lecture de données. Une fiche
 * n'est servie que si le client est rattaché à CE partenaire ; sinon (inconnue, non cliente, cliente d'un autre
 * partenaire) la réponse est un 404 vide, identique dans tous les cas, pour ne rien révéler des clients d'autrui.</p>
 */
@RestController
public class ClientsController {

	static final String DROIT = "clients.lire";

	static final String DROIT_CREER = "clients.creer";

	static final String DROIT_ENTRER = "clients.entrer";

	/** Corps de la creation : le partenaire n'y figure volontairement pas (c'est toujours la societe de l'appelant). */
	record DemandeCreation(boolean particulier, String siren, String tva, boolean etrangere, String email, String nom) {
	}

	private final IdentiteContrat identite;

	private final AnnuaireClientsContrat annuaire;

	public ClientsController(IdentiteContrat identite, AnnuaireClientsContrat annuaire) {
		this.identite = identite;
		this.annuaire = annuaire;
	}

	@GetMapping("/liste")
	public ResponseEntity<Object> liste() {
		Optional<Long> societe = societeAutorisee(DROIT);
		if (societe.isEmpty()) {
			return ResponseEntity.status(403).build();
		}
		return ResponseEntity.ok(annuaire.clientsDuPartenaire(societe.get()));
	}

	@GetMapping("/{societeId}/fiche")
	public ResponseEntity<Object> fiche(@PathVariable("societeId") Long societeId) {
		return ficheDuPartenaire(societeId, false);
	}

	/**
	 * Bloc « resume » de la fiche (chemin {@code /blocs/resume} du manifeste, appele pour un client donne) : memes
	 * controles que la fiche (droit sur SA societe, rattachement a CE partenaire, 404 vide identique, 403 echec ferme).
	 */
	@GetMapping("/{societeId}/blocs/resume")
	public ResponseEntity<Object> blocResume(@PathVariable("societeId") Long societeId) {
		return ficheDuPartenaire(societeId, true);
	}

	private ResponseEntity<Object> ficheDuPartenaire(Long societeId, boolean resumeSeul) {
		Optional<Long> societe = societeAutorisee(DROIT);
		if (societe.isEmpty()) {
			return ResponseEntity.status(403).build();
		}
		Optional<FicheClient> fiche = annuaire.fiche(societeId);
		if (fiche.isEmpty() || !societe.get().equals(fiche.get().partenaireSocieteId())) {
			return ResponseEntity.status(404).build();
		}
		return ResponseEntity.ok(resumeSeul ? fiche.get().resume() : fiche.get());
	}

	/**
	 * Crée un client pour le partenaire courant (droit {@value #DROIT_CREER} sur SA société). 201 si créé ; 409 avec
	 * un message neutre (sans identité de l'autre société) si la société est déjà connue ou l'e-mail déjà utilisé ;
	 * 400 générique si la demande est invalide. Toute autre erreur du contrat remonte (500) : échec fermé.
	 */
	@PostMapping
	public ResponseEntity<Object> creer(@RequestBody DemandeCreation demande) {
		Optional<Long> societe = societeAutorisee(DROIT_CREER);
		if (societe.isEmpty()) {
			return ResponseEntity.status(403).build();
		}
		ResultatCreation resultat;
		try {
			resultat = annuaire.creerClient(new CreationClient(societe.get(), demande.particulier(), demande.siren(),
					demande.tva(), demande.etrangere(), demande.email(), demande.nom()));
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.status(400).body(Map.of("message", "Données invalides."));
		}
		String issue = resultat == null ? null : resultat.issue();
		if ("CREE".equals(issue) && resultat.societeId() != null) {
			return ResponseEntity.status(201).body(Map.of("issue", "CREE", "societeId", resultat.societeId()));
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
		throw new IllegalStateException("Issue de création inattendue.");
	}

	/**
	 * Entrée du partenaire dans l'espace d'un de ses clients (droit {@value #DROIT_ENTRER} sur SA société). Aucune
	 * donnée de la requête hormis l'identifiant du client : l'utilisateur et la société viennent de l'identité. Sans le
	 * droit : 403 vide. Refus du noyau (client inconnu, d'un autre partenaire, tout autre refus) : un 404 vide, identique
	 * dans tous les cas. Toute autre erreur remonte (500) : échec fermé.
	 */
	@PostMapping("/{societeId}/entrer")
	public ResponseEntity<Object> entrer(@PathVariable("societeId") Long societeId) {
		Optional<Long> societe = societeAutorisee(DROIT_ENTRER);
		if (societe.isEmpty()) {
			return ResponseEntity.status(403).build();
		}
		Long userId = identite.courante().userId();
		try {
			return ResponseEntity.ok(annuaire.entrerEspaceClient(userId, societeId));
		}
		catch (SecurityException e) {
			return ResponseEntity.status(404).build();
		}
	}

	/** Société du partenaire courant si elle existe et si son utilisateur a le droit ; vide sinon (403). */
	private Optional<Long> societeAutorisee(String droit) {
		IdentiteCourante moi;
		try {
			moi = identite.courante();
		}
		catch (IllegalStateException e) {
			return Optional.empty();
		}
		if (moi == null || moi.userId() == null || moi.societeId() == null) {
			return Optional.empty();
		}
		return annuaire.autorise(moi.userId(), moi.societeId(), droit) ? Optional.of(moi.societeId())
				: Optional.empty();
	}

}
