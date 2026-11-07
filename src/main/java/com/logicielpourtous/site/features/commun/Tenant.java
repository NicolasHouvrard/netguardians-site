package com.logicielpourtous.site.features.commun;

import java.util.ArrayList;
import java.util.List;

import com.logicielpourtous.site.features.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenants", schema = "public")
public class Tenant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() { return id; }

    @Column(nullable = false)
    private String name;
    public String getName(){
        return name;
    }
    public  void setName(String  name) {
            this.name = name;
    }

     @Column(name = "schema_name", unique = true, nullable = false)
    private String schemaName;
    // Type d'entreprise (Cumulables)
    
    @Column(name = "is_esn")
    private boolean isEsn;       // Prestataire / Facture ses services
    public boolean isEsn() {
        return isEsn;
    }

    public void setEsn(boolean isEsn) {
        this.isEsn = isEsn;
    }

    @Column(name = "is_client")
    private boolean isClient;    // Consomme des services
    public boolean isClient() {
        return isClient;
    }

    public void setClient(boolean isClient) {
        this.isClient = isClient;
    }

    @Column(name = "is_vendor")
    private boolean isVendor;    // Fournisseur de matériel/logiciel

    public boolean isVendor() {
        return isVendor;
    }

    public void setVendor(boolean isVendor) {
        this.isVendor = isVendor;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<User> users = new ArrayList<>();

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    @Column(length = 9)
private String siren;
    public String getSiren() {
        return siren;
    }

    public void setSiren(String siren) {
        this.siren = siren;
    }

    @Column(name = "billing_address")
    private String billingAddress;

    public String getBillingAddress() { return billingAddress; }
    public void setBillingAddress(String billingAddress) { this.billingAddress = billingAddress; }

    @Column(name = "vat_number", length = 20)
    private String vatNumber;

    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String vatNumber) { this.vatNumber = vatNumber; }

    @Column(length = 30)
    private String phone;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    @Column(length = 150)
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    @Column(name = "customer_id")
    private Long customerId;

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    /** Serveur d'accueil du tenant : id d'un {@code LicenceServeur} ; {@code null} = rattache au Maitre. */
    @Column(name = "serveur_licence_id")
    private Long serveurLicenceId;

    public Long getServeurLicenceId() { return serveurLicenceId; }
    public void setServeurLicenceId(Long serveurLicenceId) { this.serveurLicenceId = serveurLicenceId; }

    /** Pour un tenant client : le tenant partenaire dont il depend (un seul niveau). */
    @Column(name = "tenant_partenaire_id")
    private Long tenantPartenaireId;

    public Long getTenantPartenaireId() { return tenantPartenaireId; }
    public void setTenantPartenaireId(Long tenantPartenaireId) { this.tenantPartenaireId = tenantPartenaireId; }

    // Getters et Setters
    public String getSchemaName() {
        return schemaName;
    }

    public  void setSchemaName(String  schemaName) {
        this.schemaName = schemaName;
    }

}
