package com.logicielpourtous.site.features.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.Site;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.identite.EnregistrementConcurrentException;
import com.logicielpourtous.site.features.identite.IdentiteInvalideException;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteDejaConnueException;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Inscription autonome d'une societe sur la plateforme : controle de l'identite (SIREN/SIRET, TVA),
 * enregistrement au registre unique, puis creation de la fiche et du premier compte.
 *
 * <p><b>Transactions.</b> Toute l'inscription se fait dans UNE transaction : si une etape echoue apres
 * l'ecriture au registre (fiche, compte...), rien ne subsiste. Si la base refuse l'ecriture au registre
 * ({@link EnregistrementConcurrentException} : une inscription simultanee de la meme societe vient d'etre
 * validee), cette transaction est deja avortee par PostgreSQL et ne peut pas etre poursuivie : l'inscription
 * est donc rejouee UNE fois, dans une NOUVELLE transaction (la societe y est alors trouvee « deja connue »).
 * C'est pourquoi {@link #inscrireClient} n'est pas annotee {@code @Transactional} : elle pilote elle-meme ses
 * transactions avec un {@link TransactionTemplate} en {@code REQUIRES_NEW}, une methode transactionnelle ne
 * pouvant pas se rejouer dans sa propre transaction avortee.
 *
 * <p><b>Anti-enumeration.</b> Une societe deja inscrite et une societe suivie par un partenaire donnent la
 * MEME issue ({@link ResultatInscription.DejaConnue}), par le meme chemin de code ; rien n'est cree hormis la
 * {@link DemandeSocieteConnue} destinee au proprietaire.
 *
 * <p><b>Inscriptions jamais verifiees.</b> Une societe inscrite seule dont aucun compte n'a verifie son e-mail
 * dans le delai {@code app.inscription.delai-verification-heures} (72 h par defaut) n'empeche pas une nouvelle
 * inscription : {@link #liberer} la supprime (sous conditions strictes) et l'inscription continue comme pour une
 * societe libre. {@link NettoyageInscriptionsNonVerifiees} fait de meme chaque nuit.
 *
 * <p><b>Double envoi du formulaire</b> (connu, accepte) : le 2e envoi peut finir sur « Cet email est déjà
 * utilisé. » alors que l'inscription du 1er a reussi.
 *
 * <p><b>Donnees non fiables</b> : {@code nom_demandeur} et {@code email_demandeur} des demandes viennent de
 * visiteurs anonymes ; tout ecran qui les affiche doit les echapper ({@code th:text} uniquement, jamais
 * {@code th:utext}).
 */
@Service
public class InscriptionSocieteService {

    private static final Logger log = LoggerFactory.getLogger(InscriptionSocieteService.class);

    /** Message du refus d'un e-mail deja utilise (inchange par rapport a l'ancienne inscription). */
    public static final String EMAIL_DEJA_UTILISE = "Cet email est déjà utilisé.";

    /** Type de fiche renseigne au registre pour une societe cliente. */
    static final String FICHE_CUSTOMERS = "customers";

    /** Origine au registre d'un client inscrit seul : seul cas liberable par {@link #liberer}. */
    static final String ORIGINE_INSCRIPTION_CLIENT = "INSCRIPTION_CLIENT";

    private static final String ROLE_GESTIONNAIRE = "GESTIONNAIRE";

    /** Donnees saisies sur le formulaire d'inscription d'un client. */
    public record DemandeClient(String nomSociete, String nomComplet, String email, String motDePasse,
                                String siren, String siret, String tva) {
    }

    /** Issue d'une inscription. */
    public sealed interface ResultatInscription {

        /** Societe et compte crees ; le compte est inactif jusqu'a la verification de l'e-mail. */
        record Creee(User utilisateur, String jetonVerification) implements ResultatInscription {
        }

        /** Societe deja connue de la plateforme : rien n'est cree, une demande est transmise a son responsable. */
        record DejaConnue() implements ResultatInscription {
        }
    }

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final SiteRepository siteRepository;
    private final ContactRepository contactRepository;
    private final PortalUserService portalUserService;
    private final RegistreSocietes registre;
    private final AppartenanceRepository appartenanceRepository;
    private final DemandeSocieteConnueRepository demandeSocieteConnueRepository;
    private final JdbcTemplate jdbc;
    private final long delaiVerificationHeures;
    private final TransactionTemplate nouvelleTransaction;
    private final TransactionTemplate transactionRequise;

    public InscriptionSocieteService(UserRepository userRepository,
                                     CustomerRepository customerRepository,
                                     SiteRepository siteRepository,
                                     ContactRepository contactRepository,
                                     PortalUserService portalUserService,
                                     RegistreSocietes registre,
                                     AppartenanceRepository appartenanceRepository,
                                     DemandeSocieteConnueRepository demandeSocieteConnueRepository,
                                     PlatformTransactionManager transactionManager,
                                     JdbcTemplate jdbc,
                                     @Value("${app.inscription.delai-verification-heures:72}") long delaiVerificationHeures) {
        if (delaiVerificationHeures < 1) {
            throw new IllegalStateException(
                    "app.inscription.delai-verification-heures doit valoir au moins 1 (heure) : " + delaiVerificationHeures);
        }
        this.jdbc = jdbc;
        this.delaiVerificationHeures = delaiVerificationHeures;
        // Transaction en cours (inscription), ou transaction propre s'il n'y en a pas (nettoyage).
        this.transactionRequise = new TransactionTemplate(transactionManager);
        this.transactionRequise.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.siteRepository = siteRepository;
        this.contactRepository = contactRepository;
        this.portalUserService = portalUserService;
        this.registre = registre;
        this.appartenanceRepository = appartenanceRepository;
        this.demandeSocieteConnueRepository = demandeSocieteConnueRepository;
        this.nouvelleTransaction = new TransactionTemplate(transactionManager);
        this.nouvelleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Inscrit un client qui s'enregistre seul. La societe appartient au Maitre (proprietaire {@code null}).
     *
     * @throws IdentiteInvalideException si un numero est invalide, incoherent, ou si la TVA est etrangere
     * @throws IllegalArgumentException  ({@value #EMAIL_DEJA_UTILISE}) si l'e-mail a deja un compte
     */
    public ResultatInscription inscrireClient(DemandeClient d) {
        try {
            return nouvelleTransaction.execute(statut -> tenterInscriptionClient(d));
        } catch (EnregistrementConcurrentException premierConflit) {
            // Transaction avortee par la base : on rejoue une fois dans une transaction neuve.
            try {
                return nouvelleTransaction.execute(statut -> tenterInscriptionClient(d));
            } catch (EnregistrementConcurrentException secondConflit) {
                // Ne devrait pas arriver (la societe gagnante est validee) : on reste sur l'issue neutre.
                log.warn("Inscription client : second conflit d'enregistrement au registre, issue neutre renvoyee");
                return new ResultatInscription.DejaConnue();
            }
        }
    }

    /** Une tentative complete, dans la transaction courante. */
    private ResultatInscription tenterInscriptionClient(DemandeClient d) {
        // 1. Controles, avant toute ecriture
        IdentiteSociete identite = IdentiteSociete.depuis(d.siren(), d.siret(), d.tva());
        if (identite.etrangere()) {
            throw new IdentiteInvalideException("tva",
                    "Seule une société française peut s'inscrire : saisissez un numéro de TVA français.");
        }
        if (userRepository.findByEmail(d.email()).isPresent()) {
            throw new IllegalArgumentException(EMAIL_DEJA_UTILISE);
        }

        // 2. Registre unique (le Maitre est proprietaire d'un client inscrit seul)
        SocieteIdentite societe;
        try {
            societe = registre.enregistrer(TypeSociete.CLIENT, identite, null);
        } catch (SocieteDejaConnueException e) {
            // Levee avant toute ecriture : la transaction reste utilisable.
            // Societe squattee par une inscription jamais verifiee et perimee : liberee, puis inscription normale.
            // Sinon, la ligne vue peut avoir ete liberee entre-temps (nettoyage concurrent) : on la reverrouille
            // avant d'y rattacher la demande ; si elle a disparu, la societe est libre.
            SocieteIdentite connue = e.existante();
            boolean libre = connue != null && (liberer(connue) || !verrouillerSiPresente(connue.getId()));
            SocieteIdentite liberee = null;
            if (libre) {
                try {
                    liberee = registre.enregistrer(TypeSociete.CLIENT, identite, null);
                } catch (SocieteDejaConnueException encore) {
                    // Reprise entre-temps par une autre inscription : ligne lue dans cette transaction.
                    connue = encore.existante();
                }
            }
            if (liberee == null) {
                enregistrerDemandeSocieteConnue(connue, d);
                return new ResultatInscription.DejaConnue();
            }
            societe = liberee;
        }
        societe.setOrigine(ORIGINE_INSCRIPTION_CLIENT);

        // 3. Client
        Customer customer = new Customer();
        customer.setCompanyName(d.nomSociete());
        customer.setEmail(d.email());
        customer.setContactName(d.nomComplet());
        customer.setSiren(identite.siren());
        customer.setSiret(NumerosSociete.chiffres(d.siret()));
        customer.setVatNumber(identite.tva());
        customerRepository.save(customer);

        // Lien registre -> fiche (entite geree : ecrite au commit)
        societe.setFicheType(FICHE_CUSTOMERS);
        societe.setFicheId(customer.getId());

        // 4. Site "Siège" par defaut
        Site siege = new Site();
        siege.setName("Siège");
        siege.setCustomer(customer);
        siteRepository.save(siege);

        // 5. Contact principal
        Contact contact = new Contact();
        String[] parts = (d.nomComplet() != null ? d.nomComplet().trim() : "").split(" ", 2);
        contact.setFirstName(parts[0]);
        contact.setLastName(parts.length > 1 ? parts[1] : "");
        contact.setEmail(d.email());
        contact.setCustomer(customer);
        contact.setSite(siege);
        contactRepository.save(contact);

        // 6. Compte portail GESTIONNAIRE, inactif jusqu'a la verification de l'e-mail
        User user = portalUserService.createExplicit(d.email(), d.nomComplet(), d.motDePasse(),
                customer.getId(), ROLE_GESTIONNAIRE).user();
        contact.setUserId(user.getId());
        contactRepository.save(contact);

        String jeton = UUID.randomUUID().toString();
        // Le compte vient d'etre enregistre actif (donc marque verifie) : il nait en fait en attente de verification.
        user.creeNonVerifie();
        user.setEmailVerificationToken(jeton);
        userRepository.save(user);

        // 7. Appartenance du nouveau compte a sa societe
        creerAppartenance(user, customer.getId());

        return new ResultatInscription.Creee(user, jeton);
    }

    /**
     * Demande d'inscription d'un partenaire : controle l'identite saisie, sans rien ecrire au registre
     * (une demande en attente ne reserve pas la societe ; le registre est ecrit a l'approbation).
     *
     * @throws IdentiteInvalideException si un numero est invalide, incoherent, ou si la TVA est etrangere
     */
    public IdentiteSociete controlerIdentitePartenaire(String siren, String siret, String tva) {
        IdentiteSociete identite = IdentiteSociete.depuis(siren, siret, tva);
        if (identite.etrangere()) {
            throw new IdentiteInvalideException("tva",
                    "Seule une société française peut s'inscrire : saisissez un numéro de TVA français.");
        }
        return identite;
    }

    /**
     * Si la societe est deja au registre, enregistre (sans doublon) la demande destinee a son responsable et
     * renvoie {@code true} ; l'appelant donne alors la MEME issue neutre que pour un client. Sinon {@code false}.
     */
    @Transactional
    public boolean refuserSiSocieteConnue(IdentiteSociete identite, String email, String nomDemandeur) {
        Optional<SocieteIdentite> existante = registre.trouver(identite);
        if (existante.isEmpty()) {
            return false;
        }
        enregistrerDemandeSocieteConnue(existante.get(), email, nomDemandeur);
        return true;
    }

    // ── Liberation des inscriptions jamais verifiees ─────────────────────

    /** Limite d'anciennete : une inscription creee AVANT cet instant peut etre liberee. */
    Instant limiteVerification() {
        return Instant.now().minus(Duration.ofHours(delaiVerificationHeures));
    }

    /**
     * Libere une societe squattee par une inscription jamais verifiee : supprime, dans UNE transaction, la ligne
     * de registre, ses demandes « societe connue », le client, ses sites et contacts, ses comptes (roles et
     * appartenances compris). Renvoie {@code true} si tout a ete supprime, {@code false} sinon (rien n'est
     * alors modifie).
     *
     * <p>Ne libere QUE si toutes ces conditions sont reunies, relues en base sous verrou :
     * <ul>
     *   <li>ligne de registre d'origine {@value #ORIGINE_INSCRIPTION_CLIENT} (jamais une ligne reprise de
     *       l'existant ni un partenaire), de type client, appartenant au Maitre, liee a une fiche
     *       {@code customers}, creee depuis plus de {@code app.inscription.delai-verification-heures} ;</li>
     *   <li>client sans tenant (non rattache a un partenaire) ;</li>
     *   <li>au moins un compte, et TOUS ses comptes jamais verifies ({@code email_verified_at} nul : un compte
     *       verifie puis desactive peut avoir recu un nouveau jeton), inactifs, avec un jeton en attente, crees
     *       depuis plus du delai, sans appartenance a une autre societe ;</li>
     *   <li>aucune autre donnee ne reference le client, ses sites, contacts, comptes ou sa ligne de registre :
     *       toutes les cles etrangeres de la base ET toutes les colonnes d'identifiant usuelles
     *       ({@code customer_id}, {@code user_id}, {@code *_user_id}, {@code site_id}...) sont verifiees
     *       (demandes, devis, factures, commandes, abonnements, tenants...). Seul le journal d'audit est ignore.</li>
     * </ul>
     *
     * <p>Rejoint la transaction en cours ({@code REQUIRED}), sinon ouvre la sienne. Un refus ({@code false})
     * n'ecrit rien et laisse la transaction utilisable. Une erreur INATTENDUE (base) remonte telle quelle et
     * annule toute la transaction : pendant une inscription, l'inscription echoue sans rien laisser (le
     * gestionnaire de transactions JPA n'autorise pas les points de sauvegarde, qui permettraient d'isoler
     * la liberation).
     */
    public boolean liberer(SocieteIdentite s) {
        if (s == null || s.getId() == null) {
            return false;
        }
        return Boolean.TRUE.equals(transactionRequise.execute(statut -> libererSousVerrou(s.getId())));
    }

    /**
     * Verrou partage sur une ligne du registre (une liberation concurrente attend alors la fin de cette
     * transaction) ; {@code false} si la ligne n'existe plus.
     */
    private boolean verrouillerSiPresente(Long registreId) {
        return !jdbc.queryForList("SELECT id FROM societes_identites WHERE id = ? FOR SHARE", Long.class, registreId)
                .isEmpty();
    }

    private record LigneRegistre(String type, Long proprietaire, String ficheType, Long ficheId, String origine,
                                 OffsetDateTime creeA) {
    }

    private record Compte(long id, boolean actif, String jeton, LocalDateTime creeLe, LocalDateTime verifieLe) {
    }

    private boolean libererSousVerrou(long registreId) {
        Instant limite = limiteVerification();

        // 1. Ligne de registre, verrouillee
        List<LigneRegistre> lignes = jdbc.query(
                "SELECT type, proprietaire_tenant_id, fiche_type, fiche_id, origine, cree_a "
                        + "FROM societes_identites WHERE id = ? FOR UPDATE",
                (rs, i) -> new LigneRegistre(rs.getString(1), rs.getObject(2, Long.class), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getString(5), rs.getObject(6, OffsetDateTime.class)),
                registreId);
        if (lignes.isEmpty()) {
            return false;
        }
        LigneRegistre ligne = lignes.get(0);
        if (!ORIGINE_INSCRIPTION_CLIENT.equals(ligne.origine())
                || !TypeSociete.CLIENT.name().equals(ligne.type())
                || ligne.proprietaire() != null
                || !FICHE_CUSTOMERS.equals(ligne.ficheType())
                || ligne.ficheId() == null
                || ligne.creeA() == null
                || !ligne.creeA().toInstant().isBefore(limite)) {
            return false;
        }
        long customerId = ligne.ficheId();

        // 2. Client, verrouille : il doit exister et ne pas etre rattache a un partenaire
        List<Long> tenantsClient = jdbc.query("SELECT tenant_id FROM customers WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getObject(1, Long.class), customerId);
        if (tenantsClient.size() != 1 || tenantsClient.get(0) != null) {
            return false;
        }

        // 3. Comptes, verrouilles : tous inactifs, en attente de verification, anciens
        LocalDateTime limiteLocale = LocalDateTime.ofInstant(limite, ZoneId.systemDefault());
        List<Compte> comptes = jdbc.query(
                "SELECT id, active, email_verification_token, created_at, email_verified_at FROM users "
                        + "WHERE customer_id = ? ORDER BY id FOR UPDATE",
                (rs, i) -> new Compte(rs.getLong(1), rs.getBoolean(2), rs.getString(3),
                        rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class)),
                customerId);
        if (comptes.isEmpty()) {
            return false;
        }
        for (Compte c : comptes) {
            // Un compte verifie un jour (meme desactive depuis, meme avec un nouveau jeton) n'est jamais libere.
            if (c.verifieLe() != null || c.actif() || c.jeton() == null || c.jeton().isBlank()
                    || c.creeLe() == null || !c.creeLe().isBefore(limiteLocale)) {
                return false;
            }
        }
        List<Long> users = comptes.stream().map(Compte::id).toList();

        // 4. Rattachements propres a l'inscription
        List<Long> sites = jdbc.queryForList("SELECT id FROM sites WHERE customer_id = ?", Long.class, customerId);
        List<Long> contacts = jdbc.queryForList("SELECT id FROM contacts WHERE customer_id = ?", Long.class,
                customerId);
        List<Map<String, Object>> appartenancesComptes = jdbc.queryForList(
                "SELECT id, customer_id FROM appartenances WHERE " + dans("user_id", users));
        List<Long> appartenances = new ArrayList<>();
        for (Map<String, Object> a : appartenancesComptes) {
            Object fiche = a.get("customer_id");
            if (!(fiche instanceof Number n) || n.longValue() != customerId) {
                // Le compte appartient aussi a une autre societe
                return false;
            }
            appartenances.add(((Number) a.get("id")).longValue());
        }
        Long autresLignes = jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites "
                + "WHERE fiche_type = ? AND fiche_id = ? AND id <> ?", Long.class, FICHE_CUSTOMERS, customerId, registreId);
        if (autresLignes != null && autresLignes > 0) {
            return false;
        }

        // 5. Aucune autre donnee ne doit referencer ce qui va etre supprime
        Map<String, List<Long>> cibles = Map.of(
                "customers", List.of(customerId),
                "users", users,
                "sites", sites,
                "contacts", contacts,
                "societes_identites", List.of(registreId));
        Map<String, String> supprimees = Map.of(
                "customers", dans("id", List.of(customerId)),
                "societes_identites", dans("id", List.of(registreId)),
                "demandes_societe_connue", dans("societe_identite_id", List.of(registreId)),
                "sites", dans("id", sites),
                "contacts", dans("id", contacts),
                "users", dans("id", users),
                "users_roles", dans("user_id", users),
                "appartenances", dans("id", appartenances),
                "appartenances_roles", dans("appartenance_id", appartenances));
        for (Reference r : referencesPossibles()) {
            List<Long> ids = cibles.get(r.cible());
            if (ids == null || ids.isEmpty() || TABLES_IGNOREES.contains(r.table())) {
                continue;
            }
            String sql = "SELECT EXISTS (SELECT 1 FROM public." + identifiant(r.table())
                    + " WHERE " + dans(identifiant(r.colonne()), ids)
                    + " AND NOT COALESCE(" + supprimees.getOrDefault(r.table(), "FALSE") + ", FALSE))";
            if (Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class))) {
                log.info("Societe du registre #{} non liberee : referencee par {}.{}", registreId, r.table(),
                        r.colonne());
                return false;
            }
        }

        // 6. Suppression, des feuilles vers la racine
        jdbc.update("DELETE FROM appartenances_roles WHERE " + dans("appartenance_id", appartenances));
        jdbc.update("DELETE FROM appartenances WHERE " + dans("id", appartenances));
        jdbc.update("DELETE FROM users_roles WHERE " + dans("user_id", users));
        jdbc.update("DELETE FROM contacts WHERE " + dans("id", contacts));
        jdbc.update("DELETE FROM users WHERE " + dans("id", users));
        jdbc.update("DELETE FROM sites WHERE " + dans("id", sites));
        jdbc.update("DELETE FROM demandes_societe_connue WHERE societe_identite_id = ?", registreId);
        jdbc.update("DELETE FROM societes_identites WHERE id = ?", registreId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        log.info("Societe du registre #{} liberee : inscription jamais verifiee (client #{}, {} compte(s)) supprimee",
                registreId, customerId, users.size());
        return true;
    }

    /** Colonne pouvant designer une ligne supprimee par une liberation. */
    private record Reference(String table, String colonne, String cible) {
    }

    /** Journal d'audit : trace historique (connexions, pages vues), ne rattache rien ; laisse tel quel. */
    private static final Set<String> TABLES_IGNOREES = Set.of("audit_logs");

    /**
     * Toutes les colonnes du schema {@code public} pouvant designer une ligne supprimee : les cles etrangeres
     * declarees vers ces tables, plus les colonnes d'identifiant usuelles sans contrainte (nombreuses dans le
     * schema historique). Relu a chaque appel : une table ajoutee plus tard est couverte d'office.
     */
    private List<Reference> referencesPossibles() {
        List<Reference> references = new ArrayList<>(jdbc.query(
                "SELECT cl.relname, att.attname, ref.relname "
                        + "FROM pg_constraint c "
                        + "JOIN pg_class cl ON cl.oid = c.conrelid "
                        + "JOIN pg_namespace n ON n.oid = cl.relnamespace "
                        + "JOIN pg_class ref ON ref.oid = c.confrelid "
                        + "JOIN pg_namespace rn ON rn.oid = ref.relnamespace "
                        + "JOIN pg_attribute att ON att.attrelid = c.conrelid AND att.attnum = ANY (c.conkey) "
                        + "WHERE c.contype = 'f' AND n.nspname = 'public' AND rn.nspname = 'public' "
                        + "AND ref.relname IN ('customers', 'users', 'sites', 'contacts', 'societes_identites')",
                (rs, i) -> new Reference(rs.getString(1), rs.getString(2), rs.getString(3))));
        references.addAll(jdbc.query(
                "SELECT c.table_name, c.column_name FROM information_schema.columns c "
                        + "JOIN information_schema.tables t ON t.table_schema = c.table_schema "
                        + "AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE' "
                        + "WHERE c.table_schema = 'public' AND c.data_type IN ('bigint', 'integer') "
                        + "AND (c.column_name IN ('customer_id', 'customerid', 'client_id', 'supplier_id', 'vendor_id', "
                        + "'user_id', 'userid', 'author_id', 'created_by_id', 'assigned_to_id', 'technician_id', "
                        + "'commercial_id', 'assigned_commercial_id', "
                        + "'site_id', 'contact_id', 'societe_identite_id') "
                        + "OR c.column_name LIKE '%\\_user\\_id')",
                (rs, i) -> {
                    String colonne = rs.getString(2);
                    return new Reference(rs.getString(1), colonne, cibleParNom(colonne));
                }));
        return references;
    }

    private static String cibleParNom(String colonne) {
        return switch (colonne) {
            case "customer_id", "customerid", "client_id", "supplier_id", "vendor_id" -> "customers";
            case "site_id" -> "sites";
            case "contact_id" -> "contacts";
            case "societe_identite_id" -> "societes_identites";
            default -> "users";
        };
    }

    /** Identifiant SQL entre guillemets (noms lus dans le catalogue). */
    private static String identifiant(String nom) {
        return "\"" + nom.replace("\"", "\"\"") + "\"";
    }

    /** {@code colonne IN (ids)} ; {@code FALSE} si la liste est vide. Les ids sont des {@code long} lus en base. */
    private static String dans(String colonne, List<Long> ids) {
        if (ids.isEmpty()) {
            return "FALSE";
        }
        return colonne + " IN (" + ids.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")";
    }

    private void enregistrerDemandeSocieteConnue(SocieteIdentite existante, DemandeClient d) {
        enregistrerDemandeSocieteConnue(existante, d.email(), d.nomComplet());
    }

    private void enregistrerDemandeSocieteConnue(SocieteIdentite existante, String emailDemandeur, String nomDemandeur) {
        if (existante == null) {
            // Conflit detecte par la base sans fiche connue : rien a rattacher (cas theorique ici).
            log.warn("Inscription client : societe deja connue sans ligne de registre, demande non enregistree");
            return;
        }
        String email = emailDemandeur == null ? null : emailDemandeur.trim();
        // Pas de doublon : une seule demande non traitee par societe et par e-mail (casse et espaces ignores).
        if (demandeSocieteConnueRepository.existsBySocieteIdentiteIdAndEmailDemandeurIgnoreCaseAndTraiteeFalse(
                existante.getId(), email)) {
            return;
        }
        DemandeSocieteConnue demande = new DemandeSocieteConnue();
        demande.setSocieteIdentiteId(existante.getId());
        demande.setEmailDemandeur(email);
        demande.setNomDemandeur(nomDemandeur);
        demandeSocieteConnueRepository.save(demande);
    }

    private void creerAppartenance(User user, Long customerId) {
        if (user.getTenant() == null) {
            // Prudence : sans tenant, pas d'appartenance plutot qu'un echec de l'inscription.
            log.warn("Inscription client : compte {} sans tenant, appartenance non creee", user.getId());
            return;
        }
        Appartenance appartenance = new Appartenance();
        appartenance.setUserId(user.getId());
        appartenance.setTenant(user.getTenant());
        appartenance.setCustomerId(customerId);
        appartenance.setRoles(new HashSet<>(user.getRoles()));
        appartenanceRepository.save(appartenance);
    }
}
