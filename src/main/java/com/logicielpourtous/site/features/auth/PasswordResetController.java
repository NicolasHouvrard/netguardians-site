package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MailTemplateService;
import com.logicielpourtous.site.features.mail.MailTemplateService.RenderedMail;
import com.logicielpourtous.site.features.mail.MailTemplateType;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Controller
public class PasswordResetController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);
    private static final int TOKEN_VALIDITY_MINUTES = 15;

    @Value("${app.oauth.redirect-base:http://localhost:8080}")
    private String baseUrl;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailTemplateService templateService;
    private final MailAccountRepository mailAccountRepository;
    private final GoogleMailService googleMailService;
    private final MicrosoftMailService microsoftMailService;

    public PasswordResetController(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   MailTemplateService templateService,
                                   MailAccountRepository mailAccountRepository,
                                   GoogleMailService googleMailService,
                                   MicrosoftMailService microsoftMailService) {
        this.userRepository       = userRepository;
        this.passwordEncoder      = passwordEncoder;
        this.templateService      = templateService;
        this.mailAccountRepository = mailAccountRepository;
        this.googleMailService    = googleMailService;
        this.microsoftMailService = microsoftMailService;
    }

    // ── Étape 1 : formulaire saisie email ─────────────────────────────────────

    @GetMapping("/reset-password")
    public String resetForm(@RequestParam(required = false) String sent, Model model) {
        model.addAttribute("sent", sent != null);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetRequest(@RequestParam String email) {
        Optional<User> opt = userRepository.findByEmail(email.trim().toLowerCase());
        if (opt.isPresent()) {
            User user = opt.get();
            String token = UUID.randomUUID().toString();
            user.setResetToken(token);
            user.setResetTokenExpiresAt(LocalDateTime.now().plusMinutes(TOKEN_VALIDITY_MINUTES));
            userRepository.save(user);
            sendResetEmail(user, token);
        }
        // toujours rediriger avec le même message (sécurité anti-enumeration)
        return "redirect:/reset-password?sent";
    }

    // ── Étape 2 : formulaire nouveau mot de passe ─────────────────────────────

    @GetMapping("/reset-password/confirm")
    public String confirmForm(@RequestParam String token, Model model) {
        Optional<User> opt = userRepository.findByResetToken(token);
        if (opt.isEmpty() || opt.get().getResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
            model.addAttribute("error", "Ce lien est invalide ou a expiré. Veuillez refaire une demande.");
            return "reset-password-confirm";
        }
        model.addAttribute("token", token);
        return "reset-password-confirm";
    }

    @PostMapping("/reset-password/confirm")
    public String confirmReset(@RequestParam String token,
                               @RequestParam String password,
                               @RequestParam String passwordConfirm,
                               Model model) {
        Optional<User> opt = userRepository.findByResetToken(token);
        if (opt.isEmpty() || opt.get().getResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
            model.addAttribute("error", "Ce lien est invalide ou a expiré. Veuillez refaire une demande.");
            return "reset-password-confirm";
        }
        if (!password.equals(passwordConfirm)) {
            model.addAttribute("token", token);
            model.addAttribute("error", "Les mots de passe ne correspondent pas.");
            return "reset-password-confirm";
        }
        if (password.length() < 8) {
            model.addAttribute("token", token);
            model.addAttribute("error", "Le mot de passe doit contenir au moins 8 caractères.");
            return "reset-password-confirm";
        }
        User user = opt.get();
        user.setPassword(passwordEncoder.encode(password));
        user.setResetToken(null);
        user.setResetTokenExpiresAt(null);
        userRepository.save(user);
        return "redirect:/login?reset=true";
    }

    // ── Envoi email ───────────────────────────────────────────────────────────

    private void sendResetEmail(User user, String token) {
        String resetUrl = baseUrl + "/reset-password/confirm?token=" + token;
        Map<String, String> vars = new HashMap<>();
        vars.put("fullName",  user.getFullName() != null ? user.getFullName() : user.getEmail());
        vars.put("resetUrl",  resetUrl);
        vars.put("expiresIn", TOKEN_VALIDITY_MINUTES + " minutes");

        RenderedMail mail = templateService.render(MailTemplateType.PASSWORD_RESET, vars);
        MailAccount account = resolveAccount();
        if (account == null) {
            log.warn("Aucun compte mail configuré pour l'envoi de réinitialisation à {}", user.getEmail());
            return;
        }
        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.sendHtmlEmail(account, user.getEmail(), mail.subject(), mail.html());
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.sendHtmlEmail(account, user.getEmail(), mail.subject(), mail.html());
            } else {
                sendSmtpHtml(account, user.getEmail(), mail.subject(), mail.html());
            }
        } catch (Exception e) {
            log.error("Erreur envoi email reset à {} : {}", user.getEmail(), e.getMessage());
        }
    }

    private MailAccount resolveAccount() {
        List<MailAccount> active = mailAccountRepository.findByActiveTrueOrderByNameAsc();
        return active.isEmpty() ? null : active.get(0);
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
}
