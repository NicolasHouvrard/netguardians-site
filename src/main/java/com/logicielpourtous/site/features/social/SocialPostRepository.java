package com.logicielpourtous.site.features.social;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SocialPostRepository extends JpaRepository<SocialPost, Long> {
    List<SocialPost> findByTenantOrderByCreatedAtDesc(Tenant tenant);
    List<SocialPost> findByTenantAndStatusOrderByCreatedAtDesc(Tenant tenant, SocialPost.PostStatus status);
    Optional<SocialPost> findByIdAndTenant(Long id, Tenant tenant);
    long countByTenantAndStatus(Tenant tenant, SocialPost.PostStatus status);

    @Query("SELECT p FROM SocialPost p WHERE p.status = 'SCHEDULED' AND p.scheduledAt <= :now")
    List<SocialPost> findDueForPublishing(LocalDateTime now);

    @Query("SELECT p FROM SocialPost p WHERE p.status = 'PUBLISHING'")
    List<SocialPost> findStuckInPublishing();
}
