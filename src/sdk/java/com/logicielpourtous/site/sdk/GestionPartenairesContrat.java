package com.logicielpourtous.site.sdk;

import java.util.List;
import java.util.Set;

/**
 * Contrat « gestion des partenaires » offert aux plugins par le noyau. Reserve a l'administrateur du serveur maitre :
 * chaque methode verifie cette qualite cote noyau et leve {@link SecurityException} dans le cas contraire.
 */
public interface GestionPartenairesContrat {

	/**
	 * Cree un partenaire (societe, capacite PARTENAIRE, premier utilisateur) a partir de la demande. Issues :
	 * {@code CREE}, {@code SIREN_DEJA_CONNU}, {@code EMAIL_DEJA_UTILISE} (dans les deux derniers cas rien n'est ecrit).
	 *
	 * @throws SecurityException si l'appelant n'est pas l'admin du serveur maitre (verifie par le noyau)
	 * @throws IllegalArgumentException si la demande est invalide (numeros, e-mail, nom, raison sociale)
	 */
	ResultatPartenaire creerPartenaire(CreationPartenaire demande);

	/**
	 * Cree un client pour le partenaire cible, qui est {@code demande.partenaireSocieteId()}. Memes issues et meme
	 * resultat que {@link AnnuaireClientsContrat#creerClient}.
	 *
	 * @throws SecurityException si l'appelant n'est pas l'admin du serveur maitre (verifie par le noyau)
	 * @throws IllegalArgumentException si la demande est invalide (numeros, e-mail, nom, partenaire)
	 */
	AnnuaireClientsContrat.ResultatCreation creerClientPourPartenaire(AnnuaireClientsContrat.CreationClient demande);

	/**
	 * Liste les partenaires du serveur.
	 *
	 * @throws SecurityException si l'appelant n'est pas l'admin du serveur maitre (verifie par le noyau)
	 */
	List<PartenaireResume> listerPartenaires();

	/**
	 * Droits du modele applique par defaut aux nouveaux partenaires.
	 *
	 * @throws SecurityException si l'appelant n'est pas l'admin du serveur maitre (verifie par le noyau)
	 */
	Set<String> modeleDroits();

	/**
	 * Remplace le modele de droits applique par defaut aux nouveaux partenaires.
	 *
	 * @throws SecurityException si l'appelant n'est pas l'admin du serveur maitre (verifie par le noyau)
	 * @throws IllegalArgumentException si un droit est invalide
	 */
	void remplacerModeleDroits(Set<String> droits);

	/** Demande de creation d'un partenaire ; la validation des valeurs (y compris nulles) est faite par le noyau. */
	record CreationPartenaire(String siren, String tva, String raisonSociale, String email, String nom) {
	}

	/** {@code issue} : CREE, SIREN_DEJA_CONNU ou EMAIL_DEJA_UTILISE ; {@code societeId} seulement pour CREE. */
	record ResultatPartenaire(String issue, Long societeId) {
	}

	/** Resume d'un partenaire ; {@code etat} est l'etat courant du partenaire. */
	record PartenaireResume(Long societeId, String siren, String raisonSociale, String etat) {
	}

}
