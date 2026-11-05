package com.logicielpourtous.site.features.registration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionRequestRepository extends JpaRepository<SubscriptionRequest, Long> {
    List<SubscriptionRequest> findAllByOrderByRequestedAtDesc();
    List<SubscriptionRequest> findByStatusOrderByRequestedAtDesc(SubscriptionRequest.SubscriptionRequestStatus status);
    long countByStatus(SubscriptionRequest.SubscriptionRequestStatus status);
    boolean existsByEmail(String email);
}
