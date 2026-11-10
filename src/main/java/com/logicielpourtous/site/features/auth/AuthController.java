package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.mail.GoogleMailService;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.mail.MailTemplateService;
import com.logicielpourtous.site.features.mail.MailTemplateService.RenderedMail;
import com.logicielpourtous.site.features.mail.MailTemplateType;
import com.logicielpourtous.site.features.mail.MicrosoftMailService;
import com.logicielpourtous.site.features.auth.InscriptionSocieteService.DemandeClient;
import com.logicielpourtous.site.features.auth.InscriptionSocieteService.ResultatInscription;
import com.logicielpourtous.site.features.identite.IdentiteInvalideException;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
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
    private final InscriptionSocieteService      inscriptionSocieteService;
    private final SubscriptionRequestRepository  subscriptionRequestRepository;
    private final MailTemplateService            templateService;
    private final MailAccountRepository          mailAccountRepository;
    private final GoogleMailService              googleMailService;
    private final MicrosoftMailService           microsoftMailService;
    private final ClientRegistrationRepository   clientRegistrationRepository;

    public AuthController(CaptchaService captchaService,
                          UserRepository userRepository,
                          InscriptionSocieteService inscriptionSocieteService,
                          SubscriptionRequestRepository subscriptionRequestRepository,
                          MailTemplateService templateService,
                          MailAccountRepository mailAccountRepository,
                          GoogleMailService googleMailService,
                          MicrosoftMailService microsoftMailService,
                          ClientRegistrationRepository clientRegistrationRepository) {
        this.captchaService                = captchaService;
        this.userRepository                = userRepository;
        this.inscriptionSocieteService     = inscriptionSocieteService;
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
                           @RequestParam(required = false) String siren,
                           @RequestParam(required = false) String siret,
                           @RequestParam(required = false) String tva,
                           // ── ESN uniquement ──
                           @RequestParam(required = false) String phone,
                           @RequestParam(required = false) String plan,
                           Model model) {

        if ("ESN".equals(accountType)) {
            return registerEsn(companyName, fullName, email, phone, plan, siren, siret, tva, model);
        } else {
            return registerClient(companyName, fullName, email, password, siren, siret, tva, model);
        }
    }

    // ── Inscription CLIENT ─────────────────────────────────────────────────────

    private String registerClient(String companyName, String fullName, String email, String password,
                                  String siren, String siret, String tva, Model model) {
        if (email == null || email.isBlank() || password == null || password.isBlank()
                || companyName == null || companyName.isBlank()) {
            model.addAttribute("error", "Tous les champs sont obligatoires.");
            model.addAttribute("activeType", "CLIENT");
            return formulaire(model);
        }

        // Longueur maximale des champs libres (colonnes en 255) : refus clair au lieu d'une erreur 500
        String champTropLong = champTropLong(companyName, fullName, email);
        if (champTropLong != null) {
            model.addAttribute("error", "Ce champ est trop long (255 caractères au maximum).");
            model.addAttribute("champErreur", champTropLong);
            model.addAttribute("activeType", "CLIENT");
            return formulaire(model);
        }

        ResultatInscription resultat;
        try {
            resultat = inscriptionSocieteService.inscrireClient(
                    new DemandeClient(companyName, fullName, email, password, siren, siret, tva));
        } catch (IdentiteInvalideException e) {
            // Numero refuse : retour au formulaire avec l'erreur sur le champ fautif
            model.addAttribute("error", e.getMessage());
            model.addAttribute("champErreur", e.champ());
            model.addAttribute("activeType", "CLIENT");
            return formulaire(model);
        } catch (IllegalArgumentException e) {
            // Seul le refus de l'e-mail est montre (message inchange) ; toute autre erreur remonte telle quelle.
            if (!InscriptionSocieteService.EMAIL_DEJA_UTILISE.equals(e.getMessage())) {
                throw e;
            }
            model.addAttribute("error", e.getMessage());
            model.addAttribute("activeType", "CLIENT");
            return formulaire(model);
        }

        if (resultat instanceof ResultatInscription.Creee creee) {
            // Envoi APRES le commit de l'inscription : un echec d'envoi n'annule rien (renvoi possible).
            sendVerificationEmail(creee.utilisateur(), companyName, creee.jetonVerification());
            return "redirect:/register/email-sent?email=" + java.net.URLEncoder.encode(email.trim(), java.nio.charset.StandardCharsets.UTF_8);
        }
        // Societe deja connue : issue neutre, identique quel que soit son proprietaire
        return "redirect:/register/societe-connue";
    }

    /** Re-affiche le formulaire en gardant le captcha (sinon le widget disparait et la re-soumission est refusee). */
    private String formulaire(Model model) {
        model.addAttribute("captchaEnabled", captchaService.isEnabled());
        model.addAttribute("captchaSiteKey", captchaSiteKey);
        return "feature/auth/register";
    }

    private static final int LONGUEUR_MAX = 255;

    /** Nom du premier champ libre depassant 255 caracteres, ou {@code null}. */
    private static String champTropLong(String companyName, String fullName, String email) {
        if (companyName != null && companyName.length() > LONGUEUR_MAX) return "companyName";
        if (fullName != null && fullName.length() > LONGUEUR_MAX) return "fullName";
        if (email != null && email.length() > LONGUEUR_MAX) return "email";
        return null;
    }

    @GetMapping("/register/societe-connue")
    public String societeConnue() {
        return "feature/auth/register-societe-connue";
    }

    // ── Inscription ESN ────────────────────────────────────────────────────────

    private String registerEsn(String companyName, String contactName,
                                String email, String phone, String planStr,
                                String siren, String siret, String tva, Model model) {
        if (email == null || email.isBlank() || companyName == null || companyName.isBlank()
                || contactName == null || contactName.isBlank()) {
            model.addAttribute("error", "Tous les champs sont obligatoires.");
            model.addAttribute("activeType", "ESN");
            return formulaire(model);
        }
        // Longueur maximale des champs libres (colonnes en 255) : refus clair au lieu d'une erreur 500
        String champTropLong = champTropLong(companyName, contactName, email);
        if (champTropLong != null) {
            model.addAttribute("error", "Ce champ est trop long (255 caractères au maximum).");
            model.addAttribute("champErreur", champTropLong);
            model.addAttribute("activeType", "ESN");
            return formulaire(model);
        }

        // Identite obligatoire : numeros controles et normalises ; seule une societe francaise peut s'inscrire
        IdentiteSociete identite;
        try {
            identite = inscriptionSocieteService.controlerIdentitePartenaire(siren, siret, tva);
        } catch (IdentiteInvalideException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("champErreur", e.champ());
            model.addAttribute("activeType", "ESN");
            return formulaire(model);
        }

        if (subscriptionRequestRepository.existsByEmail(email)) {
            model.addAttribute("error", "Une demande existe déjà pour cet email.");
            model.addAttribute("activeType", "ESN");
            return formulaire(model);
        }
        if (userRepository.findByEmail(email).isPresent()) {
            model.addAttribute("error", "Cet email est déjà associé à un compte.");
            model.addAttribute("activeType", "ESN");
            return formulaire(model);
        }

        // Societe deja au registre : meme issue neutre que pour un client, aucune demande creee.
        // Le registre n'est PAS ecrit ici : une demande refusee ne doit pas reserver la societe.
        if (inscriptionSocieteService.refuserSiSocieteConnue(identite, email, contactName)) {
            return "redirect:/register/societe-connue";
        }

        SubscriptionRequest req = new SubscriptionRequest();
        req.setCompanyName(companyName);
        req.setContactName(contactName);
        req.setEmail(email);
        req.setPhone(phone);
        req.setSiren(identite.siren());
        req.setVatNumber(identite.tva());

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
            // Jamais pour un compte deja verifie (puis desactive) : un jeton ne doit signifier « jamais verifie ».
            if (!user.isActive() && user.getEmailVerifiedAt() == null) {
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
        user.marquerEmailVerifie();
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
