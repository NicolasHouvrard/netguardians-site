package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.module.AdminModule;
import com.logicielpourtous.site.features.module.AdminModuleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class IntraGeneralDashboardInitializer implements ApplicationRunner {

    private final IntraDashboardRepository dashboardRepo;
    private final AdminModuleRepository moduleRepo;
    private final DashboardModuleLinkRepository linkRepo;

    public IntraGeneralDashboardInitializer(IntraDashboardRepository dashboardRepo,
                                             AdminModuleRepository moduleRepo,
                                             DashboardModuleLinkRepository linkRepo) {
        this.dashboardRepo = dashboardRepo;
        this.moduleRepo = moduleRepo;
        this.linkRepo = linkRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        IntraDashboard general = dashboardRepo.findBySlug("general").orElseGet(() -> {
            IntraDashboard d = new IntraDashboard();
            d.setName("Général");
            d.setSlug("general");
            d.setIcon("fas fa-th-large");
            d.setColor("#3b82f6");
            d.setDisplayOrder(0);
            return dashboardRepo.save(d);
        });

        link(general, "/intra/tickets",      "Tickets",    1);
        link(general, "/intra/appointments", "Calendrier", 2);
    }

    private void link(IntraDashboard dashboard, String moduleUrl, String subCategory, int order) {
        AdminModule module = moduleRepo.findFirstByUrl(moduleUrl).orElse(null);
        if (module == null) return;
        if (linkRepo.existsByDashboardAndModule(dashboard, module)) return;

        DashboardModuleLink link = new DashboardModuleLink();
        link.setDashboard(dashboard);
        link.setModule(module);
        link.setSubCategory(subCategory);
        link.setDisplayOrder(order);
        linkRepo.save(link);
    }
}
