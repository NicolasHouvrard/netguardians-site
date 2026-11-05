package com.logicielpourtous.site.features.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MailCampaignRepository extends JpaRepository<MailCampaign, Long> {
    List<MailCampaign> findAllByOrderByCreatedAtDesc();
    List<MailCampaign> findByStatusOrderByCreatedAtDesc(MailCampaign.CampaignStatus status);
    long countByStatus(MailCampaign.CampaignStatus status);
}
