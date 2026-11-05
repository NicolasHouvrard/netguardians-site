package com.logicielpourtous.site.features.esn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_esns")
@Getter @Setter @NoArgsConstructor
public class PartnerEsn {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "contact_name")
    private String contactName;

    private String email;
    private String phone;
    private String address;
    private String city;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    private String siret;

    @Column(name = "vat_number", length = 20)
    private String vatNumber;

    @Column(name = "default_hourly_rate", precision = 10, scale = 2)
    private BigDecimal defaultHourlyRate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }
}
