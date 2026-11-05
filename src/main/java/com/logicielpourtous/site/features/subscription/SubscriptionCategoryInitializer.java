package com.logicielpourtous.site.features.subscription;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionCategoryInitializer implements CommandLineRunner {

    private final SubscriptionCategoryRepository repo;

    public SubscriptionCategoryInitializer(SubscriptionCategoryRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) return;

        seed("Support / Tickets",          "fas fa-headset",   "text-bg-primary",   1);
        seed("Noms de domaine",            "fas fa-globe",     "text-bg-info",      2);
        seed("RMM / Gestion des postes",   "fas fa-desktop",   "text-bg-warning",   3);
        seed("Messagerie Microsoft",       "fab fa-microsoft", "text-bg-primary",   4);
        seed("Messagerie Google",          "fab fa-google",    "text-bg-danger",    5);
        seed("Messagerie autre",           "fas fa-envelope",  "text-bg-secondary", 6);
        seed("Serveurs / NAS",             "fas fa-server",    "text-bg-dark",      7);
        seed("Autre",                      "fas fa-box",       "text-bg-light",     99);
    }

    private void seed(String label, String icon, String badgeCss, int order) {
        SubscriptionCategory c = new SubscriptionCategory();
        c.setLabel(label);
        c.setIcon(icon);
        c.setBadgeCss(badgeCss);
        c.setSortOrder(order);
        c.setActive(true);
        repo.save(c);
    }
}
