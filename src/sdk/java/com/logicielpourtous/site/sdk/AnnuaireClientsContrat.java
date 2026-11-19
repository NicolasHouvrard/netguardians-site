package com.logicielpourtous.site.sdk;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

	/**
	 * Entree du partenaire dans l'espace d'un client, en tant que manager, tracee dans un journal. {@code userId} doit
	 * etre celui de l'identite courante et la societe du partenaire est celle de cette identite (jamais une valeur de la
	 * requete). L'identite reste celle du partenaire.
	 *
	 * @throws SecurityException refus, quelle qu'en soit la cause (sans droit, client inconnu, client d'un autre
	 * partenaire, identite absente ou incoherente) : l'appelant ne doit pas distinguer les causes
	 */
	PorteeAcces entrerEspaceClient(Long userId, Long clientSocieteId);

	/** Perimetre accorde : la societe cliente et les droits effectifs (droits du manager dans la limite du plafond). */
	record PorteeAcces(Long societeId, Set<String> droitsEffectifs) {

		public PorteeAcces {
			droitsEffectifs = droitsEffectifs == null ? Set.of() : Set.copyOf(droitsEffectifs);
		}

	}

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
