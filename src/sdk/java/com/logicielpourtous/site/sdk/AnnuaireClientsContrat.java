package com.logicielpourtous.site.sdk;

import java.util.List;
import java.util.Optional;

/**
 * Contrat « annuaire clients » offert aux plugins par le noyau. {@code clientsDuPartenaire} et {@code fiche} sont de
 * simples accès aux données : l'appelant doit d'abord vérifier le droit de son utilisateur avec {@link #autorise}.
 */
public interface AnnuaireClientsContrat {

	/** Clients liés au partenaire donné (jamais ceux d'un autre partenaire). */
	List<ClientResume> clientsDuPartenaire(Long partenaireSocieteId);

	/** Fiche d'un client ; vide si la société n'est pas liée à un partenaire ou n'a pas la capacité CLIENT. */
	Optional<FicheClient> fiche(Long clientSocieteId);

	/** Droit de l'utilisateur sur la société (plafond, réglages du manager, rôles) ; faux par défaut. */
	boolean autorise(Long userId, Long societeId, String droit);

	record ClientResume(Long societeId, String siren, String tva, String origine) {
	}

	record FicheClient(ClientResume resume, Long partenaireSocieteId, List<String> capacites) {

		public FicheClient {
			capacites = capacites == null ? List.of() : List.copyOf(capacites);
		}

	}

}
