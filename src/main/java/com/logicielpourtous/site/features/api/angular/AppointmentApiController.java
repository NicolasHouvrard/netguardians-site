package com.logicielpourtous.site.features.api.angular;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicielpourtous.site.features.appointment.Appointment;
import com.logicielpourtous.site.features.appointment.AppointmentRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.data.domain.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/intra/appointments")
public class AppointmentApiController {

    private final AppointmentRepository  appointmentRepository;
    private final InterventionRepository interventionRepository;
    private final TeamRepository         teamRepository;
    private final MailAccountRepository  mailAccountRepository;
    private final UserRepository         userRepository;
    private final GoogleMailService      googleMailService;
    private final MicrosoftMailService   microsoftMailService;

    public AppointmentApiController(AppointmentRepository appointmentRepository,
                                    InterventionRepository interventionRepository,
                                    TeamRepository teamRepository,
                                    MailAccountRepository mailAccountRepository,
                                    UserRepository userRepository,
                                    GoogleMailService googleMailService,
                                    MicrosoftMailService microsoftMailService) {
        this.appointmentRepository  = appointmentRepository;
        this.interventionRepository = interventionRepository;
        this.teamRepository         = teamRepository;
        this.mailAccountRepository  = mailAccountRepository;
        this.userRepository         = userRepository;
        this.googleMailService      = googleMailService;
        this.microsoftMailService   = microsoftMailService;
    }

    // ── Liste paginée (vue tableau) ───────────────────────────────────────────

    public record AppointmentPageResponse(
            List<AppointmentDto> content,
            int  page,
            int  size,
            long totalElements,
            int  totalPages
    ) {}

    @GetMapping
    public AppointmentPageResponse list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "startAt"));

        Page<Appointment> result;
        if (status != null && !status.isBlank()) {
            try {
                Appointment.AppointmentStatus s = Appointment.AppointmentStatus.valueOf(status);
                result = appointmentRepository.findByStatus(s, pageable);
            } catch (IllegalArgumentException e) {
                result = appointmentRepository.findAll(pageable);
            }
        } else {
            result = appointmentRepository.findAll(pageable);
        }

        return new AppointmentPageResponse(
                result.getContent().stream().map(AppointmentDto::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    // ── Métadonnées (statuts / types) ─────────────────────────────────────────

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "statuses", List.of(Appointment.AppointmentStatus.values()).stream()
                        .map(s -> Map.of("value", s.name(), "label", s.label)).toList(),
                "types",    List.of(Appointment.AppointmentType.values()).stream()
                        .map(t -> Map.of("value", t.name(), "label", t.label)).toList()
        );
    }

    // ── Événements FullCalendar (RDV + interventions + agendas externes) ──────

    @GetMapping("/events")
    public List<Map<String, Object>> calendarEvents(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(required = false) List<Long> userIds,
            @RequestParam(required = false) Boolean unassigned,
            @AuthenticationPrincipal CustomUserDetails principal) {

        LocalDateTime from = parseDateTime(start);
        LocalDateTime to   = parseDateTime(end);
        Long currentUserId = principal != null ? principal.getUserId() : null;
        boolean filtering      = userIds != null && !userIds.isEmpty();
        boolean onlyUnassigned = Boolean.TRUE.equals(unassigned);

        List<Map<String, Object>> events = new ArrayList<>();

        // — RDV locaux —
        appointmentRepository.findByStartAtBetweenOrderByStartAt(from, to).stream()
                .filter(a -> {
                    if (onlyUnassigned) return a.getAssignedTo() == null;
                    if (filtering && (a.getAssignedTo() == null
                            || !userIds.contains(a.getAssignedTo().getId()))) return false;
                    if (a.getType() == Appointment.AppointmentType.PRIVATE) {
                        return a.getAssignedTo() != null && currentUserId != null
                                && a.getAssignedTo().getId().equals(currentUserId);
                    }
                    return true;
                })
                .forEach(a -> {
                    String title = a.getType() == Appointment.AppointmentType.PRIVATE
                            ? "🔒 " + a.getTitle()
                            : a.getTitle() + (a.getCustomer() != null
                                ? " · " + a.getCustomer().getCompanyName() : "");

                    Map<String, Object> ext = new HashMap<>();
                    ext.put("eventKind",      "appointment");
                    ext.put("synced",         a.getCalendarEventId() != null);
                    ext.put("statusKey",      a.getStatus().name());
                    ext.put("statusLabel",    a.getStatus().label);
                    ext.put("typeKey",        a.getType().name());
                    ext.put("typeLabel",      a.getType().label);
                    ext.put("assignedToId",   a.getAssignedTo() != null ? a.getAssignedTo().getId()      : null);
                    ext.put("assignedToName", a.getAssignedTo() != null ? a.getAssignedTo().getFullName() : null);
                    ext.put("customerId",     a.getCustomer()   != null ? a.getCustomer().getId()         : null);
                    ext.put("customerName",   a.getCustomer()   != null ? a.getCustomer().getCompanyName(): null);
                    ext.put("notes",          a.getNotes());
                    ext.put("meetLink",       a.getMeetLink());

                    Map<String, Object> ev = new HashMap<>();
                    ev.put("id",            String.valueOf(a.getId()));
                    ev.put("title",         title);
                    ev.put("start",         a.getStartAt().toString());
                    ev.put("end",           a.getEndAt() != null
                            ? a.getEndAt().toString()
                            : a.getStartAt().plusHours(1).toString());
                    ev.put("color",         a.getType() == Appointment.AppointmentType.PRIVATE
                            ? "#6f42c1" : statusColor(a.getStatus()));
                    ev.put("extendedProps", ext);
                    events.add(ev);
                });

        // — Interventions planifiées —
        if (!onlyUnassigned || true) {
            interventionRepository.findScheduledBetween(from, to).stream()
                    .filter(i -> {
                        if (onlyUnassigned) return i.getTechnician() == null;
                        return !filtering || (i.getTechnician() != null
                                && userIds.contains(i.getTechnician().getId()));
                    })
                    .forEach(i -> {
                        String label = "🔧 " + i.getTitle()
                                + (i.getCustomer() != null ? " · " + i.getCustomer().getCompanyName() : "");
                        long dh = (i.getDurationMinutes() != null && i.getDurationMinutes() > 0)
                                ? i.getDurationMinutes() / 60 : 1;

                        Map<String, Object> ext = new HashMap<>();
                        ext.put("eventKind",      "intervention");
                        ext.put("statusKey",      i.getStatus().name());
                        ext.put("technicianName", i.getTechnician() != null ? i.getTechnician().getFullName() : null);
                        ext.put("customerName",   i.getCustomer()   != null ? i.getCustomer().getCompanyName(): null);

                        Map<String, Object> ev = new HashMap<>();
                        ev.put("id",            "int-" + i.getId());
                        ev.put("title",         label);
                        ev.put("start",         i.getScheduledStart().toString());
                        ev.put("end",           i.getScheduledEnd() != null
                                ? i.getScheduledEnd().toString()
                                : i.getScheduledStart().plusHours(dh).toString());
                        ev.put("color",         "#fd7e14");
                        ev.put("extendedProps", ext);
                        events.add(ev);
                    });
        }

        // — Événements externes (Google Calendar / Microsoft) —
        // Les événements déjà synchronisés localement sont exclus pour éviter les doublons
        if (!onlyUnassigned && currentUserId != null) {
            Set<String> syncedIds = new HashSet<>();
            appointmentRepository.findAllCalendarEventIds().forEach(syncedIds::add);
            interventionRepository.findAllCalendarEventIds().forEach(syncedIds::add);

            List<MailAccount> accounts = oauthAccounts(currentUserId, filtering ? userIds : null);

            for (MailAccount account : accounts) {
                String ownerName = account.getUserId() != null
                        ? userRepository.findById(account.getUserId())
                                .map(u -> u.getFullName() != null ? u.getFullName() : u.getEmail())
                                .orElse(account.getName())
                        : account.getName();
                try {
                    if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                        JsonNode result = googleMailService.listCalendarEvents(
                                account, toRfc3339(start), toRfc3339(end));
                        JsonNode items = result.path("items");
                        if (items.isArray()) {
                            for (JsonNode item : items) {
                                String googleId = item.path("id").asText("");
                                if (!googleId.isEmpty() && syncedIds.contains(googleId)) continue;
                                String editUrl = item.path("htmlLink").asText(null);
                                String location = item.path("location").asText(null);
                                String description = item.path("description").asText(null);
                                events.add(buildExternalEvent(
                                        item.path("summary").asText("(sans titre)"),
                                        item.path("start").path("dateTime").asText(
                                                item.path("start").path("date").asText("")),
                                        item.path("end").path("dateTime").asText(
                                                item.path("end").path("date").asText("")),
                                        "Google — " + account.getName(), ownerName,
                                        account.getUserId(), editUrl, location, description,
                                        currentUserId));
                            }
                        }
                    } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                        String s19 = start.length() > 19 ? start.substring(0, 19) : start;
                        String e19 = end.length()   > 19 ? end.substring(0, 19)   : end;
                        JsonNode result = microsoftMailService.listCalendarEvents(account, s19, e19);
                        JsonNode values = result.path("value");
                        if (values.isArray()) {
                            for (JsonNode item : values) {
                                String msId = item.path("id").asText("");
                                if (!msId.isEmpty() && syncedIds.contains(msId)) continue;
                                String editUrl = item.path("webLink").asText(null);
                                String location = item.path("location").path("displayName").asText(null);
                                String description = item.path("body").path("content").asText(null);
                                events.add(buildExternalEvent(
                                        item.path("subject").asText("(sans titre)"),
                                        item.path("start").path("dateTime").asText(""),
                                        item.path("end").path("dateTime").asText(""),
                                        "Outlook — " + account.getName(), ownerName,
                                        account.getUserId(), editUrl, location, description,
                                        currentUserId));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Un compte en erreur ne bloque pas les autres
                }
            }
        }

        return events;
    }

    // ── Équipes + membres (filtre calendrier) ─────────────────────────────────

    @GetMapping("/teams")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> teams() {
        return teamRepository.findAllWithMembers().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",   t.getId());
            m.put("name", t.getName());
            m.put("members", t.getMembers().stream()
                    .sorted(Comparator.comparing(u -> u.getFullName() != null ? u.getFullName() : ""))
                    .map(u -> {
                        Map<String, Object> um = new LinkedHashMap<>();
                        um.put("id",       u.getId());
                        um.put("fullName", u.getFullName() != null ? u.getFullName() : u.getEmail());
                        return um;
                    })
                    .collect(Collectors.toList()));
            return m;
        }).collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Comptes Google/Microsoft OAuth avec token valide, filtrés par utilisateur si demandé. */
    private List<MailAccount> oauthAccounts(Long currentUserId, List<Long> filterUserIds) {
        return mailAccountRepository.findAll().stream()
                .filter(a -> a.getAccessToken() != null)
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2
                          || a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .filter(a -> {
                    if (filterUserIds != null && !filterUserIds.isEmpty()) {
                        return a.getUserId() != null && filterUserIds.contains(a.getUserId());
                    }
                    // Sans filtre : uniquement l'utilisateur courant
                    return currentUserId.equals(a.getUserId());
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildExternalEvent(String title, String start, String end,
                                                    String calendarName, String ownerName,
                                                    Long ownerId, String editUrl,
                                                    String location, String description,
                                                    Long currentUserId) {
        Map<String, Object> ext = new HashMap<>();
        ext.put("eventKind",      "external");
        ext.put("calendarName",   calendarName);
        ext.put("assignedToName", ownerName);
        ext.put("assignedToId",   ownerId);
        ext.put("editUrl",        editUrl);
        ext.put("location",       location);
        ext.put("description",    description);
        // L'utilisateur peut modifier s'il est propriétaire du compte
        ext.put("canEdit",        ownerId != null && ownerId.equals(currentUserId));

        Map<String, Object> ev = new HashMap<>();
        ev.put("title",         title);
        ev.put("start",         start);
        ev.put("end",           end);
        ev.put("color",         "#9e9e9e");
        ev.put("extendedProps", ext);
        return ev;
    }

    private static LocalDateTime parseDateTime(String s) {
        String base = s.length() > 19 ? s.substring(0, 19) : s;
        return LocalDateTime.parse(base);
    }

    private static String toRfc3339(String s) {
        if (s == null || s.isBlank()) return s;
        String base = s.length() >= 19 ? s.substring(0, 19) : s;
        if (base.length() == 10) base = base + "T00:00:00";
        return base + "Z";
    }

    private static String statusColor(Appointment.AppointmentStatus s) {
        return switch (s) {
            case SCHEDULED -> "#0dcaf0";
            case CONFIRMED -> "#198754";
            case COMPLETED -> "#6c757d";
            case CANCELLED -> "#dc3545";
        };
    }
}
