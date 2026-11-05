package com.logicielpourtous.site.features.dashboard;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DashboardWidgetService {

    public record WidgetData(WidgetType type, String value, String label, String icon, String color, String link) {}

    private static final List<RequestStatus> OPEN_STATUSES =
            List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS);

    private final RequestRepository      requestRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository         userRepository;

    public DashboardWidgetService(RequestRepository requestRepository,
                                  SubscriptionRepository subscriptionRepository,
                                  UserRepository userRepository) {
        this.requestRepository      = requestRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository         = userRepository;
    }

    public WidgetData compute(WidgetType type, CustomUserDetails user) {
        String value = "—";
        try {
            Long customerId = user.getCustomerId();
            value = switch (type) {
                case MY_OPEN_TICKETS -> customerId != null
                        ? String.valueOf(requestRepository.countByCustomerIdAndStatusIn(customerId, OPEN_STATUSES))
                        : "—";
                case MY_DEVICES      -> "—"; // données NinjaOne — chargées séparément
                case MY_SUBSCRIPTIONS -> customerId != null
                        ? String.valueOf(subscriptionRepository
                            .findByCustomerIdAndActiveTrueOrderByStartDateDesc(customerId).size())
                        : "—";
                case COMPANY_TICKETS -> customerId != null
                        ? String.valueOf(requestRepository.countByCustomerIdAndStatusIn(customerId, OPEN_STATUSES))
                        : String.valueOf(requestRepository.countByStatusIn(OPEN_STATUSES));
                case TEAM_COUNT      -> customerId != null
                        ? String.valueOf(userRepository.findByCustomerId(customerId).size())
                        : "—";
                case COMPANY_DEVICES  -> "—";
                case PENDING_INVOICES -> "—";
                case COMPANY_SITES    -> "—";
            };
        } catch (Exception ignored) {}

        return new WidgetData(type, value, type.label, type.icon, type.color, type.link);
    }

    /** Widgets disponibles pour un utilisateur selon ses rôles */
    public List<WidgetType> availableWidgets(Set<String> roles) {
        return Arrays.stream(WidgetType.values())
                .filter(w -> w.isAllowedFor(roles))
                .toList();
    }

    /** Widgets actifs de l'utilisateur dans leur ordre */
    public List<WidgetData> activeWidgets(UserDashboardConfig config, CustomUserDetails user,
                                          Set<String> roles) {
        if (config == null || config.getWidgetCodes().isBlank()) {
            return defaultWidgets(user, roles);
        }
        List<WidgetData> result = new ArrayList<>();
        for (String code : config.getWidgetCodes().split(",")) {
            try {
                WidgetType type = WidgetType.valueOf(code.trim());
                if (type.isAllowedFor(roles)) {
                    result.add(compute(type, user));
                }
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private List<WidgetData> defaultWidgets(CustomUserDetails user, Set<String> roles) {
        boolean isGestionnaire = roles.contains("ROLE_GESTIONNAIRE");
        List<WidgetType> defaults = isGestionnaire
                ? List.of(WidgetType.COMPANY_TICKETS, WidgetType.TEAM_COUNT,
                           WidgetType.MY_SUBSCRIPTIONS, WidgetType.COMPANY_DEVICES)
                : List.of(WidgetType.MY_OPEN_TICKETS, WidgetType.MY_DEVICES,
                           WidgetType.MY_SUBSCRIPTIONS);
        return defaults.stream()
                .filter(w -> w.isAllowedFor(roles))
                .map(w -> compute(w, user))
                .toList();
    }
}
