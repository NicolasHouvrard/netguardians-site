package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.Site;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MailTemplateService;
import com.logicielpourtous.site.features.mail.MailTemplateService.RenderedMail;
import com.logicielpourtous.site.features.mail.MailTemplateType;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.registration.SubscriptionRequest;
import com.logicielpourtous.site.features.registration.SubscriptionRequestRepository;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import com.logicielpourtous.site.config.CaptchaService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Value("${app.oauth.redirect-base:http://localhost:8080}")
    private String baseUrl;

    @Value("${captcha.site-key:}")
    private String captchaSiteKey;

    private final CaptchaService captchaService;
    private final UserRepository userRepository;
    private final CustomerRepository             customerRepository;
    private final SiteRepository                 siteRepository;
    private final ContactRepository              contactRepository;
    private final PortalUserService              portalUserService;
    private final SubscriptionRequestRepository  subscriptionRequestRepository;
    private final MailTemplateService            templateService;
    private final MailAccountRepository          mailAccountRepository;
    private final GoogleMailService              googleMailService;
    private final MicrosoftMailService           microsoftMailService;
    private final ClientRegistrationRepository   clientRegistrationRepository;

    public AuthController(CaptchaService captchaService,
                          UserRepository userRepository,
                          CustomerRepository customerRepository,
                          SiteRepository siteRepository,
                          ContactRepository contactRepository,
                          PortalUserService portalUserService,
                          SubscriptionRequestRepository subscriptionRequestRepository,
                          MailTemplateService templateService,
                          MailAccountRepository mailAccountRepository,
                          GoogleMailService googleMailService,
                          MicrosoftMailService microsoftMailService,
                          ClientRegistrationRepository clientRegistrationRepository) {
        this.captchaService                = captchaService;
        this.userRepository                = userRepository;
        this.customerRepository            = customerRepository;
        this.siteRepository                = siteRepository;
        this.contactRepository             = contactRepository;
        this.portalUserService             = portalUserService;
        this.subscriptionRequestRepository = subscriptionRequestRepository;
        this.templateService               = templateService;
        this.mailAccountRepository         = mailAccountRepository;
        this.googleMailService             = googleMailService;
        this.microsoftMailService          = microsoftMailService;
        this.clientRegistrationRepository  = clientRegistrationRepository;
    }

    @GetMapping("/register")
    public String showRegisterPage(Model model) {
        model.addAttribute("captchaEnabled", captchaService.isEnabled());
        model.addAttribute("captchaSiteKey", captchaSiteKey);
        return "feature/auth/register";
    }

    @GetMapping("/register/confirm")
    public String registerConfirm() {
        return "feature/auth/register-confirm";
    }

    @PostMapping("/register")
    public String register(@RequestParam(defaultValue = "CLIENT") String accountType,
                           // ── Champs communs ──
                           @RequestParam(required = false) String companyName,
                           @RequestParam(required = false) String fullName,
                           @RequestParam(required = false) String email,
                           // ── Client uniquement ──
                           @RequestParam(required = false) String password,
                           // ── ESN uniquement ──
                           @RequestParam(required = false) String phone,
                           @RequestParam(required = false) String plan,
                           Model model) {

        if ("ESN".equals(accountType)) {
            return registerEsn(companyName, fullName, email, phone, plan, model);
        } else {
            return registerClient(companyName, fullName, email, password, model);
        }
    }

    // ── Inscription CLIENT ─────────────────────────────────────────────────────

    private String registerClient(String companyName, String fullName,
                                   String email, String password, Model model) {
        if (email == null || email.isBlank() || password == null || password.isBlank()
                || companyName == null || companyName.isBlank()) {
            model.addAttribute("error", "Tous les champs sont obligatoires.");
            model.addAttribute("activeType", "CLIENT");
            return "feature/auth/register";
        }
        if (userRepository.findByEmail(email).isPresent()) {
            model.addAttribute("error", "Cet email est déjà utilisé.");
            model.addAttribute("activeType", "CLIENT");
            return "feature/auth/register";
        }

        // 1. Créer le client
        Customer customer = new Customer();
        customer.setCompanyName(companyName);
        customer.setEmail(email);
        customer.setContactName(fullName);
        customerRepository.save(customer);

        // 2. Site "Siège" par défaut
        Site siege = new Site();
        siege.setName("Siège");
        siege.setCustomer(customer);
        siteRepository.save(siege);

        // 3. Contact principal
        Contact contact = new Contact();
        String[] parts = (fullName != null ? fullName.trim() : "").split(" ", 2);
        contact.setFirstName(parts[0]);
        contact.setLastName(parts.length > 1 ? parts[1] : "");
        contact.setEmail(email);
        contact.setCustomer(customer);
        contact.setSite(siege);
        contactRepository.save(contact);

        // 4. Compte portail GESTIONNAIRE (inactif jusqu'à vérification email)
        PortalUserService.CreationResult result =
                portalUserService.createExplicit(email, fullName, password, customer.getId(), "GESTIONNAIRE");
        User user = result.user();
        contact.setUserId(user.getId());
        contactRepository.save(contact);

        String token = UUID.randomUUID().toString();
        user.setActive(false);
        user.setEmailVerificationToken(token);
        userRepository.save(user);

        sendVerificationEmail(user, companyName, token);

        return "redirect:/register/email-sent?email=" + java.net.URLEncoder.encode(email.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Inscription ESN ────────────────────────────────────────────────────────

    private String registerEsn(String companyName, String contactName,
                                String email, String phone, String planStr, Model model) {
        if (email == null || email.isBlank() || companyName == null || companyName.isBlank()
                || contactName == null || contactName.isBlank()) {
            model.addAttribute("error", "Tous les champs sont obligatoires.");
            model.addAttribute("activeType", "ESN");
            return "feature/auth/register";
        }
        if (subscriptionRequestRepository.existsByEmail(email)) {
            model.addAttribute("error", "Une demande existe déjà pour cet email.");
            model.addAttribute("activeType", "ESN");
            return "feature/auth/register";
        }
        if (userRepository.findByEmail(email).isPresent()) {
            model.addAttribute("error", "Cet email est déjà associé à un compte.");
            model.addAttribute("activeType", "ESN");
            return "feature/auth/register";
        }

        SubscriptionRequest req = new SubscriptionRequest();
        req.setCompanyName(companyName);
        req.setContactName(contactName);
        req.setEmail(email);
        req.setPhone(phone);

        if (planStr != null && !planStr.isBlank()) {
            try { req.setPlan(SubscriptionRequest.SubscriptionPlan.valueOf(planStr)); }
            catch (IllegalArgumentException ignored) {}
        }
        subscriptionRequestRepository.save(req);

        return "redirect:/register/confirm";
    }

    @GetMapping("/register/email-sent")
    public String emailSent(@RequestParam(required = false) String email, Model model) {
        if (email != null) model.addAttribute("email", email);
        return "feature/auth/register-email-sent";
    }

    @PostMapping("/register/resend")
    public String resendVerification(@RequestParam String email) {
        userRepository.findByEmail(email.trim().toLowerCase()).ifPresent(user -> {
            if (!user.isActive()) {
                String token = UUID.randomUUID().toString();
                user.setEmailVerificationToken(token);
                userRepository.save(user);
                sendVerificationEmail(user, "", token);
            }
        });
        // Anti-enumeration: toujours même réponse
        return "redirect:/register/email-sent?sent&email=" + java.net.URLEncoder.encode(email.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @GetMapping("/register/verify")
    public String verifyEmail(@RequestParam String token, Model model) {
        Optional<User> opt = userRepository.findByEmailVerificationToken(token);
        if (opt.isEmpty()) {
            model.addAttribute("error", "Lien invalide ou déjà utilisé.");
            return "feature/auth/register-verify-error";
        }
        User user = opt.get();
        user.setActive(true);
        user.setEmailVerificationToken(null);
        userRepository.save(user);
        return "redirect:/login?verified=true";
    }

    @GetMapping("/login")
    public String login(Model model, jakarta.servlet.http.HttpServletRequest request) {
        model.addAttribute("googleEnabled",    isProviderConfigured("google"));
        model.addAttribute("microsoftEnabled", isProviderConfigured("microsoft"));
        model.addAttribute("captchaEnabled",   captchaService.isEnabled());
        model.addAttribute("captchaSiteKey",   captchaSiteKey);

        // Message d'erreur précis pour les échecs OAuth2
        if (request.getParameter("error") != null) {
            jakarta.servlet.http.HttpSession session = request.getSession(false);
            if (session != null) {
                Exception ex = (Exception) session.getAttribute(
                        org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION);
                if (ex != null) {
                    model.addAttribute("oauthError", ex.getMessage());
                }
            }
        }

        return "feature/auth/login";
    }

    private boolean isProviderConfigured(String registrationId) {
        try {
            return clientRegistrationRepository.findByRegistrationId(registrationId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Envoi email de vérification ───────────────────────────────────────────

    private void sendVerificationEmail(User user, String companyName, String token) {
        String verifyUrl = baseUrl + "/register/verify?token=" + token;
        Map<String, String> vars = new HashMap<>();
        vars.put("fullName",    user.getFullName() != null ? user.getFullName() : user.getEmail());
        vars.put("companyName", companyName);
        vars.put("verifyUrl",   verifyUrl);

        RenderedMail mail = templateService.render(MailTemplateType.REGISTRATION_CONFIRMATION, vars);
        List<MailAccount> active = mailAccountRepository.findByActiveTrueOrderByNameAsc();
        if (active.isEmpty()) {
            log.warn("Aucun compte mail configuré pour l'envoi de vérification à {}", user.getEmail());
            return;
        }
        MailAccount account = active.get(0);
        try {
            if (account.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2) {
                googleMailService.sendHtmlEmail(account, user.getEmail(), mail.subject(), mail.html());
            } else if (account.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2) {
                microsoftMailService.sendHtmlEmail(account, user.getEmail(), mail.subject(), mail.html());
            } else {
                sendSmtpHtml(account, user.getEmail(), mail.subject(), mail.html());
            }
        } catch (Exception e) {
            log.error("Erreur envoi email vérification à {} : {}", user.getEmail(), e.getMessage());
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
}
