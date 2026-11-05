package com.logicielpourtous.site.features.ninja;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/admin/ninja")
public class NinjaOneConfigController {

    private final NinjaOneConfigRepository configRepository;
    private final NinjaOneClient           ninjaOneClient;
    private final NinjaOneSyncService      syncService;

    public NinjaOneConfigController(NinjaOneConfigRepository configRepository,
                                    NinjaOneClient ninjaOneClient,
                                    NinjaOneSyncService syncService) {
        this.configRepository = configRepository;
        this.ninjaOneClient   = ninjaOneClient;
        this.syncService      = syncService;
    }

    @GetMapping({"","/config"})
    public String config(Model model) {
        NinjaOneConfig config = configRepository.findById(1L)
                .orElseGet(() -> {
                    NinjaOneConfig c = new NinjaOneConfig();
                    return configRepository.save(c);
                });
        model.addAttribute("config", config);
        return "feature/ninja/config";
    }

    @GetMapping("/test")
    public String test(RedirectAttributes attrs) {
        NinjaOneConfig config = configRepository.findById(1L).orElse(null);
        if (config == null || config.getClientId() == null || config.getClientSecret() == null) {
            attrs.addFlashAttribute("errorMessage", "Configuration incomplète — renseignez le client ID et le secret.");
            return "redirect:/admin/ninja/config";
        }
        try {
            int count = ninjaOneClient.testConnection(config);
            config.setLastSyncAt(LocalDateTime.now());
            config.setLastError(null);
            config.setEnabled(true);
            configRepository.save(config);
            attrs.addFlashAttribute("successMessage",
                    "Connexion NinjaOne réussie ! " + count + " organisation(s) trouvée(s).");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 300) msg = msg.substring(0, 300) + "…";
            config.setLastError(msg);
            configRepository.save(config);
            attrs.addFlashAttribute("errorMessage", "Échec de connexion : " + msg);
        }
        return "redirect:/admin/ninja/config";
    }

    @GetMapping("/sync")
    public String sync(RedirectAttributes attrs) {
        NinjaOneConfig config = configRepository.findById(1L).orElse(null);
        if (config == null || !config.isEnabled()) {
            attrs.addFlashAttribute("errorMessage", "NinjaOne non configuré ou désactivé.");
            return "redirect:/admin/ninja/config";
        }
        try {
            NinjaOneSyncService.SyncResult result = syncService.syncAll(config);
            String msg = result.customers() + " client(s) synchronisé(s), "
                       + result.subscriptions() + " abonnement(s) mis à jour.";
            if (result.hasErrors()) msg += " (avec erreurs — voir détails)";
            attrs.addFlashAttribute("successMessage", msg);
        } catch (Exception e) {
            attrs.addFlashAttribute("errorMessage", "Erreur de synchronisation : " + e.getMessage());
        }
        return "redirect:/admin/ninja/config";
    }

    @PostMapping("/config/save")
    public String save(@ModelAttribute NinjaOneConfig config, RedirectAttributes attrs) {
        config.setId(1L);
        // Ne pas écraser le secret si le champ est laissé vide (masqué dans le form)
        if (config.getClientSecret() != null && config.getClientSecret().isBlank()) {
            configRepository.findById(1L).ifPresent(existing ->
                    config.setClientSecret(existing.getClientSecret()));
        }
        if (config.getClientId() != null && config.getClientId().isBlank()) {
            configRepository.findById(1L).ifPresent(existing ->
                    config.setClientId(existing.getClientId()));
        }
        config.setLastError(null);
        configRepository.save(config);
        attrs.addFlashAttribute("successMessage", "Configuration NinjaOne enregistrée.");
        return "redirect:/admin/ninja/config";
    }
}
