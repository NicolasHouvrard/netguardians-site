package com.logicielpourtous.site.features.auth;

import java.util.HashSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
    private final TransactionTemplate nouvelleTransaction;

    public InscriptionSocieteService(UserRepository userRepository,
                                     CustomerRepository customerRepository,
                                     SiteRepository siteRepository,
                                     ContactRepository contactRepository,
                                     PortalUserService portalUserService,
                                     RegistreSocietes registre,
                                     AppartenanceRepository appartenanceRepository,
                                     DemandeSocieteConnueRepository demandeSocieteConnueRepository,
                                     PlatformTransactionManager transactionManager) {
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
            // Levee avant toute ecriture : la transaction reste utilisable pour enregistrer la demande.
            enregistrerDemandeSocieteConnue(e.existante(), d);
            return new ResultatInscription.DejaConnue();
        }

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
        user.setActive(false);
        user.setEmailVerificationToken(jeton);
        userRepository.save(user);

        // 7. Appartenance du nouveau compte a sa societe
        creerAppartenance(user, customer.getId());

        return new ResultatInscription.Creee(user, jeton);
    }

    private void enregistrerDemandeSocieteConnue(SocieteIdentite existante, DemandeClient d) {
        if (existante == null) {
            // Conflit detecte par la base sans fiche connue : rien a rattacher (cas theorique ici).
            log.warn("Inscription client : societe deja connue sans ligne de registre, demande non enregistree");
            return;
        }
        String email = d.email() == null ? null : d.email().trim();
        // Pas de doublon : une seule demande non traitee par societe et par e-mail (casse et espaces ignores).
        if (demandeSocieteConnueRepository.existsBySocieteIdentiteIdAndEmailDemandeurIgnoreCaseAndTraiteeFalse(
                existante.getId(), email)) {
            return;
        }
        DemandeSocieteConnue demande = new DemandeSocieteConnue();
        demande.setSocieteIdentiteId(existante.getId());
        demande.setEmailDemandeur(email);
        demande.setNomDemandeur(d.nomComplet());
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
