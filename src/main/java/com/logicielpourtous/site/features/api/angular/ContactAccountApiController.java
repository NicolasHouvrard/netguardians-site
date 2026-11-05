package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MailTemplateService;
import com.logicielpourtous.site.features.mail.MailTemplateType;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/intra/customers/{cid}/contacts/{ctid}/account")
public class ContactAccountApiController {

    private static final Logger log = LoggerFactory.getLogger(ContactAccountApiController.class);

    @Value("${app.oauth.redirect-base:http://localhost:8080}")
    private String baseUrl;

    private final ContactRepository    contactRepository;
    private final UserRepository       userRepository;
    private final RoleRepository       roleRepository;
    private final PortalUserService    portalUserService;
    private final MailTemplateService  templateService;
    private final MailAccountRepository mailAccountRepository;
    private final GoogleMailService    googleMailService;
    private final MicrosoftMailService microsoftMailService;

    public ContactAccountApiController(ContactRepository contactRepository,
                                       UserRepository userRepository,
                                       RoleRepository roleRepository,
                                       PortalUserService portalUserService,
                                       MailTemplateService templateService,
                                       MailAccountRepository mailAccountRepository,
                                       GoogleMailService googleMailService,
                                       MicrosoftMailService microsoftMailService) {
        this.contactRepository    = contactRepository;
        this.userRepository       = userRepository;
        this.roleRepository       = roleRepository;
        this.portalUserService    = portalUserService;
        this.templateService      = templateService;
        this.mailAccountRepository = mailAccountRepository;
        this.googleMailService    = googleMailService;
        this.microsoftMailService = microsoftMailService;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record AccountDto(
            Long         userId,
            boolean      active,
            boolean      emailVerified,
            boolean      mustChangePassword,
            List<String> roles
    ) {
        static AccountDto from(User u) {
            return new AccountDto(
                    u.getId(),
                    u.isActive(),
                    u.getEmailVerificationToken() == null,
                    u.isMustChangePassword(),
                    u.getRoles().stream().map(Role::getName).toList()
            );
        }
    }

    public record EsnRolesRequest(List<String> roleNames) {}

    private static final List<String> ESN_ROLES =
            List.of("USER", "COMMERCIAL", "TECHNICIEN", "MARKETING", "COMPTABLE", "ADMIN");

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Optional<Contact> resolve(Long cid, Long ctid) {
        return contactRepository.findById(ctid)
                .filter(c -> c.getCustomer() != null && c.getCustomer().getId().equals(cid));
    }

    // ── GET — état du compte ──────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<AccountDto> get(@PathVariable Long cid, @PathVariable Long ctid) {
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> ResponseEntity.ok(AccountDto.from(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST — créer un compte ────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long cid, @PathVariable Long ctid) {
        Contact contact = resolve(cid, ctid).orElse(null);
        if (contact == null) return ResponseEntity.notFound().build();
        if (contact.getEmail() == null || contact.getEmail().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Le contact n'a pas d'email."));
        if (contact.getUserId() != null)
            return ResponseEntity.badRequest().body(Map.of("error", "Un compte existe déjà."));

        return portalUserService.createForContact(contact, cid, "CLIENT")
                .<ResponseEntity<?>>map(result -> {
                    contact.setUserId(result.user().getId());
                    contactRepository.save(contact);
                    sendVerificationEmailInternal(result.user());
                    return ResponseEntity.ok(AccountDto.from(result.user()));
                })
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(Map.of("error", "Compte déjà existant pour cet email.")));
    }

    // ── DELETE — révoquer (désactiver) ────────────────────────────────────────

    @DeleteMapping
    public ResponseEntity<?> revoke(@PathVariable Long cid, @PathVariable Long ctid) {
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> {
                    u.setActive(false);
                    userRepository.save(u);
                    return ResponseEntity.ok(AccountDto.from(u));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH — réactiver ────────────────────────────────────────────────────

    @PatchMapping("/activate")
    public ResponseEntity<?> activate(@PathVariable Long cid, @PathVariable Long ctid) {
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> {
                    u.setActive(true);
                    userRepository.save(u);
                    return ResponseEntity.ok(AccountDto.from(u));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH — promouvoir en GESTIONNAIRE ───────────────────────────────────

    @PatchMapping("/promote")
    public ResponseEntity<?> promote(@PathVariable Long cid, @PathVariable Long ctid,
                                     Authentication auth) {
        if (!canManageCustomer(auth, cid))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Vous ne pouvez gérer que les contacts de votre propre entreprise."));
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> {
                    portalUserService.promoteToGestionnaire(u);
                    return ResponseEntity.ok(AccountDto.from(u));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH — rétrograder en CLIENT ────────────────────────────────────────

    @PatchMapping("/demote")
    public ResponseEntity<?> demote(@PathVariable Long cid, @PathVariable Long ctid,
                                    Authentication auth) {
        if (!canManageCustomer(auth, cid))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Vous ne pouvez gérer que les contacts de votre propre entreprise."));
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> {
                    portalUserService.demoteToClient(u);
                    return ResponseEntity.ok(AccountDto.from(u));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Vérification : GESTIONNAIRE ne peut agir que sur son propre customer ──

    private boolean canManageCustomer(Authentication auth, Long cid) {
        if (!(auth.getPrincipal() instanceof CustomUserDetails user)) return false;
        boolean isEsnRole = user.getAuthorities().stream()
                .anyMatch(a -> java.util.List.of(
                        "ROLE_ADMIN","ROLE_USER","ROLE_COMMERCIAL",
                        "ROLE_TECHNICIEN","ROLE_MARKETING","ROLE_COMPTABLE")
                        .contains(a.getAuthority()));
        if (isEsnRole) return true;  // accès total pour les rôles internes
        // GESTIONNAIRE : seulement ses propres contacts
        return user.getCustomerId() != null && user.getCustomerId().equals(cid);
    }

    // ── PUT — rôles ESN ──────────────────────────────────────────────────────

    @PutMapping("/esn-roles")
    public ResponseEntity<?> setEsnRoles(@PathVariable Long cid, @PathVariable Long ctid,
                                         @RequestBody EsnRolesRequest req) {
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> {
                    // Retire tous les rôles ESN existants
                    u.getRoles().removeIf(r -> ESN_ROLES.contains(r.getName()));
                    // Ajoute les nouveaux rôles ESN demandés
                    for (String name : req.roleNames()) {
                        if (ESN_ROLES.contains(name)) {
                            Role role = roleRepository.findByName(name)
                                    .orElseGet(() -> roleRepository.save(new Role(name, name, "ESN")));
                            u.getRoles().add(role);
                        }
                    }
                    userRepository.save(u);
                    return ResponseEntity.ok(AccountDto.from(u));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST — renvoyer email de vérification ─────────────────────────────────

    @PostMapping("/send-verification")
    public ResponseEntity<?> sendVerification(@PathVariable Long cid, @PathVariable Long ctid) {
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> {
                    if (u.getEmailVerificationToken() == null) {
                        String token = UUID.randomUUID().toString();
                        u.setEmailVerificationToken(token);
                        userRepository.save(u);
                    }
                    sendVerificationEmailInternal(u);
                    return ResponseEntity.ok(Map.of("sent", true));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST — envoyer email de réinitialisation ──────────────────────────────

    @PostMapping("/send-reset")
    public ResponseEntity<?> sendReset(@PathVariable Long cid, @PathVariable Long ctid) {
        return resolve(cid, ctid)
                .flatMap(c -> c.getUserId() != null
                        ? userRepository.findById(c.getUserId()) : Optional.empty())
                .map(u -> {
                    String token = UUID.randomUUID().toString();
                    u.setResetToken(token);
                    u.setResetTokenExpiresAt(LocalDateTime.now().plusMinutes(15));
                    userRepository.save(u);
                    sendResetEmailInternal(u, token);
                    return ResponseEntity.ok(Map.of("sent", true));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Envoi des emails ──────────────────────────────────────────────────────

    private void sendVerificationEmailInternal(User user) {
        String token = user.getEmailVerificationToken();
        if (token == null) return;
        String url = baseUrl + "/register/verify?token=" + token;
        Map<String, String> vars = new HashMap<>();
        vars.put("fullName",    user.getFullName() != null ? user.getFullName() : user.getEmail());
        vars.put("companyName", "");
        vars.put("verifyUrl",   url);
        send(user.getEmail(),
                templateService.render(MailTemplateType.REGISTRATION_CONFIRMATION, vars));
    }

    private void sendResetEmailInternal(User user, String token) {
        String url = baseUrl + "/reset-password/confirm?token=" + token;
        Map<String, String> vars = new HashMap<>();
        vars.put("fullName",  user.getFullName() != null ? user.getFullName() : user.getEmail());
        vars.put("resetUrl",  url);
        vars.put("expiresIn", "15 minutes");
        send(user.getEmail(),
                templateService.render(MailTemplateType.PASSWORD_RESET, vars));
    }

    private void send(String to, MailTemplateService.RenderedMail mail) {
        List<MailAccount> active = mailAccountRepository.findByActiveTrueOrderByNameAsc();
        if (active.isEmpty()) { log.warn("Aucun compte mail configuré pour {}", to); return; }
        MailAccount account = active.get(0);
        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.sendHtmlEmail(account, to, mail.subject(), mail.html());
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.sendHtmlEmail(account, to, mail.subject(), mail.html());
            } else {
                sendSmtp(account, to, mail.subject(), mail.html());
            }
        } catch (Exception e) {
            log.error("Erreur envoi mail à {} : {}", to, e.getMessage());
        }
    }

    private void sendSmtp(MailAccount account, String to, String subject, String html) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host",            account.getSmtpHost());
        props.put("mail.smtp.port",            String.valueOf(account.getSmtpPort()));
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.ssl.enable",      String.valueOf(account.isSmtpSsl()));
        props.put("mail.smtp.starttls.enable", String.valueOf(account.isSmtpStarttls()));
        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(account.getUsername(), account.getEncryptedPassword());
            }
        });
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(account.getUsername(),
                account.getFromName() != null ? account.getFromName() : account.getUsername(), "UTF-8"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject, "UTF-8");
        msg.setContent(html, "text/html; charset=UTF-8");
        Transport.send(msg);
    }
}
