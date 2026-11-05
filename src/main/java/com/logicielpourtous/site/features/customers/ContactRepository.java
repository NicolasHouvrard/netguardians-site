package com.logicielpourtous.site.features.customers;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {

    List<Contact> findByCustomerId(Long customerId);
    Optional<Contact> findFirstByEmail(String email);
    Optional<Contact> findByUserId(Long userId);
    List<Contact> findByUserIdIn(List<Long> userIds);

    @Query("SELECT c FROM Contact c " +
           "WHERE c.customer.id = :customerId " +
           "AND (:siteId IS NULL OR c.site.id = :siteId) " +
           "AND (:q = '' OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "            OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%')))")
    List<Contact> search(@Param("customerId") Long customerId,
                         @Param("siteId")     Long siteId,
                         @Param("q")          String q);
}
