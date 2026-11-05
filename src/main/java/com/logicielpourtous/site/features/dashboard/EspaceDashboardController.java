package com.logicielpourtous.site.features.dashboard;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/espace/dashboard")
public class EspaceDashboardController {

    private final UserDashboardConfigRepository configRepository;

    public EspaceDashboardController(UserDashboardConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /** Sauvegarde la sélection de widgets */
    @PostMapping("/config")
    public String saveConfig(@AuthenticationPrincipal CustomUserDetails user,
                             @RequestParam(required = false) List<String> widgets,
                             @RequestParam(required = false, defaultValue = "true") boolean bannerVisible,
                             @RequestParam(required = false, defaultValue = "/espace") String returnTo,
                             RedirectAttributes ra) {
        UserDashboardConfig config = configRepository.findById(user.getUserId())
                .orElseGet(() -> { var c = new UserDashboardConfig(); c.setUserId(user.getUserId()); return c; });

        String codes = widgets == null ? "" : String.join(",", widgets);
        config.setWidgetCodes(codes);
        config.setBannerVisible(bannerVisible);
        configRepository.save(config);

        ra.addFlashAttribute("successMessage", "Tableau de bord mis à jour.");
        return "redirect:" + returnTo;
    }

    /** Bascule visibilité du bandeau */
    @PostMapping("/toggle")
    public String toggle(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam(required = false, defaultValue = "/espace") String returnTo) {
        UserDashboardConfig config = configRepository.findById(user.getUserId())
                .orElseGet(() -> { var c = new UserDashboardConfig(); c.setUserId(user.getUserId()); return c; });
        config.setBannerVisible(!config.isBannerVisible());
        configRepository.save(config);
        return "redirect:" + returnTo;
    }
}
