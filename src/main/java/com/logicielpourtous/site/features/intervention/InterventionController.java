package com.logicielpourtous.site.features.intervention;

import com.logicielpourtous.site.features.appointment.CalendarSyncService;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.esn.PartnerEsnRepository;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.vatrate.VatRateRepository;
import com.logicielpourtous.site.features.invoice.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/intra/interventions")
public class InterventionController {

    private final InterventionRepository            interventionRepository;
    private final InterventionApplicationRepository applicationRepository;
    private final InterventionImageRepository       imageRepository;
    private final CustomerRepository                customerRepository;
    private final UserRepository                    userRepository;
    private final PartnerEsnRepository              esnRepository;
    private final InvoiceRepository                 invoiceRepository;
    private final InvoiceLineRepository             invoiceLineRepository;
    private final VatRateRepository                 vatRateRepository;
    private final CalendarSyncService               calendarSyncService;
    private final MailAccountRepository             mailAccountRepository;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public InterventionController(InterventionRepository interventionRepository,
                                  InterventionApplicationRepository applicationRepository,
                                  InterventionImageRepository imageRepository,
                                  CustomerRepository customerRepository,
                                  UserRepository userRepository,
                                  PartnerEsnRepository esnRepository,
                                  InvoiceRepository invoiceRepository,
                                  InvoiceLineRepository invoiceLineRepository,
                                  VatRateRepository vatRateRepository,
                                  CalendarSyncService calendarSyncService,
                                  MailAccountRepository mailAccountRepository) {
        this.interventionRepository = interventionRepository;
        this.applicationRepository  = applicationRepository;
        this.imageRepository        = imageRepository;
        this.customerRepository     = customerRepository;
        this.userRepository         = userRepository;
        this.esnRepository          = esnRepository;
        this.invoiceRepository      = invoiceRepository;
        this.invoiceLineRepository  = invoiceLineRepository;
        this.vatRateRepository      = vatRateRepository;
        this.calendarSyncService    = calendarSyncService;
        this.mailAccountRepository  = mailAccountRepository;
    }

    /** Trouve le calendrier personnel du technicien et synchro l'intervention. */
    private void autoSyncIntervention(Intervention i) {
        if (i.getTechnician() == null || i.getScheduledStart() == null) return;
        mailAccountRepository.findFirstByUserIdAndAuthTypeInAndAccessTokenIsNotNull(
                i.getTechnician().getId(),
                List.of(MailAccount.AuthType.GOOGLE_OAUTH2, MailAccount.AuthType.MICROSOFT_OAUTH2))
                .ifPresent(acc -> calendarSyncService.syncIntervention(i, acc.getId()));
    }

    // ── Liste ──────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        model.addAttribute("interventions", interventionRepository.findAllByOrderByCreatedAtDesc());
        model.addAttribute("inProgress",   interventionRepository.countByStatus(Intervention.InterventionStatus.IN_PROGRESS));
        model.addAttribute("completed",    interventionRepository.countByStatus(Intervention.InterventionStatus.COMPLETED));
        model.addAttribute("published",    interventionRepository.countByStatus(Intervention.InterventionStatus.PUBLISHED));
        model.addAttribute("customers",    customerRepository.findAll());
        model.addAttribute("technicians",  userRepository.findByRoleNameIn(List.of("ROLE_TECHNICIEN", "ROLE_USER")));
        model.addAttribute("esns",         esnRepository.findByActiveTrueOrderByCompanyNameAsc());
        model.addAttribute("types",        Intervention.InterventionType.values());
        model.addAttribute("billingTypes", Intervention.BillingType.values());
        return "intra/interventions/list";
    }

    // ── Créer ──────────────────────────────────────────────────────────────────

    @PostMapping
    public String create(@RequestParam String title,
                         @RequestParam Intervention.InterventionType type,
                         @RequestParam(required = false) Long customerId,
                         @RequestParam(required = false) String date,
                         @RequestParam(required = false) String scheduledStart,
                         @RequestParam(required = false) String scheduledEnd,
                         @RequestParam(required = false) Integer durationMinutes,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) Intervention.BillingType billingType,
                         @RequestParam(required = false) BigDecimal hourlyRateClient,
                         @RequestParam(required = false) BigDecimal fixedAmountClient,
                         RedirectAttributes ra) {
        Intervention i = new Intervention();
        i.setTitle(title);
        i.setType(type);
        i.setBillingType(billingType != null ? billingType : Intervention.BillingType.HOURLY);
        i.setDurationMinutes(durationMinutes != null ? durationMinutes : 0);
        i.setDescription(description);
        i.setAddress(address);
        i.setCity(city);
        i.setHourlyRateClient(hourlyRateClient);
        i.setFixedAmountClient(fixedAmountClient);
        if (date != null && !date.isBlank()) i.setDate(LocalDate.parse(date));
        if (scheduledStart != null && !scheduledStart.isBlank())
            i.setScheduledStart(LocalDateTime.parse(scheduledStart));
        if (scheduledEnd != null && !scheduledEnd.isBlank())
            i.setScheduledEnd(LocalDateTime.parse(scheduledEnd));
        if (customerId != null) customerRepository.findById(customerId).ifPresent(i::setCustomer);
        interventionRepository.save(i);
        i.setReference("INT-" + LocalDate.now().getYear() + "-" + i.getId());
        interventionRepository.save(i);
        ra.addFlashAttribute("successMessage", "Intervention " + i.getReference() + " créée.");
        return "redirect:/intra/interventions/" + i.getId();
    }

    // ── Détail ─────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Intervention i = interventionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Intervention introuvable : " + id));
        i.getApplications().size();
        i.getImages().size();
        if (i.getCustomer()   != null) i.getCustomer().getCompanyName();
        if (i.getTechnician() != null) i.getTechnician().getFullName();
        if (i.getPartnerEsn() != null) i.getPartnerEsn().getCompanyName();
        if (i.getRequest()    != null) { i.getRequest().getId(); i.getRequest().getTitle(); }

        model.addAttribute("intervention", i);
        model.addAttribute("statuses",    Intervention.InterventionStatus.values());
        model.addAttribute("types",       Intervention.InterventionType.values());
        model.addAttribute("billingTypes",Intervention.BillingType.values());
        model.addAttribute("customers",   customerRepository.findAll());
        model.addAttribute("technicians", userRepository.findByRoleNameIn(List.of("ROLE_TECHNICIEN", "ROLE_USER")));
        model.addAttribute("esns",        esnRepository.findByActiveTrueOrderByCompanyNameAsc());
        return "intra/interventions/detail";
    }

    // ── Mettre à jour ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String title,
                         @RequestParam Intervention.InterventionType type,
                         @RequestParam(required = false) Long customerId,
                         @RequestParam(required = false) String date,
                         @RequestParam(required = false) String scheduledStart,
                         @RequestParam(required = false) String scheduledEnd,
                         @RequestParam(required = false) Integer durationMinutes,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) Intervention.BillingType billingType,
                         @RequestParam(required = false) BigDecimal hourlyRateClient,
                         @RequestParam(required = false) BigDecimal fixedAmountClient,
                         @RequestParam(required = false) BigDecimal hourlyRateEsn,
                         @RequestParam(required = false) BigDecimal fixedAmountEsn,
                         RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();
        i.setTitle(title);
        i.setType(type);
        if (billingType != null) i.setBillingType(billingType);
        i.setDurationMinutes(durationMinutes != null ? durationMinutes : 0);
        i.setDescription(description);
        i.setAddress(address);
        i.setCity(city);
        i.setHourlyRateClient(hourlyRateClient);
        i.setFixedAmountClient(fixedAmountClient);
        i.setHourlyRateEsn(hourlyRateEsn);
        i.setFixedAmountEsn(fixedAmountEsn);
        if (date != null && !date.isBlank()) i.setDate(LocalDate.parse(date));
        else i.setDate(null);
        if (scheduledStart != null && !scheduledStart.isBlank())
            i.setScheduledStart(LocalDateTime.parse(scheduledStart));
        else i.setScheduledStart(null);
        if (scheduledEnd != null && !scheduledEnd.isBlank())
            i.setScheduledEnd(LocalDateTime.parse(scheduledEnd));
        else i.setScheduledEnd(null);
        if (customerId != null) customerRepository.findById(customerId).ifPresent(i::setCustomer);
        interventionRepository.save(i);
        autoSyncIntervention(i);
        ra.addFlashAttribute("successMessage", "Intervention mise à jour.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Assigner à un technicien interne ───────────────────────────────────────

    @PostMapping("/{id}/assign-technician")
    public String assignTechnician(@PathVariable Long id,
                                   @RequestParam Long technicianId,
                                   RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();
        userRepository.findById(technicianId).ifPresent(t -> {
            i.setTechnician(t);
            i.setAssignmentMode(Intervention.AssignmentMode.INTERNAL);
            i.setPartnerEsn(null);
            if (i.getStatus() == Intervention.InterventionStatus.DRAFT
                    || i.getStatus() == Intervention.InterventionStatus.PUBLISHED) {
                i.setStatus(Intervention.InterventionStatus.ASSIGNED);
            }
        });
        interventionRepository.save(i);
        autoSyncIntervention(i);
        ra.addFlashAttribute("successMessage", "Technicien assigné.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Publier sur la place de marché ─────────────────────────────────────────

    @PostMapping("/{id}/publish")
    public String publish(@PathVariable Long id, RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();
        i.setAssignmentMode(Intervention.AssignmentMode.MARKETPLACE);
        i.setStatus(Intervention.InterventionStatus.PUBLISHED);
        i.setTechnician(null);
        interventionRepository.save(i);
        ra.addFlashAttribute("successMessage", "Intervention publiée sur la place de marché.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Ajouter candidature ESN (depuis la fiche ou le marketplace) ────────────

    @PostMapping("/{id}/applications")
    public String addApplication(@PathVariable Long id,
                                 @RequestParam Long esnId,
                                 @RequestParam(required = false) BigDecimal proposedRate,
                                 @RequestParam(required = false) String message,
                                 RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();
        esnRepository.findById(esnId).ifPresent(esn -> {
            InterventionApplication app = new InterventionApplication();
            app.setIntervention(i);
            app.setPartnerEsn(esn);
            app.setProposedRate(proposedRate);
            app.setMessage(message);
            applicationRepository.save(app);
        });
        ra.addFlashAttribute("successMessage", "Candidature enregistrée.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Accepter une candidature ESN ───────────────────────────────────────────

    @PostMapping("/{id}/applications/{appId}/accept")
    public String acceptApplication(@PathVariable Long id,
                                    @PathVariable Long appId,
                                    RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();
        applicationRepository.findById(appId).ifPresent(app -> {
            // Rejeter toutes les autres
            applicationRepository.findByInterventionIdOrderByAppliedAtAsc(id).forEach(a -> {
                if (!a.getId().equals(appId)) {
                    a.setStatus(InterventionApplication.ApplicationStatus.REJECTED);
                    applicationRepository.save(a);
                }
            });
            app.setStatus(InterventionApplication.ApplicationStatus.ACCEPTED);
            applicationRepository.save(app);
            i.setPartnerEsn(app.getPartnerEsn());
            if (app.getProposedRate() != null) i.setHourlyRateEsn(app.getProposedRate());
            i.setStatus(Intervention.InterventionStatus.ASSIGNED);
            interventionRepository.save(i);
        });
        ra.addFlashAttribute("successMessage", "Candidature acceptée — intervention assignée.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Rejeter une candidature ESN ────────────────────────────────────────────

    @PostMapping("/{id}/applications/{appId}/reject")
    public String rejectApplication(@PathVariable Long id,
                                    @PathVariable Long appId,
                                    RedirectAttributes ra) {
        applicationRepository.findById(appId).ifPresent(app -> {
            app.setStatus(InterventionApplication.ApplicationStatus.REJECTED);
            applicationRepository.save(app);
        });
        ra.addFlashAttribute("successMessage", "Candidature refusée.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Changer statut ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam Intervention.InterventionStatus status,
                               RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();
        i.setStatus(status);
        interventionRepository.save(i);
        ra.addFlashAttribute("successMessage", "Statut : " + status.label + ".");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Clôturer (rapport) ─────────────────────────────────────────────────────

    @PostMapping("/{id}/close")
    public String close(@PathVariable Long id,
                        @RequestParam(required = false) String actualStartDate,
                        @RequestParam(required = false) String actualStartTime,
                        @RequestParam(required = false) String actualEndTime,
                        @RequestParam(required = false) String resolution,
                        @RequestParam(required = false) Boolean successful,
                        @RequestParam(required = false) String technicianSignature,
                        @RequestParam(required = false) String technicianSignatureName,
                        @RequestParam(required = false) String clientSignature,
                        @RequestParam(required = false) String clientSignatureName,
                        RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();

        // Calcul de actualStart / actualEnd / actualDurationMinutes
        if (actualStartDate != null && !actualStartDate.isBlank()
                && actualStartTime != null && !actualStartTime.isBlank()) {
            LocalDateTime start = LocalDateTime.parse(actualStartDate + "T" + actualStartTime);
            i.setActualStart(start);
            i.setDate(start.toLocalDate());
            if (actualEndTime != null && !actualEndTime.isBlank()) {
                LocalDateTime end = LocalDateTime.parse(actualStartDate + "T" + actualEndTime);
                // Si fin < début → on passe au lendemain (ex: intervention nocturne)
                if (end.isBefore(start)) end = end.plusDays(1);
                i.setActualEnd(end);
                int minutes = (int) java.time.Duration.between(start, end).toMinutes();
                i.setActualDurationMinutes(minutes);
            }
        }

        i.setResolution(resolution);
        i.setSuccessful(successful != null && successful);
        if (technicianSignature != null && !technicianSignature.isBlank())
            i.setTechnicianSignature(technicianSignature);
        if (technicianSignatureName != null && !technicianSignatureName.isBlank())
            i.setTechnicianSignatureName(technicianSignatureName);
        if (clientSignature != null && !clientSignature.isBlank())
            i.setClientSignature(clientSignature);
        if (clientSignatureName != null && !clientSignatureName.isBlank())
            i.setClientSignatureName(clientSignatureName);
        i.setStatus(Intervention.InterventionStatus.COMPLETED);
        interventionRepository.save(i);
        ra.addFlashAttribute("successMessage", "Intervention clôturée.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Upload image ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/images")
    public String uploadImage(@PathVariable Long id,
                              @RequestParam MultipartFile file,
                              RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Fichier vide.");
            return "redirect:/intra/interventions/" + id;
        }
        Intervention i = interventionRepository.findById(id).orElseThrow();
        try {
            String ext = "";
            String orig = file.getOriginalFilename();
            if (orig != null && orig.contains("."))
                ext = orig.substring(orig.lastIndexOf('.'));
            String filename = UUID.randomUUID() + ext;
            Path dir = Paths.get(uploadDir, "interventions", String.valueOf(id));
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(filename));

            InterventionImage img = new InterventionImage();
            img.setIntervention(i);
            img.setFilename("/uploads/interventions/" + id + "/" + filename);
            img.setOriginalName(orig);
            imageRepository.save(img);
            ra.addFlashAttribute("successMessage", "Image ajoutée.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur upload : " + e.getMessage());
        }
        return "redirect:/intra/interventions/" + id;
    }

    @PostMapping("/{id}/images/{imageId}/delete")
    public String deleteImage(@PathVariable Long id,
                              @PathVariable Long imageId,
                              RedirectAttributes ra) {
        imageRepository.findById(imageId)
                .filter(img -> img.getIntervention().getId().equals(id))
                .ifPresent(img -> {
                    try {
                        Path f = Paths.get(uploadDir,
                                img.getFilename().replace("/uploads/", ""));
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {}
                    imageRepository.delete(img);
                });
        ra.addFlashAttribute("successMessage", "Image supprimée.");
        return "redirect:/intra/interventions/" + id;
    }

    // ── Facturer directement (par intervention) ────────────────────────────────

    @PostMapping("/{id}/bill")
    @Transactional
    public String billSingle(@PathVariable Long id, RedirectAttributes ra) {
        Intervention i = interventionRepository.findById(id).orElseThrow();
        if (i.getStatus() != Intervention.InterventionStatus.COMPLETED) {
            ra.addFlashAttribute("errorMessage", "Seules les interventions terminées peuvent être facturées.");
            return "redirect:/intra/interventions/" + id;
        }
        Invoice invoice = buildInvoice(List.of(i));
        invoiceRepository.save(invoice);
        invoice.setInvoiceNumber("FAC-" + LocalDate.now().getYear() + "-" + invoice.getId());
        invoiceRepository.save(invoice);
        i.setStatus(Intervention.InterventionStatus.INVOICED);
        i.setLinkedInvoice(invoice);
        interventionRepository.save(i);
        ra.addFlashAttribute("successMessage", "Facture " + invoice.getInvoiceNumber() + " créée.");
        return "redirect:/intra/invoices/" + invoice.getId();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    Invoice buildInvoice(List<Intervention> list) {
        Invoice invoice = new Invoice();
        if (!list.isEmpty() && list.get(0).getCustomer() != null) {
            invoice.setCustomer(list.get(0).getCustomer());
        }
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setDueDate(LocalDate.now().plusDays(30));
        invoice.setType(Invoice.InvoiceType.SALE);
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoiceRepository.save(invoice);

        BigDecimal ht = BigDecimal.ZERO;
        for (Intervention i : list) {
            BigDecimal amount = i.getClientAmount() != null ? i.getClientAmount() : BigDecimal.ZERO;
            if (amount.compareTo(BigDecimal.ZERO) == 0) continue;
            InvoiceLine line = new InvoiceLine();
            line.setInvoice(invoice);
            String desc = i.getReference() + " – " + i.getTitle();
            if (i.getBillingType() == Intervention.BillingType.HOURLY && i.getActualDurationMinutes() != null) {
                desc += " (" + i.getFormattedDuration() + " à " + i.getHourlyRateClient() + " €/h)";
            }
            line.setDescription(desc);
            line.setQuantity(1);
            line.setUnitPrice(amount);
            line.setTotalHT(amount);

            var zone = invoice.getCustomer() != null ? invoice.getCustomer().getFiscalZone() : null;
            if (zone != null) {
                vatRateRepository.findFirstByZoneAndIsDefaultTrueAndActiveTrue(zone).ifPresent(vr -> {
                    line.setVatRate(vr.getRate());
                    line.setVatRateLabel(vr.getName());
                    BigDecimal tva = amount.multiply(vr.getRate().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP))
                            .setScale(2, RoundingMode.HALF_UP);
                    line.setTotalTVA(tva);
                });
            }
            invoiceLineRepository.save(line);
            ht = ht.add(amount);
        }

        BigDecimal tva = BigDecimal.ZERO;
        for (var line : invoiceLineRepository.findByInvoiceId(invoice.getId())) {
            if (line.getTotalTVA() != null) tva = tva.add(line.getTotalTVA());
        }
        invoice.setTotalHT(ht);
        invoice.setTotalTVA(tva);
        invoice.setTotalTTC(ht.add(tva));
        return invoice;
    }
}
