package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class RecentTicketsWidget implements IntraWidgetProvider {

    private static final List<RequestStatus> OPEN = List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS);
    private final RequestRepository repo;

    public RecentTicketsWidget(RequestRepository repo) { this.repo = repo; }

    @Override public String key() { return "RECENT_TICKETS"; }
    @Override public String template() { return "intra/widgets/_ticket-list"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("tickets",
                repo.findByStatusInOrderByCreatedAtDesc(OPEN).stream().limit(5).toList());
    }
}
