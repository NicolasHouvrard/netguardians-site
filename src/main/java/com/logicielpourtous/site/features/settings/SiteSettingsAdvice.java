package com.logicielpourtous.site.features.settings;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class SiteSettingsAdvice {

    private final SiteSettingsRepository repository;

    public SiteSettingsAdvice(SiteSettingsRepository repository) {
        this.repository = repository;
    }

    @ModelAttribute("siteSettings")
    public SiteSettings siteSettings() {
        return repository.findById(1L).orElseGet(SiteSettings::new);
    }
}
