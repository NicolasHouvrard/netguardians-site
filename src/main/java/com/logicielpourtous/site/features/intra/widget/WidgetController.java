package com.logicielpourtous.site.features.intra.widget;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.IntraWidget;
import com.logicielpourtous.site.features.intra.IntraWidgetRepository;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/widgets")
@Secured({"ROLE_ADMIN","ROLE_USER","ROLE_COMMERCIAL","ROLE_TECHNICIEN","ROLE_MARKETING","ROLE_COMPTABLE"})
public class WidgetController {

    private final Map<String, IntraWidgetProvider> providers;
    private final IntraWidgetRepository widgetRepo;

    public WidgetController(List<IntraWidgetProvider> providerList, IntraWidgetRepository widgetRepo) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(IntraWidgetProvider::key, p -> p));
        this.widgetRepo = widgetRepo;
    }

    @GetMapping("/{key}")
    public String render(@PathVariable String key,
                         @RequestParam(required = false) String dashboard,
                         @AuthenticationPrincipal CustomUserDetails user,
                         Authentication auth, Model model) {
        String k = key.toUpperCase();
        if (auth == null) return "intra/widgets/_not-found :: widget";

        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        IntraWidget widget = widgetRepo.findByWidgetKey(k).orElse(null);
        if (widget == null) return "intra/widgets/_not-found :: widget";

        if (!widget.isAllowedFor(roles) && !roles.contains("ROLE_ADMIN")) {
            return "intra/widgets/_not-found :: widget";
        }

        model.addAttribute("widget", widget);
        model.addAttribute("widgetKey", k);
        model.addAttribute("dashboard", dashboard);

        IntraWidgetProvider provider = providers.get(k);
        if (provider == null) return "intra/widgets/_not-found :: widget";

        try {
            provider.populate(model, user);
        } catch (Exception e) {
            model.addAttribute("errorMsg", "Erreur de chargement");
            return "intra/widgets/_error :: widget";
        }

        return provider.template() + " :: widget";
    }
}
