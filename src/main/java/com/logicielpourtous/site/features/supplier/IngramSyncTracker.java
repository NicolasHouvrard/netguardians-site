package com.logicielpourtous.site.features.supplier;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IngramSyncTracker {

    public enum SyncStatus { IDLE, RUNNING, DONE, ERROR }

    public static class JobState {
        public volatile SyncStatus status = SyncStatus.IDLE;
        public volatile int page = 0;
        public volatile int created = 0;
        public volatile int updated = 0;
        public volatile int errors = 0;
        public volatile String message = "";
        public volatile LocalDateTime startedAt;
        public volatile LocalDateTime finishedAt;
        // Filtres actifs (pour affichage dans le widget de progression)
        public volatile String filterVendors;   // ex: "HP, Lenovo"
        public volatile String filterCategory;
        public volatile String filterKeyword;
    }

    private final ConcurrentHashMap<Long, JobState> jobs = new ConcurrentHashMap<>();

    public JobState getOrCreate(Long supplierId) {
        return jobs.computeIfAbsent(supplierId, id -> new JobState());
    }

    public JobState get(Long supplierId) {
        return jobs.getOrDefault(supplierId, new JobState());
    }
}
