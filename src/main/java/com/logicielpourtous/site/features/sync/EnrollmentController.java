package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.logicielpourtous.site.features.commun.ServerRole;

/**
 * Enrolement d'un serveur secondaire : echange d'un code a usage unique (genere cote maitre)
 * contre sa cle API. La cle est renvoyee en clair dans CETTE reponse uniquement ; seule son
 * empreinte ({@link SyncApiKeys#hash}) est conservee, aucune route ne permet de la relire ensuite.
 *
 * <p>Route publique (le code lui-meme est la preuve) — voir {@code SecurityConfig.syncApiFilterChain}.
 * Codes de reponse :
 * <ul>
 *   <li>400 : code absent ;</li>
 *   <li>401 : code inconnu ou deja consomme (indiscernables : un code consomme est efface),
 *       ou serveur {@code REVOQUE} ;</li>
 *   <li>410 : code expire (ou sans date d'expiration) ;</li>
 *   <li>409 : serveur enregistre avec un role autre que {@code CLIENT}/{@code PARTNER}.</li>
 * </ul>
 */
@RestController
public class EnrollmentController {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentController.class);

    public record EnrollmentRequest(String enrollmentCode) {}

    private final SecondaryServerRepository secondaryServerRepository;

    public EnrollmentController(SecondaryServerRepository secondaryServerRepository) {
        this.secondaryServerRepository = secondaryServerRepository;
    }

    @PostMapping("/api/sync/enroll")
    @Transactional
    public ResponseEntity<Map<String, String>> enroll(@RequestBody(required = false) EnrollmentRequest body) {
        if (body == null || body.enrollmentCode() == null || body.enrollmentCode().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Optional<SecondaryServer> found = secondaryServerRepository.findByEnrollmentCode(body.enrollmentCode());
        if (found.isEmpty()) {
            log.warn("Enrolement sync refuse : code inconnu ou deja utilise");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SecondaryServer server = found.get();

        if (server.getStatut() != SecondaryServer.SecondaryServerStatut.ACTIF) {
            log.warn("Enrolement sync refuse : serveur secondaire {} revoque", server.getId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (server.getEnrollmentCodeExpiresAt() == null || !server.getEnrollmentCodeExpiresAt().isAfter(Instant.now())) {
            log.warn("Enrolement sync refuse : code expire pour le serveur secondaire {}", server.getId());
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        if (server.getRole() != ServerRole.CLIENT && server.getRole() != ServerRole.PARTNER) {
            log.warn("Enrolement sync refuse : role {} invalide pour le serveur secondaire {}",
                    server.getRole(), server.getId());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        String apiKey = SyncApiKeys.generate();
        server.setApiKeyHash(SyncApiKeys.hash(apiKey));
        server.setEnrollmentCode(null);          // usage unique
        server.setEnrollmentCodeExpiresAt(null);
        secondaryServerRepository.save(server);
        log.info("Serveur secondaire {} enrole", server.getId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("apiKey", apiKey));
    }
}
