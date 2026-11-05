package com.logicielpourtous.site.features.accounting;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "accounting_entry_lines", schema = "public")
public class AccountingEntryLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id", nullable = false)
    private AccountingEntry entry;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountingAccount account;

    @Column(nullable = false)
    private String label;

    @Column(precision = 15, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(precision = 15, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    private String lettrage;

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AccountingEntry getEntry() { return entry; }
    public void setEntry(AccountingEntry entry) { this.entry = entry; }
    public AccountingAccount getAccount() { return account; }
    public void setAccount(AccountingAccount account) { this.account = account; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = debit; }
    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }
    public String getLettrage() { return lettrage; }
    public void setLettrage(String lettrage) { this.lettrage = lettrage; }
}
