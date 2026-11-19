package com.logicielpourtous.site.features.clients;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.InscriptionSocieteService;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.EmailDejaUtiliseException;
import com.logicielpourtous.site.features.identite.EmailsUtilises;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteDejaConnueException;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;

/**
 * Annuaire clients offert aux plugins. Accès aux données seulement ({@code clientsDuPartenaire}, {@code fiche}) :
 * l'autorisation passe par {@link #autorise}, qui délègue sans raccourci à {@link DroitsService}.
 */
@Component
class AnnuaireClientsContratImpl implements AnnuaireClientsContrat {

	private static final String CREE = "CREE";

	private static final String DEJA_CONNUE = "DEJA_CONNUE";

	private static final String EMAIL_DEJA_UTILISE = "EMAIL_DEJA_UTILISE";

	private final LienClientRepository liens;

	private final CapaciteSocieteRepository capacites;

	private final SocieteIdentiteRepository societes;

	private final DroitsService droits;

	private final RegistreSocietes registre;

	private final PartenaireReferentService referents;

	private final ManagersService managers;

	private final UserRepository users;

	private final RoleRepository roles;

	private final TenantRepository tenants;

	private final PasswordEncoder passwordEncoder;

	private final EmailsUtilises emailsUtilises;

	private final InscriptionSocieteService inscription;

	private final IdentiteContrat identite;

	private final AccesEspaceClientService acces;

	private final AppartenanceRepository appartenances;

	AnnuaireClientsContratImpl(LienClientRepository liens, CapaciteSocieteRepository capacites,
			SocieteIdentiteRepository societes, DroitsService droits, RegistreSocietes registre,
			PartenaireReferentService referents, ManagersService managers, UserRepository users, RoleRepository roles,
			TenantRepository tenants, PasswordEncoder passwordEncoder, EmailsUtilises emailsUtilises,
			InscriptionSocieteService inscription, IdentiteContrat identite, AccesEspaceClientService acces,
			AppartenanceRepository appartenances) {
		this.liens = liens;
		this.capacites = capacites;
		this.societes = societes;
		this.droits = droits;
		this.registre = registre;
		this.referents = referents;
		this.managers = managers;
		this.users = users;
		this.roles = roles;
		this.tenants = tenants;
		this.passwordEncoder = passwordEncoder;
		this.emailsUtilises = emailsUtilises;
		this.inscription = inscription;
		this.identite = identite;
		this.acces = acces;
		this.appartenances = appartenances;
	}

	/**
	 * Creation en une seule transaction : toute erreur (hors issues metier) annule societe, lien, utilisateur et
	 * appartenance. Les issues {@code DEJA_CONNUE} et {@code EMAIL_DEJA_UTILISE} sont des retours normaux : la
	 * premiere valide la seule demande d'acces enregistree, la seconde n'ecrit rien.
	 */
	@Override
	@Transactional
	public ResultatCreation creerClient(CreationClient demande) {
		if (demande == null) {
			throw new IllegalArgumentException("La demande doit être renseignée.");
		}
		Long partenaire = demande.partenaireSocieteId();
		if (partenaire == null) {
			throw new IllegalArgumentException("La société partenaire doit être renseignée.");
		}
		String email = demande.email() == null ? "" : demande.email().strip().toLowerCase(Locale.ROOT);
		if (email.isEmpty() || email.length() < 3 || email.indexOf('@') < 1 || email.indexOf('@') == email.length() - 1
				|| email.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalArgumentException("L'adresse e-mail est invalide.");
		}
		String nom = demande.nom() == null ? "" : demande.nom().strip();
		if (nom.isEmpty()) {
			throw new IllegalArgumentException("Le nom doit être renseigné.");
		}
		SocieteIdentite societePartenaire = societes.findById(partenaire)
			.filter(s -> capacites.existsBySocieteIdAndCapacite(s.getId(), TypeCapacite.PARTENAIRE))
			.orElseThrow(() -> new IllegalArgumentException("Société partenaire inconnue ou sans capacité partenaire."));
		Long proprietaireTenantId = societePartenaire.getProprietaireTenantId();

		SocieteIdentite societe;
		if (demande.particulier()) {
			if (demande.siren() != null || demande.tva() != null) {
				throw new IllegalArgumentException("Un particulier n'a ni SIREN ni TVA.");
			}
			try {
				societe = registre.enregistrerParticulier(email, proprietaireTenantId);
			}
			catch (EmailDejaUtiliseException e) {
				return new ResultatCreation(EMAIL_DEJA_UTILISE, null);
			}
		}
		else {
			IdentiteSociete identite = identite(demande);
			if (identite.etrangere()) {
				throw new IllegalArgumentException("Un client doit être une société française.");
			}
			// Société déjà connue : demande d'accès pour son manager, sans lien ni utilisateur.
			if (inscription.refuserSiSocieteConnue(identite, email, nom)) {
				return new ResultatCreation(DEJA_CONNUE, null);
			}
			if (emailsUtilises.estUtilise(email)) {
				return new ResultatCreation(EMAIL_DEJA_UTILISE, null);
			}
			try {
				societe = registre.enregistrer(TypeSociete.CLIENT, identite, proprietaireTenantId);
			}
			catch (SocieteDejaConnueException e) {
				// Course avec une autre création : avant toute écriture, la transaction reste utilisable.
				inscription.refuserSiSocieteConnue(identite, email, nom);
				return new ResultatCreation(DEJA_CONNUE, null);
			}
		}
		referents.rattacher(societe.getId(), partenaire, "PARTENAIRE");
		User premier = creerUtilisateur(email, nom, proprietaireTenantId);
		managers.ajouterUtilisateur(premier.getId(), societe.getId(), Set.of());
		return new ResultatCreation(CREE, societe.getId());
	}

	private static IdentiteSociete identite(CreationClient demande) {
		String siren = NumerosSociete.chiffres(demande.siren());
		String tva = NumerosSociete.tva(demande.tva());
		if (tva == null && NumerosSociete.sirenValide(siren)) {
			tva = NumerosSociete.tvaFrancaise(siren);
		}
		return IdentiteSociete.depuis(siren, null, tva);
	}

	/** Compte du premier manager : mot de passe aléatoire inutilisable, à définir via « mot de passe oublié ». */
	private User creerUtilisateur(String email, String nom, Long proprietaireTenantId) {
		Tenant tenant = proprietaireTenantId != null ? tenants.findById(proprietaireTenantId).orElse(null) : null;
		if (tenant == null) {
			tenant = tenants.findAll().stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("Aucun tenant trouvé."));
		}
		User user = new User();
		user.setEmail(email);
		user.setFullName(nom);
		user.setPassword(passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()));
		user.setTenant(tenant);
		user.setMustChangePassword(true);
		user.setActive(true);
		roles.findByName(ManagersService.ROLE_MANAGER).ifPresent(r -> user.getRoles().add(r));
		return users.saveAndFlush(user);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ClientResume> clientsDuPartenaire(Long partenaireSocieteId) {
		if (partenaireSocieteId == null) {
			throw new IllegalArgumentException("La société partenaire doit être renseignée.");
		}
		List<LienClient> lies = liens.findByPartenaireSocieteId(partenaireSocieteId);
		if (lies.isEmpty()) {
			return List.of();
		}
		Set<Long> ids = lies.stream().map(LienClient::getClientSocieteId).collect(Collectors.toSet());
		Set<Long> clients = capacites.findBySocieteIdIn(ids)
			.stream()
			.filter(c -> c.getCapacite() == TypeCapacite.CLIENT)
			.map(CapaciteSociete::getSocieteId)
			.collect(Collectors.toSet());
		Map<Long, SocieteIdentite> parId = societes.findAllById(clients)
			.stream()
			.collect(Collectors.toMap(SocieteIdentite::getId, Function.identity()));
		Map<Long, String> noms = noms(parId.keySet());
		return lies.stream()
			.filter(l -> parId.containsKey(l.getClientSocieteId()))
			.map(l -> resume(parId.get(l.getClientSocieteId()), l, noms.get(l.getClientSocieteId())))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FicheClient> fiche(Long clientSocieteId) {
		if (clientSocieteId == null) {
			throw new IllegalArgumentException("La société cliente doit être renseignée.");
		}
		Optional<LienClient> lien = liens.findByClientSocieteId(clientSocieteId);
		if (lien.isEmpty()) {
			return Optional.empty();
		}
		List<String> noms = capacites.findBySocieteId(clientSocieteId)
			.stream()
			.map(c -> c.getCapacite().name())
			.sorted()
			.toList();
		if (!noms.contains(TypeCapacite.CLIENT.name())) {
			return Optional.empty();
		}
		return societes.findById(clientSocieteId)
			.map(s -> new FicheClient(resume(s, lien.get(), noms(Set.of(s.getId())).get(s.getId())),
					lien.get().getPartenaireSocieteId(), noms));
	}

	@Override
	public boolean autorise(Long userId, Long societeId, String droit) {
		return droits.autorise(userId, societeId, droit);
	}

	@Override
	public PorteeAcces entrerEspaceClient(Long userId, Long clientSocieteId) {
		IdentiteCourante moi;
		try {
			moi = identite.courante();
		}
		catch (IllegalStateException e) {
			throw new SecurityException("Accès refusé.");
		}
		// Échec fermé : l'utilisateur est celui de la session et la société du partenaire vient de la session, jamais
		// de l'appelant.
		if (moi == null || userId == null || !userId.equals(moi.userId()) || moi.societeId() == null) {
			throw new SecurityException("Accès refusé.");
		}
		try {
			return acces.entrer(userId, moi.societeId(), clientSocieteId);
		}
		catch (AccesRefuseException e) {
			throw new SecurityException("Accès refusé.");
		}
	}

	private static ClientResume resume(SocieteIdentite s, LienClient lien, String nom) {
		return new ClientResume(s.getId(), s.getSiren(), s.getTva(), lien.getOrigine(), nom);
	}

	/**
	 * Nom affiché de chaque société : le registre ne stocke aucun nom de société, on prend donc le nom complet du
	 * premier membre (plus petit id d'appartenance), à défaut son e-mail (un particulier est ainsi la personne). Absent
	 * de la map si la société n'a aucun membre.
	 */
	private Map<Long, String> noms(java.util.Collection<Long> societeIds) {
		if (societeIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Long> premierMembre = new java.util.HashMap<>();
		for (Appartenance a : appartenances.findBySocieteIdInOrderById(societeIds)) {
			premierMembre.putIfAbsent(a.getSocieteId(), a.getUserId());
		}
		Map<Long, User> membres = users.findAllById(premierMembre.values())
			.stream()
			.collect(Collectors.toMap(User::getId, Function.identity()));
		Map<Long, String> noms = new java.util.HashMap<>();
		premierMembre.forEach((societe, userId) -> {
			User u = membres.get(userId);
			if (u != null) {
				String nom = u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName().strip() : u.getEmail();
				if (nom != null && !nom.isBlank()) {
					noms.put(societe, nom);
				}
			}
		});
		return noms;
	}

}
