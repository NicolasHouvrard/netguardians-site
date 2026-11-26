package com.logicielpourtous.site.features.licence;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/licence/aiguillage} : reservee (SecurityConfig) aux serveurs de licence actifs, identifies par
 * leur cle API (principal = id du {@code LicenceServeur}). Repond toujours 200 avec la seule liste
 * {@code serveurs} (eventuellement vide) : aucune autre information, aucune difference selon la cause d'un refus.
 */
@RestController
@RequestMapping("/api/licence/aiguillage")
public class AiguillageController {

    public record Requete(String idToken) {
    }

    public record Reponse(List<AiguillageService.Destination> serveurs) {
    }

    private final AiguillageService service;

    public AiguillageController(AiguillageService service) {
        this.service = service;
    }

    @PostMapping
    public Reponse aiguiller(Authentication authentication, @RequestBody(required = false) Requete requete) {
        Long licenceServeurId = authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
        String jeton = requete == null ? null : requete.idToken();
        return new Reponse(service.aiguiller(licenceServeurId, jeton));
    }
}
