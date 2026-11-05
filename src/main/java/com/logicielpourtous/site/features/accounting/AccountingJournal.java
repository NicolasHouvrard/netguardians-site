package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;

@Entity
@Table(name = "accounting_journals", schema = "public",
       uniqueConstraints = @UniqueConstraint(columnNames = {"code", "tenant_id"}))
public class AccountingJournal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "journal_type")
    private JournalType type;

    private boolean active = true;

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public JournalType getType() { return type; }
    public void setType(JournalType type) { this.type = type; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
}
