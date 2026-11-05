package com.logicielpourtous.site.features.api.mobile;

import com.logicielpourtous.site.features.api.mobile.dto.*;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.intervention.InterventionImage;
import com.logicielpourtous.site.features.intervention.InterventionImageRepository;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mobile/interventions")
@Tag(name = "Interventions", description = "Consultation et clôture des interventions du technicien connecté")
public class MobileInterventionController {

    private final InterventionRepository      interventionRepository;
    private final InterventionImageRepository imageRepository;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public MobileInterventionController(InterventionRepository interventionRepository,
                                        InterventionImageRepository imageRepository) {
        this.interventionRepository = interventionRepository;
        this.imageRepository        = imageRepository;
    }

    @Operation(summary = "Liste mes interventions", description = "Retourne toutes les interventions assignées au technicien authentifié, triées par date décroissante.")
    @ApiResponse(responseCode = "200", description = "Liste retournée")
    @GetMapping
    public ResponseEntity<List<InterventionSummaryDto>> list(Authentication authentication) {
        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        List<InterventionSummaryDto> result = interventionRepository
            .findByTechnicianIdOrderByDateDesc(user.getUserId())
            .stream()
            .map(this::toSummary)
            .toList();
        return ResponseEntity.ok(result);
    }

    /** Full detail of a single intervention (only accessible to the assigned technician). */
    @GetMapping("/{id}")
    public ResponseEntity<InterventionDetailDto> detail(@PathVariable Long id,
                                                        Authentication authentication) {
        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        return interventionRepository.findById(id)
            .filter(i -> i.getTechnician() != null && user.getUserId().equals(i.getTechnician().getId()))
            .map(this::toDetail)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** Update the status of an intervention (e.g. ASSIGNED → IN_PROGRESS). */
    @PostMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable Long id,
                                             @RequestBody StatusUpdateRequest req,
                                             Authentication authentication) {
        Intervention intervention = interventionRepository.findById(id).orElse(null);
        if (intervention == null) return ResponseEntity.notFound().build();

        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        if (!isTechnician(user, intervention)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        try {
            intervention.setStatus(Intervention.InterventionStatus.valueOf(req.status()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        interventionRepository.save(intervention);
        return ResponseEntity.ok().build();
    }

    /** Close an intervention: resolution, durations, success flag, and digital signatures. */
    @PostMapping("/{id}/close")
    public ResponseEntity<Void> close(@PathVariable Long id,
                                      @RequestBody CloseInterventionRequest req,
                                      Authentication authentication) {
        Intervention intervention = interventionRepository.findById(id).orElse(null);
        if (intervention == null) return ResponseEntity.notFound().build();

        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        if (!isTechnician(user, intervention)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        intervention.setResolution(req.resolution());
        intervention.setSuccessful(req.successful());
        intervention.setActualDurationMinutes(req.durationMinutes());

        if (req.actualStart() != null) intervention.setActualStart(LocalDateTime.parse(req.actualStart()));
        if (req.actualEnd()   != null) intervention.setActualEnd(LocalDateTime.parse(req.actualEnd()));

        intervention.setTechnicianSignature(req.technicianSignature());
        intervention.setTechnicianSignatureName(req.technicianSignatureName());
        intervention.setClientSignature(req.clientSignature());
        intervention.setClientSignatureName(req.clientSignatureName());
        intervention.setStatus(Intervention.InterventionStatus.COMPLETED);

        interventionRepository.save(intervention);
        return ResponseEntity.ok().build();
    }

    // ── Images ──────────────────────────────────────────────────────────────────

    @Operation(summary = "Liste les images d'une intervention")
    @GetMapping("/{id}/images")
    public ResponseEntity<List<InterventionImageDto>> listImages(@PathVariable Long id,
                                                                 Authentication authentication) {
        Intervention intervention = interventionRepository.findById(id).orElse(null);
        if (intervention == null) return ResponseEntity.notFound().build();

        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        if (!isTechnician(user, intervention)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        List<InterventionImageDto> images = imageRepository
                .findByInterventionIdOrderByUploadedAtAsc(id)
                .stream().map(InterventionImageDto::from).toList();
        return ResponseEntity.ok(images);
    }

    @Operation(summary = "Téléverse une ou plusieurs images sur une intervention",
               description = "Champ multipart 'files' (multiple) accepté. Réservé au technicien assigné.")
    @PostMapping(value = "/{id}/images", consumes = "multipart/form-data")
    public ResponseEntity<List<InterventionImageDto>> uploadImages(
            @PathVariable Long id,
            @RequestParam("files") MultipartFile[] files,
            Authentication authentication) {

        Intervention intervention = interventionRepository.findById(id).orElse(null);
        if (intervention == null) return ResponseEntity.notFound().build();

        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        if (!isTechnician(user, intervention)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        if (files == null || files.length == 0)
            return ResponseEntity.badRequest().build();

        List<InterventionImageDto> saved = new ArrayList<>();
        try {
            Path dir = Paths.get(uploadDir, "interventions", String.valueOf(id));
            Files.createDirectories(dir);

            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                String orig = file.getOriginalFilename();
                String ext  = (orig != null && orig.contains("."))
                        ? orig.substring(orig.lastIndexOf('.')) : "";
                String stored = UUID.randomUUID() + ext;
                Files.copy(file.getInputStream(), dir.resolve(stored));

                InterventionImage img = new InterventionImage();
                img.setIntervention(intervention);
                img.setFilename("/uploads/interventions/" + id + "/" + stored);
                img.setOriginalName(orig);
                imageRepository.save(img);
                saved.add(InterventionImageDto.from(img));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "Supprime une image d'une intervention")
    @DeleteMapping("/{id}/images/{imageId}")
    public ResponseEntity<Void> deleteImage(@PathVariable Long id,
                                            @PathVariable Long imageId,
                                            Authentication authentication) {
        Intervention intervention = interventionRepository.findById(id).orElse(null);
        if (intervention == null) return ResponseEntity.notFound().build();

        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        if (!isTechnician(user, intervention)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        return imageRepository.findById(imageId)
                .filter(img -> img.getIntervention().getId().equals(id))
                .<ResponseEntity<Void>>map(img -> {
                    try {
                        Path f = Paths.get(uploadDir, img.getFilename().replaceFirst("^/uploads/", ""));
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {}
                    imageRepository.delete(img);
                    return ResponseEntity.noContent().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isTechnician(CustomUserDetails user, Intervention intervention) {
        return intervention.getTechnician() != null
            && user.getUserId().equals(intervention.getTechnician().getId());
    }

    private InterventionSummaryDto toSummary(Intervention i) {
        return new InterventionSummaryDto(
            i.getId(),
            i.getReference(),
            i.getTitle(),
            i.getStatus().name(),
            i.getStatus().label,
            i.getStatus().badgeCss,
            i.getType().name(),
            i.getType().label,
            i.getDate()           != null ? i.getDate().toString()           : null,
            i.getScheduledStart() != null ? i.getScheduledStart().toString() : null,
            i.getScheduledEnd()   != null ? i.getScheduledEnd().toString()   : null,
            i.getCustomer()       != null ? i.getCustomer().getCompanyName() : null,
            i.getAddress(),
            i.getCity(),
            i.getZipCode()
        );
    }

    private InterventionDetailDto toDetail(Intervention i) {
        return new InterventionDetailDto(
            i.getId(),
            i.getReference(),
            i.getTitle(),
            i.getStatus().name(),
            i.getStatus().label,
            i.getType().name(),
            i.getType().label,
            i.getDate()           != null ? i.getDate().toString()           : null,
            i.getScheduledStart() != null ? i.getScheduledStart().toString() : null,
            i.getScheduledEnd()   != null ? i.getScheduledEnd().toString()   : null,
            i.getActualStart()    != null ? i.getActualStart().toString()    : null,
            i.getActualEnd()      != null ? i.getActualEnd().toString()      : null,
            i.getActualDurationMinutes(),
            i.getDescription(),
            i.getResolution(),
            i.getSuccessful(),
            i.getAddress(),
            i.getCity(),
            i.getZipCode(),
            i.getCustomer() != null ? i.getCustomer().getCompanyName() : null,
            i.getCustomer() != null ? i.getCustomer().getId()          : null,
            i.getTechnicianSignature(),
            i.getTechnicianSignatureName(),
            i.getClientSignature(),
            i.getClientSignatureName()
        );
    }
}
