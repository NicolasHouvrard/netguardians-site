package com.logicielpourtous.site.features.commercial;

import com.logicielpourtous.site.features.appointment.Appointment;
import com.logicielpourtous.site.features.appointment.AppointmentRepository;
import com.logicielpourtous.site.features.crm.objective.CommercialObjective;
import com.logicielpourtous.site.features.crm.objective.CommercialObjectiveRepository;
import com.logicielpourtous.site.features.crm.objective.ObjectiveMetric;
import com.logicielpourtous.site.features.crm.objective.ObjectivePeriod;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteStatus;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequestMapping("/intra/commercial")
@Secured({"ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL", "ROLE_TECHNICIEN"})
public class CommercialDashboardController {

    private final CustomerRepository             customerRepo;
    private final SubscriptionRepository         subscriptionRepo;
    private final RequestRepository              requestRepo;
    private final InterventionRepository         interventionRepo;
    private final InvoiceRepository              invoiceRepo;
    private final AppointmentRepository          appointmentRepo;
    private final CommercialObjectiveRepository  objectiveRepo;
    private final QuoteRepository                quoteRepo;
    private final UserRepository                 userRepo;
    private final TeamRepository                 teamRepo;

    public CommercialDashboardController(CustomerRepository customerRepo,
                                          SubscriptionRepository subscriptionRepo,
                                          RequestRepository requestRepo,
                                          InterventionRepository interventionRepo,
                                          InvoiceRepository invoiceRepo,
                                          AppointmentRepository appointmentRepo,
                                          CommercialObjectiveRepository objectiveRepo,
                                          QuoteRepository quoteRepo,
                                          UserRepository userRepo,
                                          TeamRepository teamRepo) {
        this.customerRepo     = customerRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.requestRepo      = requestRepo;
        this.interventionRepo = interventionRepo;
        this.invoiceRepo      = invoiceRepo;
        this.appointmentRepo  = appointmentRepo;
        this.objectiveRepo    = objectiveRepo;
        this.quoteRepo        = quoteRepo;
        this.userRepo         = userRepo;
        this.teamRepo         = teamRepo;
    }

    @GetMapping({"", "/", "/dashboard"})
    public String dashboard(Model model) {

        // ── KPIs ──────────────────────────────────────────────────────────────
        model.addAttribute("customerCount",       customerRepo.findCustomers().size());
        model.addAttribute("activeSubscriptions", subscriptionRepo.countByActiveTrue());
        model.addAttribute("cliRevenue",          invoiceRepo.sumPaidSales());
        model.addAttribute("cliUnpaid",           invoiceRepo.sumOverdue());

        // ── Tickets ───────────────────────────────────────────────────────────
        List<RequestStatus> openStatuses = List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS);
        model.addAttribute("ticketsOpen",   requestRepo.countByStatusIn(openStatuses));
        model.addAttribute("ticketsUrgent", requestRepo.countByStatusInAndPriorityIn(
                openStatuses, List.of(RequestPriority.URGENT, RequestPriority.HIGH)));
        model.addAttribute("recentTickets", requestRepo
                .findByStatusInOrderByCreatedAtDesc(openStatuses).stream().limit(5).toList());

        // ── Interventions ─────────────────────────────────────────────────────
        model.addAttribute("interventionsInProgress",
                interventionRepo.countByStatus(Intervention.InterventionStatus.IN_PROGRESS));
        model.addAttribute("interventionsToBill",
                interventionRepo.findByStatusAndLinkedInvoiceIsNullOrderByDateAsc(
                        Intervention.InterventionStatus.COMPLETED).size());

        // ── Factures ──────────────────────────────────────────────────────────
        model.addAttribute("invoiceDraft",   invoiceRepo.countByStatus(Invoice.InvoiceStatus.DRAFT));
        model.addAttribute("invoiceSent",    invoiceRepo.countByStatus(Invoice.InvoiceStatus.SENT));
        model.addAttribute("invoiceOverdue", invoiceRepo.countByStatus(Invoice.InvoiceStatus.OVERDUE));

        // ── Rendez-vous ───────────────────────────────────────────────────────
        LocalDateTime dayStart  = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd    = LocalDate.now().atTime(23, 59, 59);
        LocalDateTime weekEnd   = LocalDate.now().plusDays(7).atTime(23, 59, 59);

        List<Appointment> todayAppts = appointmentRepo
                .findByStartAtBetweenOrderByStartAt(dayStart, dayEnd).stream()
                .filter(a -> a.getStatus() != Appointment.AppointmentStatus.CANCELLED)
                .toList();
        List<Appointment> weekAppts = appointmentRepo
                .findByStartAtBetweenOrderByStartAt(dayStart, weekEnd).stream()
                .filter(a -> a.getStatus() != Appointment.AppointmentStatus.CANCELLED)
                .toList();

        model.addAttribute("todayAppointments", todayAppts);
        model.addAttribute("weekAppointments",  weekAppts);
        model.addAttribute("today",             LocalDate.now());

        return "intra/commercial/dashboard";
    }

    // ── Objectifs commerciaux ─────────────────────────────────────────────────

    @GetMapping("/objectives")
    public String objectives(Model model) {
        List<CommercialObjective> objectives = objectiveRepo.findAllByOrderByYearDescMonthDesc();

        Map<Long, BigDecimal> achievedMap = new LinkedHashMap<>();
        for (CommercialObjective obj : objectives)
            achievedMap.put(obj.getId(), computeAchieved(obj));

        model.addAttribute("objectives",   objectives);
        model.addAttribute("achievedMap",  achievedMap);
        model.addAttribute("users",        userRepo.findAll());
        model.addAttribute("teams",        teamRepo.findAll());
        model.addAttribute("periods",      ObjectivePeriod.values());
        model.addAttribute("metrics",      ObjectiveMetric.values());
        model.addAttribute("currentYear",  LocalDate.now().getYear());
        model.addAttribute("currentMonth", LocalDate.now().getMonthValue());
        return "intra/commercial/objectives";
    }

    @PostMapping("/objectives")
    @Secured({"ROLE_ADMIN"})
    @Transactional
    public String createObjective(@RequestParam ObjectivePeriod period,
                                   @RequestParam int year,
                                   @RequestParam(required = false, defaultValue = "0") int month,
                                   @RequestParam ObjectiveMetric metric,
                                   @RequestParam BigDecimal targetValue,
                                   @RequestParam(required = false) Long commercialId,
                                   @RequestParam(required = false) Long teamId,
                                   @RequestParam(required = false) String notes,
                                   RedirectAttributes ra) {
        CommercialObjective obj = new CommercialObjective();
        obj.setPeriod(period);
        obj.setYear(year);
        obj.setMonth(month);
        obj.setMetric(metric);
        obj.setTargetValue(targetValue);
        obj.setNotes(notes);
        if (commercialId != null) userRepo.findById(commercialId).ifPresent(obj::setCommercial);
        if (teamId != null)       teamRepo.findById(teamId).ifPresent(obj::setTeam);
        objectiveRepo.save(obj);
        ra.addFlashAttribute("successMessage", "Objectif créé.");
        return "redirect:/intra/commercial/objectives";
    }

    @PostMapping("/objectives/{id}/delete")
    @Secured({"ROLE_ADMIN"})
    @Transactional
    public String deleteObjective(@PathVariable Long id, RedirectAttributes ra) {
        objectiveRepo.deleteById(id);
        ra.addFlashAttribute("successMessage", "Objectif supprimé.");
        return "redirect:/intra/commercial/objectives";
    }

    private BigDecimal computeAchieved(CommercialObjective obj) {
        try {
            return switch (obj.getMetric()) {
                case CHIFFRE_AFFAIRES -> quoteRepo.sumTotalHTByStatusEnvoye();
                case NOUVEAUX_CLIENTS -> BigDecimal.valueOf(customerRepo.findCustomers().size());
                case DEVIS_ENVOYES    -> BigDecimal.valueOf(quoteRepo.countByStatus(QuoteStatus.ENVOYE));
                case CONVERSIONS      -> BigDecimal.valueOf(quoteRepo.countAccepted());
                case VALEUR_PIPELINE  -> customerRepo.findProspects().stream()
                        .filter(p -> p.getEstimatedValue() != null)
                        .map(p -> p.getEstimatedValue())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            };
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
