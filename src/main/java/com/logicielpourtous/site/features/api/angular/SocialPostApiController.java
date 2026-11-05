package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.social.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/intra/social")
public class SocialPostApiController {

    private final SocialPostRepository           postRepo;
    private final SocialPostTargetRepository     targetRepo;
    private final SocialNetworkConfigRepository  configRepo;
    private final SocialPublisherService         publisherService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public SocialPostApiController(SocialPostRepository postRepo,
                                   SocialPostTargetRepository targetRepo,
                                   SocialNetworkConfigRepository configRepo,
                                   SocialPublisherService publisherService) {
        this.postRepo         = postRepo;
        this.targetRepo       = targetRepo;
        this.configRepo       = configRepo;
        this.publisherService = publisherService;
    }

    // ── Configs actives ───────────────────────────────────────────────────────

    @GetMapping("/configs")
    public List<Map<String, Object>> configs(@AuthenticationPrincipal CustomUserDetails p) {
        return configRepo.findByTenantAndActiveTrueOrderByNetworkTypeAsc(p.getTenant())
                .stream().map(this::configToMap).toList();
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @GetMapping("/posts/stats")
    public Map<String, Long> stats(@AuthenticationPrincipal CustomUserDetails p) {
        Tenant t = p.getTenant();
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("draft",     postRepo.countByTenantAndStatus(t, SocialPost.PostStatus.DRAFT));
        m.put("scheduled", postRepo.countByTenantAndStatus(t, SocialPost.PostStatus.SCHEDULED));
        m.put("published", postRepo.countByTenantAndStatus(t, SocialPost.PostStatus.PUBLISHED)
                         + postRepo.countByTenantAndStatus(t, SocialPost.PostStatus.PARTIAL));
        m.put("failed",    postRepo.countByTenantAndStatus(t, SocialPost.PostStatus.FAILED));
        return m;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping("/posts")
    public List<Map<String, Object>> list(@AuthenticationPrincipal CustomUserDetails p) {
        return postRepo.findByTenantOrderByCreatedAtDesc(p.getTenant())
                .stream().map(post -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          post.getId());
                    m.put("title",       post.getTitle());
                    m.put("content",     post.getContent());
                    m.put("status",      post.getStatus().name());
                    m.put("statusLabel", post.getStatus().getLabel());
                    m.put("scheduledAt", post.getScheduledAt() != null ? post.getScheduledAt().toString() : null);
                    m.put("publishedAt", post.getPublishedAt() != null ? post.getPublishedAt().toString() : null);
                    m.put("createdAt",   post.getCreatedAt()  != null ? post.getCreatedAt().toString()  : null);
                    List<Map<String, String>> targets = targetRepo.findByPostOrderByIdAsc(post).stream()
                            .map(t -> Map.of(
                                    "network", t.getNetworkConfig().getNetworkType().name(),
                                    "label",   t.getNetworkConfig().getName(),
                                    "icon",    t.getNetworkConfig().getNetworkType().getIcon(),
                                    "status",  t.getStatus().name()))
                            .toList();
                    m.put("targets", targets);
                    return m;
                }).toList();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/posts/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id,
                                                       @AuthenticationPrincipal CustomUserDetails p) {
        return postRepo.findByIdAndTenant(id, p.getTenant())
                .map(post -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          post.getId());
                    m.put("title",       post.getTitle());
                    m.put("content",     post.getContent());
                    m.put("imagePath",   post.getImagePath());
                    m.put("status",      post.getStatus().name());
                    m.put("statusLabel", post.getStatus().getLabel());
                    m.put("scheduledAt", post.getScheduledAt() != null ? post.getScheduledAt().toString() : null);
                    m.put("publishedAt", post.getPublishedAt() != null ? post.getPublishedAt().toString() : null);
                    m.put("createdAt",   post.getCreatedAt()  != null ? post.getCreatedAt().toString()  : null);
                    List<Map<String, Object>> targets = targetRepo.findByPostOrderByIdAsc(post).stream()
                            .map(t -> {
                                Map<String, Object> tm = new LinkedHashMap<>();
                                tm.put("id",            t.getId());
                                tm.put("network",       t.getNetworkConfig().getNetworkType().name());
                                tm.put("label",         t.getNetworkConfig().getName());
                                tm.put("icon",          t.getNetworkConfig().getNetworkType().getIcon());
                                tm.put("status",        t.getStatus().name());
                                tm.put("statusLabel",   t.getStatus().getLabel());
                                tm.put("customContent", t.getCustomContent());
                                tm.put("error",         t.getError());
                                tm.put("publishedAt",   t.getPublishedAt() != null ? t.getPublishedAt().toString() : null);
                                return tm;
                            }).toList();
                    m.put("targets", targets);
                    return ResponseEntity.ok(m);
                }).orElse(ResponseEntity.notFound().build());
    }

    // ── Création ──────────────────────────────────────────────────────────────

    @PostMapping("/posts")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails p,
            @RequestParam String title,
            @RequestParam String content,
            @RequestParam(required = false) String xContent,
            @RequestParam(required = false) String scheduledAt,
            @RequestParam(required = false) List<Long> configIds,
            @RequestParam(required = false) MultipartFile image) throws IOException {

        Tenant tenant = p.getTenant();
        SocialPost post = new SocialPost();
        post.setTenant(tenant);
        post.setTitle(title);
        post.setContent(content);
        post.setCreatedById(p.getUserId());

        if (image != null && !image.isEmpty()) {
            post.setImagePath(saveImage(image, tenant.getId()));
        }

        if (scheduledAt != null && !scheduledAt.isBlank()) {
            post.setScheduledAt(LocalDateTime.parse(scheduledAt, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
            post.setStatus(SocialPost.PostStatus.SCHEDULED);
        }

        postRepo.save(post);

        if (configIds != null) {
            for (Long configId : configIds) {
                configRepo.findById(configId).ifPresent(cfg -> {
                    SocialPostTarget target = new SocialPostTarget();
                    target.setPost(post);
                    target.setNetworkConfig(cfg);
                    if (cfg.getNetworkType() == SocialNetworkType.X
                            && xContent != null && !xContent.isBlank()) {
                        target.setCustomContent(xContent.length() > 280
                                ? xContent.substring(0, 277) + "..."
                                : xContent);
                    }
                    targetRepo.save(target);
                });
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", post.getId());
        return ResponseEntity.ok(res);
    }

    // ── Publication immédiate ─────────────────────────────────────────────────

    @PatchMapping("/posts/{id}/publish")
    @Transactional
    public ResponseEntity<Map<String, Object>> publish(@PathVariable Long id,
                                                        @AuthenticationPrincipal CustomUserDetails p) {
        return postRepo.findByIdAndTenant(id, p.getTenant()).map(post -> {
            if (post.getStatus() != SocialPost.PostStatus.DRAFT
                    && post.getStatus() != SocialPost.PostStatus.SCHEDULED
                    && post.getStatus() != SocialPost.PostStatus.FAILED) {
                return ResponseEntity.badRequest().<Map<String, Object>>body(Map.of("error", "Ce post ne peut pas être republié."));
            }
            try {
                publisherService.publishPost(post);
                return ResponseEntity.ok(Map.<String, Object>of("ok", true));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().<Map<String, Object>>body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Annulation planification ──────────────────────────────────────────────

    @PatchMapping("/posts/{id}/cancel")
    @Transactional
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id,
                                                       @AuthenticationPrincipal CustomUserDetails p) {
        return postRepo.findByIdAndTenant(id, p.getTenant()).map(post -> {
            if (post.getStatus() == SocialPost.PostStatus.SCHEDULED) {
                post.setStatus(SocialPost.PostStatus.DRAFT);
                post.setScheduledAt(null);
                postRepo.save(post);
            }
            return ResponseEntity.ok(Map.<String, Object>of("ok", true));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @DeleteMapping("/posts/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal CustomUserDetails p) {
        var opt = postRepo.findByIdAndTenant(id, p.getTenant());
        if (opt.isEmpty()) return ResponseEntity.<Void>notFound().build();
        SocialPost post = opt.get();
        if (post.getStatus() != SocialPost.PostStatus.DRAFT) return ResponseEntity.<Void>badRequest().build();
        targetRepo.deleteByPost(post);
        postRepo.delete(post);
        return ResponseEntity.<Void>ok().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> configToMap(SocialNetworkConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          cfg.getId());
        m.put("name",        cfg.getName());
        m.put("networkType", cfg.getNetworkType().name());
        m.put("label",       cfg.getNetworkType().getLabel());
        m.put("icon",        cfg.getNetworkType().getIcon());
        m.put("maxChars",    cfg.getNetworkType().getMaxChars());
        m.put("connected",   cfg.isConnected());
        return m;
    }

    private String saveImage(MultipartFile file, Long tenantId) throws IOException {
        String ext      = getExtension(file.getOriginalFilename());
        String filename = UUID.randomUUID() + ext;
        String subPath  = "social-posts/" + tenantId + "/" + filename;
        Path target     = Paths.get(uploadDir, "social-posts", String.valueOf(tenantId));
        Files.createDirectories(target);
        file.transferTo(target.resolve(filename));
        return subPath;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }
}
