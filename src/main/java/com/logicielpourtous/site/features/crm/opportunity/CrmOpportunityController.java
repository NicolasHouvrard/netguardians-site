package com.logicielpourtous.site.features.crm.opportunity;

import com.logicielpourtous.site.features.crm.activity.ActivityType;
import com.logicielpourtous.site.features.crm.activity.ProspectActivity;
import com.logicielpourtous.site.features.crm.activity.ProspectActivityRepository;
import com.logicielpourtous.site.features.crm.quote.Quote;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteStatus;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.ProspectSource;
import com.logicielpourtous.site.features.customers.ProspectStatus;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/pipeline")
public class CrmOpportunityController {

    private final CrmOpportunityRepository opportunityRepo;
    private final CustomerRepository       customerRepo;
    private final QuoteRepository          quoteRepo;
    private final ProspectActivityRepository activityRepo;
    private final UserRepository           userRepo;
    private final TeamRepository           teamRepo;

    public CrmOpportunityController(CrmOpportunityRepository opportunityRepo,
                                     CustomerRepository customerRepo,
                                     QuoteRepository quoteRepo,
                                     ProspectActivityRepository activityRepo,
                                     UserRepository userRepo,
                                     TeamRepository teamRepo) {
        this.opportunityRepo = opportunityRepo;
        this.customerRepo    = customerRepo;
        this.quoteRepo       = quoteRepo;
        this.activityRepo    = activityRepo;
        this.userRepo        = userRepo;
        this.teamRepo        = teamRepo;
    }

    // ── Liste / Kanban ────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(required = false) String q, Model model) {
        List<CrmOpportunity> all = (q != null && !q.isBlank())
                ? opportunityRepo.search(q)
                : opportunityRepo.findAllByOrderByCreatedAtDesc();

        // Clé String pour accès fiable depuis Thymeleaf/SpEL
        Map<String, List<CrmOpportunity>> byStatus = new HashMap<>();
        for (ProspectStatus s : ProspectStatus.values()) byStatus.put(s.name(), new java.util.ArrayList<>());
        for (CrmOpportunity o : all) byStatus.get(o.getStatus().name()).add(o);

        // Bulk load quote totals (avoid N+1)
        List<Long> ids = all.stream().map(CrmOpportunity::getId).toList();
        Map<Long, BigDecimal> quoteTotals = new HashMap<>();
        if (!ids.isEmpty()) {
            List<Object[]> rows = quoteRepo.sumTotalHTByOpportunityIds(ids,
                    List.of(QuoteStatus.ENVOYE, QuoteStatus.ACCEPTE));
            for (Object[] row : rows) {
                quoteTotals.put((Long) row[0], (BigDecimal) row[1]);
            }
        }

        // Total par colonne (clé = status.name() pour accès simple depuis Thymeleaf)
        Map<String, BigDecimal> columnTotals = new HashMap<>();
        for (ProspectStatus s : ProspectStatus.values()) {
            List<CrmOpportunity> col = byStatus.getOrDefault(s, List.of());
            BigDecimal colTotal = col.stream()
                    .map(o -> quoteTotals.getOrDefault(o.getId(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            columnTotals.put(s.name(), colTotal);
        }

        model.addAttribute("byStatus",     byStatus);
        model.addAttribute("statuses",     ProspectStatus.values());
        model.addAttribute("quoteTotals",  quoteTotals);
        model.addAttribute("columnTotals", columnTotals);
        model.addAttribute("q",            q != null ? q : "");
        model.addAttribute("totalCount",   all.size());
        model.addAttribute("all",          all);
        model.addAttribute("today",        LocalDate.now());
        return "intra/pipeline/list";
    }

    // ── Nouvelle affaire ──────────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Long customerId, Model model) {
        model.addAttribute("sources",     ProspectSource.values());
        model.addAttribute("teams",       teamRepo.findAll());
        model.addAttribute("commercials", userRepo.findByRoleNameIn(
                List.of("ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL")));
        model.addAttribute("customers",   customerRepo.findAllContacts());
        model.addAttribute("preselectedCustomerId", customerId);
        return "intra/pipeline/new";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam Long customerId,
                         @RequestParam(required = false) ProspectSource source,
                         @RequestParam(required = false) Long teamId,
                         @RequestParam(required = false) Long assignedCommercialId,
                         @RequestParam(required = false) BigDecimal estimatedValue,
                         @RequestParam(required = false) LocalDate expectedCloseDate,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        Customer customer = customerRepo.findById(customerId).orElseThrow();
        CrmOpportunity opp = new CrmOpportunity();
        opp.setName(name);
        opp.setCustomer(customer);
        opp.setSource(source);
        opp.setAssignedTeamId(teamId);
        opp.setAssignedCommercialId(assignedCommercialId);
        opp.setEstimatedValue(estimatedValue);
        opp.setExpectedCloseDate(expectedCloseDate);
        opp.setNotes(notes);
        opportunityRepo.save(opp);
        ra.addFlashAttribute("successMessage", "Affaire « " + name + " » créée.");
        return "redirect:/intra/pipeline/" + opp.getId();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        CrmOpportunity opp = opportunityRepo.findById(id).orElseThrow();
        List<ProspectActivity> activities = activityRepo.findByOpportunityIdOrderByActivityDateDesc(id);
        List<Quote> quotes = quoteRepo.findByOpportunityIdOrderByCreatedAtDesc(id);

        BigDecimal quoteTotalHT = quotes.stream()
                .filter(q -> q.getStatus() == QuoteStatus.ENVOYE || q.getStatus() == QuoteStatus.ACCEPTE)
                .map(Quote::getTotalHT)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<Long, String> userNames = new HashMap<>();
        activities.stream()
                .filter(a -> a.getCommercial() != null)
                .forEach(a -> userNames.put(a.getCommercial().getId(), a.getCommercial().getFullName()));

        String commercialName = opp.getAssignedCommercialId() != null
                ? userRepo.findById(opp.getAssignedCommercialId()).map(u -> u.getFullName()).orElse(null)
                : null;

        model.addAttribute("opp",            opp);
        model.addAttribute("activities",     activities);
        model.addAttribute("quotes",         quotes);
        model.addAttribute("quoteTotalHT",   quoteTotalHT);
        model.addAttribute("activityTypes",  ActivityType.values());
        model.addAttribute("sources",        ProspectSource.values());
        model.addAttribute("statuses",       ProspectStatus.values());
        model.addAttribute("commercialName", commercialName);
        model.addAttribute("userNames",      userNames);
        model.addAttribute("today",          LocalDate.now());
        model.addAttribute("allQuotes",      quoteRepo.findAllByOrderByCreatedAtDesc());
        return "intra/pipeline/detail";
    }

    // ── Mise à jour info ──────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) ProspectSource source,
                         @RequestParam(required = false) Long assignedCommercialId,
                         @RequestParam(required = false) BigDecimal estimatedValue,
                         @RequestParam(required = false) LocalDate expectedCloseDate,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        CrmOpportunity opp = opportunityRepo.findById(id).orElseThrow();
        opp.setName(name);
        opp.setSource(source);
        opp.setAssignedCommercialId(assignedCommercialId);
        opp.setEstimatedValue(estimatedValue);
        opp.setExpectedCloseDate(expectedCloseDate);
        opp.setNotes(notes);
        opportunityRepo.save(opp);
        ra.addFlashAttribute("successMessage", "Affaire mise à jour.");
        return "redirect:/intra/pipeline/" + id;
    }

    // ── Changement de statut ──────────────────────────────────────────────────

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam ProspectStatus status,
                               @RequestParam(required = false) String lostReason,
                               RedirectAttributes ra) {
        CrmOpportunity opp = opportunityRepo.findById(id).orElseThrow();
        opp.setStatus(status);
        if (status == ProspectStatus.PERDU && lostReason != null && !lostReason.isBlank()) {
            opp.setLostReason(lostReason);
        }
        opportunityRepo.save(opp);
        ra.addFlashAttribute("successMessage", "Statut mis à jour : " + status.label);
        return "redirect:/intra/pipeline/" + id;
    }

    // ── Activités ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/activities")
    public String addActivity(@PathVariable Long id,
                              @RequestParam ActivityType type,
                              @RequestParam LocalDate activityDate,
                              @RequestParam(required = false) Integer duration,
                              @RequestParam(required = false) String notes,
                              @RequestParam(required = false) String nextAction,
                              @RequestParam(required = false) LocalDate nextActionDate,
                              @AuthenticationPrincipal UserDetails userDetails,
                              RedirectAttributes ra) {
        CrmOpportunity opp = opportunityRepo.findById(id).orElseThrow();
        ProspectActivity activity = new ProspectActivity();
        activity.setOpportunity(opp);
        activity.setType(type);
        activity.setActivityDate(activityDate);
        activity.setDuration(duration);
        activity.setNotes(notes);
        activity.setNextAction(nextAction);
        activity.setNextActionDate(nextActionDate);
        userRepo.findByEmail(userDetails.getUsername())
                .ifPresent(activity::setCommercial);
        activityRepo.save(activity);
        ra.addFlashAttribute("successMessage", "Activité enregistrée.");
        return "redirect:/intra/pipeline/" + id + "#tab-activities";
    }

    @PostMapping("/{id}/activities/{actId}/delete")
    public String deleteActivity(@PathVariable Long id, @PathVariable Long actId, RedirectAttributes ra) {
        activityRepo.findById(actId)
                .filter(a -> a.getOpportunity() != null && id.equals(a.getOpportunity().getId()))
                .ifPresent(activityRepo::delete);
        ra.addFlashAttribute("successMessage", "Activité supprimée.");
        return "redirect:/intra/pipeline/" + id + "#tab-activities";
    }

    // ── Devis ─────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/link-quote")
    public String linkQuote(@PathVariable Long id,
                            @RequestParam Long quoteId,
                            RedirectAttributes ra) {
        CrmOpportunity opp = opportunityRepo.findById(id).orElseThrow();
        Quote quote = quoteRepo.findById(quoteId).orElseThrow();
        quote.setOpportunity(opp);
        quoteRepo.save(quote);
        ra.addFlashAttribute("successMessage", "Devis rattaché à l'affaire.");
        return "redirect:/intra/pipeline/" + id + "#tab-quotes";
    }

    @PostMapping("/{id}/unlink-quote")
    public String unlinkQuote(@PathVariable Long id,
                              @RequestParam Long quoteId,
                              RedirectAttributes ra) {
        quoteRepo.findById(quoteId).ifPresent(q -> {
            q.setOpportunity(null);
            quoteRepo.save(q);
        });
        ra.addFlashAttribute("successMessage", "Devis détaché.");
        return "redirect:/intra/pipeline/" + id + "#tab-quotes";
    }
}
