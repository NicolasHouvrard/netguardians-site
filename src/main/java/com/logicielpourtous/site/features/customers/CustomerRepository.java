package com.logicielpourtous.site.features.customers;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.logicielpourtous.site.features.customers.Customer;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {
    Optional<Customer> findByEmail(String email);

    List<Customer> findByCompanyNameContainingIgnoreCase(String name);

    List<Customer> findByNinjaOrganizationIdIsNotNull();

    @Query("SELECT c FROM Customer c WHERE c.isSupplier = true ORDER BY c.companyName ASC")
    List<Customer> findSuppliers();

    @Query("SELECT c FROM Customer c WHERE c.isCustomer = true ORDER BY c.companyName ASC")
    List<Customer> findCustomers();

    @Query("SELECT c FROM Customer c WHERE c.isProspect = true ORDER BY c.companyName ASC")
    List<Customer> findProspects();

    @Query("SELECT c FROM Customer c WHERE c.isProspect = true AND c.assignedCommercialId = :uid ORDER BY c.companyName ASC")
    List<Customer> findProspectsByCommercial(@Param("uid") Long userId);

    @Query("SELECT c FROM Customer c WHERE (c.isCustomer = true OR c.isCustomer IS NULL OR c.isProspect = true OR c.isEsn = true) ORDER BY c.companyName ASC")
    List<Customer> findAllContacts();

    @Query("""
            SELECT c FROM Customer c
            WHERE (c.isCustomer = true OR c.isCustomer IS NULL OR c.isProspect = true OR c.isEsn = true)
            AND (LOWER(c.companyName) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(c.contactName) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(c.email)       LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY c.companyName ASC
            """)
    List<Customer> searchAllContacts(@Param("q") String q);
}