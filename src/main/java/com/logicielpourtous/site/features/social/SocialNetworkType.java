package com.logicielpourtous.site.features.social;

public enum SocialNetworkType {

    LINKEDIN("LinkedIn — Compte perso", "fab fa-linkedin", 3000, false,
            "https://www.linkedin.com/oauth/v2/authorization",
            "https://www.linkedin.com/oauth/v2/accessToken",
            "openid profile w_member_social"),

    LINKEDIN_PAGE("LinkedIn — Page entreprise", "fab fa-linkedin", 3000, false,
            "https://www.linkedin.com/oauth/v2/authorization",
            "https://www.linkedin.com/oauth/v2/accessToken",
            "w_organization_social"),

    FACEBOOK("Facebook", "fab fa-facebook", 63206, false,
            "https://www.facebook.com/v19.0/dialog/oauth",
            "https://graph.facebook.com/v19.0/oauth/access_token",
            "pages_manage_posts pages_read_engagement"),

    INSTAGRAM("Instagram", "fab fa-instagram", 2200, true,
            "https://www.facebook.com/v19.0/dialog/oauth",
            "https://graph.facebook.com/v19.0/oauth/access_token",
            "instagram_basic instagram_content_publish pages_show_list"),

    X("X (Twitter)", "fab fa-x-twitter", 280, false,
            "https://twitter.com/i/oauth2/authorize",
            "https://api.twitter.com/2/oauth2/token",
            "tweet.write users.read offline.access");

    private final String label;
    private final String icon;
    private final int maxChars;
    private final boolean requiresImage;
    private final String authUrl;
    private final String tokenUrl;
    private final String scope;

    SocialNetworkType(String label, String icon, int maxChars, boolean requiresImage,
                      String authUrl, String tokenUrl, String scope) {
        this.label         = label;
        this.icon          = icon;
        this.maxChars      = maxChars;
        this.requiresImage = requiresImage;
        this.authUrl       = authUrl;
        this.tokenUrl      = tokenUrl;
        this.scope         = scope;
    }

    public String getLabel()        { return label; }
    public String getIcon()         { return icon; }
    public int    getMaxChars()     { return maxChars; }
    public boolean isRequiresImage(){ return requiresImage; }
    public String getAuthUrl()      { return authUrl; }
    public String getTokenUrl()     { return tokenUrl; }
    public String getScope()        { return scope; }
}
