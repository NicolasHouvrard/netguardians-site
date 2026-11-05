package com.logicielpourtous.site.features.intra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DashboardWidgetLayoutRepository extends JpaRepository<DashboardWidgetLayout, Long> {
    List<DashboardWidgetLayout> findByDashboardOrderByPositionAsc(IntraDashboard dashboard);
    void deleteByDashboard(IntraDashboard dashboard);
    boolean existsByDashboardAndWidgetType(IntraDashboard dashboard, String widgetType);

    @Modifying
    @Query("DELETE FROM DashboardWidgetLayout l WHERE l.dashboard.id = :dashboardId")
    void deleteByDashboardId(@Param("dashboardId") Long dashboardId);
}
