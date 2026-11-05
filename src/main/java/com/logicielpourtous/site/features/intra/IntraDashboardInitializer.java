package com.logicielpourtous.site.features.intra;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class IntraDashboardInitializer implements ApplicationRunner {

    private final IntraDashboardRepository repo;

    public IntraDashboardInitializer(IntraDashboardRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("Commercial",    "commercial",    "fas fa-handshake",  "#10b981", 1, null);
        seed("Comptabilité",  "comptabilite",  "fas fa-calculator", "#8b5cf6", 2, null);
        seed("Marketing",     "marketing",     "fas fa-bullhorn",   "#ec4899", 3, null);
        seed("Support",       "support",       "fas fa-headset",    "#ef4444", 4, null);
        seed("Technicien",    "technicien",    "fas fa-hard-hat",   "#f97316", 5, null);
        seed("Configuration", "config",        "fas fa-sliders",    "#64748b", 6, "ROLE_ADMIN");
    }

    private void seed(String name, String slug, String icon, String color,
                      int order, String roleRequired) {
        if (repo.existsBySlug(slug)) return;
        IntraDashboard d = new IntraDashboard();
        d.setName(name);
        d.setSlug(slug);
        d.setIcon(icon);
        d.setColor(color);
        d.setDisplayOrder(order);
        d.setRoleRequired(roleRequired);
        repo.save(d);
    }
}
