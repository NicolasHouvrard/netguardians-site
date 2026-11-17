package com.logicielpourtous.site.features.plateforme;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Dernier état d'un plugin rapporté par un serveur au Maître (table {@code plateforme_etat_serveur}, voir
 * {@code V9__plateforme_etat_serveur.sql}). Clé : (id du {@code LicenceServeur} authentifié, nom du plugin) ; un
 * nouveau rapport remplace le précédent. {@code statut} est un nom de {@link ResultatInstallation.Statut} ;
 * {@code raisons} contient les motifs déjà nettoyés, un par ligne. Écrite uniquement par
 * {@link EtatPluginServeurRepository#enregistrer}.
 */
@Entity
@Table(name = "plateforme_etat_serveur", schema = "public")
@IdClass(EtatPluginServeur.Cle.class)
public class EtatPluginServeur {

	/** Clé composée (serveur, plugin). */
	public static class Cle implements Serializable {

		private static final long serialVersionUID = 1L;

		private Long licenceServeurId;

		private String nom;

		public Cle() {
		}

		public Cle(Long licenceServeurId, String nom) {
			this.licenceServeurId = licenceServeurId;
			this.nom = nom;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Cle c && Objects.equals(licenceServeurId, c.licenceServeurId)
					&& Objects.equals(nom, c.nom);
		}

		@Override
		public int hashCode() {
			return Objects.hash(licenceServeurId, nom);
		}

	}

	@Id
	@Column(name = "licence_serveur_id", nullable = false)
	private Long licenceServeurId;

	@Id
	@Column(name = "nom", nullable = false, length = 40)
	private String nom;

	@Column(name = "version", nullable = false, length = 64)
	private String version;

	@Column(name = "statut", nullable = false, length = 16)
	private String statut;

	@Column(name = "raisons", nullable = false, columnDefinition = "text")
	private String raisons;

	@Column(name = "metaspace_mo")
	private Long metaspaceMo;

	@Column(name = "seuil_mo")
	private Long seuilMo;

	@Column(name = "redemarrage_recommande")
	private Boolean redemarrageRecommande;

	@Column(name = "rapporte_le", nullable = false)
	private Instant rapporteLe;

	protected EtatPluginServeur() {
	}

	public Long getLicenceServeurId() {
		return licenceServeurId;
	}

	public String getNom() {
		return nom;
	}

	public String getVersion() {
		return version;
	}

	public String getStatut() {
		return statut;
	}

	public String getRaisons() {
		return raisons;
	}

	public Long getMetaspaceMo() {
		return metaspaceMo;
	}

	public Long getSeuilMo() {
		return seuilMo;
	}

	public Boolean getRedemarrageRecommande() {
		return redemarrageRecommande;
	}

	public Instant getRapporteLe() {
		return rapporteLe;
	}

}
