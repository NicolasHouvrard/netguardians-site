package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class OpenTicketsWidget implements IntraWidgetProvider {

    private static final List<RequestStatus> OPEN = List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS);
    private final RequestRepository repo;

    public OpenTicketsWidget(RequestRepository repo) { this.repo = repo; }

    @Override public String key() { return "OPEN_TICKETS"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(repo.countByStatusIn(OPEN)));
    }
}
