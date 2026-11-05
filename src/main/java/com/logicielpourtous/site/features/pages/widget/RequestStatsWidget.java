package com.logicielpourtous.site.features.pages.widget;

import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RequestStatsWidget implements PublicWidget {

    private final RequestRepository requestRepository;

    public RequestStatsWidget(RequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    @Override
    public String getId() { return "request-stats"; }

    @Override
    public String getLabel() { return "Statistiques tickets"; }

    @Override
    public String getDescription() { return "Nombre de tickets ouverts"; }

    @Override
    public Map<String, Object> getData(Map<String, String> params) {
        long count = requestRepository.countByStatusIn(
            List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS)
        );
        return Map.of(
            "count", count,
            "label", "Tickets en cours"
        );
    }
}
