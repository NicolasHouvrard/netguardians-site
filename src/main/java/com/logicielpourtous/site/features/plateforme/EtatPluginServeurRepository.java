package com.logicielpourtous.site.features.plateforme;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * États rapportés par les serveurs (Maître seulement : n'existe que si {@code app.plateforme.actif=true} et
 * {@code app.role=MASTER}, comme le dépôt).
 */
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@Conditional(SurServeurMaitre.class)
public interface EtatPluginServeurRepository extends JpaRepository<EtatPluginServeur, EtatPluginServeur.Cle> {

	/**
	 * Enregistre le dernier état d'un plugin pour un serveur, en remplaçant le précédent : un seul ordre SQL
	 * atomique (deux rapports simultanés ne peuvent pas produire de doublon ni d'erreur de clé). Les valeurs doivent
	 * être déjà validées et nettoyées par l'appelant.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(nativeQuery = true, value = """
			INSERT INTO public.plateforme_etat_serveur (licence_serveur_id, nom, version, statut, raisons, rapporte_le)
			VALUES (:serveur, :nom, :version, :statut, :raisons, :rapporteLe)
			ON CONFLICT (licence_serveur_id, nom) DO UPDATE SET version = EXCLUDED.version,
				statut = EXCLUDED.statut, raisons = EXCLUDED.raisons, rapporte_le = EXCLUDED.rapporte_le
			""")
	void enregistrer(@Param("serveur") long serveur, @Param("nom") String nom, @Param("version") String version,
			@Param("statut") String statut, @Param("raisons") String raisons, @Param("rapporteLe") Instant rapporteLe);

	List<EtatPluginServeur> findAllByOrderByLicenceServeurIdAscNomAsc();

}
