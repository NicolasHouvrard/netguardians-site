package com.logicielpourtous.site.features.plateforme;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.plateforme.DistributionController.EtatServeur;
import com.logicielpourtous.site.features.plateforme.DistributionController.MetaspaceServeur;

/**
 * Lecture (sans HTTP) des derniers états de plugins rapportés par les serveurs annexes : partagée par
 * {@code GET /api/plateforme/serveurs-etat} et par l'écran {@code /admin/plugins}. Même condition que
 * {@link DistributionController} : Maître seulement.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@Conditional(SurServeurMaitre.class)
public class LectureEtatsServeurs {

	private final EtatPluginServeurRepository etats;

	public LectureEtatsServeurs(EtatPluginServeurRepository etats) {
		this.etats = etats;
	}

	/** Au plus {@code limite} états, triés par serveur puis par plugin ; la limite est bornée par l'appelant. */
	public List<EtatServeur> derniers(int limite) {
		return etats.findAllByOrderByLicenceServeurIdAscNomAsc(Limit.of(limite))
			.stream()
			.map(e -> new EtatServeur(e.getLicenceServeurId(), e.getNom(), e.getVersion(), e.getStatut(),
					e.getRaisons() == null || e.getRaisons().isEmpty() ? List.of() : List.of(e.getRaisons().split("\n")),
					e.getRapporteLe(),
					e.getMetaspaceMo() == null || e.getSeuilMo() == null || e.getRedemarrageRecommande() == null ? null
							: new MetaspaceServeur(e.getMetaspaceMo(), e.getSeuilMo(), e.getRedemarrageRecommande())))
			.toList();
	}

}
