package com.logicielpourtous.site.features.plateforme;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.logicielpourtous.site.features.plateforme.EmplacementsService.ElementAffiche;

/**
 * Expose à tous les écrans l'attribut {@code uiSlots}, utilisé par les fragments {@code socle/fragments/slots}. L'objet
 * est PARESSEUX : le construire ne coûte rien, le service n'est appelé que lorsqu'un gabarit appelle
 * {@code uiSlots.elements(emplacement, idEntite)}. Aucun état partagé entre requêtes ni entre utilisateurs ; toute
 * erreur donne une liste vide (échec fermé).
 */
@ControllerAdvice
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class EmplacementsAdvice {

	private final EmplacementsService service;

	public EmplacementsAdvice(EmplacementsService service) {
		this.service = service;
	}

	@ModelAttribute("uiSlots")
	public UiSlots uiSlots() {
		return new UiSlots(service);
	}

	/** Accès aux éléments d'un emplacement, pour les gabarits (société de la session). */
	public static final class UiSlots {

		private static final Logger log = LoggerFactory.getLogger(UiSlots.class);

		private final EmplacementsService service;

		UiSlots(EmplacementsService service) {
			this.service = service;
		}

		/** @param idEntite identifiant de l'entité de l'écran hôte (texte : le service accepte seulement un nombre) */
		public List<ElementAffiche> elements(String emplacement, String idEntite) {
			try {
				return service.elements(emplacement, null, idEntite);
			}
			catch (RuntimeException e) {
				log.warn("Emplacements : lecture en échec ({}), liste vide", e.getClass().getSimpleName());
				return List.of();
			}
		}

	}

}
