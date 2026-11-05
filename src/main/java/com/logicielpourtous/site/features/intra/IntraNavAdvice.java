package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.module.AdminModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ControllerAdvice
public class IntraNavAdvice {

    private static final Set<String> ESN_ROLES = Set.of(
            "ROLE_ADMIN", "ROLE_USER", "ROLE_TECHNICIEN",
            "ROLE_COMMERCIAL", "ROLE_MARKETING", "ROLE_COMPTABLE");

    private static final Pattern DASHBOARD_PATH = Pattern.compile("^/intra/dashboard/([^/?]+)");
    private static final Set<String> RESERVED_SLUGS = Set.of("config", "categories", "legacy");
    private static final String SESSION_KEY = "lastIntraDashboardSlug";

    private final IntraDashboardRepository dashboardRepository;
    private final CustomerRepository customerRepository;

    public IntraNavAdvice(IntraDashboardRepository dashboardRepository,
                          CustomerRepository customerRepository) {
        this.dashboardRepository = dashboardRepository;
        this.customerRepository = customerRepository;
    }

    @ModelAttribute
    public void prepareIntraNav(Authentication auth,
                                HttpServletRequest request,
                                HttpSession session,
                                Model model) {
        if (auth == null || !auth.isAuthenticated()) return;
        if (!(auth.getPrincipal() instanceof CustomUserDetails)) return;

        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        boolean hasEsnRole = roles.stream().anyMatch(ESN_ROLES::contains);
        if (!hasEsnRole) return;

        boolean isAdmin = roles.contains("ROLE_ADMIN");

        // ── Tenant courant + customer associé ─────────────────────────────
        CustomUserDetails principal = (CustomUserDetails) auth.getPrincipal();
        Tenant tenant = principal.getTenant();
        if (tenant != null) {
            model.addAttribute("currentTenant", tenant);
            if (tenant.getCustomerId() != null) {
                customerRepository.findById(tenant.getCustomerId())
                        .ifPresent(c -> model.addAttribute("tenantCustomer", c));
            }
        }

        String uri = request.getRequestURI();
        model.addAttribute("currentUri", uri);

        // ── 1. Onglets dynamiques (Bande 1) ───────────────────────────────
        List<IntraDashboard> tabs = dashboardRepository
                .findByActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .filter(d -> isAdmin || d.getRoleRequired() == null || roles.contains(d.getRoleRequired()))
                .collect(Collectors.toList());
        model.addAttribute("intraDashboards", tabs);

        // ── 2. Déterminer le dashboard actif ──────────────────────────────
        String slug = null;
        Matcher m = DASHBOARD_PATH.matcher(uri);
        if (m.find()) {
            String candidate = m.group(1);
            if (!RESERVED_SLUGS.contains(candidate)) {
                slug = candidate;
                session.setAttribute(SESSION_KEY, slug);
            }
        } else if (uri.startsWith("/intra/") && !uri.startsWith("/intra/dashboards")) {
            // Navigation dans un module : conserver le dernier dashboard visité
            slug = (String) session.getAttribute(SESSION_KEY);
        }

        // ── 3. Méga-menu Bande 2 ──────────────────────────────────────────
        if (slug != null) {
            final String finalSlug = slug;
            dashboardRepository.findBySlugAndActiveTrue(finalSlug).ifPresent(db -> {
                model.addAttribute("currentDashboardObj", db);

                Map<String, List<AdminModule>> groups = new LinkedHashMap<>();
                db.getModuleLinks().stream()
                        .filter(link -> link.getModule() != null
                                     && link.getModule().isActive()
                                     && (isAdmin
                                         || link.getModule().getRoleRequired() == null
                                         || roles.contains(link.getModule().getRoleRequired())))
                        .sorted(Comparator.comparing(DashboardModuleLink::getDisplayOrder,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .forEach(link -> {
                            String sub = link.getSubCategory() != null
                                    ? link.getSubCategory() : "Général";
                            groups.computeIfAbsent(sub, k -> new ArrayList<>())
                                  .add(link.getModule());
                        });

                if (!groups.isEmpty()) {
                    model.addAttribute("currentDashboardModuleGroups", groups);
                }
            });
        }
    }
}
