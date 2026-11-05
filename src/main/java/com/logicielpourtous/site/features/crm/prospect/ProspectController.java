package com.logicielpourtous.site.features.crm.prospect;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.crm.activity.ActivityType;
import com.logicielpourtous.site.features.crm.activity.ProspectActivity;
import com.logicielpourtous.site.features.crm.activity.ProspectActivityRepository;
import com.logicielpourtous.site.features.crm.quote.Quote;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.ProspectSource;
import com.logicielpourtous.site.features.customers.ProspectStatus;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/prospects")
@Secured({"ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL"})
public class ProspectController {

    private final CustomerRepository         customerRepository;
    private final ProspectActivityRepository activityRepository;
    private final QuoteRepository            quoteRepository;
    private final UserRepository             userRepository;
    private final TeamRepository             teamRepository;

    public ProspectController(CustomerRepository customerRepository,
                              ProspectActivityRepository activityRepository,
                              QuoteRepository quoteRepository,
                              UserRepository userRepository,
                              TeamRepository teamRepository) {
        this.customerRepository = customerRepository;
        this.activityRepository = activityRepository;
        this.quoteRepository    = quoteRepository;
        this.userRepository     = userRepository;
        this.teamRepository     = teamRepository;
    }

    private List<User> defaultCommercials() {
        return userRepository.findByRoleNameIn(List.of("ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL"));
    }

    // ── Liste Kanban ──────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        List<Customer> prospects = customerRepository.findProspects();

        Map<ProspectStatus, List<Customer>> byStatus = new LinkedHashMap<>();
        for (ProspectStatus s : ProspectStatus.values()) byStatus.put(s, new ArrayList<>());
        prospects.forEach(p -> byStatus.get(
                p.getProspectStatus() != null ? p.getProspectStatus() : ProspectStatus.NOUVEAU
        ).add(p));

        Map<Long, String> commercialNames = new HashMap<>();
        userRepository.findAll().forEach(u -> commercialNames.put(u.getId(), u.getFullName()));

        model.addAttribute("prospectsByStatus", byStatus);
        model.addAttribute("allProspects",      prospects);
        model.addAttribute("commercialNames",   commercialNames);
        model.addAttribute("statuses",          ProspectStatus.values());
        return "intra/prospects/list";
    }

    // ── Formulaire nouveau prospect ───────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("sources",     ProspectSource.values());
        model.addAttribute("teams",       teamRepository.findAll());
        model.addAttribute("commercials", defaultCommercials());
        return "intra/prospects/new";
    }

    @GetMapping("/team-members")
    public String teamMembers(@RequestParam(required = false) Long teamId, Model model) {
        List<User> members = teamId != null
                ? teamRepository.findByIdWithMembers(teamId)
                        .<List<User>>map(t -> new ArrayList<>(t.getMembers()))
                        .orElseGet(this::defaultCommercials)
                : defaultCommercials();
        model.addAttribute("members", members);
        return "intra/prospects/_team-members :: options";
    }

    @PostMapping
    public String create(@RequestParam String companyName,
                         @RequestParam(required = false) String contactName,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) ProspectSource prospectSource,
                         @RequestParam(required = false) Long teamId,
                         @RequestParam(required = false) Long assignedCommercialId,
                         @RequestParam(required = false) BigDecimal estimatedValue,
                         @RequestParam(required = false) LocalDate expectedCloseDate,
                         @RequestParam(required = false) String prospectNotes,
                         RedirectAttributes ra) {
        Customer c = new Customer();
        c.setCompanyName(companyName);
        c.setContactName(contactName);
        c.setEmail(email);
        c.setPhone(phone);
        c.setProspect(true);
        c.setCustomer(false);
        c.setProspectStatus(ProspectStatus.NOUVEAU);
        c.setProspectSource(prospectSource);
        c.setAssignedTeamId(teamId);
        c.setAssignedCommercialId(assignedCommercialId);
        c.setEstimatedValue(estimatedValue);
        c.setExpectedCloseDate(expectedCloseDate);
        c.setProspectNotes(prospectNotes);
        customerRepository.save(c);
        ra.addFlashAttribute("successMessage", "Prospect " + companyName + " créé.");
        return "redirect:/intra/prospects/" + c.getId();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Customer prospect = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prospect introuvable : " + id));

        List<ProspectActivity> activities = activityRepository.findByCustomerIdOrderByActivityDateDesc(id);
        List<Quote> quotes = quoteRepository.findByCustomerIdOrderByCreatedAtDesc(id);

        String commercialName = prospect.getAssignedCommercialId() != null
                ? userRepository.findById(prospect.getAssignedCommercialId())
                        .map(u -> u.getFullName()).orElse("—")
                : "—";

        List<User> commercials = prospect.getAssignedTeamId() != null
                ? teamRepository.findByIdWithMembers(prospect.getAssignedTeamId())
                        .<List<User>>map(t -> new ArrayList<>(t.getMembers()))
                        .orElseGet(this::defaultCommercials)
                : defaultCommercials();

        model.addAttribute("prospect",       prospect);
        model.addAttribute("activities",     activities);
        model.addAttribute("quotes",         quotes);
        model.addAttribute("commercialName", commercialName);
        model.addAttribute("activityTypes",  ActivityType.values());
        model.addAttribute("sources",        ProspectSource.values());
        model.addAttribute("statuses",       ProspectStatus.values());
        model.addAttribute("teams",          teamRepository.findAll());
        model.addAttribute("commercials",    commercials);
        return "intra/prospects/detail";
    }

    // ── Mise à jour infos ─────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
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
                         RedirectAttributes ra) {
        Customer c = customerRepository.findById(id).orElseThrow();
        c.setCompanyName(companyName);
        c.setContactName(contactName);
        c.setEmail(email);
        c.setPhone(phone);
        c.setWebsite(website);
        c.setProspectSource(prospectSource);
        c.setAssignedTeamId(teamId);
        c.setAssignedCommercialId(assignedCommercialId);
        c.setEstimatedValue(estimatedValue);
        c.setExpectedCloseDate(expectedCloseDate);
        c.setProspectNotes(prospectNotes);
        customerRepository.save(c);
        ra.addFlashAttribute("successMessage", "Prospect mis à jour.");
        return "redirect:/intra/prospects/" + id;
    }

    // ── Changement de statut ──────────────────────────────────────────────────

    @PostMapping("/{id}/status")
    @Transactional
    public String changeStatus(@PathVariable Long id,
                               @RequestParam ProspectStatus status,
                               @RequestParam(required = false) String lostReason,
                               RedirectAttributes ra) {
        Customer c = customerRepository.findById(id).orElseThrow();
        c.setProspectStatus(status);
        if (status == ProspectStatus.GAGNE) {
            c.setCustomer(true);
            c.setProspect(false);
            ra.addFlashAttribute("successMessage", "Prospect converti en client !");
            customerRepository.save(c);
            return "redirect:/intra/customers/" + id;
        }
        if (status == ProspectStatus.PERDU && lostReason != null) {
            c.setLostReason(lostReason);
        }
        customerRepository.save(c);
        ra.addFlashAttribute("successMessage", "Statut : " + status.label + ".");
        return "redirect:/intra/prospects/" + id;
    }

    // ── Conversion directe en client ──────────────────────────────────────────

    @PostMapping("/{id}/convert")
    @Transactional
    public String convert(@PathVariable Long id, RedirectAttributes ra) {
        Customer c = customerRepository.findById(id).orElseThrow();
        c.setCustomer(true);
        c.setProspect(false);
        c.setProspectStatus(ProspectStatus.GAGNE);
        customerRepository.save(c);
        ra.addFlashAttribute("successMessage", "Prospect converti en client avec succès.");
        return "redirect:/intra/customers/" + id;
    }

    // ── Activités commerciales ────────────────────────────────────────────────

    @PostMapping("/{id}/activities")
    public String addActivity(@PathVariable Long id,
                              @RequestParam ActivityType type,
                              @RequestParam LocalDate activityDate,
                              @RequestParam(required = false) Integer duration,
                              @RequestParam(required = false) String notes,
                              @RequestParam(required = false) String nextAction,
                              @RequestParam(required = false) LocalDate nextActionDate,
                              @AuthenticationPrincipal CustomUserDetails principal,
                              RedirectAttributes ra) {
        Customer c = customerRepository.findById(id).orElseThrow();
        ProspectActivity activity = new ProspectActivity();
        activity.setCustomer(c);
        activity.setType(type);
        activity.setActivityDate(activityDate);
        activity.setDuration(duration);
        activity.setNotes(notes);
        activity.setNextAction(nextAction);
        activity.setNextActionDate(nextActionDate);
        if (principal != null) {
            userRepository.findById(principal.getUserId()).ifPresent(activity::setCommercial);
        }
        activityRepository.save(activity);
        ra.addFlashAttribute("successMessage", "Activité enregistrée.");
        return "redirect:/intra/prospects/" + id + "#activites";
    }

    @PostMapping("/{id}/activities/{actId}/delete")
    public String deleteActivity(@PathVariable Long id,
                                 @PathVariable Long actId,
                                 RedirectAttributes ra) {
        activityRepository.findById(actId)
                .filter(a -> a.getCustomer().getId().equals(id))
                .ifPresent(activityRepository::delete);
        ra.addFlashAttribute("successMessage", "Activité supprimée.");
        return "redirect:/intra/prospects/" + id + "#activites";
    }
}
