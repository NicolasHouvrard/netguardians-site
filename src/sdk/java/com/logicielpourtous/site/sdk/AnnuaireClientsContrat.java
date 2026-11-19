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

	/**
	 * Cree un client pour le partenaire {@code demande.partenaireSocieteId()} (societe, capacite CLIENT, lien d'origine
	 * {@code PARTENAIRE}, premier utilisateur manager) en une seule transaction. Comme {@code clientsDuPartenaire}, c'est
	 * un simple acces : l'appelant verifie d'abord le droit {@code clients.creer} de son utilisateur sur la societe du
	 * partenaire. Issues : {@code CREE}, {@code DEJA_CONNUE} (societe deja au registre : une demande d'acces est
	 * enregistree, rien d'autre n'est ecrit, aucune identite revelee), {@code EMAIL_DEJA_UTILISE} (rien d'ecrit).
	 *
	 * @throws IllegalArgumentException si la demande est invalide (numeros, e-mail, nom, partenaire)
	 */
	ResultatCreation creerClient(CreationClient demande);

	record CreationClient(Long partenaireSocieteId, boolean particulier, String siren, String tva, boolean etrangere,
			String email, String nom) {
	}

	/** {@code issue} : CREE, DEJA_CONNUE ou EMAIL_DEJA_UTILISE ; {@code societeId} seulement pour CREE. */
	record ResultatCreation(String issue, Long societeId) {
	}

	record ClientResume(Long societeId, String siren, String tva, String origine) {
	}

	record FicheClient(ClientResume resume, Long partenaireSocieteId, List<String> capacites) {

		public FicheClient {
			capacites = capacites == null ? List.of() : List.copyOf(capacites);
		}

	}

}
