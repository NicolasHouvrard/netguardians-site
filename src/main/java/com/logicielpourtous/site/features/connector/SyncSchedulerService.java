package com.logicielpourtous.site.features.connector;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.supplier.IngramMicroService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class SyncSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SyncSchedulerService.class);

    private final TaskScheduler               taskScheduler;
    private final PlatformConnectorRepository connectorRepository;
    private final ConnectorSyncService        syncService;
    private final IngramMicroService          ingramMicroService;
    private final CustomerRepository          customerRepository;

    private final Map<Long, ScheduledFuture<?>> scheduledTasks      = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> priceScheduledTasks = new ConcurrentHashMap<>();

    public SyncSchedulerService(TaskScheduler taskScheduler,
                                PlatformConnectorRepository connectorRepository,
                                ConnectorSyncService syncService,
                                IngramMicroService ingramMicroService,
                                CustomerRepository customerRepository) {
        this.taskScheduler       = taskScheduler;
        this.connectorRepository = connectorRepository;
        this.syncService         = syncService;
        this.ingramMicroService  = ingramMicroService;
        this.customerRepository  = customerRepository;
    }

    @PostConstruct
    public void initSchedules() {
        List<PlatformConnector> connectors = connectorRepository.findAll();
        int catalogCount = 0, priceCount = 0;
        for (PlatformConnector c : connectors) {
            if (c.isActive() && c.isSchedulingEnabled()
                    && c.getCronExpression() != null && !c.getCronExpression().isBlank()) {
                schedule(c);
                catalogCount++;
            }
            if (c.isActive() && c.isPriceSchedulingEnabled()
                    && c.getPriceCronExpression() != null && !c.getPriceCronExpression().isBlank()) {
                schedulePriceSync(c);
                priceCount++;
            }
        }
        log.info("SyncScheduler : {} sync catalogue + {} sync prix planifiées au démarrage.",
                catalogCount, priceCount);
    }

    public void reschedule(PlatformConnector connector) {
        cancel(connector.getId());
        if (connector.isSchedulingEnabled() && connector.isActive()
                && connector.getCronExpression() != null
                && !connector.getCronExpression().isBlank()) {
            schedule(connector);
        }
        cancelPriceSync(connector.getId());
        if (connector.isPriceSchedulingEnabled() && connector.isActive()
                && connector.getPriceCronExpression() != null
                && !connector.getPriceCronExpression().isBlank()) {
            schedulePriceSync(connector);
        }
    }

    public void cancel(Long connectorId) {
        ScheduledFuture<?> future = scheduledTasks.remove(connectorId);
        if (future != null) {
            future.cancel(false);
            log.info("Cron catalogue annulé pour connecteur id={}", connectorId);
        }
    }

    public void cancelPriceSync(Long connectorId) {
        ScheduledFuture<?> future = priceScheduledTasks.remove(connectorId);
        if (future != null) {
            future.cancel(false);
            log.info("Cron prix annulé pour connecteur id={}", connectorId);
        }
    }

    private void schedule(PlatformConnector connector) {
        String cron = connector.getCronExpression();
        if (cron == null || cron.isBlank()) return;
        try {
            CronTrigger trigger = new CronTrigger(cron, TimeZone.getTimeZone("Europe/Paris"));
            Long cid = connector.getId();
            ScheduledFuture<?> future = taskScheduler.schedule(() -> runScheduled(cid), trigger);
            scheduledTasks.put(cid, future);
            log.info("Cron catalogue planifié connecteur id={} '{}' cron='{}'",
                    cid, connector.getName(), cron);
        } catch (IllegalArgumentException e) {
            log.warn("Expression cron invalide pour connecteur id={}: {}", connector.getId(), cron);
        }
    }

    private void schedulePriceSync(PlatformConnector connector) {
        String cron = connector.getPriceCronExpression();
        if (cron == null || cron.isBlank()) return;
        try {
            CronTrigger trigger = new CronTrigger(cron, TimeZone.getTimeZone("Europe/Paris"));
            Long cid = connector.getId();
            ScheduledFuture<?> future = taskScheduler.schedule(() -> runPriceSync(cid), trigger);
            priceScheduledTasks.put(cid, future);
            log.info("Cron prix planifié connecteur id={} '{}' cron='{}'",
                    cid, connector.getName(), cron);
        } catch (IllegalArgumentException e) {
            log.warn("Expression cron prix invalide pour connecteur id={}: {}", connector.getId(), cron);
        }
    }

    private void runScheduled(Long connectorId) {
        log.info("Sync catalogue planifiée — connecteur id={}", connectorId);
        PlatformConnector c = connectorRepository.findById(connectorId).orElse(null);
        if (c == null) return;
        try {
            if (c.getConnectorType() == ConnectorType.INGRAM_MICRO) {
                Customer supplier = resolveIngramSupplier(c);
                if (supplier != null) {
                    ingramMicroService.syncCatalogFullAsync(supplier.getId(), connectorId, null, null, null);
                } else {
                    log.warn("Sync catalogue planifiée annulée — aucun fournisseur Ingram configuré (connecteur id={})", connectorId);
                }
            } else {
                syncService.syncWithLog(connectorId, SyncLog.Trigger.SCHEDULED);
            }
        } catch (Exception e) {
            log.error("Erreur sync catalogue planifiée connecteur id={}: {}", connectorId, e.getMessage());
        }
    }

    private void runPriceSync(Long connectorId) {
        log.info("Sync prix/stock planifiée — connecteur id={}", connectorId);
        PlatformConnector c = connectorRepository.findById(connectorId).orElse(null);
        if (c == null) return;
        try {
            Customer supplier = resolveIngramSupplier(c);
            if (supplier != null) {
                ingramMicroService.refreshPricesAndStockAsync(supplier.getId(), connectorId);
            } else {
                log.warn("Sync prix planifiée annulée — aucun fournisseur Ingram configuré (connecteur id={})", connectorId);
            }
        } catch (Exception e) {
            log.error("Erreur sync prix planifiée connecteur id={}: {}", connectorId, e.getMessage());
        }
    }

    /**
     * Trouve le fournisseur Ingram avec credentials :
     * 1. Customer lié au connecteur s'il est un supplier avec credentials
     * 2. Sinon : premier supplier ayant un supplierApiClientId configuré
     */
    private Customer resolveIngramSupplier(PlatformConnector c) {
        if (c.getCustomer() != null) {
            Customer linked = customerRepository.findById(c.getCustomer().getId()).orElse(null);
            if (linked != null && linked.isSupplier()
                    && linked.getSupplierApiClientId() != null
                    && !linked.getSupplierApiClientId().isBlank()
                    && linked.getSupplierApiKey() != null
                    && !linked.getSupplierApiKey().isBlank()) {
                return linked;
            }
        }
        return customerRepository.findSuppliers().stream()
                .filter(s -> s.getSupplierApiClientId() != null
                        && !s.getSupplierApiClientId().isBlank()
                        && s.getSupplierApiKey() != null
                        && !s.getSupplierApiKey().isBlank())
                .findFirst().orElse(null);
    }
}
