package com.logicielpourtous.site.features.campaign;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/campaigns")
public class CampaignController {

    private final MailCampaignRepository campaignRepository;

    public CampaignController(MailCampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("campaigns", campaignRepository.findAllByOrderByCreatedAtDesc());
        model.addAttribute("drafts",    campaignRepository.countByStatus(MailCampaign.CampaignStatus.DRAFT));
        model.addAttribute("scheduled", campaignRepository.countByStatus(MailCampaign.CampaignStatus.SCHEDULED));
        model.addAttribute("sent",      campaignRepository.countByStatus(MailCampaign.CampaignStatus.SENT));
        return "intra/campaigns/list";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam String subject,
                         @RequestParam(required = false) String htmlContent,
                         RedirectAttributes ra) {
        MailCampaign c = new MailCampaign();
        c.setName(name);
        c.setSubject(subject);
        c.setHtmlContent(htmlContent);
        campaignRepository.save(c);
        ra.addFlashAttribute("successMessage", "Campagne « " + name + " » créée.");
        return "redirect:/intra/campaigns";
    }
}
