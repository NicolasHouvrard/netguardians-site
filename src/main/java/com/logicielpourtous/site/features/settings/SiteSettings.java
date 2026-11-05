package com.logicielpourtous.site.features.settings;

import jakarta.persistence.*;

@Entity
@Table(name = "site_settings", schema = "public")
public class SiteSettings {

    @Id
    private Long id = 1L;

    // ── Identité ──────────────────────────────────────────────────────────────
    @Column(name = "site_name")
    private String siteName = "Mon Site";

    @Column(name = "site_url")
    private String siteUrl;

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "favicon_url")
    private String faviconUrl;

    @Column(name = "logo_url")
    private String logoUrl;

    // ── SEO ──────────────────────────────────────────────────────────────────
    @Column(name = "meta_description", columnDefinition = "TEXT")
    private String metaDescription;

    @Column(name = "meta_keywords")
    private String metaKeywords;

    // ── Contact ───────────────────────────────────────────────────────────────
    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    // ── Réseaux sociaux ───────────────────────────────────────────────────────
    @Column(name = "social_facebook")
    private String socialFacebook;

    @Column(name = "social_linkedin")
    private String socialLinkedin;

    @Column(name = "social_instagram")
    private String socialInstagram;

    @Column(name = "social_twitter")
    private String socialTwitter;

    @Column(name = "social_youtube")
    private String socialYoutube;

    // ── Pied de page & tracking ───────────────────────────────────────────────
    @Column(name = "footer_text")
    private String footerText;

    @Column(name = "google_analytics_id")
    private String googleAnalyticsId;

    // Getters & Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSiteName() { return siteName; }
    public void setSiteName(String siteName) { this.siteName = siteName; }

    public String getSiteUrl() { return siteUrl; }
    public void setSiteUrl(String siteUrl) { this.siteUrl = siteUrl; }

    public String getTagline() { return tagline; }
    public void setTagline(String tagline) { this.tagline = tagline; }

    public String getFaviconUrl() { return faviconUrl; }
    public void setFaviconUrl(String faviconUrl) { this.faviconUrl = faviconUrl; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getMetaDescription() { return metaDescription; }
    public void setMetaDescription(String metaDescription) { this.metaDescription = metaDescription; }

    public String getMetaKeywords() { return metaKeywords; }
    public void setMetaKeywords(String metaKeywords) { this.metaKeywords = metaKeywords; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getSocialFacebook() { return socialFacebook; }
    public void setSocialFacebook(String socialFacebook) { this.socialFacebook = socialFacebook; }

    public String getSocialLinkedin() { return socialLinkedin; }
    public void setSocialLinkedin(String socialLinkedin) { this.socialLinkedin = socialLinkedin; }

    public String getSocialInstagram() { return socialInstagram; }
    public void setSocialInstagram(String socialInstagram) { this.socialInstagram = socialInstagram; }

    public String getSocialTwitter() { return socialTwitter; }
    public void setSocialTwitter(String socialTwitter) { this.socialTwitter = socialTwitter; }

    public String getSocialYoutube() { return socialYoutube; }
    public void setSocialYoutube(String socialYoutube) { this.socialYoutube = socialYoutube; }

    public String getFooterText() { return footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }

    public String getGoogleAnalyticsId() { return googleAnalyticsId; }
    public void setGoogleAnalyticsId(String googleAnalyticsId) { this.googleAnalyticsId = googleAnalyticsId; }
}
