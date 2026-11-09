package com.logicielpourtous.site.features.registration;

import com.logicielpourtous.site.features.customers.*;
import com.logicielpourtous.site.features.identite.EnregistrementConcurrentException;
import com.logicielpourtous.site.features.identite.IdentiteInvalideException;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteDejaConnueException;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequestMapping("/intra/registrations")
public class SubscriptionRequestController {

    private final SubscriptionRequestRepository repository;
    private final CustomerRepository            customerRepository;
    private final SiteRepository                siteRepository;
    private final ContactRepository             contactRepository;
    private final UserRepository                userRepository;
    private final PortalUserService             portalUserService;
    private final RegistreSocietes              registre;
    private final TransactionTemplate           transaction;

    /** Type de fiche renseigne au registre pour la fiche creee a l'approbation. */
    private static final String FICHE_CUSTOMERS = "customers";

    public SubscriptionRequestController(SubscriptionRequestRepository repository,
                                         CustomerRepository customerRepository,
                                         SiteRepository siteRepository,
                                         ContactRepository contactRepository,
                                         UserRepository userRepository,
                                         PortalUserService portalUserService,
                                         RegistreSocietes registre,
                                         PlatformTransactionManager transactionManager) {
        this.repository         = repository;
        this.customerRepository = customerRepository;
        this.siteRepository     = siteRepository;
        this.contactRepository  = contactRepository;
        this.userRepository     = userRepository;
        this.portalUserService  = portalUserService;
        this.registre           = registre;
        this.transaction        = new TransactionTemplate(transactionManager);
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("requests", repository.findAllByOrderByRequestedAtDesc());
        model.addAttribute("pending",  repository.countByStatus(SubscriptionRequest.SubscriptionRequestStatus.PENDING));
        return "intra/registrations/list";
    }

    /**
     * Approuve une demande. Tout (registre des societes, fiche client, site, contact, compte) est ecrit dans
     * UNE transaction : si la societe est entre-temps connue du registre, ou si la base refuse l'ecriture
     * pour cause d'unicite, rien n'est cree et la demande reste en attente.
     *
     * <p>Le pilotage est explicite (pas de {@code @Transactional} sur la methode) : un conflit d'unicite
     * avorte la transaction cote base, l'erreur doit donc etre attrapee APRES son annulation pour pouvoir
     * afficher un message clair a l'administrateur au lieu d'une erreur 500.
     */
    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id,
                          @RequestParam(required = false) String adminNotes,
                          RedirectAttributes ra) {
        try {
            return transaction.execute(statut -> approuver(id, adminNotes, ra));
        } catch (SocieteDejaConnueException e) {
            ra.addFlashAttribute("errorMessage", "Approbation impossible : cette société est déjà enregistrée "
                    + "sur la plateforme (inscrite entre-temps). La demande reste en attente ; "
                    + "refusez-la ou contactez le demandeur. Rien n'a été créé.");
        } catch (EnregistrementConcurrentException e) {
            ra.addFlashAttribute("errorMessage", "Approbation impossible : la société vient d'être enregistrée "
                    + "par une autre inscription simultanée. La demande reste en attente ; "
                    + "réessayez ou refusez-la. Rien n'a été créé.");
        } catch (IdentiteInvalideException e) {
            ra.addFlashAttribute("errorMessage", "Approbation impossible : l'identité de la société est "
                    + "invalide (" + e.getMessage() + "). La demande reste en attente.");
        }
        return "redirect:/intra/registrations";
    }

    private String approuver(Long id, String adminNotes, RedirectAttributes ra) {

        SubscriptionRequest req = repository.findById(id).orElse(null);
        if (req == null || req.getStatus() != SubscriptionRequest.SubscriptionRequestStatus.PENDING) {
            ra.addFlashAttribute("errorMessage", "Demande introuvable ou déjà traitée.");
            return "redirect:/intra/registrations";
        }

        // ── 0. Registre des sociétés, EN PREMIER (avant toute écriture) ──────
        // Une demande ancienne, sans identité, s'approuve comme avant (sans ligne de registre).
        // Si la société est déjà connue, l'exception annule toute la transaction : rien n'est créé.
        SocieteIdentite ligneRegistre = null;
        if (req.getSiren() != null || req.getVatNumber() != null) {
            IdentiteSociete identite = IdentiteSociete.depuis(req.getSiren(), null, req.getVatNumber());
            ligneRegistre = registre.enregistrer(TypeSociete.PARTENAIRE, identite, null);
        } else {
            ra.addFlashAttribute("warningMessage", "Demande sans identité de société (ancienne demande) : "
                    + "aucune ligne n'a été ajoutée au registre des sociétés.");
        }

        // ── 1. Trouver ou créer le Customer ──────────────────────────────────
        boolean isNewCustomer = false;
        Customer customer = customerRepository.findByEmail(req.getEmail()).orElse(null);

        if (customer == null) {
            isNewCustomer = true;
            customer = new Customer();
            customer.setCompanyName(req.getCompanyName());
            customer.setEmail(req.getEmail());
            customer.setContactName(req.getContactName());
            customer.setPhone(req.getPhone());
            customer.setSiren(req.getSiren());
            customer.setVatNumber(req.getVatNumber());
            customerRepository.save(customer);
        }

        // Lien registre -> fiche (entité gérée : écrite au commit, avec le reste)
        if (ligneRegistre != null) {
            ligneRegistre.setFicheType(FICHE_CUSTOMERS);
            ligneRegistre.setFicheId(customer.getId());
        }

        // ── 2. Si nouveau client : créer le site + contact ───────────────────
        if (isNewCustomer) {
            Site siege = new Site();
            siege.setName("Siège");
            siege.setCustomer(customer);
            siteRepository.save(siege);

            Contact contact = new Contact();
            String[] parts = req.getContactName().trim().split(" ", 2);
            contact.setFirstName(parts[0]);
            contact.setLastName(parts.length > 1 ? parts[1] : "");
            contact.setEmail(req.getEmail());
            contact.setPhone(req.getPhone());
            contact.setCustomer(customer);
            contact.setSite(siege);
            contactRepository.save(contact);

            // Créer le compte portail si aucun compte n'existe pour cet email
            if (userRepository.findByEmail(req.getEmail()).isEmpty()) {
                String tempPwd = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                PortalUserService.CreationResult result =
                        portalUserService.createExplicit(req.getEmail(), req.getContactName(),
                                tempPwd, customer.getId(), "GESTIONNAIRE");
                contact.setUserId(result.user().getId());
                contactRepository.save(contact);
                ra.addFlashAttribute("createdEmail",    req.getEmail());
                ra.addFlashAttribute("createdPassword", tempPwd);
            }
        } else {
            // Client existant — créer le compte s'il n'en a pas encore
            if (userRepository.findByEmail(req.getEmail()).isEmpty()) {
                String tempPwd = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                portalUserService.createExplicit(req.getEmail(), req.getContactName(),
                        tempPwd, customer.getId(), "GESTIONNAIRE");
                ra.addFlashAttribute("createdEmail",    req.getEmail());
                ra.addFlashAttribute("createdPassword", tempPwd);
            }
            ra.addFlashAttribute("linkedCompany", customer.getCompanyName());
        }

        // ── 3. Valider la demande ─────────────────────────────────────────────
        req.setStatus(SubscriptionRequest.SubscriptionRequestStatus.APPROVED);
        req.setProcessedAt(LocalDateTime.now());
        if (adminNotes != null && !adminNotes.isBlank()) req.setAdminNotes(adminNotes);
        repository.save(req);

        ra.addFlashAttribute("successMessage",
                "Demande approuvée — compte créé pour " + req.getCompanyName() + ".");
        return "redirect:/intra/registrations";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam(required = false) String adminNotes,
                         RedirectAttributes ra) {
        repository.findById(id).ifPresent(r -> {
            r.setStatus(SubscriptionRequest.SubscriptionRequestStatus.REJECTED);
            r.setProcessedAt(LocalDateTime.now());
            if (adminNotes != null && !adminNotes.isBlank()) r.setAdminNotes(adminNotes);
            repository.save(r);
        });
        ra.addFlashAttribute("successMessage", "Demande refusée.");
        return "redirect:/intra/registrations";
    }
}
