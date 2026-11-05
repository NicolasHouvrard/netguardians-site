package com.logicielpourtous.site.features.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentConfigRepository extends JpaRepository<PaymentConfig, Long> {
    Optional<PaymentConfig> findByType(PaymentConfig.ConfigType type);
}
