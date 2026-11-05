package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.settings.SiteSettings;
import com.logicielpourtous.site.features.settings.SiteSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/intra/site-config")
public class SiteConfigApiController {

    private final SiteSettingsRepository siteSettingsRepository;

    public SiteConfigApiController(SiteSettingsRepository siteSettingsRepository) {
        this.siteSettingsRepository = siteSettingsRepository;
    }

    @GetMapping
    public Map<String, String> siteConfig() {
        return siteSettingsRepository.findById(1L)
                .map(this::toMap)
                .orElse(defaultMap());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> save(@RequestBody Map<String, String> body) {
        SiteSettings s = siteSettingsRepository.findById(1L).orElseGet(SiteSettings::new);
        if (body.containsKey("siteName"))        s.setSiteName(body.get("siteName"));
        if (body.containsKey("siteUrl"))         s.setSiteUrl(body.get("siteUrl"));
        if (body.containsKey("tagline"))         s.setTagline(body.get("tagline"));
        if (body.containsKey("faviconUrl"))      s.setFaviconUrl(body.get("faviconUrl"));
        if (body.containsKey("logoUrl"))         s.setLogoUrl(body.get("logoUrl"));
        if (body.containsKey("contactEmail"))    s.setContactEmail(body.get("contactEmail"));
        if (body.containsKey("contactPhone"))    s.setContactPhone(body.get("contactPhone"));
        if (body.containsKey("address"))         s.setAddress(body.get("address"));
        if (body.containsKey("metaDescription")) s.setMetaDescription(body.get("metaDescription"));
        if (body.containsKey("metaKeywords"))    s.setMetaKeywords(body.get("metaKeywords"));
        if (body.containsKey("socialFacebook"))  s.setSocialFacebook(body.get("socialFacebook"));
        if (body.containsKey("socialLinkedin"))  s.setSocialLinkedin(body.get("socialLinkedin"));
        if (body.containsKey("socialInstagram")) s.setSocialInstagram(body.get("socialInstagram"));
        if (body.containsKey("socialTwitter"))   s.setSocialTwitter(body.get("socialTwitter"));
        if (body.containsKey("socialYoutube"))   s.setSocialYoutube(body.get("socialYoutube"));
        if (body.containsKey("footerText"))      s.setFooterText(body.get("footerText"));
        if (body.containsKey("googleAnalyticsId")) s.setGoogleAnalyticsId(body.get("googleAnalyticsId"));
        return ResponseEntity.ok(toMap(siteSettingsRepository.save(s)));
    }

    private Map<String, String> toMap(SiteSettings s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("siteName",        nvl(s.getSiteName(), "Mon Site"));
        m.put("siteUrl",         nvl(s.getSiteUrl(), "/"));
        m.put("tagline",         nvl(s.getTagline(), ""));
        m.put("faviconUrl",      nvl(s.getFaviconUrl(), ""));
        m.put("logoUrl",         nvl(s.getLogoUrl(), ""));
        m.put("contactEmail",    nvl(s.getContactEmail(), ""));
        m.put("contactPhone",    nvl(s.getContactPhone(), ""));
        m.put("address",         nvl(s.getAddress(), ""));
        m.put("metaDescription", nvl(s.getMetaDescription(), ""));
        m.put("metaKeywords",    nvl(s.getMetaKeywords(), ""));
        m.put("socialFacebook",  nvl(s.getSocialFacebook(), ""));
        m.put("socialLinkedin",  nvl(s.getSocialLinkedin(), ""));
        m.put("socialInstagram", nvl(s.getSocialInstagram(), ""));
        m.put("socialTwitter",   nvl(s.getSocialTwitter(), ""));
        m.put("socialYoutube",   nvl(s.getSocialYoutube(), ""));
        m.put("footerText",      nvl(s.getFooterText(), ""));
        m.put("googleAnalyticsId", nvl(s.getGoogleAnalyticsId(), ""));
        return m;
    }

    private Map<String, String> defaultMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("siteName", "Mon Site"); m.put("siteUrl", "/");
        m.put("tagline", ""); m.put("faviconUrl", ""); m.put("logoUrl", "");
        m.put("contactEmail", ""); m.put("contactPhone", ""); m.put("address", "");
        m.put("metaDescription", ""); m.put("metaKeywords", "");
        m.put("socialFacebook", ""); m.put("socialLinkedin", "");
        m.put("socialInstagram", ""); m.put("socialTwitter", ""); m.put("socialYoutube", "");
        m.put("footerText", ""); m.put("googleAnalyticsId", "");
        return m;
    }

    private String nvl(String v, String def) { return v != null ? v : def; }
}
