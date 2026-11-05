package com.logicielpourtous.site.features.booking;

import com.logicielpourtous.site.features.appointment.Appointment;
import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MailTemplateService;
import com.logicielpourtous.site.features.mail.MailTemplateService.RenderedMail;
import com.logicielpourtous.site.features.mail.MailTemplateType;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class BookingMailService {

    private static final Logger log = LoggerFactory.getLogger(BookingMailService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.FRENCH);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Value("${app.oauth.redirect-base:http://localhost:8080}")
    private String baseUrl;

    private final MailTemplateService      templateService;
    private final MailAccountRepository    mailAccountRepository;
    private final GoogleMailService        googleMailService;
    private final MicrosoftMailService     microsoftMailService;

    public BookingMailService(MailTemplateService templateService,
                              MailAccountRepository mailAccountRepository,
                              GoogleMailService googleMailService,
                              MicrosoftMailService microsoftMailService) {
        this.templateService       = templateService;
        this.mailAccountRepository = mailAccountRepository;
        this.googleMailService     = googleMailService;
        this.microsoftMailService  = microsoftMailService;
    }

    public void sendConfirmation(Appointment appointment, BookingConfig config) {
        String to = appointment.getClientEmail();
        if (to == null || to.isBlank()) return;

        String cancelUrl = baseUrl + "/booking/cancel/" + appointment.getCancellationToken();
        String meetBlock = buildMeetBlock(appointment.getMeetLink());

        Map<String, String> vars = new HashMap<>();
        vars.put("clientName",    clientName(appointment));
        vars.put("bookingTitle",  config.getTitle());
        vars.put("date",          DATE_FMT.format(appointment.getStartAt()));
        vars.put("time",          TIME_FMT.format(appointment.getStartAt()));
        vars.put("duration",      config.getDurationMinutes() + " minutes");
        vars.put("type",          appointment.getType() != null ? appointment.getType().label : "");
        vars.put("assignedTo",    appointment.getAssignedTo() != null ? appointment.getAssignedTo().getFullName() : "");
        vars.put("meetLink",      meetBlock);
        vars.put("cancelUrl",     cancelUrl);

        RenderedMail mail = templateService.render(MailTemplateType.BOOKING_CONFIRMATION, vars);
        sendHtml(resolveAccount(appointment), to, mail.subject(), mail.html());
    }

    public void sendCancellation(Appointment appointment, BookingConfig config) {
        String to = appointment.getClientEmail();
        if (to == null || to.isBlank()) return;

        String newBookingUrl = baseUrl + "/booking/" + config.getSlug();

        Map<String, String> vars = new HashMap<>();
        vars.put("clientName",    clientName(appointment));
        vars.put("bookingTitle",  config.getTitle());
        vars.put("date",          DATE_FMT.format(appointment.getStartAt()));
        vars.put("time",          TIME_FMT.format(appointment.getStartAt()));
        vars.put("newBookingUrl", newBookingUrl);

        RenderedMail mail = templateService.render(MailTemplateType.BOOKING_CANCELLATION, vars);
        sendHtml(resolveAccount(appointment), to, mail.subject(), mail.html());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendHtml(MailAccount account, String to, String subject, String html) {
        if (account == null) {
            log.warn("Aucun compte mail disponible pour envoyer l'email à {}", to);
            return;
        }
        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.sendHtmlEmail(account, to, subject, html);
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.sendHtmlEmail(account, to, subject, html);
            } else {
                sendSmtpHtml(account, to, subject, html);
            }
        } catch (Exception e) {
            log.error("Erreur envoi email booking à {} : {}", to, e.getMessage());
        }
    }

    private void sendSmtpHtml(MailAccount account, String to, String subject, String html) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host",            account.getSmtpHost());
        props.put("mail.smtp.port",            String.valueOf(account.getSmtpPort()));
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.ssl.enable",      String.valueOf(account.isSmtpSsl()));
        props.put("mail.smtp.starttls.enable", String.valueOf(account.isSmtpStarttls()));

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(account.getUsername(), account.getEncryptedPassword());
            }
        });
        MimeMessage msg = new MimeMessage(session);
        String fromName = account.getFromName() != null ? account.getFromName() : account.getUsername();
        msg.setFrom(new InternetAddress(account.getUsername(), fromName, "UTF-8"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject, "UTF-8");
        msg.setContent(html, "text/html; charset=UTF-8");
        Transport.send(msg);
    }

    private MailAccount resolveAccount(Appointment appointment) {
        if (appointment.getAssignedTo() != null) {
            List<MailAccount.AuthType> oauth = List.of(
                    MailAccount.AuthType.GOOGLE_OAUTH2, MailAccount.AuthType.MICROSOFT_OAUTH2);
            MailAccount acc = mailAccountRepository
                    .findFirstByUserIdAndAuthTypeInAndAccessTokenIsNotNull(
                            appointment.getAssignedTo().getId(), oauth)
                    .orElse(null);
            if (acc != null) return acc;
        }
        List<MailAccount> active = mailAccountRepository.findByActiveTrueOrderByNameAsc();
        return active.isEmpty() ? null : active.get(0);
    }

    private String clientName(Appointment appointment) {
        if (appointment.getNotes() == null) return "";
        String notes = appointment.getNotes();
        String prefix = "Prénom : ";
        int start = notes.indexOf(prefix);
        if (start < 0) return "";
        int end = notes.indexOf('\n', start);
        return end > start ? notes.substring(start + prefix.length(), end).trim() : "";
    }

    private String buildMeetBlock(String meetLink) {
        if (meetLink == null || meetLink.isBlank()) return "";
        return "<div style=\"background:#f0fdf4;border-left:3px solid #22c55e;padding:12px 16px;margin:16px 0;border-radius:4px\">"
                + "<strong style=\"color:#16a34a\">🎥 Google Meet :</strong> "
                + "<a href=\"" + meetLink + "\" style=\"color:#16a34a\">" + meetLink + "</a>"
                + "</div>";
    }
}
