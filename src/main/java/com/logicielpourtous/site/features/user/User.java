package com.logicielpourtous.site.features.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.role.Role;

@Entity
@Table(name = "users",  schema = "public")
public class User implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Lien vers le Customer (schéma tenant) — null pour les utilisateurs internes */
    @Column(name = "customer_id")
    private Long customerId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "users_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    @Column(nullable = false, columnDefinition = "boolean DEFAULT true")
    private boolean active = true;

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    @Column(name = "must_change_password")
    private Boolean mustChangePassword = false;

    public boolean isMustChangePassword() { return Boolean.TRUE.equals(mustChangePassword); }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    @Column(name = "email_verification_token")
    private String emailVerificationToken;

    public String getEmailVerificationToken() { return emailVerificationToken; }
    public void setEmailVerificationToken(String emailVerificationToken) { this.emailVerificationToken = emailVerificationToken; }

    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }

    @Column(name = "reset_token")
    private String resetToken;

    @Column(name = "reset_token_expires_at")
    private LocalDateTime resetTokenExpiresAt;

    public String getResetToken() { return resetToken; }
    public void setResetToken(String resetToken) { this.resetToken = resetToken; }
    public LocalDateTime getResetTokenExpiresAt() { return resetTokenExpiresAt; }
    public void setResetTokenExpiresAt(LocalDateTime resetTokenExpiresAt) { this.resetTokenExpiresAt = resetTokenExpiresAt; }
    // --- LE GETTER MANQUANT ---
    public Set<Role> getRoles() {
        return roles;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * Date a laquelle l'e-mail du compte a ete verifie (ou le compte active par l'equipe). Jamais effacee : un
     * compte verifie puis desactive reste verifie, meme s'il recoit un nouveau jeton de verification. Un compte
     * enregistre ACTIF est considere verifie (voir {@link #marquerVerifieSiActif()}).
     */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    public LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }

    /** Marque l'e-mail verifie maintenant, sauf s'il l'etait deja (la premiere date est conservee). */
    public void marquerEmailVerifie() {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = LocalDateTime.now();
        }
    }

    /**
     * Annule la marque posee a la creation par {@link #marquerVerifieSiActif()} pour un compte qui nait en
     * attente de verification (inscription autonome). A n'appeler QUE dans la transaction qui cree le compte.
     */
    public void creeNonVerifie() {
        this.active = false;
        this.emailVerifiedAt = null;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        marquerVerifieSiActif();
    }

    @PreUpdate
    protected void onUpdate() {
        marquerVerifieSiActif();
    }

    /** Tout compte enregistre actif (cree actif, verifie, active par l'equipe) est verifie. */
    private void marquerVerifieSiActif() {
        if (active) {
            marquerEmailVerifie();
        }
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    // Getters et Setters (indispensables pour Spring)
    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    // Getter
    public Tenant getTenant() {
        return tenant;
    }

    // Setter
    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }
}