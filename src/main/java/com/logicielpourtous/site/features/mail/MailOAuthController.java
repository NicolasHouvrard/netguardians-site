package com.logicielpourtous.site.features.mail;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gère les flux OAuth2 pour les comptes mail Google et Microsoft.
 * Callbacks accessibles via /intra/mail/oauth/... (zone authentifiée admin).
 */
@Controller
@RequestMapping("/intra/mail")
public class MailOAuthController {

    private static final Logger log = LoggerFactory.getLogger(MailOAuthController.class);

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String MS_AUTH_BASE    = "https://login.microsoftonline.com";

    private static final String GOOGLE_SCOPES = "https://mail.google.com/ https://www.googleapis.com/auth/calendar";
    private static final String MS_SCOPES     = "Mail.ReadWrite Mail.Send Calendars.ReadWrite offline_access";

    @Value("${app.oauth.redirect-base:http://localhost:8080}")
    private String redirectBase;

    private final MailAccountRepository accountRepository;
    private final GoogleMailService     googleMailService;
    private final MicrosoftMailService  microsoftMailService;

    public MailOAuthController(MailAccountRepository accountRepository,
                               GoogleMailService googleMailService,
                               MicrosoftMailService microsoftMailService) {
        this.accountRepository   = accountRepository;
        this.googleMailService   = googleMailService;
        this.microsoftMailService = microsoftMailService;
    }

    // ── Initiation Google ─────────────────────────────────────────────────────

    @GetMapping("/accounts/{id}/auth/google")
    public String initiateGoogle(@PathVariable Long id, RedirectAttributes ra) {
        return accountRepository.findById(id).map(account -> {
            if (account.getClientId() == null || account.getClientId().isBlank()) {
                ra.addFlashAttribute("errorMessage", "Veuillez d'abord enregistrer un Client ID Google.");
                return "redirect:/intra/mail/accounts";
            }
            String redirectUri = redirectBase + "/intra/mail/oauth/google/callback";
            String url = GOOGLE_AUTH_URL
                    + "?client_id=" + enc(account.getClientId())
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + enc(GOOGLE_SCOPES)
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&state=" + id;
            return "redirect:" + url;
        }).orElse("redirect:/intra/mail/accounts");
    }

    /** Initiation Google depuis /mon-compte — crée ou réutilise le compte perso de l'utilisateur. */
    @GetMapping("/personal/auth/google")
    public String initiatePersonalGoogle(@AuthenticationPrincipal CustomUserDetails principal,
                                         RedirectAttributes ra) {
        if (principal == null) return "redirect:/login";
        Long userId = principal.getUserId();

        // Cherche un compte partagé Google avec clientId pour en hériter les credentials
        MailAccount template = accountRepository.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2)
                .filter(a -> a.getClientId() != null)
                .findFirst().orElse(null);

        if (template == null) {
            ra.addFlashAttribute("errorMessage", "Aucune configuration Google OAuth disponible. Contactez l'administrateur.");
            return "redirect:/mon-compte?tab=agenda";
        }

        // Crée ou réutilise le compte perso
        MailAccount personal = accountRepository
                .findFirstByUserIdAndAuthTypeInAndAccessTokenIsNotNull(userId,
                        List.of(MailAccount.AuthType.GOOGLE_OAUTH2))
                .orElseGet(() -> {
                    MailAccount a = new MailAccount();
                    a.setName("Agenda de " + principal.getFullName());
                    a.setAuthType(MailAccount.AuthType.GOOGLE_OAUTH2);
                    a.setUsername(principal.getUsername());
                    a.setClientId(template.getClientId());
                    a.setClientSecret(template.getClientSecret());
                    a.setUserId(userId);
                    return accountRepository.save(a);
                });

        String redirectUri = redirectBase + "/intra/mail/oauth/google/callback";
        String url = GOOGLE_AUTH_URL
                + "?client_id=" + enc(personal.getClientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(GOOGLE_SCOPES)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + personal.getId();
        return "redirect:" + url;
    }

    // ── Callback Google ───────────────────────────────────────────────────────

    @GetMapping("/oauth/google/callback")
    public String googleCallback(@RequestParam(required = false) String code,
                                 @RequestParam(required = false) String state,
                                 @RequestParam(required = false) String error,
                                 @AuthenticationPrincipal CustomUserDetails principal,
                                 RedirectAttributes ra) {
        if (error != null) {
            ra.addFlashAttribute("errorMessage", "Autorisation Google refusée : " + error);
            return "redirect:/intra/mail/accounts";
        }
        if (code == null || state == null) {
            ra.addFlashAttribute("errorMessage", "Réponse Google invalide.");
            return "redirect:/intra/mail/accounts";
        }
        try {
            Long accountId = Long.parseLong(state);
            MailAccount account = accountRepository.findById(accountId).orElseThrow();
            String redirectUri = redirectBase + "/intra/mail/oauth/google/callback";
            googleMailService.exchangeCode(account, code, redirectUri);
            // Associe l'utilisateur si c'est son compte perso
            if (account.getUserId() != null && principal != null) {
                ra.addFlashAttribute("successMessage", "Votre agenda Google est connecté.");
                return "redirect:/mon-compte?tab=agenda";
            }
            ra.addFlashAttribute("successMessage", "Compte Google connecté avec succès.");
        } catch (Exception e) {
            log.error("Erreur callback Google OAuth : {}", e.getMessage());
            ra.addFlashAttribute("errorMessage", "Erreur lors de la connexion Google : " + e.getMessage());
        }
        return "redirect:/intra/mail/accounts";
    }

    // ── Initiation Microsoft ──────────────────────────────────────────────────

    @GetMapping("/accounts/{id}/auth/microsoft")
    public String initiateMicrosoft(@PathVariable Long id, RedirectAttributes ra) {
        return accountRepository.findById(id).map(account -> {
            if (account.getClientId() == null || account.getClientId().isBlank()) {
                ra.addFlashAttribute("errorMessage", "Veuillez d'abord enregistrer un Client ID Microsoft.");
                return "redirect:/intra/mail/accounts";
            }
            String tenant = account.getMsTenantId() != null ? account.getMsTenantId() : "common";
            String redirectUri = redirectBase + "/intra/mail/oauth/microsoft/callback";
            String url = MS_AUTH_BASE + "/" + tenant + "/oauth2/v2.0/authorize"
                    + "?client_id=" + enc(account.getClientId())
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + enc(MS_SCOPES)
                    + "&response_mode=query"
                    + "&state=" + id;
            return "redirect:" + url;
        }).orElse("redirect:/intra/mail/accounts");
    }

    // ── Callback Microsoft ────────────────────────────────────────────────────

    @GetMapping("/oauth/microsoft/callback")
    public String microsoftCallback(@RequestParam(required = false) String code,
                                    @RequestParam(required = false) String state,
                                    @RequestParam(required = false) String error,
                                    @RequestParam(name = "error_description", required = false) String errorDescription,
                                    RedirectAttributes ra) {
        if (error != null) {
            ra.addFlashAttribute("errorMessage", "Autorisation Microsoft refusée : " + errorDescription);
            return "redirect:/intra/mail/accounts";
        }
        if (code == null || state == null) {
            ra.addFlashAttribute("errorMessage", "Réponse Microsoft invalide.");
            return "redirect:/intra/mail/accounts";
        }
        try {
            Long accountId = Long.parseLong(state);
            MailAccount account = accountRepository.findById(accountId).orElseThrow();
            String redirectUri = redirectBase + "/intra/mail/oauth/microsoft/callback";
            microsoftMailService.exchangeCode(account, code, redirectUri);
            ra.addFlashAttribute("successMessage", "Compte Microsoft connecté avec succès.");
        } catch (Exception e) {
            log.error("Erreur callback Microsoft OAuth : {}", e.getMessage());
            ra.addFlashAttribute("errorMessage", "Erreur lors de la connexion Microsoft : " + e.getMessage());
        }
        return "redirect:/intra/mail/accounts";
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
