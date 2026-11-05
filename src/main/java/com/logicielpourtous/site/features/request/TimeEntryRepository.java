package com.logicielpourtous.site.features.request;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, Long> {
    void deleteByRequestId(Long requestId);
}
