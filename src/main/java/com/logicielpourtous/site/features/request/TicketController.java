package com.logicielpourtous.site.features.request;

import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.mail.MailMessageRepository;
import com.logicielpourtous.site.features.mail.MailService;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import com.logicielpourtous.site.features.order.OrderRepository;
import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/intra/tickets")
public class TicketController {

    private final RequestRepository          requestRepository;
    private final CustomerRepository         customerRepository;
    private final ContactRepository          contactRepository;
    private final SiteRepository             siteRepository;
    private final CommentRepository          commentRepository;
    private final TimeEntryRepository        timeEntryRepository;
    private final UserRepository             userRepository;
    private final TeamRepository             teamRepository;
    private final OrderRepository            orderRepository;
    private final InvoiceRepository          invoiceRepository;
    private final InterventionRepository     interventionRepository;
    private final TicketAttachmentRepository attachmentRepository;
    private final MailMessageRepository      mailMessageRepository;
    private final MailService                mailService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public TicketController(RequestRepository requestRepository,
                            CustomerRepository customerRepository,
                            ContactRepository contactRepository,
                            SiteRepository siteRepository,
                            CommentRepository commentRepository,
                            TimeEntryRepository timeEntryRepository,
                            UserRepository userRepository,
                            TeamRepository teamRepository,
                            OrderRepository orderRepository,
                            InvoiceRepository invoiceRepository,
                            InterventionRepository interventionRepository,
                            TicketAttachmentRepository attachmentRepository,
                            MailMessageRepository mailMessageRepository,
                            MailService mailService) {
        this.requestRepository      = requestRepository;
        this.customerRepository     = customerRepository;
        this.contactRepository      = contactRepository;
        this.siteRepository         = siteRepository;
        this.commentRepository      = commentRepository;
        this.timeEntryRepository    = timeEntryRepository;
        this.userRepository         = userRepository;
        this.teamRepository         = teamRepository;
        this.orderRepository        = orderRepository;
        this.invoiceRepository      = invoiceRepository;
        this.interventionRepository = interventionRepository;
        this.attachmentRepository   = attachmentRepository;
        this.mailMessageRepository  = mailMessageRepository;
        this.mailService            = mailService;
    }

    private static final int PAGE_SIZE = 50;

    @GetMapping
    public String list(@RequestParam(required = false) Long customerId,
                       @RequestParam(required = false) String statusFilter,
                       @RequestParam(required = false) String priorityFilter,
                       @RequestParam(required = false) String assignedToFilter,
                       @RequestParam(required = false) String teamFilter,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        List<Request> all = requestRepository.findAllByOrderByCreatedAtDesc();

        if (customerId != null) {
            all = all.stream()
                    .filter(r -> r.getCustomer() != null && r.getCustomer().getId().equals(customerId)).toList();
        }
        if ("open".equals(statusFilter)) {
            all = all.stream()
                    .filter(r -> r.getStatus() == RequestStatus.NEW
                              || r.getStatus() == RequestStatus.IN_PROGRESS).toList();
        } else if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                RequestStatus s = RequestStatus.valueOf(statusFilter);
                all = all.stream().filter(r -> r.getStatus() == s).toList();
            } catch (IllegalArgumentException ignored) {}
        }
        if (priorityFilter != null && !priorityFilter.isBlank()) {
            try {
                RequestPriority p = RequestPriority.valueOf(priorityFilter);
                all = all.stream().filter(r -> r.getPriority() == p).toList();
            } catch (IllegalArgumentException ignored) {}
        }
        if ("unassigned".equals(assignedToFilter)) {
            all = all.stream().filter(r -> r.getAssignedTo() == null).toList();
        } else if (assignedToFilter != null && !assignedToFilter.isBlank()) {
            try {
                Long techId = Long.parseLong(assignedToFilter);
                all = all.stream()
                        .filter(r -> r.getAssignedTo() != null
                                  && r.getAssignedTo().getId().equals(techId)).toList();
            } catch (NumberFormatException ignored) {}
        }
        if ("unassigned".equals(teamFilter)) {
            all = all.stream().filter(r -> r.getAssignedTeam() == null).toList();
        } else if (teamFilter != null && !teamFilter.isBlank()) {
            try {
                Long teamId = Long.parseLong(teamFilter);
                all = all.stream()
                        .filter(r -> r.getAssignedTeam() != null
                                  && r.getAssignedTeam().getId().equals(teamId)).toList();
            } catch (NumberFormatException ignored) {}
        }

        int totalCount = all.size();
        int totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);
        int currentPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int fromIdx = currentPage * PAGE_SIZE;
        int toIdx   = Math.min(fromIdx + PAGE_SIZE, totalCount);
        List<Request> requests = fromIdx < totalCount ? all.subList(fromIdx, toIdx) : List.of();

        model.addAttribute("requests",           requests);
        model.addAttribute("totalCount",         totalCount);
        model.addAttribute("totalPages",         totalPages);
        model.addAttribute("currentPage",        currentPage);
        model.addAttribute("customers",          customerRepository.findAll());
        model.addAttribute("technicians",        userRepository.findByRoleNameIn(List.of("ROLE_TECHNICIEN", "ROLE_ADMIN")));
        model.addAttribute("teams",              teamRepository.findAll());
        model.addAttribute("selectedCustomerId", customerId);
        model.addAttribute("statusFilter",       statusFilter);
        model.addAttribute("priorityFilter",     priorityFilter);
        model.addAttribute("assignedToFilter",   assignedToFilter);
        model.addAttribute("teamFilter",         teamFilter);
        model.addAttribute("statuses",           RequestStatus.values());
        model.addAttribute("priorities",         RequestPriority.values());
        return "intra/tickets/list";
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Request request = requestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ticket introuvable : " + id));
        request.getComments().size();
        request.getTimeEntries().size();
        // Force load des associations lazy pour le template
        if (request.getCreatedBy()    != null) request.getCreatedBy().getFullName();
        if (request.getAssignedTo()   != null) request.getAssignedTo().getFullName();
        if (request.getSite()         != null) request.getSite().getName();
        if (request.getAssignedTeam() != null) request.getAssignedTeam().getName();

        // Construire la timeline unifiée (commentaires + interventions) triée ASC
        List<TimelineEvent> timeline = new ArrayList<>();
        for (Comment c : request.getComments()) {
            timeline.add(new TimelineEvent(c.getCreatedAt(), "comment", c, null));
        }
        for (TimeEntry te : request.getTimeEntries()) {
            te.getAuthor().getFullName(); // force load LAZY
            LocalDateTime ts = te.getStartTime() != null
                    ? LocalDateTime.of(te.getDate(), te.getStartTime())
                    : te.getDate().atStartOfDay();
            timeline.add(new TimelineEvent(ts, "timeentry", null, te));
        }
        timeline.sort(Comparator.comparing(TimelineEvent::when).reversed());

        Long cid = request.getCustomer() != null ? request.getCustomer().getId() : null;
        model.addAttribute("request",         request);
        model.addAttribute("timeline",        timeline);
        model.addAttribute("statuses",        RequestStatus.values());
        model.addAttribute("allCustomers",    customerRepository.findAll(Sort.by("companyName")));
        model.addAttribute("customerSites",   cid != null ? siteRepository.findByCustomerId(cid) : List.of());
        model.addAttribute("contacts",        cid != null ? contactRepository.search(cid, null, "") : List.of());
        model.addAttribute("internalUsers",   userRepository.findByRoleNameIn(List.of("ROLE_TECHNICIEN", "ROLE_ADMIN")));
        model.addAttribute("allTeams",        teamRepository.findAll());
        model.addAttribute("teamsForType",    request.getType() != null
                ? teamRepository.findByRequestTypesContaining(request.getType())
                : teamRepository.findAll());
        model.addAttribute("requestTypes",    RequestType.values());
        model.addAttribute("ticketOrders",        orderRepository.findByTicketIdOrderByCreatedAtDesc(id));
        model.addAttribute("ticketInvoices",     invoiceRepository.findByTicketIdOrderByCreatedAtDesc(id));
        model.addAttribute("ticketInterventions", interventionRepository.findByRequestIdOrderByCreatedAtDesc(id));
        var attachments = attachmentRepository.findByRequestIdOrderByUploadedAtDesc(id);
        attachments.forEach(a -> { if (a.getUploadedBy() != null) a.getUploadedBy().getFullName(); });
        model.addAttribute("ticketAttachments", attachments);
        model.addAttribute("interventionTypes",   Intervention.InterventionType.values());
        model.addAttribute("billingTypes",        Intervention.BillingType.values());
        return "intra/tickets/detail";
    }

    @GetMapping("/create")
    public String createForm(@RequestParam(required = false) Long customerId, Model model) {
        model.addAttribute("customers",     customerRepository.findAll(Sort.by("companyName")));
        model.addAttribute("technicians",   userRepository.findByRoleNameIn(List.of("ROLE_TECHNICIEN", "ROLE_ADMIN")));
        model.addAttribute("teams",         teamRepository.findAll());
        model.addAttribute("priorities",    RequestPriority.values());
        model.addAttribute("requestTypes",  RequestType.values());
        model.addAttribute("preCustomerId", customerId);
        return "intra/tickets/create";
    }

    @PostMapping("/create")
    public String create(@RequestParam String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) Long customerId,
                         @RequestParam(required = false) Long contactId,
                         @RequestParam(required = false) Long siteId,
                         @RequestParam(required = false) RequestPriority priority,
                         @RequestParam(required = false) String type,
                         @RequestParam(required = false) Long assignedToId,
                         @RequestParam(required = false) Long teamId,
                         @RequestParam(required = false) MultipartFile file,
                         Principal principal,
                         RedirectAttributes ra) {
        Request request = new Request();
        request.setTitle(title);
        request.setDescription(description);
        request.setStatus(RequestStatus.NEW);
        request.setPriority(priority != null ? priority : RequestPriority.MEDIUM);

        if (customerId != null)
            customerRepository.findById(customerId).ifPresent(request::setCustomer);
        if (contactId != null)
            contactRepository.findById(contactId).ifPresent(request::setContact);
        if (siteId != null)
            siteRepository.findById(siteId).ifPresent(request::setSite);
        if (type != null && !type.isBlank()) {
            try { request.setType(RequestType.valueOf(type)); } catch (IllegalArgumentException ignored) {}
        }
        if (assignedToId != null)
            userRepository.findById(assignedToId).ifPresent(request::setAssignedTo);
        if (teamId != null)
            teamRepository.findById(teamId).ifPresent(request::setAssignedTeam);

        userRepository.findByEmail(principal.getName()).ifPresent(request::setCreatedBy);
        request.registerChange();
        requestRepository.save(request);

        if (file != null && !file.isEmpty()) {
            try {
                String orig   = file.getOriginalFilename();
                String ext    = (orig != null && orig.contains(".")) ? orig.substring(orig.lastIndexOf('.')) : "";
                String stored = UUID.randomUUID() + ext;
                Path dir = Paths.get(uploadDir, "tickets", String.valueOf(request.getId()));
                Files.createDirectories(dir);
                Files.copy(file.getInputStream(), dir.resolve(stored));

                TicketAttachment att = new TicketAttachment();
                att.setRequest(request);
                att.setFilename("/uploads/tickets/" + request.getId() + "/" + stored);
                att.setOriginalName(orig);
                att.setContentType(file.getContentType());
                att.setFileSize(file.getSize());
                userRepository.findByEmail(principal.getName()).ifPresent(att::setUploadedBy);
                attachmentRepository.save(att);
            } catch (Exception ignored) {}
        }

        ra.addFlashAttribute("successMessage", "Ticket créé.");
        return "redirect:/intra/tickets/" + request.getId();
    }

    @PostMapping("/{id}/intervention")
    public String createIntervention(@PathVariable Long id,
                                     @RequestParam String title,
                                     @RequestParam Intervention.InterventionType type,
                                     @RequestParam(required = false) String date,
                                     @RequestParam(required = false) String startTime,
                                     @RequestParam(required = false) Integer durationMinutes,
                                     @RequestParam(required = false) String description,
                                     @RequestParam(required = false) String city,
                                     @RequestParam(required = false) String address,
                                     @RequestParam(required = false) Intervention.BillingType billingType,
                                     @RequestParam(required = false) java.math.BigDecimal hourlyRateClient,
                                     @RequestParam(required = false) java.math.BigDecimal fixedAmountClient,
                                     RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        Intervention intervention = new Intervention();
        intervention.setTitle(title);
        intervention.setType(type);
        intervention.setBillingType(billingType != null ? billingType : Intervention.BillingType.HOURLY);
        intervention.setDurationMinutes(durationMinutes != null ? durationMinutes : 0);
        intervention.setDescription(description);
        intervention.setCity(city);
        intervention.setAddress(address);
        intervention.setHourlyRateClient(hourlyRateClient);
        intervention.setFixedAmountClient(fixedAmountClient);
        intervention.setRequest(request);
        if (request.getCustomer() != null) intervention.setCustomer(request.getCustomer());
        if (date != null && !date.isBlank()) {
            java.time.LocalDate ld = java.time.LocalDate.parse(date);
            intervention.setDate(ld);
            if (startTime != null && !startTime.isBlank()) {
                intervention.setScheduledStart(java.time.LocalDateTime.of(ld, java.time.LocalTime.parse(startTime)));
            }
        }
        interventionRepository.save(intervention);
        intervention.setReference("INT-" + java.time.LocalDate.now().getYear() + "-" + intervention.getId());
        interventionRepository.save(intervention);
        ra.addFlashAttribute("successMessage", "Intervention " + intervention.getReference() + " créée.");
        return "redirect:/intra/interventions/" + intervention.getId();
    }

    @PostMapping("/{id}/comment")
    public String addComment(@PathVariable Long id,
                             @RequestParam String content,
                             @RequestParam(defaultValue = "false") boolean isPrivate,
                             Principal principal,
                             RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        User author = userRepository.findByEmail(principal.getName()).orElseThrow();

        Comment comment = new Comment();
        comment.setContent(content);
        comment.setPublic(!isPrivate);
        comment.setAuthor(author);
        comment.setRequest(request);
        comment.registerAdded();
        commentRepository.save(comment);

        // Commentaire public → répondre par email au client
        if (!isPrivate) {
            mailService.sendReply(request, content);
        }

        ra.addFlashAttribute("successMessage", "Commentaire ajouté.");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/time-entry")
    public String addTimeEntry(@PathVariable Long id,
                               @RequestParam Integer durationHours,
                               @RequestParam Integer durationMins,
                               @RequestParam(required = false) String description,
                               @RequestParam LocalDate date,
                               @RequestParam(required = false) String startTimeStr,
                               Principal principal,
                               RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        User author = userRepository.findByEmail(principal.getName()).orElseThrow();

        int totalMinutes = (durationHours != null ? durationHours : 0) * 60
                         + (durationMins  != null ? durationMins  : 0);
        if (totalMinutes <= 0) {
            ra.addFlashAttribute("errorMessage", "Durée invalide.");
            return "redirect:/intra/tickets/" + id;
        }

        LocalTime startTime = null;
        if (startTimeStr != null && !startTimeStr.isBlank()) {
            try {
                startTime = LocalTime.parse(startTimeStr, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception ignored) {}
        }

        TimeEntry entry = new TimeEntry();
        entry.setDurationMinutes(totalMinutes);
        entry.setDescription(description);
        entry.setDate(date);
        entry.setStartTime(startTime);
        entry.setAuthor(author);
        entry.setRequest(request);
        timeEntryRepository.save(entry);

        ra.addFlashAttribute("successMessage", "Intervention enregistrée.");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam RequestStatus status,
                               Principal principal,
                               RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        String oldLabel = request.getStatus().getLabel();
        request.setStatus(status);
        request.registerChange();
        requestRepository.save(request);

        User author = userRepository.findByEmail(principal.getName()).orElseThrow();
        Comment log = new Comment();
        log.setContent("Statut changé : " + oldLabel + " → " + status.getLabel());
        log.setPublic(true);
        log.setAuthor(author);
        log.setRequest(request);
        log.registerAdded();
        commentRepository.save(log);

        ra.addFlashAttribute("successMessage", "Statut mis à jour : " + status.getLabel() + ".");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/assign-user")
    public String assignUser(@PathVariable Long id,
                             @RequestParam(required = false) Long assignedToId,
                             Principal principal,
                             RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        User author = userRepository.findByEmail(principal.getName()).orElseThrow();

        String assigneeName;
        if (assignedToId != null) {
            User assignee = userRepository.findById(assignedToId).orElse(null);
            request.setAssignedTo(assignee);
            assigneeName = assignee != null ? assignee.getFullName() : "—";
        } else {
            request.setAssignedTo(null);
            assigneeName = "—";
        }
        request.registerChange();
        requestRepository.save(request);

        Comment log = new Comment();
        log.setContent("Ticket affecté à : " + assigneeName);
        log.setPublic(true);
        log.setAuthor(author);
        log.setRequest(request);
        log.registerAdded();
        commentRepository.save(log);

        ra.addFlashAttribute("successMessage", "Ticket affecté à " + assigneeName + ".");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/type")
    public String changeType(@PathVariable Long id,
                             @RequestParam(required = false) String type,
                             Principal principal,
                             RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        String oldLabel = request.getType() != null ? request.getType().getLabel() : "—";

        RequestType newType = null;
        if (type != null && !type.isBlank()) {
            try { newType = RequestType.valueOf(type); } catch (IllegalArgumentException ignored) {}
        }
        request.setType(newType);
        request.registerChange();
        requestRepository.save(request);

        User author = userRepository.findByEmail(principal.getName()).orElseThrow();
        String newLabel = newType != null ? newType.getLabel() : "—";
        Comment log = new Comment();
        log.setContent("Type modifié : " + oldLabel + " → " + newLabel);
        log.setPublic(true);
        log.setAuthor(author);
        log.setRequest(request);
        log.registerAdded();
        commentRepository.save(log);

        ra.addFlashAttribute("successMessage", "Type mis à jour : " + newLabel + ".");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/assign-team")
    public String assignTeam(@PathVariable Long id,
                             @RequestParam(required = false) Long teamId,
                             Principal principal,
                             RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        User author = userRepository.findByEmail(principal.getName()).orElseThrow();

        String teamName;
        if (teamId != null) {
            Team team = teamRepository.findById(teamId).orElse(null);
            request.setAssignedTeam(team);
            teamName = team != null ? team.getName() : "—";
        } else {
            request.setAssignedTeam(null);
            teamName = "—";
        }
        request.registerChange();
        requestRepository.save(request);

        Comment log = new Comment();
        log.setContent("Ticket affecté à l'équipe : " + teamName);
        log.setPublic(true);
        log.setAuthor(author);
        log.setRequest(request);
        log.registerAdded();
        commentRepository.save(log);

        ra.addFlashAttribute("successMessage", "Ticket affecté à l'équipe " + teamName + ".");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/assign")
    public String assign(@PathVariable Long id,
                         @RequestParam(required = false) Long customerId,
                         @RequestParam(required = false) Long contactId,
                         @RequestParam(required = false) Long siteId,
                         RedirectAttributes ra) {
        Request request = requestRepository.findById(id).orElseThrow();
        if (customerId != null) {
            customerRepository.findById(customerId).ifPresent(request::setCustomer);
        }
        request.setContact(contactId != null
                ? contactRepository.findById(contactId).orElse(null) : null);
        request.setSite(siteId != null
                ? siteRepository.findById(siteId).orElse(null) : null);
        request.registerChange();
        requestRepository.save(request);
        ra.addFlashAttribute("successMessage", "Ticket réassigné.");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/time-entry/update")
    public String updateTimeEntry(@PathVariable Long id,
                                  @RequestParam Long entryId,
                                  @RequestParam LocalDate date,
                                  @RequestParam(required = false) String startTimeStr,
                                  @RequestParam Integer durationHours,
                                  @RequestParam Integer durationMins,
                                  @RequestParam(required = false) String description,
                                  RedirectAttributes ra) {
        TimeEntry entry = timeEntryRepository.findById(entryId)
                .filter(e -> e.getRequest().getId().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("Intervention introuvable"));

        int totalMinutes = durationHours * 60 + durationMins;
        if (totalMinutes <= 0) {
            ra.addFlashAttribute("errorMessage", "Durée invalide.");
            return "redirect:/intra/tickets/" + id;
        }

        LocalTime startTime = null;
        if (startTimeStr != null && !startTimeStr.isBlank()) {
            try {
                startTime = LocalTime.parse(startTimeStr, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception ignored) {}
        }

        entry.setDate(date);
        entry.setStartTime(startTime);
        entry.setDurationMinutes(totalMinutes);
        entry.setDescription(description);
        timeEntryRepository.save(entry);

        ra.addFlashAttribute("successMessage", "Intervention mise à jour.");
        return "redirect:/intra/tickets/" + id;
    }

    @PostMapping("/{id}/time-entry/{entryId}/delete")
    public String deleteTimeEntry(@PathVariable Long id,
                                  @PathVariable Long entryId,
                                  RedirectAttributes ra) {
        timeEntryRepository.findById(entryId)
                .filter(e -> e.getRequest().getId().equals(id))
                .ifPresent(timeEntryRepository::delete);
        ra.addFlashAttribute("successMessage", "Intervention supprimée.");
        return "redirect:/intra/tickets/" + id;
    }

    // ── Pièces jointes ───────────────────────────────────────────────────

    @PostMapping("/{id}/attachments")
    public String uploadAttachment(@PathVariable Long id,
                                   @RequestParam("file") MultipartFile file,
                                   Principal principal,
                                   RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Fichier vide.");
            return "redirect:/intra/tickets/" + id;
        }
        Request request = requestRepository.findById(id).orElseThrow();
        try {
            String orig = file.getOriginalFilename();
            String ext  = (orig != null && orig.contains("."))
                    ? orig.substring(orig.lastIndexOf('.')) : "";
            String stored   = UUID.randomUUID() + ext;
            Path dir = Paths.get(uploadDir, "tickets", String.valueOf(id));
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(stored));

            TicketAttachment att = new TicketAttachment();
            att.setRequest(request);
            att.setFilename("/uploads/tickets/" + id + "/" + stored);
            att.setOriginalName(orig);
            att.setContentType(file.getContentType());
            att.setFileSize(file.getSize());
            userRepository.findByEmail(principal.getName()).ifPresent(att::setUploadedBy);
            attachmentRepository.save(att);

            ra.addFlashAttribute("successMessage", "Fichier « " + orig + " » ajouté.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur lors de l'upload : " + e.getMessage());
        }
        return "redirect:/intra/tickets/" + id;
    }

    // ── Actions en masse ─────────────────────────────────────────────────

    @PostMapping("/bulk")
    @Transactional
    public String bulk(@RequestParam List<Long> ids,
                       @RequestParam String action,
                       RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Aucun ticket sélectionné.");
            return "redirect:/intra/tickets";
        }
        int count = 0;
        for (Long id : ids) {
            Request request = requestRepository.findById(id).orElse(null);
            if (request == null) continue;
            switch (action) {
                case "delete" -> {
                    commentRepository.deleteByRequestId(id);
                    timeEntryRepository.deleteByRequestId(id);
                    attachmentRepository.deleteByRequestId(id);
                    mailMessageRepository.deleteByRequestId(id);
                    requestRepository.delete(request);
                    count++;
                }
                case "cancel" -> {
                    if (request.getStatus() != RequestStatus.CANCELLED) {
                        request.setStatus(RequestStatus.CANCELLED);
                        request.registerChange();
                        requestRepository.save(request);
                        count++;
                    }
                }
                case "close" -> {
                    if (request.getStatus() != RequestStatus.COMPLETED) {
                        request.setStatus(RequestStatus.COMPLETED);
                        request.registerChange();
                        requestRepository.save(request);
                        count++;
                    }
                }
            }
        }
        String label = switch (action) {
            case "delete" -> count + " ticket(s) supprimé(s).";
            case "cancel" -> count + " ticket(s) annulé(s).";
            case "close"  -> count + " ticket(s) clôturé(s).";
            default       -> count + " ticket(s) traité(s).";
        };
        ra.addFlashAttribute("successMessage", label);
        return "redirect:/intra/tickets";
    }

    @PostMapping("/{id}/attachments/{attachId}/delete")
    public String deleteAttachment(@PathVariable Long id,
                                   @PathVariable Long attachId,
                                   RedirectAttributes ra) {
        attachmentRepository.findById(attachId)
                .filter(a -> a.getRequest().getId().equals(id))
                .ifPresent(a -> {
                    try {
                        Path f = Paths.get(uploadDir,
                                a.getFilename().replaceFirst("^/uploads/", ""));
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {}
                    attachmentRepository.delete(a);
                });
        ra.addFlashAttribute("successMessage", "Pièce jointe supprimée.");
        return "redirect:/intra/tickets/" + id;
    }

    // ── Fragments HTMX pour le formulaire de création ───────────────────

    @GetMapping("/create/site-options")
    public String createSiteOptions(@RequestParam Long customerId, Model model) {
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        return "feature/requests/_fragments :: site-options";
    }

    @GetMapping("/create/contact-options")
    public String createContactOptions(@RequestParam Long customerId,
                                       @RequestParam(required = false) Long siteId,
                                       Model model) {
        model.addAttribute("contacts", contactRepository.search(customerId, siteId, ""));
        return "feature/requests/_fragments :: contact-options";
    }

    // ── Fragments HTMX pour le formulaire de réassignation ───────────────

    @GetMapping("/{id}/site-options")
    public String siteOptions(@PathVariable Long id,
                              @RequestParam Long customerId,
                              Model model) {
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        return "feature/requests/_fragments :: site-options";
    }

    @GetMapping("/{id}/contact-options")
    public String contactOptions(@PathVariable Long id,
                                 @RequestParam Long customerId,
                                 @RequestParam(required = false) Long siteId,
                                 Model model) {
        model.addAttribute("contacts", contactRepository.search(customerId, siteId, ""));
        return "feature/requests/_fragments :: contact-options";
    }
}
