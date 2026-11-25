package com.logicielpourtous.site.features.clients;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantManagementService;
import com.logicielpourtous.site.features.identite.EmailsUtilises;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteDejaConnueException;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.CreationPartenaire;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.ResultatPartenaire;

/**
 * Creation complete d'un partenaire (tenant et schema, societe, capacite, premier manager, droits de depart) en une
 * seule transaction : toute erreur annule tout, schema compris.
 */
@Service
class CreationPartenaireService {

	/** Taille maximale (colonnes varchar(255)) de l'e-mail, du nom et de la raison sociale. */
	private static final int MAX = 255;

	private static final String CREE = "CREE";

	private static final String SIREN_DEJA_CONNU = "SIREN_DEJA_CONNU";

	private static final String EMAIL_DEJA_UTILISE = "EMAIL_DEJA_UTILISE";

	private final TenantManagementService tenants;

	private final RegistreSocietes registre;

	private final EmailsUtilises emailsUtilises;

	private final CapaciteSocieteRepository capacites;

	private final PremierManagerService premierManager;

	private final ManagersService managers;

	private final DroitsService droits;

	private final ModeleDroitsPartenaireService modele;

	private final EtatPartenaireRepository etats;

	CreationPartenaireService(TenantManagementService tenants, RegistreSocietes registre, EmailsUtilises emailsUtilises,
			CapaciteSocieteRepository capacites, PremierManagerService premierManager, ManagersService managers,
			DroitsService droits, ModeleDroitsPartenaireService modele, EtatPartenaireRepository etats) {
		this.tenants = tenants;
		this.registre = registre;
		this.emailsUtilises = emailsUtilises;
		this.capacites = capacites;
		this.premierManager = premierManager;
		this.managers = managers;
		this.droits = droits;
		this.modele = modele;
		this.etats = etats;
	}

	/**
	 * REQUIRES_NEW : la création (et un éventuel rollback-only de la course sur le SIREN) est isolée de la transaction
	 * d'un appelant. Conséquence : si l'appelant annule ensuite sa propre transaction, le partenaire reste créé.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	ResultatPartenaire creer(CreationPartenaire demande) {
		if (demande == null) {
			throw new IllegalArgumentException("La demande doit être renseignée.");
		}
		String email = demande.email() == null ? "" : demande.email().strip().toLowerCase(Locale.ROOT);
		if (email.isEmpty() || email.length() < 3 || email.indexOf('@') < 1 || email.indexOf('@') == email.length() - 1
				|| email.length() > MAX || email.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalArgumentException("L'adresse e-mail est invalide.");
		}
		String nom = demande.nom() == null ? "" : demande.nom().strip();
		if (nom.isEmpty() || nom.length() > MAX) {
			throw new IllegalArgumentException("Le nom doit être renseigné.");
		}
		String raisonSociale = demande.raisonSociale() == null ? "" : demande.raisonSociale().strip();
		if (raisonSociale.isEmpty() || raisonSociale.length() > MAX) {
			throw new IllegalArgumentException("La raison sociale doit être renseignée.");
		}
		IdentiteSociete identite = identite(demande);
		if (identite.etrangere()) {
			throw new IllegalArgumentException("Un partenaire doit être une société française.");
		}

		// Tous les refus métier sont tranchés AVANT la première écriture : rien n'est écrit dans ces cas.
		User compteExistant = null;
		if (emailsUtilises.estUtilise(email)) {
			// Rattachement explicite d'un compte existant : seulement s'il est actif ; sinon refus comme avant.
			if (demande.rattacherCompteExistant()) {
				compteExistant = premierManager.trouverActif(email).orElse(null);
			}
			if (compteExistant == null) {
				return new ResultatPartenaire(EMAIL_DEJA_UTILISE, null);
			}
		}
		if (registre.trouver(identite).isPresent()) {
			return new ResultatPartenaire(SIREN_DEJA_CONNU, null);
		}

		Tenant tenant = tenants.creerTenantTransactionnel(raisonSociale, TenantManagementService.nouveauNomDeSchema());
		SocieteIdentite societe;
		try {
			societe = registre.enregistrer(TypeSociete.PARTENAIRE, identite, tenant.getId());
		}
		catch (SocieteDejaConnueException e) {
			// Course avec une autre création entre le contrôle et l'écriture. enregistrer() est en noRollbackFor pour cette
			// exception : on annule donc explicitement la transaction, qui emporte le tenant et le schéma déjà créés.
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			return new ResultatPartenaire(SIREN_DEJA_CONNU, null);
		}
		Long societeId = societe.getId();
		CapaciteSociete capacite = new CapaciteSociete();
		capacite.setSocieteId(societeId);
		capacite.setCapacite(TypeCapacite.PARTENAIRE);
		capacites.saveAndFlush(capacite);

		// Compte existant : ni création, ni changement de ses rôles globaux, tenant, nom ou mot de passe.
		User premier = compteExistant != null ? compteExistant : premierManager.creer(email, nom, tenant.getId());
		managers.ajouterUtilisateur(premier.getId(), societeId, Set.of());

		// Copie du modèle À CET INSTANT : une modification ultérieure du modèle ne touche pas ce partenaire.
		Set<String> droitsDeDepart = modele.droits();
		droits.fixerPlafond(societeId, droitsDeDepart);
		droits.accorderAuRole(societeId, ManagersService.ROLE_MANAGER, droitsDeDepart);

		EtatPartenaire etat = new EtatPartenaire();
		etat.setSocieteId(societeId);
		etat.setEtat("ACTIF");
		etat.setDebutValidation(Instant.now());
		etats.saveAndFlush(etat);
		return new ResultatPartenaire(CREE, societeId);
	}

	private static IdentiteSociete identite(CreationPartenaire demande) {
		String siren = NumerosSociete.chiffres(demande.siren());
		String tva = NumerosSociete.tva(demande.tva());
		if (tva == null && NumerosSociete.sirenValide(siren)) {
			tva = NumerosSociete.tvaFrancaise(siren);
		}
		return IdentiteSociete.depuis(siren, null, tva);
	}

}
