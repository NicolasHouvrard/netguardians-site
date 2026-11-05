package com.logicielpourtous.site.features.dashboard;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ControllerAdvice(basePackages = {
        "com.logicielpourtous.site.features.espace",
        "com.logicielpourtous.site.features.auth",
        "com.logicielpourtous.site.features.customers",
        "com.logicielpourtous.site.features.dashboard",
        "com.logicielpourtous.site.features.integration",
        "com.logicielpourtous.site.features.request",
        "com.logicielpourtous.site.features.subscription"
})
public class DashboardAdvice {

    private final UserDashboardConfigRepository configRepository;
    private final DashboardWidgetService        widgetService;

    public DashboardAdvice(UserDashboardConfigRepository configRepository,
                           DashboardWidgetService widgetService) {
        this.configRepository = configRepository;
        this.widgetService    = widgetService;
    }

    @ModelAttribute
    public void prepareDashboard(Authentication authentication, Model model) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) return;
            if (!(authentication.getPrincipal() instanceof CustomUserDetails user)) return;

            Set<String> roles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());

            boolean isPortalUser = roles.stream().anyMatch(r ->
                    r.equals("ROLE_CLIENT") || r.equals("ROLE_GESTIONNAIRE"));
            if (!isPortalUser) return;

            UserDashboardConfig config = configRepository.findById(user.getUserId()).orElse(null);

            boolean bannerVisible = config == null || config.isBannerVisible();
            List<DashboardWidgetService.WidgetData> widgets =
                    widgetService.activeWidgets(config, user, roles);
            List<WidgetType> available = widgetService.availableWidgets(roles);

            model.addAttribute("dashboardWidgets",  widgets);
            model.addAttribute("dashboardAvailable", available);
            model.addAttribute("dashboardVisible",  bannerVisible);
            model.addAttribute("dashboardConfig",   config);
        } catch (Exception ignored) {}
    }
}
