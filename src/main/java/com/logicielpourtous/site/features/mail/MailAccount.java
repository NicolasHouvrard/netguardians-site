package com.logicielpourtous.site.features.mail;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "mail_accounts")
@Getter @Setter @NoArgsConstructor
public class MailAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom affiché (ex: "Support Gmail", "Contact Microsoft") */
    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private MailProvider provider = MailProvider.CUSTOM;

    /** Mode d'authentification */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private AuthType authType = AuthType.PASSWORD;

    /** Adresse email / login IMAP */
    @Column(nullable = false)
    private String username;

    /** Mot de passe chiffré AES en base (utilisé si authType=PASSWORD) */
    @Convert(converter = MailEncryptionConverter.class)
    @Column(name = "encrypted_password")
    private String encryptedPassword;

    /** Nom affiché dans les emails sortants */
    @Column(name = "from_name")
    private String fromName;

    // ── IMAP (utilisé si authType=PASSWORD) ──────────────────────────────────
    @Column(name = "imap_host")
    private String imapHost;

    @Column(name = "imap_port")
    private int imapPort = 993;

    @Column(name = "imap_ssl")
    private boolean imapSsl = true;

    // ── SMTP (utilisé si authType=PASSWORD) ──────────────────────────────────
    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private int smtpPort = 587;

    @Column(name = "smtp_ssl")
    private boolean smtpSsl = false;

    @Column(name = "smtp_starttls")
    private boolean smtpStarttls = true;

    // ── OAuth2 (utilisé si authType=GOOGLE_OAUTH2 ou MICROSOFT_OAUTH2) ────────
    @Convert(converter = MailEncryptionConverter.class)
    @Column(name = "client_id")
    private String clientId;

    @Convert(converter = MailEncryptionConverter.class)
    @Column(name = "client_secret")
    private String clientSecret;

    @Convert(converter = MailEncryptionConverter.class)
    @Column(name = "refresh_token", length = 1024)
    private String refreshToken;

    @Convert(converter = MailEncryptionConverter.class)
    @Column(name = "access_token", length = 2048)
    private String accessToken;

    @Column(name = "token_expiry")
    private LocalDateTime tokenExpiry;

    /** Tenant ID Microsoft (ex: "common" ou UUID tenant) */
    @Column(name = "ms_tenant_id")
    private String msTenantId = "common";

    /** Utilisateur propriétaire de ce compte (null = compte partagé de l'équipe) */
    @Column(name = "user_id")
    private Long userId;

    // ── État ──────────────────────────────────────────────────────────────────
    private boolean active = true;

    @Column(name = "last_checked")
    private LocalDateTime lastChecked;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum AuthType {
        PASSWORD("Mot de passe / App Password"),
        GOOGLE_OAUTH2("Google OAuth2 (Gmail API)"),
        MICROSOFT_OAUTH2("Microsoft OAuth2 (Graph API)");

        public final String label;
        AuthType(String label) { this.label = label; }
    }

    public enum MailProvider {
        GMAIL("Gmail", "imap.gmail.com", 993, true, "smtp.gmail.com", 587, false, true),
        MICROSOFT("Microsoft / Outlook", "outlook.office365.com", 993, true, "smtp.office365.com", 587, false, true),
        CUSTOM("Serveur personnalisé", "", 993, true, "", 587, false, true);

        public final String label;
        public final String imapHost;
        public final int    imapPort;
        public final boolean imapSsl;
        public final String smtpHost;
        public final int    smtpPort;
        public final boolean smtpSsl;
        public final boolean smtpStarttls;

        MailProvider(String label,
                     String imapHost, int imapPort, boolean imapSsl,
                     String smtpHost, int smtpPort, boolean smtpSsl, boolean smtpStarttls) {
            this.label        = label;
            this.imapHost     = imapHost;
            this.imapPort     = imapPort;
            this.imapSsl      = imapSsl;
            this.smtpHost     = smtpHost;
            this.smtpPort     = smtpPort;
            this.smtpSsl      = smtpSsl;
            this.smtpStarttls = smtpStarttls;
        }
    }
}
