package com.logicielpourtous.site.features.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppartenanceRepository extends JpaRepository<Appartenance, Long> {

    List<Appartenance> findByUserIdOrderById(Long userId);
}
