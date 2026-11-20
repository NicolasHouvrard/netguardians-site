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

/** Un droit que le partenaire referent autorise pour une societe cliente (couche 1 : le plafond). */
@Entity
@Table(name = "plafonds_client", schema = "public")
@IdClass(PlafondClient.Cle.class)
@Getter
@Setter
@NoArgsConstructor
public class PlafondClient {

    @Id
    @Column(name = "client_societe_id", nullable = false)
    private Long clientSocieteId;

    @Id
    @Column(name = "droit", nullable = false, length = 100)
    private String droit;

    public PlafondClient(Long clientSocieteId, String droit) {
        this.clientSocieteId = clientSocieteId;
        this.droit = droit;
    }

    @EqualsAndHashCode
    @NoArgsConstructor
    public static class Cle implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long clientSocieteId;
        private String droit;
    }
}
