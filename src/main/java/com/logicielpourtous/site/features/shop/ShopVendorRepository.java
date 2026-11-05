package com.logicielpourtous.site.features.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ShopVendorRepository extends JpaRepository<ShopVendor, Long> {
    Optional<ShopVendor> findByApiKey(String apiKey);
    Optional<ShopVendor> findByCustomerId(Long customerId);
}
