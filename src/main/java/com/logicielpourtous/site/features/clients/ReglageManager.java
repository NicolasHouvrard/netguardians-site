package com.logicielpourtous.site.features.clients;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Un droit que le manager du client accorde a un role dans sa societe (couche 2 : les reglages du manager). */
@Entity
@Table(name = "reglages_manager", schema = "public")
@IdClass(ReglageManager.Cle.class)
@Getter
@Setter
@NoArgsConstructor
public class ReglageManager {

    @Id
    @Column(name = "client_societe_id", nullable = false)
    private Long clientSocieteId;

    @Id
    @Column(name = "role_nom", nullable = false, length = 100)
    private String roleNom;

    @Id
    @Column(name = "droit", nullable = false, length = 100)
    private String droit;

    public ReglageManager(Long clientSocieteId, String roleNom, String droit) {
        this.clientSocieteId = clientSocieteId;
        this.roleNom = roleNom;
        this.droit = droit;
    }

    @EqualsAndHashCode
    @NoArgsConstructor
    public static class Cle implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long clientSocieteId;
        private String roleNom;
        private String droit;
    }
}
