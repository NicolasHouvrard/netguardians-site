package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.campaign.MailCampaign;
import com.logicielpourtous.site.features.campaign.MailCampaign.CampaignStatus;
import com.logicielpourtous.site.features.campaign.MailCampaignRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/intra/campaigns")
public class CampaignApiController {

    private final MailCampaignRepository campaignRepository;
    private final UserRepository         userRepository;

    public CampaignApiController(MailCampaignRepository campaignRepository,
                                  UserRepository userRepository) {
        this.campaignRepository = campaignRepository;
        this.userRepository     = userRepository;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CampaignDto(
            Long id, String name, String subject, String htmlContent,
            String status, String createdByName,
            int recipientCount, int openCount, int clickCount,
            String scheduledAt, String sentAt, String createdAt
    ) {
        static CampaignDto from(MailCampaign c) {
            return new CampaignDto(
                    c.getId(), c.getName(), c.getSubject(), c.getHtmlContent(),
                    c.getStatus() != null ? c.getStatus().name() : "DRAFT",
                    c.getCreatedBy() != null ? c.getCreatedBy().getFullName() : null,
                    c.getRecipientCount() != null ? c.getRecipientCount() : 0,
                    c.getOpenCount() != null ? c.getOpenCount() : 0,
                    c.getClickCount() != null ? c.getClickCount() : 0,
                    c.getScheduledAt() != null ? c.getScheduledAt().toString() : null,
                    c.getSentAt()      != null ? c.getSentAt().toString()      : null,
                    c.getCreatedAt()   != null ? c.getCreatedAt().toString()   : null
            );
        }
    }

    public record StatsDto(long draft, long scheduled, long sent, long failed, long total) {}

    public record CreateRequest(String name, String subject, String htmlContent) {}

    public record UpdateRequest(String name, String subject, String htmlContent) {}

    // ── GET /api/intra/campaigns ──────────────────────────────────────────────

    @GetMapping
    public List<CampaignDto> list() {
        return campaignRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(CampaignDto::from).toList();
    }

    // ── GET /api/intra/campaigns/stats ────────────────────────────────────────

    @GetMapping("/stats")
    public StatsDto stats() {
        long draft     = campaignRepository.countByStatus(CampaignStatus.DRAFT);
        long scheduled = campaignRepository.countByStatus(CampaignStatus.SCHEDULED);
        long sent      = campaignRepository.countByStatus(CampaignStatus.SENT);
        long failed    = campaignRepository.countByStatus(CampaignStatus.FAILED);
        return new StatsDto(draft, scheduled, sent, failed, draft + scheduled + sent + failed);
    }

    // ── POST /api/intra/campaigns ─────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<CampaignDto> create(@RequestBody CreateRequest req,
                                               Authentication auth) {
        if (req.name() == null || req.name().isBlank())
            return ResponseEntity.badRequest().build();

        MailCampaign c = new MailCampaign();
        c.setName(req.name().trim());
        c.setSubject(req.subject());
        c.setHtmlContent(req.htmlContent());
        c.setStatus(CampaignStatus.DRAFT);

        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails user) {
            userRepository.findById(user.getUserId()).ifPresent(c::setCreatedBy);
        }

        return ResponseEntity.ok(CampaignDto.from(campaignRepository.save(c)));
    }

    // ── GET /api/intra/campaigns/{id} ─────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<CampaignDto> get(@PathVariable Long id) {
        return campaignRepository.findById(id)
                .map(c -> ResponseEntity.ok(CampaignDto.from(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PUT /api/intra/campaigns/{id} ─────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<CampaignDto> update(@PathVariable Long id,
                                               @RequestBody UpdateRequest req) {
        return campaignRepository.findById(id)
                .<ResponseEntity<CampaignDto>>map(c -> {
                    if (req.name() != null)        c.setName(req.name());
                    if (req.subject() != null)     c.setSubject(req.subject());
                    if (req.htmlContent() != null) c.setHtmlContent(req.htmlContent());
                    return ResponseEntity.ok(CampaignDto.from(campaignRepository.save(c)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /api/intra/campaigns/{id}/status ────────────────────────────────

    @PatchMapping("/{id}/status")
    public ResponseEntity<CampaignDto> updateStatus(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body) {
        return campaignRepository.findById(id)
                .<ResponseEntity<CampaignDto>>map(c -> {
                    try {
                        c.setStatus(CampaignStatus.valueOf(body.get("status")));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().build();
                    }
                    return ResponseEntity.ok(CampaignDto.from(campaignRepository.save(c)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /api/intra/campaigns/{id} ──────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!campaignRepository.existsById(id)) return ResponseEntity.notFound().build();
        campaignRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
