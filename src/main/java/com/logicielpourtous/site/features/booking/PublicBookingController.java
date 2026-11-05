package com.logicielpourtous.site.features.booking;

import com.logicielpourtous.site.features.appointment.Appointment;
import com.logicielpourtous.site.features.appointment.AppointmentRepository;
import com.logicielpourtous.site.features.appointment.CalendarSyncService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/booking")
public class PublicBookingController {

    private final BookingConfigRepository     bookingConfigRepository;
    private final BookingAvailabilityService  availabilityService;
    private final AppointmentRepository       appointmentRepository;
    private final MailAccountRepository       mailAccountRepository;
    private final CalendarSyncService         calendarSyncService;
    private final BookingMailService          bookingMailService;

    public PublicBookingController(BookingConfigRepository bookingConfigRepository,
                                   BookingAvailabilityService availabilityService,
                                   AppointmentRepository appointmentRepository,
                                   MailAccountRepository mailAccountRepository,
                                   CalendarSyncService calendarSyncService,
                                   BookingMailService bookingMailService) {
        this.bookingConfigRepository = bookingConfigRepository;
        this.availabilityService     = availabilityService;
        this.appointmentRepository   = appointmentRepository;
        this.mailAccountRepository   = mailAccountRepository;
        this.calendarSyncService     = calendarSyncService;
        this.bookingMailService      = bookingMailService;
    }

    // ── Widget public ─────────────────────────────────────────────────────────

    @GetMapping("/{slug}")
    @Transactional(readOnly = true)
    public String showWidget(@PathVariable String slug, Model model) {
        BookingConfig config = bookingConfigRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new IllegalArgumentException("Page de réservation introuvable"));

        LocalDate today   = LocalDate.now();
        LocalDate maxDate = today.plusDays(config.getAdvanceDaysMax());
        List<String> availableDates = buildAvailableDates(config, today, maxDate);

        model.addAttribute("config",         config);
        model.addAttribute("today",          today.format(DateTimeFormatter.ISO_LOCAL_DATE));
        model.addAttribute("maxDate",        maxDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        model.addAttribute("availableDates", availableDates);
        return "public/booking";
    }

    @GetMapping("/{slug}/slots")
    @ResponseBody
    @Transactional(readOnly = true)
    public List<String> getSlots(@PathVariable String slug,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        BookingConfig config = bookingConfigRepository.findBySlugAndActiveTrue(slug).orElse(null);
        if (config == null) return List.of();
        return availabilityService.getAvailableSlots(config, date).stream()
                .map(t -> t.format(DateTimeFormatter.ofPattern("HH:mm")))
                .collect(Collectors.toList());
    }

    @PostMapping("/{slug}")
    @Transactional
    public String book(@PathVariable String slug,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam String time,
                       @RequestParam String firstName,
                       @RequestParam String lastName,
                       @RequestParam String email,
                       @RequestParam String phone,
                       @RequestParam(required = false) String notes) {
        BookingConfig config = bookingConfigRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new IllegalArgumentException("Page de réservation introuvable"));

        LocalTime slotTime = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
        var memberOpt = availabilityService.findAvailableMember(config, date, slotTime);
        if (memberOpt.isEmpty()) {
            throw new IllegalStateException("Aucun membre disponible pour ce créneau.");
        }

        LocalDateTime startAt = LocalDateTime.of(date, slotTime);
        LocalDateTime endAt   = startAt.plusMinutes(config.getDurationMinutes());

        Appointment appointment = new Appointment();
        appointment.setTitle(config.getTitle() + " - " + firstName + " " + lastName);
        appointment.setNotes(buildNotes(firstName, lastName, email, phone, notes));
        appointment.setType(config.getAppointmentType());
        appointment.setStartAt(startAt);
        appointment.setEndAt(endAt);
        appointment.setStatus(Appointment.AppointmentStatus.SCHEDULED);
        appointment.setAssignedTo(memberOpt.get());
        appointment.setClientEmail(email);
        appointment.setCancellationToken(UUID.randomUUID().toString());
        appointmentRepository.save(appointment);

        // Sync calendrier + Meet
        List<MailAccount.AuthType> oauthTypes = List.of(
                MailAccount.AuthType.GOOGLE_OAUTH2, MailAccount.AuthType.MICROSOFT_OAUTH2);
        mailAccountRepository.findFirstByUserIdAndAuthTypeInAndAccessTokenIsNotNull(
                memberOpt.get().getId(), oauthTypes).ifPresent(account -> {
            try {
                calendarSyncService.sync(appointment, account.getId(), config.isWithMeet(), email);
            } catch (Exception ignored) {}
        });

        // Email de confirmation
        try {
            bookingMailService.sendConfirmation(appointment, config);
        } catch (Exception e) {
            // non-bloquant
        }

        return "redirect:/booking/" + slug + "/confirmation/" + appointment.getId();
    }

    @GetMapping("/{slug}/confirmation/{appointmentId}")
    @Transactional(readOnly = true)
    public String confirmation(@PathVariable String slug,
                               @PathVariable Long appointmentId,
                               Model model) {
        BookingConfig config = bookingConfigRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new IllegalArgumentException("Page de réservation introuvable"));
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Rendez-vous introuvable"));
        model.addAttribute("config",      config);
        model.addAttribute("appointment", appointment);
        return "public/booking-confirmation";
    }

    // ── Annulation ────────────────────────────────────────────────────────────

    @GetMapping("/cancel/{token}")
    @Transactional(readOnly = true)
    public String showCancel(@PathVariable String token,
                             @RequestParam(required = false) Boolean done,
                             Model model) {
        Appointment appointment = appointmentRepository.findByCancellationToken(token)
                .orElse(null);
        if (appointment == null) {
            model.addAttribute("error", "Ce lien d'annulation est invalide ou a expiré.");
            return "public/booking-cancel";
        }
        model.addAttribute("appointment", appointment);
        model.addAttribute("token",       token);
        model.addAttribute("done",        Boolean.TRUE.equals(done)
                || appointment.getStatus() == Appointment.AppointmentStatus.CANCELLED);
        return "public/booking-cancel";
    }

    @PostMapping("/cancel/{token}")
    @Transactional
    public String doCancel(@PathVariable String token) {
        appointmentRepository.findByCancellationToken(token).ifPresent(appointment -> {
            if (appointment.getStatus() == Appointment.AppointmentStatus.CANCELLED) return;
            appointment.setStatus(Appointment.AppointmentStatus.CANCELLED);
            appointmentRepository.save(appointment);
            calendarSyncService.delete(appointment);

            // Trouver la config pour l'email + le lien "re-booker"
            try {
                String slug = extractSlugFromTitle(appointment);
                if (slug != null) {
                    bookingConfigRepository.findBySlugAndActiveTrue(slug).ifPresent(config ->
                            bookingMailService.sendCancellation(appointment, config));
                }
            } catch (Exception ignored) {}
        });
        return "redirect:/booking/cancel/" + token + "?done=true";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> buildAvailableDates(BookingConfig config, LocalDate from, LocalDate to) {
        List<String> result = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            if (config.getOpenDays().contains(cursor.getDayOfWeek())) {
                List<LocalTime> slots = availabilityService.getAvailableSlots(config, cursor);
                if (!slots.isEmpty()) {
                    result.add(cursor.format(DateTimeFormatter.ISO_LOCAL_DATE));
                }
            }
            cursor = cursor.plusDays(1);
        }
        return result;
    }

    private String buildNotes(String firstName, String lastName, String email, String phone, String notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Prénom : ").append(firstName).append(" ").append(lastName).append("\n");
        sb.append("Email : ").append(email).append("\n");
        sb.append("Téléphone : ").append(phone);
        if (notes != null && !notes.isBlank()) sb.append("\n\n").append(notes);
        return sb.toString();
    }

    private String extractSlugFromTitle(Appointment appointment) {
        // Le titre est du format "Titre config - Prénom Nom"
        // On ne peut pas retrouver le slug directement ; on cherche la config dont le titre matche
        String title = appointment.getTitle();
        if (title == null) return null;
        return bookingConfigRepository.findAllByOrderByTitleAsc().stream()
                .filter(c -> title.startsWith(c.getTitle() + " - "))
                .map(BookingConfig::getSlug)
                .findFirst()
                .orElse(null);
    }
}
