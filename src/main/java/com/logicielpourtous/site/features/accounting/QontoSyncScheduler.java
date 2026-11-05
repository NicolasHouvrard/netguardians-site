package com.logicielpourtous.site.features.accounting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Synchronisation Qonto planifiée.
 * Vérifie toutes les minutes quels comptes ont la sync automatique activée
 * et déclenche le sync + rapprochement si les conditions (plage horaire, intervalle) sont remplies.
 */
@Component
public class QontoSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(QontoSyncScheduler.class);

    private final QontoConfigRepository  configRepo;
    private final QontoService           qontoService;
    private final QontoMatchingService   matchingService;

    public QontoSyncScheduler(QontoConfigRepository configRepo,
                               QontoService qontoService,
                               QontoMatchingService matchingService) {
        this.configRepo     = configRepo;
        this.qontoService   = qontoService;
        this.matchingService = matchingService;
    }

    @Scheduled(fixedRate = 60_000)
    public void run() {
        List<QontoConfig> configs = configRepo.findBySyncEnabledTrueAndActiveTrueOrderByIdAsc();
        if (configs.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        int currentHour = now.getHour();

        for (QontoConfig config : configs) {
            if (!config.isConfigured()) continue;
            if (currentHour < config.getSyncStartHour() || currentHour >= config.getSyncEndHour()) continue;

            if (config.getLastSyncAt() != null) {
                long minutesSince = java.time.Duration.between(config.getLastSyncAt(), now).toMinutes();
                if (minutesSince < config.getSyncIntervalMinutes()) continue;
            }

            try {
                log.info("Sync Qonto automatique — config #{} ({})", config.getId(), config.getLabel());
                QontoService.SyncResult result =
                        qontoService.syncFromQontoConfig(config.getTenant(), config, 2);
                log.info("Sync terminé — {} importée(s), {} ignorée(s), {} erreur(s)",
                        result.imported(), result.skipped(), result.errors());

                int matched = matchingService.matchUnreconciled(config.getTenant());
                if (matched > 0) log.info("Rapprochement automatique — {} transaction(s) rapprochée(s)", matched);

                config.setLastSyncAt(now);
                configRepo.save(config);
            } catch (Exception e) {
                log.error("Erreur sync Qonto config #{} : {}", config.getId(), e.getMessage());
            }
        }
    }
}
