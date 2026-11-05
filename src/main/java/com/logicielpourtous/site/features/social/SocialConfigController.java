package com.logicielpourtous.site.features.social;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/intra/social")
public class SocialConfigController {

    private final SocialNetworkConfigRepository configRepo;
    private final SocialPostTargetRepository    targetRepo;
    private final SocialOAuthService            oauthService;

    public SocialConfigController(SocialNetworkConfigRepository configRepo,
                                   SocialPostTargetRepository targetRepo,
                                   SocialOAuthService oauthService) {
        this.configRepo   = configRepo;
        this.targetRepo   = targetRepo;
        this.oauthService = oauthService;
    }

    // ── Redirect racine ───────────────────────────────────────────────────────

    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/intra/social/posts";
    }

    // ── Liste des réseaux configurés ──────────────────────────────────────────

    @GetMapping("/config")
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("configs",      configRepo.findByTenantOrderByNetworkTypeAscNameAsc(tenant));
        model.addAttribute("networkTypes", SocialNetworkType.values());
        return "intra/social/config/list";
    }

    // ── Formulaire nouveau réseau ─────────────────────────────────────────────

    @GetMapping("/config/new")
    public String newForm(Model model) {
        model.addAttribute("networkTypes", SocialNetworkType.values());
        model.addAttribute("config",       new SocialNetworkConfig());
        return "intra/social/config/form";
    }

    @PostMapping("/config")
    @Transactional
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam SocialNetworkType networkType,
                       @RequestParam String name,
                       @RequestParam(required = false) String clientId,
                       @RequestParam(required = false) String clientSecret,
                       @RequestParam(required = false) String pageId,
                       @RequestParam(required = false) String accountHandle,
                       RedirectAttributes ra) {
        SocialNetworkConfig config = new SocialNetworkConfig();
        config.setTenant(principal.getTenant());
        config.setNetworkType(networkType);
        config.setName(name);
        if (clientId != null && !clientId.isBlank())     config.setClientId(clientId);
        if (clientSecret != null && !clientSecret.isBlank()) config.setClientSecret(clientSecret);
        if (pageId != null && !pageId.isBlank())         config.setPageId(pageId);
        if (accountHandle != null && !accountHandle.isBlank()) config.setAccountHandle(accountHandle);
        configRepo.save(config);
        ra.addFlashAttribute("successMessage", "Réseau " + networkType.getLabel() + " configuré.");
        return "redirect:/intra/social/config";
    }

    // ── Édition ───────────────────────────────────────────────────────────────

    @GetMapping("/config/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal CustomUserDetails principal,
                           Model model) {
        SocialNetworkConfig config = configRepo.findByIdAndTenant(id, principal.getTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("config",       config);
        model.addAttribute("networkTypes", SocialNetworkType.values());
        return "intra/social/config/form";
    }

    @PostMapping("/config/{id}")
    @Transactional
    public String update(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         @RequestParam String name,
                         @RequestParam(required = false) String clientId,
                         @RequestParam(required = false) String clientSecret,
                         @RequestParam(required = false) String pageId,
                         @RequestParam(required = false) String accountHandle,
                         @RequestParam(defaultValue = "true") boolean active,
                         RedirectAttributes ra) {
        SocialNetworkConfig config = configRepo.findByIdAndTenant(id, principal.getTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        config.setName(name);
        boolean credentialsChanged = false;
        if (clientId != null && !clientId.isBlank()) {
            credentialsChanged = !clientId.equals(config.getClientId());
            config.setClientId(clientId);
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            config.setClientSecret(clientSecret);
            credentialsChanged = true;
        }
        if (pageId != null && !pageId.isBlank())             config.setPageId(pageId);
        if (accountHandle != null && !accountHandle.isBlank()) config.setAccountHandle(accountHandle);
        // Si les credentials changent, on invalide le token pour forcer une reconnexion
        if (credentialsChanged) {
            config.setAccessToken(null);
            config.setRefreshToken(null);
            config.setTokenExpiresAt(null);
            config.setAccountHandle(null);
        }
        config.setActive(active);
        configRepo.save(config);
        ra.addFlashAttribute("successMessage", credentialsChanged
                ? "Configuration mise à jour — reconnectez le compte."
                : "Configuration mise à jour.");
        return "redirect:/intra/social/config";
    }

    // ── OAuth : initiation ────────────────────────────────────────────────────

    @GetMapping("/config/{id}/auth")
    public String initiateAuth(@PathVariable Long id,
                                @AuthenticationPrincipal CustomUserDetails principal) {
        SocialNetworkConfig config = configRepo.findByIdAndTenant(id, principal.getTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String state = oauthService.generateState(config);
        String authUrl = oauthService.buildAuthorizationUrl(config, state);
        return "redirect:" + authUrl;
    }

    // ── OAuth : callback ──────────────────────────────────────────────────────

    @GetMapping("/oauth/{network}/callback")
    public String oauthCallback(@PathVariable String network,
                                 @RequestParam(required = false) String code,
                                 @RequestParam(required = false) String state,
                                 @RequestParam(required = false) String error) {
        if (code == null) {
            String msg = (error != null) ? "Autorisation refusée : " + error
                                         : "Flux OAuth invalide — utilisez le bouton Connecter.";
            return "redirect:/app/admin/marketing/social?oauthResult=error&msg=" + encode(msg);
        }

        // Extraire l'id de config depuis le state (format : "{id}-{random}")
        try {
            Long configId = Long.parseLong(state.split("-")[0]);
            SocialNetworkConfig config = configRepo.findById(configId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            oauthService.exchangeCode(config, code);
            return "redirect:/app/admin/marketing/social?oauthResult=success&network="
                    + encode(config.getNetworkType().getLabel());
        } catch (Exception e) {
            return "redirect:/app/admin/marketing/social?oauthResult=error&msg=" + encode(e.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String encode(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @PostMapping("/config/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id,
                          @AuthenticationPrincipal CustomUserDetails principal,
                          RedirectAttributes ra) {
        configRepo.findByIdAndTenant(id, principal.getTenant()).ifPresent(config -> {
            targetRepo.deleteByNetworkConfig(config);
            configRepo.delete(config);
        });
        ra.addFlashAttribute("successMessage", "Configuration supprimée.");
        return "redirect:/intra/social/config";
    }
}
