package com.logicielpourtous.site.features.customers;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {

    List<Site> findByCustomerId(Long customerId);
    
    List<Site> findByCustomerIdAndActiveTrue(Long customerId);

    @Query("SELECT s FROM Site s WHERE s.customer.id = :customerId " +
           "AND (:query IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Site> findByCustomerIdAndNameContainingIgnoreCase(Long customerId, String query);

    @Query("SELECT s FROM Site s LEFT JOIN FETCH s.contacts WHERE s.id = :id")
    Optional<Site> findByIdWithContacts(@Param("id") Long id);
}