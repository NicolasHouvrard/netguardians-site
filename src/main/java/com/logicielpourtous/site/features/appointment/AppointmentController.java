package com.logicielpourtous.site.features.appointment;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/appointments")
public class AppointmentController {

    private final AppointmentRepository   appointmentRepository;
    private final CustomerRepository      customerRepository;
    private final UserRepository          userRepository;
    private final TeamRepository          teamRepository;
    private final InterventionRepository  interventionRepository;
    private final MailAccountRepository   mailAccountRepository;
    private final CalendarSyncService     calendarSyncService;
    private final GoogleMailService       googleMailService;
    private final MicrosoftMailService    microsoftMailService;

    public AppointmentController(AppointmentRepository appointmentRepository,
                                 CustomerRepository customerRepository,
                                 UserRepository userRepository,
                                 TeamRepository teamRepository,
                                 InterventionRepository interventionRepository,
                                 MailAccountRepository mailAccountRepository,
                                 CalendarSyncService calendarSyncService,
                                 GoogleMailService googleMailService,
                                 MicrosoftMailService microsoftMailService) {
        this.appointmentRepository  = appointmentRepository;
        this.customerRepository     = customerRepository;
        this.userRepository         = userRepository;
        this.teamRepository         = teamRepository;
        this.interventionRepository = interventionRepository;
        this.mailAccountRepository  = mailAccountRepository;
        this.calendarSyncService    = calendarSyncService;
        this.googleMailService      = googleMailService;
        this.microsoftMailService   = microsoftMailService;
    }

    @GetMapping
    public String list(Model model, @AuthenticationPrincipal CustomUserDetails principal) {
        model.addAttribute("appointments", appointmentRepository.findAll(
                org.springframework.data.domain.Sort.by("startAt")));
        model.addAttribute("scheduled",    appointmentRepository.countByStatus(Appointment.AppointmentStatus.SCHEDULED));
        model.addAttribute("confirmed",    appointmentRepository.countByStatus(Appointment.AppointmentStatus.CONFIRMED));
        model.addAttribute("customers",    customerRepository.findAll());
        List<User> users = userRepository.findAll();
        model.addAttribute("users",        users);
        model.addAttribute("currentUserId", principal != null ? principal.getUserId() : null);
        model.addAttribute("calendarAccounts", calendarAccounts());

        List<Team> teams = teamRepository.findAllWithMembers();
        model.addAttribute("teams", teams);
        // Map teamId → [userId, ...] pour le filtre JS
        Map<Long, List<Long>> teamsData = teams.stream().collect(Collectors.toMap(
                Team::getId,
                t -> t.getMembers().stream().map(User::getId).collect(Collectors.toList())));
        model.addAttribute("teamsData", teamsData);
        // Membres appartenant à au moins une équipe (pour les chips de filtre)
        List<User> teamMembers = teams.stream()
                .flatMap(t -> t.getMembers().stream())
                .distinct()
                .sorted(java.util.Comparator.comparing(User::getFullName,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
        model.addAttribute("teamMembers", teamMembers);
        return "intra/appointments/list";
    }

    /** Endpoint JSON pour FullCalendar — RDV locaux. */
    @GetMapping("/events")
    @ResponseBody
    public List<Map<String, Object>> events(@RequestParam String start,
                                            @RequestParam String end,
                                            @RequestParam(required = false) List<Long> userIds,
                                            @RequestParam(required = false) Boolean unassigned,
                                            @AuthenticationPrincipal CustomUserDetails principal) {
        LocalDateTime from = LocalDateTime.parse(start.length() > 19 ? start.substring(0, 19) : start);
        LocalDateTime to   = LocalDateTime.parse(end.length()   > 19 ? end.substring(0, 19)   : end);
        Long currentUserId = principal != null ? principal.getUserId() : null;
        boolean filtering  = userIds != null && !userIds.isEmpty();
        boolean onlyUnassigned = Boolean.TRUE.equals(unassigned);

        return appointmentRepository.findByStartAtBetweenOrderByStartAt(from, to).stream()
                .filter(a -> {
                    // Filtre "non assigné"
                    if (onlyUnassigned) return a.getAssignedTo() == null;
                    // Filtre équipe/membre — s'applique à tous les types y compris PRIVATE
                    if (filtering && (a.getAssignedTo() == null || !userIds.contains(a.getAssignedTo().getId()))) {
                        return false;
                    }
                    // RDV privé : visible uniquement par l'utilisateur assigné
                    if (a.getType() == Appointment.AppointmentType.PRIVATE) {
                        return a.getAssignedTo() != null && currentUserId != null
                                && a.getAssignedTo().getId().equals(currentUserId);
                    }
                    return true;
                })
                .map(a -> {
                    Map<String, Object> ev = new HashMap<>();
                    String titleDisplay = a.getType() == Appointment.AppointmentType.PRIVATE
                            ? "🔒 " + a.getTitle()
                            : a.getTitle() + (a.getCustomer() != null ? " · " + a.getCustomer().getCompanyName() : "");
                    ev.put("id",    a.getId());
                    ev.put("title", titleDisplay);
                    ev.put("start", a.getStartAt().toString());
                    ev.put("end",   a.getEndAt() != null ? a.getEndAt().toString() : a.getStartAt().plusHours(1).toString());
                    ev.put("color", a.getType() == Appointment.AppointmentType.PRIVATE ? "#6f42c1" : statusColor(a.getStatus()));
                    ev.put("extendedProps", Map.of(
                            "synced", a.getCalendarEventId() != null,
                            "status", a.getStatus().name(),
                            "type",   a.getType().name()
                    ));
                    return ev;
                })
                .collect(Collectors.toList());
    }

    /** Interventions planifiées pour FullCalendar. */
    @GetMapping("/intervention-events")
    @ResponseBody
    public List<Map<String, Object>> interventionEvents(@RequestParam String start,
                                                         @RequestParam String end,
                                                         @RequestParam(required = false) List<Long> userIds,
                                                         @RequestParam(required = false) Boolean unassigned) {
        LocalDateTime from = LocalDateTime.parse(start.length() > 19 ? start.substring(0, 19) : start);
        LocalDateTime to   = LocalDateTime.parse(end.length()   > 19 ? end.substring(0, 19)   : end);
        final List<Long> ids   = userIds != null ? userIds : List.of();
        boolean filtering      = !ids.isEmpty();
        boolean onlyUnassigned = Boolean.TRUE.equals(unassigned);
        return interventionRepository.findScheduledBetween(from, to).stream()
                .filter(i -> {
                    if (onlyUnassigned) return i.getTechnician() == null;
                    return !filtering || (i.getTechnician() != null && ids.contains(i.getTechnician().getId()));
                })
                .map(i -> {
                    Map<String, Object> ev = new HashMap<>();
                    String label = "🔧 " + i.getTitle()
                            + (i.getCustomer() != null ? " · " + i.getCustomer().getCompanyName() : "")
                            + (i.getTechnician() != null ? " (" + i.getTechnician().getFullName() + ")" : "");
                    ev.put("id",    "int-" + i.getId());
                    ev.put("title", label);
                    ev.put("start", i.getScheduledStart().toString());
                    ev.put("end",   i.getScheduledEnd() != null ? i.getScheduledEnd().toString()
                                    : i.getScheduledStart().plusHours(i.getDurationMinutes() != null && i.getDurationMinutes() > 0
                                      ? i.getDurationMinutes() / 60 : 1).toString());
                    ev.put("url",   "/intra/interventions/" + i.getId());
                    Map<String, Object> extProps = new HashMap<>();
                    extProps.put("type",       "intervention");
                    extProps.put("status",     i.getStatus().name());
                    extProps.put("customer",   i.getCustomer()   != null ? i.getCustomer().getCompanyName()  : null);
                    extProps.put("technician", i.getTechnician() != null ? i.getTechnician().getFullName()   : null);
                    ev.put("extendedProps", extProps);
                    return ev;
                })
                .collect(Collectors.toList());
    }

    /** Détail d'un RDV en JSON pour la popup FullCalendar. */
    @GetMapping("/{id}/json")
    @ResponseBody
    public Map<String, Object> detail(@PathVariable Long id) {
        return appointmentRepository.findById(id).map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id",              a.getId());
            m.put("title",           a.getTitle());
            m.put("type",            a.getType().label);
            m.put("status",          a.getStatus().label);
            m.put("statusName",      a.getStatus().name());
            m.put("startAt",         a.getStartAt().toString());
            m.put("endAt",           a.getEndAt() != null ? a.getEndAt().toString() : null);
            m.put("customer",        a.getCustomer()   != null ? a.getCustomer().getCompanyName() : null);
            m.put("customerEmail",   a.getCustomer()   != null ? a.getCustomer().getEmail()       : null);
            m.put("customerPhone",   a.getCustomer()   != null ? a.getCustomer().getPhone()       : null);
            m.put("assignedTo",      a.getAssignedTo() != null ? a.getAssignedTo().getFullName()  : null);
            m.put("notes",           a.getNotes());
            m.put("synced",          a.getCalendarEventId() != null);
            m.put("meetLink",        a.getMeetLink());
            return m;
        }).orElse(Map.of());
    }

    private static final DateTimeFormatter DT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @RequestParam Appointment.AppointmentStatus status,
                       @RequestParam String startAt,
                       @RequestParam(required = false) String endAt,
                       RedirectAttributes ra) {
        appointmentRepository.findById(id).ifPresent(a -> {
            a.setStatus(status);
            a.setStartAt(LocalDateTime.parse(startAt, DT_LOCAL));
            a.setEndAt(endAt != null && !endAt.isBlank() ? LocalDateTime.parse(endAt, DT_LOCAL) : null);
            appointmentRepository.save(a);
            if (status == Appointment.AppointmentStatus.CANCELLED) {
                calendarSyncService.delete(a);
            } else if (a.getCalendarEventId() != null && a.getCalendarAccountId() != null) {
                calendarSyncService.sync(a, a.getCalendarAccountId());
            }
        });
        ra.addFlashAttribute("successMessage", "Rendez-vous mis à jour.");
        return "redirect:/intra/appointments";
    }

    /** Événements des agendas Google/Microsoft connectés (pour FullCalendar). */
    @GetMapping("/external-events")
    @ResponseBody
    public List<Map<String, Object>> externalEvents(@RequestParam String start,
                                                     @RequestParam String end,
                                                     @RequestParam(required = false) List<Long> userIds,
                                                     @RequestParam(required = false) Boolean unassigned,
                                                     @AuthenticationPrincipal CustomUserDetails principal) {
        Long userId        = principal != null ? principal.getUserId() : null;
        final List<Long> ids = userIds != null ? userIds : List.of();
        boolean filtering    = !ids.isEmpty();
        if (Boolean.TRUE.equals(unassigned)) return List.of(); // les events externes ont toujours un propriétaire

        List<MailAccount> accounts = userId != null ? userCalendarAccounts(userId) : calendarAccounts();
        // Si un filtre équipe/membre est actif, n'afficher que les comptes des membres sélectionnés
        if (filtering) {
            accounts = accounts.stream()
                    .filter(a -> a.getUserId() != null && ids.contains(a.getUserId()))
                    .collect(Collectors.toList());
        }

        // IDs déjà synchronisés localement → on les exclut pour éviter les doublons
        Set<String> syncedIds = new HashSet<>();
        appointmentRepository.findAllCalendarEventIds().forEach(syncedIds::add);
        interventionRepository.findAllCalendarEventIds().forEach(syncedIds::add);

        List<Map<String, Object>> events = new ArrayList<>();

        for (MailAccount account : accounts) {
            // Nom du propriétaire du compte
            String ownerName = account.getUserId() != null
                    ? userRepository.findById(account.getUserId())
                            .map(User::getFullName).orElse(account.getName())
                    : account.getName();

            try {
                if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                    String timeMin = toRfc3339(start);
                    String timeMax = toRfc3339(end);
                    JsonNode result = googleMailService.listCalendarEvents(account, timeMin, timeMax);
                    JsonNode items = result.path("items");
                    if (items.isArray()) {
                        for (JsonNode item : items) {
                            String googleId = item.path("id").asText("");
                            if (!googleId.isEmpty() && syncedIds.contains(googleId)) continue;
                            Map<String, Object> extProps = new HashMap<>();
                            extProps.put("external",      true);
                            extProps.put("calendarName",  "Google — " + account.getName());
                            extProps.put("assignedTo",    ownerName);
                            extProps.put("assignedToId",  account.getUserId());
                            Map<String, Object> ev = new HashMap<>();
                            ev.put("title", item.path("summary").asText("(sans titre)"));
                            String startStr = item.path("start").path("dateTime").asText("");
                            if (startStr.isEmpty()) startStr = item.path("start").path("date").asText("");
                            String endStr = item.path("end").path("dateTime").asText("");
                            if (endStr.isEmpty()) endStr = item.path("end").path("date").asText("");
                            ev.put("start", startStr);
                            ev.put("end",   endStr);
                            ev.put("extendedProps", extProps);
                            events.add(ev);
                        }
                    }
                } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                    String startStr = start.length() > 19 ? start.substring(0, 19) : start;
                    String endStr   = end.length()   > 19 ? end.substring(0, 19)   : end;
                    JsonNode result = microsoftMailService.listCalendarEvents(account, startStr, endStr);
                    JsonNode values = result.path("value");
                    if (values.isArray()) {
                        for (JsonNode item : values) {
                            String msId = item.path("id").asText("");
                            if (!msId.isEmpty() && syncedIds.contains(msId)) continue;
                            Map<String, Object> extProps = new HashMap<>();
                            extProps.put("external",      true);
                            extProps.put("calendarName",  "Outlook — " + account.getName());
                            extProps.put("assignedTo",    ownerName);
                            extProps.put("assignedToId",  account.getUserId());
                            Map<String, Object> ev = new HashMap<>();
                            ev.put("title", item.path("subject").asText("(sans titre)"));
                            ev.put("start", item.path("start").path("dateTime").asText(""));
                            ev.put("end",   item.path("end").path("dateTime").asText(""));
                            ev.put("extendedProps", extProps);
                            events.add(ev);
                        }
                    }
                }
            } catch (Exception e) {
                // Skip failing accounts silently
            }
        }
        return events;
    }

    @PostMapping
    public String create(@RequestParam String title,
                         @RequestParam String startAt,
                         @RequestParam(required = false) String endAt,
                         @RequestParam Appointment.AppointmentType type,
                         @RequestParam(required = false) Long customerId,
                         @RequestParam(required = false) Long assignedToId,
                         @RequestParam(required = false) String notes,
                         @RequestParam(required = false) Long calendarAccountId,
                         @RequestParam(required = false) Boolean withMeet,
                         @RequestParam(required = false) String attendeeEmail,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        Appointment a = new Appointment();
        a.setTitle(title);
        a.setStartAt(LocalDateTime.parse(startAt));
        if (endAt != null && !endAt.isBlank()) a.setEndAt(LocalDateTime.parse(endAt));
        a.setType(type);
        a.setNotes(notes);
        if (customerId != null) customerRepository.findById(customerId).ifPresent(a::setCustomer);
        // Pour un RDV privé, l'affecter à soi-même si non renseigné
        if (assignedToId != null) {
            userRepository.findById(assignedToId).ifPresent(a::setAssignedTo);
        } else if (type == Appointment.AppointmentType.PRIVATE && principal != null) {
            userRepository.findById(principal.getUserId()).ifPresent(a::setAssignedTo);
        }
        appointmentRepository.save(a);

        // Auto-sync : utilise le calendrier de l'utilisateur assigné, sinon celui du créateur
        Long targetUserId = a.getAssignedTo() != null ? a.getAssignedTo().getId()
                          : (principal != null ? principal.getUserId() : null);
        Long resolvedCalendarId = calendarAccountId;
        if (resolvedCalendarId == null && targetUserId != null) {
            resolvedCalendarId = mailAccountRepository
                    .findFirstByUserIdAndAuthTypeInAndAccessTokenIsNotNull(targetUserId,
                            List.of(MailAccount.AuthType.GOOGLE_OAUTH2, MailAccount.AuthType.MICROSOFT_OAUTH2))
                    .map(MailAccount::getId).orElse(null);
        }
        boolean synced = false;
        if (resolvedCalendarId != null) {
            boolean meet = Boolean.TRUE.equals(withMeet)
                    && (type == Appointment.AppointmentType.ONSITE
                        || type == Appointment.AppointmentType.REMOTE);
            calendarSyncService.sync(a, resolvedCalendarId, meet, attendeeEmail);
            synced = true;
        }

        ra.addFlashAttribute("successMessage", "Rendez-vous créé" + (synced ? " et synchronisé avec l'agenda." : "."));
        return "redirect:/intra/appointments";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam Appointment.AppointmentStatus status,
                               RedirectAttributes ra) {
        appointmentRepository.findById(id).ifPresent(a -> {
            a.setStatus(status);
            appointmentRepository.save(a);
            if (status == Appointment.AppointmentStatus.CANCELLED) {
                calendarSyncService.delete(a);
            }
        });
        ra.addFlashAttribute("successMessage", "Statut mis à jour.");
        return "redirect:/intra/appointments";
    }

    /**
     * Convertit n'importe quel format ISO envoyé par FullCalendar en RFC3339 UTC valide pour Google.
     * On tronque toujours à la partie datetime naive et on append Z — le décalage de quelques heures
     * aux extrémités d'une vue mensuelle est négligeable.
     */
    private String toRfc3339(String s) {
        if (s == null || s.isBlank()) return s;
        // Garde au maximum les 19 premiers chars (YYYY-MM-DDTHH:mm:ss) et ajoute Z
        String base = s.length() >= 19 ? s.substring(0, 19) : s;
        if (base.length() == 10) base = base + "T00:00:00"; // date seule
        return base + "Z";
    }

    /** Comptes calendrier personnels d'un utilisateur (userId renseigné). */
    private List<MailAccount> userCalendarAccounts(Long userId) {
        return mailAccountRepository.findAll().stream()
                .filter(a -> a.getAccessToken() != null)
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2
                          || a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .filter(a -> userId.equals(a.getUserId()))
                .collect(Collectors.toList());
    }

    /** Tous les comptes calendrier avec token (fallback ou liste admin). */
    private List<MailAccount> calendarAccounts() {
        return mailAccountRepository.findAll().stream()
                .filter(a -> a.getAccessToken() != null)
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2
                          || a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .collect(Collectors.toList());
    }

    private String statusColor(Appointment.AppointmentStatus s) {
        return switch (s) {
            case SCHEDULED -> "#0dcaf0";
            case CONFIRMED -> "#198754";
            case COMPLETED -> "#6c757d";
            case CANCELLED -> "#dc3545";
        };
    }
}
