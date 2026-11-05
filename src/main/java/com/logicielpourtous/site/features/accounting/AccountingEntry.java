package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "accounting_entries", schema = "public")
public class AccountingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id", nullable = false)
    private AccountingJournal journal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fiscal_year_id", nullable = false)
    private FiscalYear fiscalYear;

    @Column(nullable = false)
    private String reference;

    private String description;

    /** Tiers / partenaire (client, fournisseur, libellé banque...) */
    private String partner;

    private boolean locked = false;

    /** FK vers le lot d'écritures OD (null pour les écritures isolées) */
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<AccountingEntryLine> lines = new ArrayList<>();

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }
    public AccountingJournal getJournal() { return journal; }
    public void setJournal(AccountingJournal journal) { this.journal = journal; }
    public FiscalYear getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(FiscalYear fiscalYear) { this.fiscalYear = fiscalYear; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPartner() { return partner; }
    public void setPartner(String partner) { this.partner = partner; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<AccountingEntryLine> getLines() { return lines; }
    public void setLines(List<AccountingEntryLine> lines) { this.lines = lines; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
}
