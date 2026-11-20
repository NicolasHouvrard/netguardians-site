package com.logicielpourtous.site.features.clients;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;

/**
 * Gestion des partenaires, reservee a l'administrateur du serveur maitre. La garde est la PREMIERE instruction de
 * chaque methode et lit l'identite uniquement dans le bean {@link IdentiteContrat}, jamais dans un parametre.
 * <p>
 * Volontairement NON transactionnelle : la creation du partenaire s'execute dans sa propre transaction
 * (REQUIRES_NEW) et le journal est ecrit apres son retour. Si l'appelant annule ensuite sa propre transaction, le
 * partenaire reste cree (comportement documente et accepte).
 */
@Component
class GestionPartenairesContratImpl implements GestionPartenairesContrat {

	private static final String REFUS = "Réservé à l'administrateur du serveur maître.";

	private static final String CREE = "CREE";

	private final IdentiteContrat identite;

	private final CreationPartenaireService creation;

	private final JournalActionsMaitre journal;

	private final ModeleDroitsPartenaireService modele;

	private final AnnuaireClientsContratImpl annuaire;

	private final SocieteIdentiteRepository societes;

	private final CapaciteSocieteRepository capacites;

	private final TenantRepository tenants;

	private final EtatPartenaireRepository etats;

	GestionPartenairesContratImpl(IdentiteContrat identite, CreationPartenaireService creation,
			JournalActionsMaitre journal, ModeleDroitsPartenaireService modele, AnnuaireClientsContratImpl annuaire,
			SocieteIdentiteRepository societes, CapaciteSocieteRepository capacites, TenantRepository tenants,
			EtatPartenaireRepository etats) {
		this.identite = identite;
		this.creation = creation;
		this.journal = journal;
		this.modele = modele;
		this.annuaire = annuaire;
		this.societes = societes;
		this.capacites = capacites;
		this.tenants = tenants;
		this.etats = etats;
	}

	/** Echec ferme : toute anomalie de lecture de l'identite, ou toute identite incomplete, est un refus. */
	private IdentiteCourante adminDuMaitre() {
		IdentiteCourante moi;
		try {
			moi = identite.courante();
		}
		catch (RuntimeException e) {
			throw new SecurityException(REFUS);
		}
		if (moi == null || moi.userId() == null || moi.roles() == null || !moi.roles().contains("ROLE_ADMIN")
				|| !"MASTER".equals(moi.roleServeur())) {
			throw new SecurityException(REFUS);
		}
		return moi;
	}

	@Override
	public ResultatPartenaire creerPartenaire(CreationPartenaire demande) {
		IdentiteCourante admin = adminDuMaitre();
		ResultatPartenaire r = creation.creer(demande);
		if (CREE.equals(r.issue())) {
			journal.enregistrer(admin.userId(), JournalActionsMaitre.PARTENAIRE_CREE, r.societeId(), r.societeId());
		}
		return r;
	}

	@Override
	public ResultatCreation creerClientPourPartenaire(CreationClient demande) {
		IdentiteCourante admin = adminDuMaitre();
		if (demande == null) {
			throw new IllegalArgumentException("La demande doit être renseignée.");
		}
		Long cible = demande.partenaireSocieteId();
		if (cible == null) {
			throw new IllegalArgumentException("La société partenaire doit être renseignée.");
		}
		SocieteIdentite partenaire = societes.findById(cible)
			.filter(s -> capacites.existsBySocieteIdAndCapacite(s.getId(), TypeCapacite.PARTENAIRE))
			.orElseThrow(() -> new IllegalArgumentException("Société partenaire inconnue ou sans capacité partenaire."));
		Long tenant = partenaire.getProprietaireTenantId();
		if (tenant == null || tenants.findById(tenant).isEmpty()) {
			throw new IllegalArgumentException("La société partenaire n'a pas de tenant propriétaire valide.");
		}
		ResultatCreation r = annuaire.creerClientPour(cible, demande);
		if (CREE.equals(r.issue())) {
			journal.enregistrer(admin.userId(), JournalActionsMaitre.CLIENT_CREE_POUR_PARTENAIRE, cible, r.societeId());
		}
		return r;
	}

	@Override
	public List<PartenaireResume> listerPartenaires() {
		adminDuMaitre();
		Set<Long> ids = capacites.findByCapacite(TypeCapacite.PARTENAIRE)
			.stream()
			.map(CapaciteSociete::getSocieteId)
			.collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<Long, EtatPartenaire> etatParId = etats.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(EtatPartenaire::getSocieteId, Function.identity()));
		return societes.findAllById(ids).stream().map(s -> {
			Tenant t = s.getProprietaireTenantId() == null ? null : tenants.findById(s.getProprietaireTenantId()).orElse(null);
			EtatPartenaire etat = etatParId.get(s.getId());
			return new PartenaireResume(s.getId(), s.getSiren(), t == null ? null : t.getName(),
					etat == null ? "ACTIF" : etat.getEtat());
		}).toList();
	}

	@Override
	public Set<String> modeleDroits() {
		adminDuMaitre();
		return modele.droits();
	}

	@Override
	public void remplacerModeleDroits(Set<String> droits) {
		IdentiteCourante admin = adminDuMaitre();
		modele.remplacer(droits);
		journal.enregistrer(admin.userId(), JournalActionsMaitre.MODELE_DROITS_MODIFIE, null, null);
	}

}
