package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;

@Entity
@Table(name = "accounting_accounts", schema = "public",
       uniqueConstraints = @UniqueConstraint(columnNames = {"number", "tenant_id"}))
public class AccountingAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "number", nullable = false, length = 10)
    private String number;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "active")
    private boolean active = true;

    @Column(name = "postable")
    private boolean postable = true;

    public int getAccountClass() {
        if (number == null || number.isEmpty()) return 0;
        return number.charAt(0) - '0';
    }

    public String getDisplayName() { return number + " — " + label; }

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isPostable() { return postable; }
    public void setPostable(boolean postable) { this.postable = postable; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
}
