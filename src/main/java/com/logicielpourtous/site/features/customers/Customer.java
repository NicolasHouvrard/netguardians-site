package com.logicielpourtous.site.features.customers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.subscription.Subscription;
import com.logicielpourtous.site.features.supplier.ApiType;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter 
@Setter
@NoArgsConstructor
@Table(name = "customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @NotBlank(message = "Le nom de l'entreprise est obligatoire")
    private String companyName;

    private String contactName;

    @Email(message = "Format d'email invalide")
    private String email;
    private String phone;
    private String website;

    @Column(columnDefinition = "TEXT")
    private String address;

    private boolean active = true;

    /** Dualité client / fournisseur — Boolean wrapper pour tolérer les NULL DB */
    @Column(name = "is_customer")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean isCustomer = true;

    @Column(name = "is_supplier")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean isSupplier = false;

    public boolean isCustomer() { return isCustomer == null || isCustomer; }
    public void setCustomer(boolean v) { this.isCustomer = v; }

    public boolean isSupplier() { return Boolean.TRUE.equals(isSupplier); }
    public void setSupplier(boolean v) { this.isSupplier = v; }

    // ── Informations fiscales & légales ──────────────────────────────────────

    @Column(name = "siren", length = 9)
    private String siren;

    @Column(name = "siret", length = 14)
    private String siret;

    @Column(name = "naf_code", length = 6)
    private String nafCode;

    @Column(name = "legal_form", length = 50)
    private String legalForm;

    @Column(name = "rcs_city", length = 100)
    private String rcsCity;

    // ── Zone fiscale / TVA ────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "fiscal_zone")
    @Getter(AccessLevel.NONE)
    private FiscalZone fiscalZone = FiscalZone.FRANCE;

    @Column(name = "vat_number")
    private String vatNumber;

    public enum FiscalZone {
        FRANCE("France", "TVA française"),
        EU("Union Européenne", "Intracommunautaire"),
        OUTSIDE_EU("Hors Europe", "Export / Exonéré");

        public final String label;
        public final String taxLabel;
        FiscalZone(String label, String taxLabel) { this.label = label; this.taxLabel = taxLabel; }
    }

    public FiscalZone getFiscalZone() {
        return fiscalZone != null ? fiscalZone : FiscalZone.FRANCE;
    }

    /** true si la TVA est à 0% par défaut pour ce client */
    public boolean isVatExempt() {
        FiscalZone zone = getFiscalZone();
        if (zone == FiscalZone.OUTSIDE_EU) return true;
        if (zone == FiscalZone.EU && vatNumber != null && !vatNumber.isBlank()) return true;
        return false;
    }

    /** Mention légale à imprimer sur la facture selon la zone fiscale */
    public String getDefaultVatMention() {
        FiscalZone zone = getFiscalZone();
        if (zone == FiscalZone.EU && vatNumber != null && !vatNumber.isBlank()) {
            return "Autoliquidation – TVA non applicable, art. 283-2 du CGI. N° TVA client : " + vatNumber;
        }
        if (zone == FiscalZone.OUTSIDE_EU) {
            return "Exonération de TVA – Exportation, art. 262 I du CGI";
        }
        return null;
    }

    /** ID de l'organisation NinjaOne liée (null = non lié) */
    @Column(name = "ninja_organization_id")
    private Integer ninjaOrganizationId;

    // ── Champs fournisseur (remplis uniquement si isSupplier = true) ──────────

    @Enumerated(EnumType.STRING)
    @Column(name = "supplier_api_type")
    private ApiType supplierApiType = ApiType.NONE;

    @Column(name = "supplier_api_key", columnDefinition = "TEXT")
    private String supplierApiKey;

    @Column(name = "supplier_api_url")
    private String supplierApiUrl;

    @Column(name = "supplier_api_client_id")
    private String supplierApiClientId;

    /** Ingram Micro : numéro de compte client (IM-CustomerNumber) */
    @Column(name = "supplier_customer_number")
    private String supplierCustomerNumber;

    /** Ingram Micro : code pays ISO (FR, BE, CH…) */
    @Column(name = "supplier_country_code")
    private String supplierCountryCode = "FR";

    @Column(name = "supplier_last_sync_at")
    private LocalDateTime supplierLastSyncAt;

    // ── Champs prospect (remplis uniquement si isProspect = true) ────────────

    @Column(name = "is_prospect")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean isProspect = false;

    public boolean isProspect() { return Boolean.TRUE.equals(isProspect); }
    public void setProspect(boolean v) { this.isProspect = v; }

    @Enumerated(EnumType.STRING)
    @Column(name = "prospect_status")
    private ProspectStatus prospectStatus = ProspectStatus.NOUVEAU;

    @Enumerated(EnumType.STRING)
    @Column(name = "prospect_source")
    private ProspectSource prospectSource;

    @Column(name = "assigned_commercial_id")
    private Long assignedCommercialId;

    @Column(name = "assigned_team_id")
    private Long assignedTeamId;

    @Column(name = "estimated_value", precision = 12, scale = 2)
    private BigDecimal estimatedValue;

    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    @Column(name = "prospect_notes", columnDefinition = "TEXT")
    private String prospectNotes;

    @Column(name = "lost_reason")
    private String lostReason;

    // ── Champs ESN partenaire (remplis si isEsn = true) ──────────────────────

    @Column(name = "is_esn")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean isEsn = false;

    public boolean isEsn() { return Boolean.TRUE.equals(isEsn); }
    public void setEsn(boolean v) { this.isEsn = v; }

    @Column(name = "default_hourly_rate", precision = 10, scale = 2)
    private BigDecimal defaultHourlyRate;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("name ASC")
    private List<Site> sites = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Contact> contacts = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
    @OrderBy("createdAt DESC")
    private List<Request> requests = new ArrayList<>();

    @OneToMany(mappedBy = "customer", fetch = jakarta.persistence.FetchType.LAZY)
    @OrderBy("active DESC, startDate DESC")
    private List<Subscription> subscriptions = new ArrayList<>();
}