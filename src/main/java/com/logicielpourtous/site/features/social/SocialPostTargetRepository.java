package com.logicielpourtous.site.features.social;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SocialPostTargetRepository extends JpaRepository<SocialPostTarget, Long> {
    List<SocialPostTarget> findByPostOrderByIdAsc(SocialPost post);
    List<SocialPostTarget> findByPostAndStatus(SocialPost post, SocialPostTarget.TargetStatus status);
    void deleteByPost(SocialPost post);
    void deleteByNetworkConfig(SocialNetworkConfig config);
}
