package com.logicielpourtous.site.features.team;

import com.logicielpourtous.site.features.request.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByRequestTypesContaining(RequestType requestType);

    @Query("SELECT t FROM Team t LEFT JOIN FETCH t.members WHERE t.id = :id")
    Optional<Team> findByIdWithMembers(@Param("id") Long id);

    @Query("SELECT DISTINCT t FROM Team t LEFT JOIN FETCH t.members ORDER BY t.name")
    List<Team> findAllWithMembers();
}
