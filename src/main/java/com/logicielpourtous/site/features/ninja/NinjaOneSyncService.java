package com.logicielpourtous.site.features.ninja;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.subscription.Subscription;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NinjaOneSyncService {

    private static final Logger log = LoggerFactory.getLogger(NinjaOneSyncService.class);

    private final NinjaOneConfigRepository  configRepository;
    private final NinjaOneClient            ninjaOneClient;
    private final CustomerRepository        customerRepository;
    private final SubscriptionRepository    subscriptionRepository;

    public NinjaOneSyncService(NinjaOneConfigRepository configRepository,
                               NinjaOneClient ninjaOneClient,
                               CustomerRepository customerRepository,
                               SubscriptionRepository subscriptionRepository) {
        this.configRepository       = configRepository;
        this.ninjaOneClient         = ninjaOneClient;
        this.customerRepository     = customerRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    // ── Synchro automatique toutes les heures ─────────────────────────────

    @Scheduled(fixedDelay = 3_600_000)
    public void scheduledSync() {
        configRepository.findById(1L).ifPresent(cfg -> {
            if (cfg.isEnabled()) syncAll(cfg);
        });
    }

    // ── Synchro manuelle (déclenchée depuis l'UI) ─────────────────────────

    public SyncResult syncAll(NinjaOneConfig config) {
        int customersProcessed = 0, subscriptionsUpdated = 0;
        StringBuilder errors = new StringBuilder();

        List<Customer> linked = customerRepository.findByNinjaOrganizationIdIsNotNull();

        for (Customer customer : linked) {
            try {
                List<NinjaDevice> devices = ninjaOneClient.getDevices(config, customer.getNinjaOrganizationId());
                int deviceCount = (int) devices.stream().filter(NinjaDevice::getOnline).count();

                List<Subscription> perDeviceSubs = subscriptionRepository
                        .findActivePerDeviceByCustomerId(customer.getId());

                for (Subscription sub : perDeviceSubs) {
                    sub.setUnitCount(deviceCount);
                    subscriptionRepository.save(sub);
                    subscriptionsUpdated++;
                }
                customersProcessed++;
            } catch (Exception e) {
                log.warn("Sync Ninja échouée pour client {} : {}", customer.getId(), e.getMessage());
                errors.append("Client ").append(customer.getCompanyName())
                      .append(" : ").append(e.getMessage()).append("\n");
            }
        }

        config.setLastSyncAt(LocalDateTime.now());
        config.setLastError(errors.isEmpty() ? null : errors.toString());
        configRepository.save(config);

        return new SyncResult(customersProcessed, subscriptionsUpdated, errors.toString());
    }

    // ── Résultat ──────────────────────────────────────────────────────────

    public record SyncResult(int customers, int subscriptions, String errors) {
        public boolean hasErrors() { return errors != null && !errors.isBlank(); }
    }
}
