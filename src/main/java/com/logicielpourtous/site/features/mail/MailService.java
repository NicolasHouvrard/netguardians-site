package com.logicielpourtous.site.features.mail;

import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.CommentRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.request.TicketAttachment;
import com.logicielpourtous.site.features.request.TicketAttachmentRepository;
import com.logicielpourtous.site.features.ticketing.TicketRoutingService;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailAccountRepository      accountRepository;
    private final MailMessageRepository      mailMessageRepository;
    private final RequestRepository          requestRepository;
    private final CommentRepository          commentRepository;
    private final ContactRepository          contactRepository;
    private final TicketAttachmentRepository attachmentRepository;
    private final GoogleMailService          googleMailService;
    private final MicrosoftMailService       microsoftMailService;
    private final TicketRoutingService       routingService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public MailService(MailAccountRepository accountRepository,
                       MailMessageRepository mailMessageRepository,
                       RequestRepository requestRepository,
                       CommentRepository commentRepository,
                       ContactRepository contactRepository,
                       TicketAttachmentRepository attachmentRepository,
                       GoogleMailService googleMailService,
                       MicrosoftMailService microsoftMailService,
                       TicketRoutingService routingService) {
        this.accountRepository    = accountRepository;
        this.mailMessageRepository = mailMessageRepository;
        this.requestRepository    = requestRepository;
        this.commentRepository    = commentRepository;
        this.contactRepository    = contactRepository;
        this.attachmentRepository = attachmentRepository;
        this.googleMailService    = googleMailService;
        this.microsoftMailService = microsoftMailService;
        this.routingService       = routingService;
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 120_000)
    public void pollAllAccounts() {
        List<MailAccount> accounts = accountRepository.findByActiveTrueOrderByNameAsc();
        for (MailAccount account : accounts) {
            try {
                if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                    pollGoogleAccount(account);
                } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                    pollMicrosoftAccount(account);
                } else {
                    pollImapAccount(account);
                }
            } catch (Exception e) {
                log.error("Erreur polling compte {} : {}", account.getUsername(), e.getMessage());
            }
        }
    }

    // ── Polling Gmail API ─────────────────────────────────────────────────────

    private void pollGoogleAccount(MailAccount account) {
        try {
            List<GoogleMailService.GmailMessage> messages = googleMailService.fetchUnread(account);
            log.info("Compte Gmail {} : {} message(s) non lus", account.getUsername(), messages.size());
            for (GoogleMailService.GmailMessage gm : messages) {
                try {
                    processGmailMessage(gm, account);
                    googleMailService.markRead(account, gm.gmailId);
                } catch (Exception e) {
                    log.error("Erreur traitement message Gmail : {}", e.getMessage());
                }
            }
            account.setLastChecked(java.time.LocalDateTime.now());
            accountRepository.save(account);
        } catch (Exception e) {
            log.error("Erreur polling Gmail {} : {}", account.getUsername(), e.getMessage());
        }
    }

    @Transactional
    protected void processGmailMessage(GoogleMailService.GmailMessage gm, MailAccount account) {
        String messageId = gm.messageId != null ? gm.messageId.trim() : "<" + UUID.randomUUID() + "@gmail>";
        if (mailMessageRepository.existsByMessageId(messageId)) return;

        String fromAddress = googleMailService.extractFrom(gm.from);
        String toAddress   = googleMailService.extractFrom(gm.to);
        String subject     = gm.subject != null ? gm.subject : "(sans objet)";
        String body        = gm.body != null ? gm.body : "";
        String inReplyTo   = gm.inReplyTo;

        Request request = resolveOrCreateRequest(fromAddress, toAddress, subject, body, inReplyTo, gm.sentAt, account, messageId);
        storeMailMessage(messageId, inReplyTo, subject, fromAddress, account.getUsername(), body,
                MailMessage.Direction.INBOUND, request, account);
        for (GoogleMailService.GmailAttachment att : gm.attachments) {
            byte[] data = att.data;
            if (data == null && att.attachmentId != null)
                data = googleMailService.fetchAttachmentData(account, gm.gmailId, att.attachmentId);
            saveEmailAttachment(request, att.filename, att.mimeType, data);
        }
    }

    // ── Polling Graph API ──────────────────────────────────────────────────────

    private void pollMicrosoftAccount(MailAccount account) {
        try {
            List<MicrosoftMailService.GraphMessage> messages = microsoftMailService.fetchUnread(account);
            log.info("Compte Microsoft {} : {} message(s) non lus", account.getUsername(), messages.size());
            for (MicrosoftMailService.GraphMessage gm : messages) {
                try {
                    processMicrosoftMessage(gm, account);
                    microsoftMailService.markRead(account, gm.graphId);
                } catch (Exception e) {
                    log.error("Erreur traitement message Graph : {}", e.getMessage());
                }
            }
            account.setLastChecked(java.time.LocalDateTime.now());
            accountRepository.save(account);
        } catch (Exception e) {
            log.error("Erreur polling Microsoft {} : {}", account.getUsername(), e.getMessage());
        }
    }

    @Transactional
    protected void processMicrosoftMessage(MicrosoftMailService.GraphMessage gm, MailAccount account) {
        String messageId = gm.messageId != null ? gm.messageId.trim() : "<" + UUID.randomUUID() + "@graph>";
        if (mailMessageRepository.existsByMessageId(messageId)) return;

        String subject   = gm.subject != null ? gm.subject : "(sans objet)";
        String body      = gm.body != null ? gm.body.replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", "\n").trim() : "";
        String inReplyTo = gm.inReplyTo;

        Request request = resolveOrCreateRequest(gm.from, gm.to, subject, body, inReplyTo, gm.sentAt, account, messageId);
        storeMailMessage(messageId, inReplyTo, subject, gm.from, account.getUsername(), body,
                MailMessage.Direction.INBOUND, request, account);
    }

    // ── Polling IMAP ──────────────────────────────────────────────────────────

    private void pollImapAccount(MailAccount account) throws Exception {
        Properties props = imapProperties(account);
        Session session = Session.getInstance(props);
        Store store = session.getStore(account.isImapSsl() ? "imaps" : "imap");
        store.connect(account.getImapHost(), account.getImapPort(),
                account.getUsername(), account.getEncryptedPassword());

        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);

        Message[] unseen = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        log.info("Compte {} : {} message(s) non lus", account.getUsername(), unseen.length);

        for (Message msg : unseen) {
            try {
                processInbound((MimeMessage) msg, account);
                msg.setFlag(Flags.Flag.SEEN, true);
            } catch (Exception e) {
                log.error("Erreur traitement message IMAP : {}", e.getMessage());
            }
        }

        inbox.close(false);
        store.close();

        account.setLastChecked(java.time.LocalDateTime.now());
        accountRepository.save(account);
    }

    @Transactional
    protected void processInbound(MimeMessage msg, MailAccount account) throws Exception {
        String rawMessageId = header(msg, "Message-ID");
        String messageId    = rawMessageId != null ? rawMessageId.trim() : "<" + UUID.randomUUID() + "@mail>";
        String inReplyTo    = header(msg, "In-Reply-To");
        String subject      = msg.getSubject() != null ? msg.getSubject() : "(sans objet)";
        String fromAddress  = ((InternetAddress) msg.getFrom()[0]).getAddress();
        String toAddress    = null;
        Address[] toAddrs   = msg.getRecipients(Message.RecipientType.TO);
        if (toAddrs != null && toAddrs.length > 0) toAddress = ((InternetAddress) toAddrs[0]).getAddress();
        java.util.Date rawSent = msg.getSentDate();
        LocalDateTime sentAt   = rawSent != null
                ? rawSent.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                : null;
        String body            = extractText(msg);

        if (mailMessageRepository.existsByMessageId(messageId)) return;

        Request request = resolveOrCreateRequest(fromAddress, toAddress, subject, body, inReplyTo, sentAt, account, messageId);
        storeMailMessage(messageId, inReplyTo, subject, fromAddress, account.getUsername(), body,
                MailMessage.Direction.INBOUND, request, account);
        for (ImapAttachment att : extractImapAttachments(msg))
            saveEmailAttachment(request, att.filename(), att.contentType(), att.data());
    }

    // ── Logique commune ───────────────────────────────────────────────────────

    private Request resolveOrCreateRequest(String fromAddress, String toAddress, String subject, String body,
                                            String inReplyTo, LocalDateTime sentAt, MailAccount account, String messageId) {
        Request request = null;
        if (inReplyTo != null) {
            request = mailMessageRepository.findByMessageId(inReplyTo.trim())
                    .map(MailMessage::getRequest)
                    .orElse(null);
        }

        if (request != null) {
            Comment comment = new Comment();
            comment.setContent(body);
            comment.setPublic(true);
            comment.setSourceEmail(fromAddress);
            comment.setRequest(request);
            comment.registerAdded();
            commentRepository.save(comment);
            log.info("Réponse email rattachée à la demande #{}", request.getId());
        } else {
            Contact contact   = contactRepository.findFirstByEmail(fromAddress).orElse(null);
            Customer customer = contact != null ? contact.getCustomer() : null;

            request = new Request();
            request.setTitle(subject);
            request.setDescription(body);
            request.setContact(contact);
            request.setCustomer(customer);
            request.setSenderEmail(fromAddress);
            request.setRecipientEmail(toAddress);
            request.setSentAt(sentAt);
            request.setStatus(RequestStatus.NEW);
            request.registerChange();
            requestRepository.save(request);
            log.info("Nouveau ticket #{} créé depuis email de {}", request.getId(), fromAddress);
            routingService.applyRules(request, fromAddress);
        }
        return request;
    }

    private void storeMailMessage(String messageId, String inReplyTo, String subject,
                                   String from, String to, String body,
                                   MailMessage.Direction direction, Request request, MailAccount account) {
        MailMessage stored = new MailMessage();
        stored.setMessageId(messageId);
        stored.setInReplyTo(inReplyTo);
        stored.setSubject(subject);
        stored.setFromAddress(from);
        stored.setToAddress(to);
        stored.setBody(body);
        stored.setDirection(direction);
        stored.setRequest(request);
        stored.setAccount(account);
        mailMessageRepository.save(stored);
    }

    // ── Pièces jointes email ──────────────────────────────────────────────────

    private record ImapAttachment(String filename, String contentType, byte[] data) {}

    private List<ImapAttachment> extractImapAttachments(Part part) {
        List<ImapAttachment> result = new ArrayList<>();
        try { collectImapAttachments(part, result); } catch (Exception e) {
            log.warn("Erreur extraction PJ IMAP : {}", e.getMessage());
        }
        return result;
    }

    private void collectImapAttachments(Part part, List<ImapAttachment> result)
            throws MessagingException, IOException {
        String disposition = part.getDisposition();
        String filename    = part.getFileName();
        if (filename != null) {
            try { filename = MimeUtility.decodeText(filename); } catch (Exception ignored) {}
        }
        if (filename != null && !part.isMimeType("text/*") && !part.isMimeType("multipart/*")) {
            try (InputStream is = part.getInputStream()) {
                byte[] data = is.readAllBytes();
                String ct = part.getContentType().split(";")[0].trim();
                result.add(new ImapAttachment(filename, ct, data));
            }
        } else if (part.isMimeType("multipart/*")) {
            MimeMultipart mp = (MimeMultipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++)
                collectImapAttachments(mp.getBodyPart(i), result);
        }
    }

    private void saveEmailAttachment(Request request, String filename, String mimeType, byte[] data) {
        if (data == null || data.length == 0 || filename == null || filename.isBlank()) return;
        try {
            String safe = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            String ext  = safe.contains(".") ? safe.substring(safe.lastIndexOf('.')) : "";
            String stored = UUID.randomUUID() + ext;
            Path dir = Paths.get(uploadDir, "tickets", String.valueOf(request.getId()));
            Files.createDirectories(dir);
            Files.write(dir.resolve(stored), data);
            TicketAttachment att = new TicketAttachment();
            att.setRequest(request);
            att.setFilename("/uploads/tickets/" + request.getId() + "/" + stored);
            att.setOriginalName(filename);
            att.setContentType(mimeType != null ? mimeType : "application/octet-stream");
            att.setFileSize((long) data.length);
            attachmentRepository.save(att);
            log.info("PJ '{}' sauvegardée pour ticket #{}", filename, request.getId());
        } catch (Exception e) {
            log.error("Erreur sauvegarde PJ '{}' ticket #{} : {}", filename, request.getId(), e.getMessage());
        }
    }

    // ── Envoi SMTP ────────────────────────────────────────────────────────────

    public void sendReply(Request request, String body) {
        List<MailAccount> accounts = accountRepository.findByActiveTrueOrderByNameAsc();
        if (accounts.isEmpty()) {
            log.warn("Aucun compte mail actif, réponse non envoyée pour la demande #{}", request.getId());
            return;
        }
        sendReply(request, body, accounts.get(0));
    }

    public void sendReply(Request request, String body, MailAccount account) {
        String toEmail = resolveRecipient(request);
        if (toEmail == null) {
            log.warn("Pas d'email destinataire pour la demande #{}", request.getId());
            return;
        }

        String subj = request.getTitle();
        if (subj != null && !subj.toLowerCase().startsWith("re:")) subj = "Re: " + subj;
        if (subj == null) subj = "Re: (sans objet)";

        String generatedId = "<" + UUID.randomUUID() + "@" + account.getUsername().replaceAll(".*@", "") + ">";
        String parentId    = null;
        List<MailMessage> history = mailMessageRepository.findLastByRequestId(request.getId());
        if (!history.isEmpty()) parentId = history.get(0).getMessageId();

        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.sendEmail(account, toEmail, subj, body, parentId, generatedId);
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.sendEmail(account, toEmail, subj, body, parentId, generatedId);
            } else {
                sendSmtp(account, toEmail, subj, body, parentId, generatedId);
            }

            storeMailMessage(generatedId, parentId, subj, account.getUsername(), toEmail, body,
                    MailMessage.Direction.OUTBOUND, request, account);
            log.info("Email envoyé à {} pour la demande #{}", toEmail, request.getId());
        } catch (Exception e) {
            log.error("Erreur envoi email pour demande #{} : {}", request.getId(), e.getMessage());
        }
    }

    private void sendSmtp(MailAccount account, String toEmail, String subj, String body,
                           String parentId, String generatedId) throws Exception {
        Properties props = smtpProperties(account);
        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(account.getUsername(), account.getEncryptedPassword());
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setHeader("Message-ID", generatedId);
        if (parentId != null) {
            msg.setHeader("In-Reply-To", parentId);
            msg.setHeader("References",  parentId);
        }
        String fromName = account.getFromName() != null ? account.getFromName() : account.getUsername();
        msg.setFrom(new InternetAddress(account.getUsername(), fromName, "UTF-8"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));
        msg.setSubject(subj, "UTF-8");
        msg.setText(body, "UTF-8");
        Transport.send(msg);
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private String resolveRecipient(Request request) {
        if (request.getContact() != null && request.getContact().getEmail() != null)
            return request.getContact().getEmail();
        if (request.getCustomer() != null && request.getCustomer().getEmail() != null)
            return request.getCustomer().getEmail();
        return null;
    }

    private String header(MimeMessage msg, String name) {
        try {
            String[] values = msg.getHeader(name);
            return (values != null && values.length > 0) ? values[0] : null;
        } catch (MessagingException e) { return null; }
    }

    private String extractText(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("text/html")) {
            return ((String) part.getContent())
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s{2,}", "\n")
                    .trim();
        }
        if (part.isMimeType("multipart/*")) {
            MimeMultipart mp = (MimeMultipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) return (String) bp.getContent();
            }
            for (int i = 0; i < mp.getCount(); i++) {
                String result = extractText(mp.getBodyPart(i));
                if (result != null) return result;
            }
        }
        return "";
    }

    private Properties imapProperties(MailAccount a) {
        Properties p = new Properties();
        p.put("mail.imap.host", a.getImapHost());
        p.put("mail.imap.port", String.valueOf(a.getImapPort()));
        p.put("mail.imap.ssl.enable", String.valueOf(a.isImapSsl()));
        p.put("mail.imap.connectiontimeout", "10000");
        p.put("mail.imap.timeout", "10000");
        return p;
    }

    private Properties smtpProperties(MailAccount a) {
        Properties p = new Properties();
        p.put("mail.smtp.host", a.getSmtpHost());
        p.put("mail.smtp.port", String.valueOf(a.getSmtpPort()));
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.ssl.enable", String.valueOf(a.isSmtpSsl()));
        p.put("mail.smtp.starttls.enable", String.valueOf(a.isSmtpStarttls()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        return p;
    }
}
