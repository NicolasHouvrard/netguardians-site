package com.logicielpourtous.site.features.request;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.support.RequestContextUtils;

import jakarta.servlet.http.HttpServletRequest;

import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.Site;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import java.time.LocalDate;

@Controller
@RequestMapping("/intra/customers/{customerId}/requests")
public class RequestController {

    private static final Logger log = LoggerFactory.getLogger(RequestController.class);

    static final String MESSAGE_CONFLIT_EDITION =
            "Cette demande a été modifiée entre-temps, merci de recharger la page avant de réessayer.";

    private final RequestRepository requestRepository;
    private final CustomerRepository customerRepository;
    private final ContactRepository contactRepository;
    private final SiteRepository siteRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final TimeEntryRepository    timeEntryRepository;
    private final SubscriptionRepository subscriptionRepository;

    public RequestController(RequestRepository requestRepository,
                             CustomerRepository customerRepository,
                             ContactRepository contactRepository,
                             SiteRepository siteRepository,
                             UserRepository userRepository,
                             CommentRepository commentRepository,
                             TimeEntryRepository timeEntryRepository,
                             SubscriptionRepository subscriptionRepository) {
        this.requestRepository    = requestRepository;
        this.customerRepository   = customerRepository;
        this.contactRepository    = contactRepository;
        this.siteRepository       = siteRepository;
        this.userRepository       = userRepository;
        this.commentRepository    = commentRepository;
        this.timeEntryRepository  = timeEntryRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    private void populateModel(Model model, Long customerId) {
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        model.addAttribute("customer", customer);
        model.addAttribute("contacts", customer.getContacts());
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        model.addAttribute("requestTypes", RequestType.values());
        model.addAttribute("activeSubscriptions",
                subscriptionRepository.findByCustomerIdAndActiveTrueOrderByStartDateDesc(customerId));
    }

    @GetMapping("/add")
    public String showAddForm(@PathVariable Long customerId, Model model) {
        populateModel(model, customerId);
        Request request = new Request();
        request.setCustomer((Customer) model.getAttribute("customer"));
        model.addAttribute("request", request);
        return "feature/requests/form";
    }

    @PostMapping("/save")
    public String save(@PathVariable Long customerId,
                       @ModelAttribute Request request,
                       @RequestParam(required = false) Long contactId,
                       @RequestParam(required = false) Long siteId,
                       @RequestParam(required = false) Long subscriptionId,
                       @RequestParam(required = false, defaultValue = "0") Integer estimatedHoursInput,
                       @RequestParam(required = false, defaultValue = "0") Integer estimatedMinsInput) {
        Customer customer = customerRepository.findById(customerId).get();
        request.setCustomer(customer);

        int totalEstimated = estimatedHoursInput * 60 + estimatedMinsInput;
        request.setEstimatedMinutes(totalEstimated > 0 ? totalEstimated : null);

        if (subscriptionId != null) {
            subscriptionRepository.findById(subscriptionId).ifPresent(request::setSubscription);
        } else {
            request.setSubscription(null);
        }

        if (contactId != null) {
            contactRepository.findById(contactId).ifPresent(c -> {
                request.setContact(c);
                if (c.getSite() != null) request.setSite(c.getSite());
            });
        } else if (siteId != null) {
            siteRepository.findById(siteId).ifPresent(request::setSite);
        }

        request.registerChange();
        requestRepository.save(request);
        return "redirect:/intra/customers/edit/" + customerId + "#demandes-pane";
    }

    /**
     * Conflit d'edition (verrouillage optimiste, {@code @Version} sur {@code Syncable}) : la demande a
     * ete modifiee entre l'ouverture du formulaire et son envoi — par un autre utilisateur, ou HORS de
     * l'interface par la synchronisation multi-serveurs, qui rend ce cas frequent. Au lieu de la page
     * d'erreur generique, retour au formulaire (rechargé, donc a jour) avec un message clair.
     * La saisie du 2e envoi n'est pas reportee : l'utilisateur doit la refaire sur la version a jour.
     *
     * <p>Seule {@link ObjectOptimisticLockingFailureException} est traduite : c'est l'exception
     * reellement observee dans ce cas (verifie par {@code RequestFormVersionTest}). Une
     * {@code DataIntegrityViolationException} signale autre chose (contrainte violee, formulaire sans
     * champ version...) et ne doit pas etre maquillee en "modifiee entre-temps".
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public String conflitEdition(ObjectOptimisticLockingFailureException e, HttpServletRequest httpRequest) {
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables = (Map<String, String>) httpRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String customerId = pathVariables != null ? pathVariables.get("customerId") : null;
        String requestId = httpRequest.getParameter("id");
        log.info("Conflit d'edition sur la demande {} (client {}) : {}", requestId, customerId, e.getMessage());

        RequestContextUtils.getOutputFlashMap(httpRequest).put("errorMessage", MESSAGE_CONFLIT_EDITION);
        if (customerId == null) {
            return "redirect:/intra/customers";
        }
        if (requestId == null || !requestId.matches("\\d+")) {
            return "redirect:/intra/customers/edit/" + customerId + "#demandes-pane";
        }
        return "redirect:/intra/customers/" + customerId + "/requests/edit/" + requestId;
    }

    @GetMapping("/edit/{id}")
    @Transactional(readOnly = true)
    public String edit(@PathVariable Long customerId,
                       @PathVariable Long id,
                       Model model,
                       Principal principal) {
        Request request = requestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Demande invalide : " + id));
        User currentUser = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable"));

        List<Comment> visibleComments = request.getComments().stream()
                .filter(c -> c.isPublic() || currentUser.getRoles().stream()
                        .anyMatch(r -> r.getName().equals("ROLE_ADMIN")))
                .collect(Collectors.toList());

        populateModel(model, customerId);
        model.addAttribute("request", request);
        model.addAttribute("visibleComments", visibleComments);
        return "feature/requests/form";
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable Long customerId, @PathVariable Long id) {
        requestRepository.deleteById(id);
        return "redirect:/intra/customers/edit/" + customerId + "#demandes-pane";
    }

    @PostMapping("{requestId}/add-comment")
    public String addComment(@PathVariable Long customerId,
                             @PathVariable Long requestId,
                             String content,
                             boolean isPublic,
                             Principal principal) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Demande non trouvée"));
        User author = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        if (!isPublic) {
            boolean isAdmin = author.getRoles().stream()
                    .anyMatch(role -> role.getName().equals("ROLE_ADMIN"));
            if (!isAdmin) {
                throw new SecurityException("Seuls les membres du support peuvent poster des commentaires privés.");
            }
        }

        Comment comment = new Comment();
        comment.setContent(content);
        comment.setPublic(!isPublic);
        comment.setAuthor(author);
        comment.setRequest(request);
        comment.registerAdded();
        commentRepository.save(comment);

        return "redirect:/intra/customers/" + customerId + "/requests/edit/" + requestId;
    }

    @PostMapping("{requestId}/add-time-entry")
    public String addTimeEntry(@PathVariable Long customerId,
                               @PathVariable Long requestId,
                               @RequestParam Integer durationHours,
                               @RequestParam Integer durationMins,
                               @RequestParam(required = false) String description,
                               @RequestParam LocalDate date,
                               Principal principal) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Demande non trouvée"));
        User author = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        int totalMinutes = (durationHours != null ? durationHours : 0) * 60
                         + (durationMins  != null ? durationMins  : 0);
        if (totalMinutes <= 0) {
            return "redirect:/intra/customers/" + customerId + "/requests/edit/" + requestId;
        }

        TimeEntry entry = new TimeEntry();
        entry.setDurationMinutes(totalMinutes);
        entry.setDescription(description);
        entry.setDate(date);
        entry.setAuthor(author);
        entry.setRequest(request);
        timeEntryRepository.save(entry);

        return "redirect:/intra/customers/" + customerId + "/requests/edit/" + requestId + "#temps-pane";
    }

    // ── Fragments HTMX ─────────────────────────────────────────────────────

    /** Options du select Site, filtrées par q */
    @GetMapping("/site-options")
    public String siteOptions(@PathVariable Long customerId,
                              @RequestParam(defaultValue = "") String q,
                              Model model) {
        model.addAttribute("customerSites",
                siteRepository.findByCustomerIdAndNameContainingIgnoreCase(customerId, q));
        return "feature/requests/_fragments :: site-options";
    }

    /** Options du select Contact, filtrées par siteId et/ou q.
     *  Inclut un OOB swap pour vider la carte de détail. */
    @GetMapping("/contact-options")
    public String contactOptions(@PathVariable Long customerId,
                                 @RequestParam(required = false) Long siteId,
                                 @RequestParam(defaultValue = "") String q,
                                 Model model) {
        model.addAttribute("contacts",
                contactRepository.search(customerId, siteId, q));
        return "feature/requests/_fragments :: contact-options";
    }

    /** Carte de détail d'un contact sélectionné */
    @GetMapping("/contact-detail")
    public String contactDetail(@PathVariable Long customerId,
                                @RequestParam(required = false) Long contactId,
                                Model model) {
        if (contactId != null) {
            contactRepository.findById(contactId)
                    .ifPresent(c -> model.addAttribute("selectedContact", c));
        }
        return "feature/requests/_fragments :: contact-detail";
    }
}
