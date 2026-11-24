package com.logicielpourtous.site.features.clients;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.plateforme.ContratRegistre;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat;

/**
 * Publie au registre de la plateforme les contrats implémentés par le noyau (identité, annuaire clients), pour que les
 * plugins les reçoivent par le proxy du registre comme tous les autres contrats.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
class PublicationContratsCoeur {

	PublicationContratsCoeur(ContratRegistre registre, IdentiteContrat identite, AnnuaireClientsContrat annuaire,
			GestionPartenairesContrat gestionPartenaires) {
		registre.publierCoeur(IdentiteContrat.class, identite);
		registre.publierCoeur(AnnuaireClientsContrat.class, annuaire);
		registre.publierCoeur(GestionPartenairesContrat.class, gestionPartenaires);
	}

}
