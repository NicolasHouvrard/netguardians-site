package com.logicielpourtous.site.features.settings;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/settings/site")
public class SiteSettingsController {

    private final SiteSettingsRepository repository;

    public SiteSettingsController(SiteSettingsRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String form(Model model) {
        SiteSettings settings = repository.findById(1L).orElseGet(SiteSettings::new);
        model.addAttribute("settings", settings);
        return "intra/settings/site";
    }

    @PostMapping
    public String save(@ModelAttribute SiteSettings incoming, RedirectAttributes ra) {
        SiteSettings settings = repository.findById(1L).orElseGet(SiteSettings::new);
        settings.setId(1L);
        settings.setSiteName(incoming.getSiteName());
        settings.setSiteUrl(incoming.getSiteUrl());
        settings.setTagline(incoming.getTagline());
        settings.setFaviconUrl(incoming.getFaviconUrl());
        settings.setLogoUrl(incoming.getLogoUrl());
        settings.setMetaDescription(incoming.getMetaDescription());
        settings.setMetaKeywords(incoming.getMetaKeywords());
        settings.setContactEmail(incoming.getContactEmail());
        settings.setContactPhone(incoming.getContactPhone());
        settings.setAddress(incoming.getAddress());
        settings.setSocialFacebook(incoming.getSocialFacebook());
        settings.setSocialLinkedin(incoming.getSocialLinkedin());
        settings.setSocialInstagram(incoming.getSocialInstagram());
        settings.setSocialTwitter(incoming.getSocialTwitter());
        settings.setSocialYoutube(incoming.getSocialYoutube());
        settings.setFooterText(incoming.getFooterText());
        settings.setGoogleAnalyticsId(incoming.getGoogleAnalyticsId());
        repository.save(settings);
        ra.addFlashAttribute("successMessage", "Paramètres sauvegardés.");
        return "redirect:/intra/settings/site";
    }
}
