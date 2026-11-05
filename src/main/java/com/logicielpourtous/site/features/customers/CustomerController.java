package com.logicielpourtous.site.features.customers;

import com.logicielpourtous.site.features.ninja.NinjaOneClient;
import com.logicielpourtous.site.features.ninja.NinjaOneConfig;
import com.logicielpourtous.site.features.ninja.NinjaOneConfigRepository;
import com.logicielpourtous.site.features.ninja.NinjaOrganization;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/intra/customers")
public class CustomerController {

    private final CustomerRepository        customerRepository;
    private final RequestRepository         requestRepository;
    private final SubscriptionRepository    subscriptionRepository;
    private final UserRepository            userRepository;
    private final PortalUserService         portalUserService;
    private final PasswordEncoder           passwordEncoder;
    private final ContactRepository         contactRepository;
    private final SiteRepository            siteRepository;
    private final NinjaOneConfigRepository  ninjaConfigRepository;
    private final NinjaOneClient            ninjaOneClient;
    private final com.logicielpourtous.site.features.esn.PartnerEsnRepository esnRepository;
    private final TeamRepository            teamRepository;

    public CustomerController(CustomerRepository customerRepository,
                              RequestRepository requestRepository,
                              SubscriptionRepository subscriptionRepository,
                              UserRepository userRepository,
                              PortalUserService portalUserService,
                              PasswordEncoder passwordEncoder,
                              ContactRepository contactRepository,
                              SiteRepository siteRepository,
                              NinjaOneConfigRepository ninjaConfigRepository,
                              NinjaOneClient ninjaOneClient,
                              com.logicielpourtous.site.features.esn.PartnerEsnRepository esnRepository,
                              TeamRepository teamRepository) {
        this.customerRepository     = customerRepository;
        this.requestRepository      = requestRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository         = userRepository;
        this.portalUserService      = portalUserService;
        this.passwordEncoder        = passwordEncoder;
        this.contactRepository      = contactRepository;
        this.siteRepository         = siteRepository;
        this.ninjaConfigRepository  = ninjaConfigRepository;
        this.ninjaOneClient         = ninjaOneClient;
        this.esnRepository          = esnRepository;
        this.teamRepository         = teamRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String type,
                       Model model) {
        List<Customer> all = (q != null && !q.isBlank())
                ? customerRepository.searchAllContacts(q)
                : customerRepository.findAllContacts();

        List<Customer> filtered = switch (type != null ? type : "") {
            case "prospects" -> all.stream().filter(c ->  c.isProspect() && !c.isCustomer()).toList();
            case "clients"   -> all.stream().filter(Customer::isCustomer).toList();
            case "esn"       -> all.stream().filter(Customer::isEsn).toList();
            default          -> all;
        };

        model.addAttribute("customers", filtered);
        model.addAttribute("prospects", filtered.stream().filter(c -> c.isProspect() && !c.isCustomer()).toList());
        model.addAttribute("clients",   filtered.stream().filter(Customer::isCustomer).toList());
        model.addAttribute("esns",      filtered.stream().filter(c -> c.isEsn() && !c.isCustomer()).toList());
        model.addAttribute("totalAll",  all.size());
        model.addAttribute("totalP",    all.stream().filter(c -> c.isProspect() && !c.isCustomer()).count());
        model.addAttribute("totalC",    all.stream().filter(Customer::isCustomer).count());
        model.addAttribute("totalE",    all.stream().filter(Customer::isEsn).count());
        model.addAttribute("q",    q    != null ? q    : "");
        model.addAttribute("type", type != null ? type : "");
        return "intra/customers/list";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) String email,
                          @RequestParam(required = false) Long ticketId,
                          Model model) {
        model.addAttribute("sources",     ProspectSource.values());
        model.addAttribute("teams",       teamRepository.findAll());
        model.addAttribute("commercials", userRepository.findByRoleNameIn(
                List.of("ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL")));
        model.addAttribute("prefillEmail",    email);
        model.addAttribute("prefillTicketId", ticketId);
        return "intra/customers/new";
    }

    @PostMapping
    public String create(@RequestParam(defaultValue = "client") String type,
                         @RequestParam String companyName,
                         @RequestParam(required = false) String contactName,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String website,
                         @RequestParam(required = false) ProspectSource prospectSource,
                         @RequestParam(required = false) Long teamId,
                         @RequestParam(required = false) Long assignedCommercialId,
                         @RequestParam(required = false) BigDecimal estimatedValue,
                         @RequestParam(required = false) LocalDate expectedCloseDate,
                         @RequestParam(required = false) String prospectNotes,
                         @RequestParam(required = false) Long ticketId,
                         RedirectAttributes ra) {
        boolean isProspect = "prospect".equals(type);
        Customer c = new Customer();
        c.setCompanyName(companyName);
        c.setContactName(contactName);
        c.setEmail(email);
        c.setPhone(phone);
        c.setWebsite(website);
        c.setProspect(isProspect);
        c.setCustomer(!isProspect);
        if (isProspect) {
            c.setProspectStatus(ProspectStatus.NOUVEAU);
            c.setProspectSource(prospectSource);
            c.setAssignedTeamId(teamId);
            c.setAssignedCommercialId(assignedCommercialId);
            c.setEstimatedValue(estimatedValue);
            c.setExpectedCloseDate(expectedCloseDate);
            c.setProspectNotes(prospectNotes);
        }
        customerRepository.save(c);
        String label = isProspect ? "Prospect" : "Client";
        ra.addFlashAttribute("successMessage", label + " « " + companyName + " » créé.");
        if (ticketId != null) {
            requestRepository.findById(ticketId).ifPresent(req -> {
                req.setCustomer(c);
                req.registerChange();
                requestRepository.save(req);
            });
            return "redirect:/intra/tickets/" + ticketId;
        }
        return isProspect
                ? "redirect:/intra/prospects/" + c.getId()
                : "redirect:/intra/customers/" + c.getId();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client introuvable"));

        customer.getContacts().size();
        customer.getSites().size();

        long openTickets = requestRepository.countByCustomerIdAndStatusIn(
                id, List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS));
        var recentRequests = requestRepository.findByCustomerIdOrderByCreatedAtDesc(id)
                .stream().limit(5).toList();
        var activeSubs = subscriptionRepository
                .findByCustomerIdAndActiveTrueOrderByStartDateDesc(id);

        List<User> teamUsers = userRepository.findByCustomerId(id);
        Map<Long, User> userMap = new HashMap<>();
        Set<Long> gestionnaireUserIds = new HashSet<>();
        for (User u : teamUsers) {
            userMap.put(u.getId(), u);
            if (u.getRoles().stream().anyMatch(r -> "GESTIONNAIRE".equals(r.getName()))) {
                gestionnaireUserIds.add(u.getId());
            }
        }

        model.addAttribute("customer",            customer);
        model.addAttribute("openTickets",         openTickets);
        model.addAttribute("recentRequests",      recentRequests);
        model.addAttribute("activeSubs",          activeSubs);
        model.addAttribute("userMap",             userMap);
        model.addAttribute("gestionnaireUserIds", gestionnaireUserIds);
        model.addAttribute("fiscalZones",         Customer.FiscalZone.values());
        return "intra/customers/detail";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable Long id,
                       @RequestParam String companyName,
                       @RequestParam(required = false) String contactName,
                       @RequestParam(required = false) String email,
                       @RequestParam(required = false) String phone,
                       @RequestParam(required = false) String address,
                       @RequestParam(required = false) Integer ninjaOrganizationId,
                       @RequestParam(required = false) Customer.FiscalZone fiscalZone,
                       @RequestParam(required = false) String vatNumber,
                       @RequestParam(required = false) String siren,
                       @RequestParam(required = false) String siret,
                       @RequestParam(required = false) String nafCode,
                       @RequestParam(required = false) String legalForm,
                       @RequestParam(required = false) String rcsCity,
                       RedirectAttributes ra) {
        Customer customer = customerRepository.findById(id).orElseThrow();
        customer.setCompanyName(companyName);
        customer.setContactName(contactName);
        customer.setEmail(email);
        customer.setPhone(phone);
        customer.setAddress(address);
        customer.setNinjaOrganizationId(ninjaOrganizationId);
        customer.setFiscalZone(fiscalZone != null ? fiscalZone : Customer.FiscalZone.FRANCE);
        customer.setVatNumber(vatNumber != null && vatNumber.isBlank() ? null : vatNumber);
        customer.setSiren(siren != null && siren.isBlank() ? null : siren);
        customer.setSiret(siret != null && siret.isBlank() ? null : siret);
        customer.setNafCode(nafCode != null && nafCode.isBlank() ? null : nafCode);
        customer.setLegalForm(legalForm != null && legalForm.isBlank() ? null : legalForm);
        customer.setRcsCity(rcsCity != null && rcsCity.isBlank() ? null : rcsCity);
        customerRepository.save(customer);
        ra.addFlashAttribute("successMessage", "Fiche mise à jour.");
        return "redirect:/intra/customers/" + id;
    }

    // ── Contacts (unified contacts + portal access) ───────────────────────

    @PostMapping("/{id}/contacts")
    public String addContact(@PathVariable Long id,
                             @RequestParam String firstName,
                             @RequestParam String lastName,
                             @RequestParam(required = false) String email,
                             @RequestParam(required = false) String phone,
                             @RequestParam(required = false) String position,
                             @RequestParam(required = false) String password,
                             @RequestParam(defaultValue = "CLIENT") String role,
                             RedirectAttributes ra) {
        Customer customer = customerRepository.findById(id).orElseThrow();
        Contact contact = new Contact();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email);
        contact.setPhone(phone);
        contact.setPosition(position);
        contact.setCustomer(customer);

        if (email != null && !email.isBlank() && password != null && !password.isBlank()) {
            if (userRepository.findByEmail(email).isPresent()) {
                ra.addFlashAttribute("warningMessage", email + " est déjà associé à un compte portail.");
            } else {
                PortalUserService.CreationResult result =
                        portalUserService.createExplicit(email, firstName + " " + lastName, password, id, role);
                contact.setUserId(result.user().getId());
                ra.addFlashAttribute("successMessage",
                        firstName + " " + lastName + " ajouté — accès portail créé. Mot de passe : " + password);
            }
        }
        if (!ra.getFlashAttributes().containsKey("successMessage")) {
            ra.addFlashAttribute("successMessage", firstName + " " + lastName + " ajouté.");
        }
        contactRepository.save(contact);
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/contacts/{cid}/create-access")
    public String createAccess(@PathVariable Long id, @PathVariable Long cid,
                               @RequestParam(defaultValue = "CLIENT") String role,
                               RedirectAttributes ra) {
        Contact contact = contactRepository.findById(cid)
                .filter(c -> id.equals(c.getCustomer().getId()))
                .orElseThrow();
        if (contact.getEmail() == null || contact.getEmail().isBlank()) {
            ra.addFlashAttribute("errorMessage", "Ce contact n'a pas d'email — impossible de créer un accès portail.");
            return "redirect:/intra/customers/" + id + "#tab-contacts";
        }
        if (userRepository.findByEmail(contact.getEmail()).isPresent()) {
            ra.addFlashAttribute("warningMessage", "Un compte portail existe déjà pour " + contact.getEmail());
            return "redirect:/intra/customers/" + id + "#tab-contacts";
        }
        String tempPwd = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PortalUserService.CreationResult result =
                portalUserService.createExplicit(contact.getEmail(),
                        contact.getFirstName() + " " + contact.getLastName(), tempPwd, id, role);
        contact.setUserId(result.user().getId());
        contactRepository.save(contact);
        ra.addFlashAttribute("successMessage",
                "Accès portail créé pour " + contact.getFirstName() + ". Mot de passe provisoire : " + tempPwd);
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/contacts/{cid}/revoke-access")
    public String revokeAccess(@PathVariable Long id, @PathVariable Long cid, RedirectAttributes ra) {
        Contact contact = contactRepository.findById(cid)
                .filter(c -> id.equals(c.getCustomer().getId()))
                .orElseThrow();
        if (contact.getUserId() != null) {
            userRepository.findById(contact.getUserId()).ifPresent(userRepository::delete);
            contact.setUserId(null);
            contactRepository.save(contact);
        }
        ra.addFlashAttribute("successMessage", "Accès portail révoqué.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/contacts/{cid}/reset-password")
    public String resetPassword(@PathVariable Long id, @PathVariable Long cid,
                                @RequestParam(required = false) String password,
                                RedirectAttributes ra) {
        Contact contact = contactRepository.findById(cid)
                .filter(c -> id.equals(c.getCustomer().getId()))
                .orElseThrow();
        if (contact.getUserId() == null) {
            ra.addFlashAttribute("errorMessage", "Ce contact n'a pas d'accès portail.");
            return "redirect:/intra/customers/" + id + "#tab-contacts";
        }
        User user = userRepository.findById(contact.getUserId()).orElseThrow();
        String newPwd = (password != null && !password.isBlank())
                ? password
                : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.setPassword(passwordEncoder.encode(newPwd));
        user.setMustChangePassword(true);
        userRepository.save(user);
        ra.addFlashAttribute("successMessage",
                "Mot de passe réinitialisé pour " + contact.getFirstName() + ". Nouveau mot de passe : " + newPwd);
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/contacts/{cid}/promote")
    public String promoteContact(@PathVariable Long id, @PathVariable Long cid, RedirectAttributes ra) {
        Contact contact = contactRepository.findById(cid)
                .filter(c -> id.equals(c.getCustomer().getId()))
                .orElseThrow();
        User user = userRepository.findById(contact.getUserId()).orElseThrow();
        portalUserService.promoteToGestionnaire(user);
        ra.addFlashAttribute("successMessage",
                contact.getFirstName() + " " + contact.getLastName() + " promu Gestionnaire.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/contacts/{cid}/demote")
    public String demoteContact(@PathVariable Long id, @PathVariable Long cid, RedirectAttributes ra) {
        Contact contact = contactRepository.findById(cid)
                .filter(c -> id.equals(c.getCustomer().getId()))
                .orElseThrow();
        User user = userRepository.findById(contact.getUserId()).orElseThrow();
        portalUserService.demoteToClient(user);
        ra.addFlashAttribute("successMessage",
                contact.getFirstName() + " " + contact.getLastName() + " rétrogradé Utilisateur.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/contacts/{cid}/delete")
    public String deleteContact(@PathVariable Long id, @PathVariable Long cid, RedirectAttributes ra) {
        Contact contact = contactRepository.findById(cid)
                .filter(c -> id.equals(c.getCustomer().getId()))
                .orElseThrow();
        if (contact.getUserId() != null) {
            userRepository.findById(contact.getUserId()).ifPresent(userRepository::delete);
        }
        contactRepository.delete(contact);
        ra.addFlashAttribute("successMessage", "Contact supprimé.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    // ── Équipe portail (legacy endpoints, kept for compatibility) ─────────

    @PostMapping("/{id}/team/add")
    public String addTeamMember(@PathVariable Long id,
                                @RequestParam String fullName,
                                @RequestParam String email,
                                @RequestParam String password,
                                @RequestParam(defaultValue = "CLIENT") String role,
                                RedirectAttributes ra) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client introuvable"));
        if (userRepository.findByEmail(email).isPresent()) {
            ra.addFlashAttribute("errorMessage", "Cet email est déjà utilisé.");
            return "redirect:/intra/customers/" + id + "#tab-contacts";
        }
        PortalUserService.CreationResult result =
                portalUserService.createExplicit(email, fullName, password, id, role);
        String[] parts = fullName.trim().split(" ", 2);
        Contact contact = new Contact();
        contact.setFirstName(parts[0]);
        contact.setLastName(parts.length > 1 ? parts[1] : "");
        contact.setEmail(email);
        contact.setCustomer(customer);
        contact.setUserId(result.user().getId());
        contactRepository.save(contact);
        ra.addFlashAttribute("successMessage", fullName + " ajouté au portail et aux contacts.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/team/{userId}/promote")
    public String promoteTeamMember(@PathVariable Long id, @PathVariable Long userId, RedirectAttributes ra) {
        User target = userRepository.findById(userId)
                .filter(u -> id.equals(u.getCustomerId()))
                .orElseThrow();
        portalUserService.promoteToGestionnaire(target);
        ra.addFlashAttribute("successMessage", target.getFullName() + " promu Gestionnaire.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/team/{userId}/demote")
    public String demoteTeamMember(@PathVariable Long id, @PathVariable Long userId, RedirectAttributes ra) {
        User target = userRepository.findById(userId)
                .filter(u -> id.equals(u.getCustomerId()))
                .orElseThrow();
        portalUserService.demoteToClient(target);
        ra.addFlashAttribute("successMessage", target.getFullName() + " rétrogradé Client.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/team/{userId}/edit")
    public String editTeamMember(@PathVariable Long id, @PathVariable Long userId,
                                 @RequestParam String fullName, @RequestParam String email,
                                 @RequestParam(required = false) String password,
                                 @RequestParam(defaultValue = "CLIENT") String role,
                                 RedirectAttributes ra) {
        User target = userRepository.findById(userId)
                .filter(u -> id.equals(u.getCustomerId()))
                .orElseThrow();
        target.setFullName(fullName);
        target.setEmail(email);
        if (password != null && !password.isBlank()) {
            target.setPassword(passwordEncoder.encode(password));
            target.setMustChangePassword(true);
        }
        boolean isGestionnaire = target.getRoles().stream()
                .anyMatch(r -> r.getName().equals("GESTIONNAIRE"));
        if ("GESTIONNAIRE".equals(role) && !isGestionnaire) {
            portalUserService.promoteToGestionnaire(target);
        } else if ("CLIENT".equals(role) && isGestionnaire) {
            portalUserService.demoteToClient(target);
        } else {
            userRepository.save(target);
        }
        ra.addFlashAttribute("successMessage", fullName + " mis à jour.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    @PostMapping("/{id}/team/{userId}/delete")
    public String deleteTeamMember(@PathVariable Long id, @PathVariable Long userId, RedirectAttributes ra) {
        User target = userRepository.findById(userId)
                .filter(u -> id.equals(u.getCustomerId()))
                .orElseThrow();
        userRepository.delete(target);
        ra.addFlashAttribute("successMessage", target.getFullName() + " supprimé.");
        return "redirect:/intra/customers/" + id + "#tab-contacts";
    }

    // ── Sites ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/sites")
    public String addSite(@PathVariable Long id,
                          @RequestParam String name,
                          @RequestParam(required = false) String address,
                          @RequestParam(required = false) String city,
                          @RequestParam(required = false) String zipCode,
                          RedirectAttributes ra) {
        Customer customer = customerRepository.findById(id).orElseThrow();
        Site site = new Site();
        site.setName(name);
        site.setAddress(address);
        site.setCity(city);
        site.setZipCode(zipCode);
        site.setCustomer(customer);
        siteRepository.save(site);
        ra.addFlashAttribute("successMessage", "Site « " + name + " » ajouté.");
        return "redirect:/intra/customers/" + id + "#tab-sites";
    }

    @PostMapping("/{id}/sites/{sid}/delete")
    public String deleteSite(@PathVariable Long id, @PathVariable Long sid, RedirectAttributes ra) {
        Site site = siteRepository.findById(sid)
                .filter(s -> id.equals(s.getCustomer().getId()))
                .orElseThrow();
        siteRepository.delete(site);
        ra.addFlashAttribute("successMessage", "Site supprimé.");
        return "redirect:/intra/customers/" + id + "#tab-sites";
    }

    // ── NinjaOne organisation ─────────────────────────────────────────────

    @PostMapping("/{id}/ninja-org/link")
    public String linkNinjaOrg(@PathVariable Long id,
                               @RequestParam(required = false) Integer ninjaOrganizationId,
                               RedirectAttributes ra) {
        Customer customer = customerRepository.findById(id).orElseThrow();
        customer.setNinjaOrganizationId(ninjaOrganizationId);
        customerRepository.save(customer);
        if (ninjaOrganizationId != null) {
            ra.addFlashAttribute("successMessage", "Organisation NinjaOne liée.");
        } else {
            ra.addFlashAttribute("successMessage", "Lien NinjaOne supprimé.");
        }
        return "redirect:/intra/customers/" + id;
    }

    @PostMapping("/{id}/ninja-org/create")
    public String createNinjaOrg(@PathVariable Long id, RedirectAttributes ra) {
        Customer customer = customerRepository.findById(id).orElseThrow();
        try {
            NinjaOneConfig cfg = ninjaConfigRepository.findById(1L).orElse(null);
            if (cfg == null || !cfg.isEnabled()) {
                ra.addFlashAttribute("errorMessage", "NinjaOne n'est pas configuré ou activé.");
                return "redirect:/intra/customers/" + id;
            }
            NinjaOrganization org = ninjaOneClient.createOrganization(cfg, customer.getCompanyName());
            if (org != null && org.getId() != null) {
                customer.setNinjaOrganizationId(org.getId());
                customerRepository.save(customer);
                ra.addFlashAttribute("successMessage",
                        "Organisation NinjaOne « " + org.getName() + " » créée et liée au client.");
            } else {
                ra.addFlashAttribute("errorMessage", "Échec de la création de l'organisation NinjaOne.");
            }
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur NinjaOne : " + e.getMessage());
        }
        return "redirect:/intra/customers/" + id;
    }

    // ── Convertir en ESN partenaire ───────────────────────────────────────

    @PostMapping("/{id}/to-esn")
    public String convertToEsn(@PathVariable Long id,
                                @RequestParam(required = false) java.math.BigDecimal defaultHourlyRate,
                                @RequestParam(required = false) String notes,
                                RedirectAttributes ra) {
        Customer customer = customerRepository.findById(id).orElseThrow();

        if (customer.getSiret() != null && !customer.getSiret().isBlank()) {
            var existing = esnRepository.findBySiret(customer.getSiret());
            if (existing.isPresent()) {
                ra.addFlashAttribute("successMessage",
                        customer.getCompanyName() + " est déjà référencé comme ESN partenaire.");
                return "redirect:/intra/esns";
            }
        } else {
            var existing = esnRepository.findByCompanyName(customer.getCompanyName());
            if (existing.isPresent()) {
                ra.addFlashAttribute("successMessage",
                        customer.getCompanyName() + " est déjà référencé comme ESN partenaire.");
                return "redirect:/intra/esns";
            }
        }

        customer.setEsn(true);
        if (defaultHourlyRate != null) customer.setDefaultHourlyRate(defaultHourlyRate);
        customerRepository.save(customer);

        com.logicielpourtous.site.features.esn.PartnerEsn esn =
                new com.logicielpourtous.site.features.esn.PartnerEsn();
        esn.setCompanyName(customer.getCompanyName());
        esn.setContactName(customer.getContactName());
        esn.setEmail(customer.getEmail());
        esn.setPhone(customer.getPhone());
        esn.setAddress(customer.getAddress());
        esn.setCity(customer.getRcsCity());
        esn.setSiret(customer.getSiret());
        esn.setVatNumber(customer.getVatNumber());
        esn.setDefaultHourlyRate(defaultHourlyRate);
        esn.setNotes(notes);
        esnRepository.save(esn);

        ra.addFlashAttribute("successMessage",
                customer.getCompanyName() + " ajouté comme ESN partenaire.");
        return "redirect:/intra/esns";
    }
}
