package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ContratIndisponibleException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Un contrat sans fournisseur actif (fournisseur absent, en maintenance ou retiré) est une indisponibilité
 * passagère : 503 + {@code Retry-After}, comme le mode maintenance. Les routes de plugin sont servies par le
 * DispatcherServlet du cœur, donc par ses résolveurs d'exceptions : ce conseil du cœur s'y applique.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class ContratIndisponibleAdvice {

	@ExceptionHandler(ContratIndisponibleException.class)
	public ResponseEntity<Map<String, String>> contratIndisponible(ContratIndisponibleException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header("Retry-After", "2")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("erreur", "contrat indisponible"));
	}

}
