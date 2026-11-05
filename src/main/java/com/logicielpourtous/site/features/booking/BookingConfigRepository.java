package com.logicielpourtous.site.features.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingConfigRepository extends JpaRepository<BookingConfig, Long> {
    Optional<BookingConfig> findBySlugAndActiveTrue(String slug);
    List<BookingConfig> findAllByOrderByTitleAsc();
}
