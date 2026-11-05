package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.module.AdminModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DashboardModuleLinkRepository extends JpaRepository<DashboardModuleLink, Long> {
    List<DashboardModuleLink> findByDashboardOrderByDisplayOrderAsc(IntraDashboard dashboard);
    boolean existsByDashboardAndModule(IntraDashboard dashboard, AdminModule module);
    void deleteByDashboard(IntraDashboard dashboard);

    @Modifying
    @Query("DELETE FROM DashboardModuleLink l WHERE l.dashboard.id = :dashboardId")
    void deleteByDashboardId(@Param("dashboardId") Long dashboardId);
}
