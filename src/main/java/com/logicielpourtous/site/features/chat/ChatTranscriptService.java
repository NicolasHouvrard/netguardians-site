package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

@Service
public class ChatTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ChatMessageRepository messageRepository;
    private final MailAccountRepository mailAccountRepository;
    private final GoogleMailService googleMailService;
    private final MicrosoftMailService microsoftMailService;

    public ChatTranscriptService(ChatMessageRepository messageRepository,
                                 MailAccountRepository mailAccountRepository,
                                 GoogleMailService googleMailService,
                                 MicrosoftMailService microsoftMailService) {
        this.messageRepository  = messageRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.googleMailService  = googleMailService;
        this.microsoftMailService = microsoftMailService;
    }

    public void sendTranscript(ChatConversation conversation, String toEmail) {
        List<ChatMessage> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conversation);
        String html = buildHtml(conversation, messages);
        String subject = "Transcription de votre conversation du " +
                conversation.getCreatedAt().format(FMT);

        List<MailAccount> accounts = mailAccountRepository.findByActiveTrueOrderByNameAsc();
        if (accounts.isEmpty()) {
            log.warn("Aucun compte mail pour envoyer la transcription à {}", toEmail);
            return;
        }
        MailAccount account = accounts.get(0);
        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.sendHtmlEmail(account, toEmail, subject, html);
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.sendHtmlEmail(account, toEmail, subject, html);
            } else {
                sendSmtp(account, toEmail, subject, html);
            }
            log.info("Transcription envoyée à {}", toEmail);
        } catch (Exception e) {
            log.error("Erreur envoi transcription à {} : {}", toEmail, e.getMessage());
        }
    }

    private String buildHtml(ChatConversation conversation, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:sans-serif;max-width:600px;margin:0 auto'>");
        sb.append("<h2 style='color:#1e40af'>Transcription de conversation</h2>");
        sb.append("<p style='color:#64748b'>Date : ").append(conversation.getCreatedAt().format(FMT)).append("</p>");
        sb.append("<hr style='border:1px solid #e2e8f0'>");

        for (ChatMessage m : messages) {
            boolean isAgent = m.getSenderType() == SenderType.USER;
            boolean isBot   = m.getSenderType() == SenderType.BOT || m.getSenderType() == SenderType.AI;
            String bg    = isAgent ? "#eff6ff" : isBot ? "#f8fafc" : "#f0fdf4";
            String align = isAgent ? "right" : "left";
            sb.append("<div style='text-align:").append(align).append(";margin:8px 0'>");
            sb.append("<span style='font-size:11px;color:#94a3b8'>")
              .append(m.getSenderName() != null ? m.getSenderName() : "Visiteur")
              .append(" — ").append(m.getCreatedAt().format(FMT)).append("</span><br>");
            sb.append("<span style='display:inline-block;padding:8px 12px;border-radius:8px;background:")
              .append(bg).append(";max-width:80%'>")
              .append(m.getContent().replace("\n", "<br>"))
              .append("</span></div>");
        }

        if (conversation.getAiSummary() != null && !conversation.getAiSummary().isBlank()) {
            sb.append("<hr style='border:1px solid #e2e8f0'>");
            sb.append("<h3 style='color:#7c3aed'>Résumé IA</h3>");
            sb.append("<p>").append(conversation.getAiSummary().replace("\n", "<br>")).append("</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private void sendSmtp(MailAccount account, String to, String subject, String html) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host",       account.getSmtpHost());
        props.put("mail.smtp.port",       String.valueOf(account.getSmtpPort()));
        props.put("mail.smtp.auth",       "true");
        props.put("mail.smtp.ssl.enable", String.valueOf(account.isSmtpSsl()));
        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(account.getUsername(), account.getEncryptedPassword());
            }
        });
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(account.getUsername()));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject, "UTF-8");
        msg.setContent(html, "text/html; charset=UTF-8");
        Transport.send(msg);
    }
}
