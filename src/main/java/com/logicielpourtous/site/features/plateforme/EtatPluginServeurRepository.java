package com.logicielpourtous.site.features.plateforme;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.Limit;
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
	 * (deux rapports simultanés ne peuvent pas produire de doublon ni d'erreur de clé). Une ligne NOUVELLE n'est créée
	 * que si le serveur en a moins de {@code max} ; la mise à jour d'une ligne existante est toujours admise. Le
	 * plafond est approché sous concurrence (lecture du compte en READ COMMITTED : quelques lignes de plus au pire),
	 * ce qui suffit à borner la table. Les valeurs doivent être déjà validées et nettoyées par l'appelant ; les trois
	 * valeurs du Metaspace sont {@code null} ensemble (mesure absente ou invalide).
	 *
	 * @return 1 si enregistré, 0 si refusé par le plafond
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(nativeQuery = true, value = """
			INSERT INTO public.plateforme_etat_serveur (licence_serveur_id, nom, version, statut, raisons, rapporte_le,
					metaspace_mo, seuil_mo, redemarrage_recommande)
			SELECT :serveur, :nom, :version, :statut, :raisons, :rapporteLe,
					CAST(:metaspaceMo AS BIGINT), CAST(:seuilMo AS BIGINT), CAST(:redemarrage AS BOOLEAN)
			WHERE EXISTS (SELECT 1 FROM public.plateforme_etat_serveur e
					WHERE e.licence_serveur_id = :serveur AND e.nom = :nom)
				OR (SELECT count(*) FROM public.plateforme_etat_serveur e WHERE e.licence_serveur_id = :serveur) < :max
			ON CONFLICT (licence_serveur_id, nom) DO UPDATE SET version = EXCLUDED.version,
				statut = EXCLUDED.statut, raisons = EXCLUDED.raisons, rapporte_le = EXCLUDED.rapporte_le,
				metaspace_mo = EXCLUDED.metaspace_mo, seuil_mo = EXCLUDED.seuil_mo,
				redemarrage_recommande = EXCLUDED.redemarrage_recommande
			""")
	int enregistrer(@Param("serveur") long serveur, @Param("nom") String nom, @Param("version") String version,
			@Param("statut") String statut, @Param("raisons") String raisons, @Param("rapporteLe") Instant rapporteLe,
			@Param("metaspaceMo") Long metaspaceMo, @Param("seuilMo") Long seuilMo,
			@Param("redemarrage") Boolean redemarrage, @Param("max") int max);

	/** Derniers états triés par serveur puis par plugin, bornés par {@code limite}. */
	List<EtatPluginServeur> findAllByOrderByLicenceServeurIdAscNomAsc(Limit limite);

}
