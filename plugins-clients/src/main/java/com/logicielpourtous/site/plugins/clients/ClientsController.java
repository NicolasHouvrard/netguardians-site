package com.logicielpourtous.site.plugins.clients;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

	private final IdentiteContrat identite;

	private final AnnuaireClientsContrat annuaire;

	public ClientsController(IdentiteContrat identite, AnnuaireClientsContrat annuaire) {
		this.identite = identite;
		this.annuaire = annuaire;
	}

	@GetMapping("/liste")
	public ResponseEntity<Object> liste() {
		Optional<Long> societe = societeAutorisee();
		if (societe.isEmpty()) {
			return ResponseEntity.status(403).build();
		}
		return ResponseEntity.ok(annuaire.clientsDuPartenaire(societe.get()));
	}

	@GetMapping("/{societeId}/fiche")
	public ResponseEntity<Object> fiche(@PathVariable("societeId") Long societeId) {
		Optional<Long> societe = societeAutorisee();
		if (societe.isEmpty()) {
			return ResponseEntity.status(403).build();
		}
		Optional<FicheClient> fiche = annuaire.fiche(societeId);
		if (fiche.isEmpty() || !societe.get().equals(fiche.get().partenaireSocieteId())) {
			return ResponseEntity.status(404).build();
		}
		return ResponseEntity.ok(fiche.get());
	}

	/** Société du partenaire courant si elle existe et si son utilisateur a le droit ; vide sinon (403). */
	private Optional<Long> societeAutorisee() {
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
		return annuaire.autorise(moi.userId(), moi.societeId(), DROIT) ? Optional.of(moi.societeId())
				: Optional.empty();
	}

}
