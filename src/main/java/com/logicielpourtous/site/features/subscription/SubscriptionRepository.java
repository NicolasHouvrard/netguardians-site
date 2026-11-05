package com.logicielpourtous.site.features.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByCustomerIdOrderByActiveDescStartDateDesc(Long customerId);

    List<Subscription> findByCustomerIdAndActiveTrueOrderByStartDateDesc(Long customerId);

    @Query("SELECT s FROM Subscription s JOIN s.customer c ORDER BY c.companyName ASC, s.active DESC, s.startDate DESC")
    List<Subscription> findAllOrderByCustomerAndActive();

    long countByActiveTrue();

    /** Abonnements PER_DEVICE actifs d'un client (override ou via plan) */
    @Query("""
        SELECT s FROM Subscription s LEFT JOIN s.plan p
        WHERE s.customer.id = :customerId
          AND s.active = true
          AND (s.billingModel = 'PER_DEVICE'
               OR (s.billingModel IS NULL AND p.billingModel = 'PER_DEVICE'))
        """)
    List<Subscription> findActivePerDeviceByCustomerId(@Param("customerId") Long customerId);
}
