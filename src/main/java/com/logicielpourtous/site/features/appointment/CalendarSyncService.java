package com.logicielpourtous.site.features.appointment;

import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    @Value("${app.oauth.redirect-base:http://localhost:8080}")
    private String baseUrl;

    private final MailAccountRepository  mailAccountRepository;
    private final AppointmentRepository  appointmentRepository;
    private final InterventionRepository interventionRepository;
    private final GoogleMailService      googleMailService;
    private final MicrosoftMailService   microsoftMailService;

    public CalendarSyncService(MailAccountRepository mailAccountRepository,
                               AppointmentRepository appointmentRepository,
                               InterventionRepository interventionRepository,
                               GoogleMailService googleMailService,
                               MicrosoftMailService microsoftMailService) {
        this.mailAccountRepository  = mailAccountRepository;
        this.appointmentRepository  = appointmentRepository;
        this.interventionRepository = interventionRepository;
        this.googleMailService      = googleMailService;
        this.microsoftMailService   = microsoftMailService;
    }

    /** Crée ou met à jour l'événement dans le calendrier externe. */
    public void sync(Appointment appointment, Long calendarAccountId) {
        sync(appointment, calendarAccountId, false, null);
    }

    /** Crée ou met à jour l'événement, avec option Google Meet et invitation client. */
    public void sync(Appointment appointment, Long calendarAccountId, boolean withMeet, String attendeeEmail) {
        if (calendarAccountId == null) return;
        mailAccountRepository.findById(calendarAccountId).ifPresent(account -> {
            try {
                String eventId;
                if (appointment.getCalendarEventId() == null) {
                    if (withMeet && account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                        String desc = buildDescription(appointment);
                        String[] result = googleMailService.createCalendarEventWithMeet(
                                account, appointment.getTitle(), desc,
                                appointment.getStartAt(), appointment.getEndAt(), attendeeEmail);
                        eventId = result[0];
                        if (result[1] != null && !result[1].isBlank()) {
                            appointment.setMeetLink(result[1]);
                        }
                    } else {
                        eventId = createEvent(account, appointment);
                    }
                } else {
                    updateEvent(account, appointment);
                    eventId = appointment.getCalendarEventId();
                }
                appointment.setCalendarEventId(eventId);
                appointment.setCalendarAccountId(calendarAccountId);
                appointmentRepository.save(appointment);
                log.info("Appointment {} synced to calendar account {}", appointment.getId(), calendarAccountId);
            } catch (Exception e) {
                log.warn("Calendar sync failed for appointment {}: {}", appointment.getId(), e.getMessage());
            }
        });
    }

    /** Supprime l'événement du calendrier externe (ex: annulation). */
    public void delete(Appointment appointment) {
        if (appointment.getCalendarEventId() == null || appointment.getCalendarAccountId() == null) return;
        mailAccountRepository.findById(appointment.getCalendarAccountId()).ifPresent(account -> {
            try {
                if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                    googleMailService.deleteCalendarEvent(account, appointment.getCalendarEventId());
                } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                    microsoftMailService.deleteCalendarEvent(account, appointment.getCalendarEventId());
                }
                appointment.setCalendarEventId(null);
                appointment.setCalendarAccountId(null);
                appointmentRepository.save(appointment);
            } catch (Exception e) {
                log.warn("Calendar delete failed for appointment {}: {}", appointment.getId(), e.getMessage());
            }
        });
    }

    private String createEvent(MailAccount account, Appointment a) throws Exception {
        String desc = buildDescription(a);
        if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
            return googleMailService.createCalendarEvent(account, a.getTitle(), desc, a.getStartAt(), a.getEndAt());
        } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
            return microsoftMailService.createCalendarEvent(account, a.getTitle(), desc, a.getStartAt(), a.getEndAt());
        }
        throw new UnsupportedOperationException("Unsupported calendar provider: " + account.getAuthType());
    }

    private void updateEvent(MailAccount account, Appointment a) {
        String desc = buildDescription(a);
        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.updateCalendarEvent(account, a.getCalendarEventId(),
                        a.getTitle(), desc, a.getStartAt(), a.getEndAt());
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.updateCalendarEvent(account, a.getCalendarEventId(),
                        a.getTitle(), desc, a.getStartAt(), a.getEndAt());
            }
        } catch (Exception e) {
            log.warn("Calendar update failed: {}", e.getMessage());
        }
    }

    /** Synchronise une intervention planifiée dans l'agenda du technicien. */
    public void syncIntervention(Intervention intervention, Long calendarAccountId) {
        if (intervention.getScheduledStart() == null || calendarAccountId == null) return;
        mailAccountRepository.findById(calendarAccountId).ifPresent(account -> {
            try {
                String title = "🔧 " + intervention.getTitle()
                        + (intervention.getCustomer() != null ? " — " + intervention.getCustomer().getCompanyName() : "");
                String desc  = buildInterventionDescription(intervention);
                java.time.LocalDateTime start = intervention.getScheduledStart();
                java.time.LocalDateTime end   = intervention.getScheduledEnd();
                String eventId;
                if (intervention.getCalendarEventId() == null) {
                    if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                        eventId = googleMailService.createCalendarEvent(account, title, desc, start, end);
                    } else {
                        eventId = microsoftMailService.createCalendarEvent(account, title, desc, start, end);
                    }
                } else {
                    updateEventRaw(account, intervention.getCalendarEventId(), title, desc, start, end);
                    eventId = intervention.getCalendarEventId();
                }
                intervention.setCalendarEventId(eventId);
                intervention.setCalendarAccountId(calendarAccountId);
                interventionRepository.save(intervention);
                log.info("Intervention {} synced to calendar account {}", intervention.getId(), calendarAccountId);
            } catch (Exception e) {
                log.warn("Calendar sync failed for intervention {}: {}", intervention.getId(), e.getMessage());
            }
        });
    }

    /** Supprime l'événement calendrier d'une intervention annulée. */
    public void deleteIntervention(Intervention intervention) {
        if (intervention.getCalendarEventId() == null || intervention.getCalendarAccountId() == null) return;
        mailAccountRepository.findById(intervention.getCalendarAccountId()).ifPresent(account -> {
            try {
                if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                    googleMailService.deleteCalendarEvent(account, intervention.getCalendarEventId());
                } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                    microsoftMailService.deleteCalendarEvent(account, intervention.getCalendarEventId());
                }
                intervention.setCalendarEventId(null);
                intervention.setCalendarAccountId(null);
                interventionRepository.save(intervention);
            } catch (Exception e) {
                log.warn("Calendar delete failed for intervention {}: {}", intervention.getId(), e.getMessage());
            }
        });
    }

    private String buildInterventionDescription(Intervention i) {
        StringBuilder sb = new StringBuilder();
        if (i.getCustomer() != null) {
            sb.append("Client : ").append(i.getCustomer().getCompanyName()).append("\n");
        }
        if (i.getAddress() != null) {
            sb.append("Adresse : ").append(i.getAddress());
            if (i.getZipCode() != null) sb.append(", ").append(i.getZipCode());
            if (i.getCity()    != null) sb.append(" ").append(i.getCity());
            sb.append("\n");
        }
        if (i.getScheduledStart() != null) {
            sb.append("Date/heure : ").append(i.getScheduledStart().toString().replace("T", " ").substring(0, 16));
            if (i.getScheduledEnd() != null)
                sb.append(" → ").append(i.getScheduledEnd().toString().substring(11, 16));
            sb.append("\n");
        }
        sb.append("\n🔗 Fiche intervention : ").append(baseUrl).append("/intra/interventions/").append(i.getId());
        if (i.getDescription() != null && !i.getDescription().isBlank()) {
            sb.append("\n\n").append(i.getDescription());
        }
        return sb.toString();
    }

    private void updateEventRaw(MailAccount account, String eventId, String title, String desc,
                                java.time.LocalDateTime start, java.time.LocalDateTime end) {
        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.updateCalendarEvent(account, eventId, title, desc, start, end);
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.updateCalendarEvent(account, eventId, title, desc, start, end);
            }
        } catch (Exception e) {
            log.warn("Calendar update failed: {}", e.getMessage());
        }
    }

    private String buildDescription(Appointment a) {
        StringBuilder sb = new StringBuilder();
        if (a.getCustomer() != null) sb.append("Client : ").append(a.getCustomer().getCompanyName()).append("\n");
        if (a.getType() != null)     sb.append("Type : ").append(a.getType().label).append("\n");
        if (a.getNotes() != null && !a.getNotes().isBlank()) sb.append("\n").append(a.getNotes());
        return sb.toString();
    }
}
