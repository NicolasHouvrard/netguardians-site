package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.campaign.MailCampaign;
import com.logicielpourtous.site.features.campaign.MailCampaignRepository;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class CampagnesActivesWidget implements IntraWidgetProvider {

    private final MailCampaignRepository repo;

    public CampagnesActivesWidget(MailCampaignRepository repo) { this.repo = repo; }

    @Override public String key() { return "CAMPAGNES_ACTIVES"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(repo.countByStatus(MailCampaign.CampaignStatus.SENT)));
    }
}
