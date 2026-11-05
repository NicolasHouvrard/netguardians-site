package com.logicielpourtous.site.features.ticketing;

import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Controller
@RequestMapping("/intra/tickets/routing")
public class TicketRoutingRuleController {

    private final TicketRoutingRuleRepository ruleRepo;
    private final TeamRepository              teamRepo;
    private final UserRepository              userRepo;
    private final RequestRepository           requestRepo;
    private final TicketRoutingService        routingService;

    public TicketRoutingRuleController(TicketRoutingRuleRepository ruleRepo,
                                        TeamRepository teamRepo,
                                        UserRepository userRepo,
                                        RequestRepository requestRepo,
                                        TicketRoutingService routingService) {
        this.ruleRepo       = ruleRepo;
        this.teamRepo       = teamRepo;
        this.userRepo       = userRepo;
        this.requestRepo    = requestRepo;
        this.routingService = routingService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("rules",       ruleRepo.findAllByOrderBySortOrderAsc());
        model.addAttribute("matchFields", TicketRoutingRule.MatchField.values());
        model.addAttribute("matchTypes",  TicketRoutingRule.MatchType.values());
        model.addAttribute("statuses",    RequestStatus.values());
        model.addAttribute("priorities",  RequestPriority.values());
        model.addAttribute("teams",       teamRepo.findAll());
        model.addAttribute("technicians", userRepo.findByRoleNameIn(List.of("ROLE_TECHNICIEN", "ROLE_ADMIN")));
        return "intra/tickets/routing";
    }

    @PostMapping
    @Transactional
    public String create(@RequestParam String name,
                         @RequestParam int sortOrder,
                         @RequestParam TicketRoutingRule.MatchField matchField,
                         @RequestParam TicketRoutingRule.MatchType matchType,
                         @RequestParam String matchValue,
                         @RequestParam(required = false) Long assignTeamId,
                         @RequestParam(required = false) Long assignUserId,
                         @RequestParam(required = false) RequestStatus setStatus,
                         @RequestParam(required = false) RequestPriority setPriority,
                         @RequestParam(defaultValue = "true") boolean stopOnMatch,
                         RedirectAttributes ra) {
        TicketRoutingRule rule = new TicketRoutingRule();
        rule.setName(name);
        rule.setSortOrder(sortOrder);
        rule.setMatchField(matchField);
        rule.setMatchType(matchType);
        rule.setMatchValue(matchValue);
        rule.setAssignTeamId(assignTeamId);
        rule.setAssignUserId(assignUserId);
        rule.setSetStatus(setStatus);
        rule.setSetPriority(setPriority);
        rule.setStopOnMatch(stopOnMatch);
        ruleRepo.save(rule);
        ra.addFlashAttribute("successMessage", "Règle « " + name + " » créée.");
        return "redirect:/intra/tickets/routing";
    }

    @PostMapping("/{id}/toggle")
    @Transactional
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        TicketRoutingRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        rule.setActive(!rule.isActive());
        ruleRepo.save(rule);
        ra.addFlashAttribute("successMessage",
                rule.isActive() ? "Règle activée." : "Règle désactivée.");
        return "redirect:/intra/tickets/routing";
    }

    @PostMapping("/{id}/order")
    @Transactional
    public String reorder(@PathVariable Long id,
                          @RequestParam int sortOrder,
                          RedirectAttributes ra) {
        ruleRepo.findById(id).ifPresent(rule -> {
            rule.setSortOrder(sortOrder);
            ruleRepo.save(rule);
        });
        ra.addFlashAttribute("successMessage", "Ordre mis à jour.");
        return "redirect:/intra/tickets/routing";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        ruleRepo.findById(id).ifPresent(ruleRepo::delete);
        ra.addFlashAttribute("successMessage", "Règle supprimée.");
        return "redirect:/intra/tickets/routing";
    }

    @PostMapping("/reapply")
    @Transactional
    public String reapply(@RequestParam(defaultValue = "open") String scope,
                          RedirectAttributes ra) {
        List<Request> tickets = switch (scope) {
            case "new"  -> requestRepo.findByStatusInOrderByCreatedAtDesc(List.of(RequestStatus.NEW));
            case "all"  -> requestRepo.findAllByOrderByCreatedAtDesc();
            default     -> requestRepo.findByStatusInOrderByCreatedAtDesc(
                               List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS));
        };
        AtomicInteger matched = new AtomicInteger();
        for (Request r : tickets) {
            int before = score(r);
            routingService.applyRules(r, r.getSenderEmail());
            if (score(r) != before) matched.incrementAndGet();
        }
        ra.addFlashAttribute("successMessage",
                tickets.size() + " ticket(s) évalué(s), " + matched.get() + " modifié(s).");
        return "redirect:/intra/tickets/routing";
    }

    private int score(Request r) {
        return (r.getAssignedTeam() != null ? 1 : 0)
             + (r.getAssignedTo()   != null ? 2 : 0)
             + (r.getStatus()       != null ? r.getStatus().ordinal() * 10 : 0)
             + (r.getPriority()     != null ? r.getPriority().ordinal() : 0);
    }
}
