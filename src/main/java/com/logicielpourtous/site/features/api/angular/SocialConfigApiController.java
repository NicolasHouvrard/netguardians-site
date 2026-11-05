package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.social.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/intra/social/admin")
@PreAuthorize("hasRole('ADMIN')")
public class SocialConfigApiController {

    private final SocialNetworkConfigRepository configRepo;
    private final SocialPostTargetRepository    targetRepo;
    private final SocialOAuthService            oauthService;

    public SocialConfigApiController(SocialNetworkConfigRepository configRepo,
                                     SocialPostTargetRepository targetRepo,
                                     SocialOAuthService oauthService) {
        this.configRepo   = configRepo;
        this.targetRepo   = targetRepo;
        this.oauthService = oauthService;
    }

    // ── Types de réseaux disponibles ──────────────────────────────────────────

    @GetMapping("/network-types")
    public List<Map<String, Object>> networkTypes() {
        return Arrays.stream(SocialNetworkType.values()).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",     t.name());
            m.put("label",    t.getLabel());
            m.put("icon",     t.getIcon());
            m.put("maxChars", t.getMaxChars());
            return m;
        }).toList();
    }

    // ── Liste complète (admin) ────────────────────────────────────────────────

    @GetMapping("/configs")
    public List<Map<String, Object>> list(@AuthenticationPrincipal CustomUserDetails p) {
        return configRepo.findByTenantOrderByNetworkTypeAscNameAsc(p.getTenant())
                .stream().map(this::toMap).toList();
    }

    // ── Création ──────────────────────────────────────────────────────────────

    @PostMapping("/configs")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails p,
            @RequestBody Map<String, String> body) {
        SocialNetworkConfig cfg = new SocialNetworkConfig();
        cfg.setTenant(p.getTenant());
        cfg.setNetworkType(SocialNetworkType.valueOf(body.get("networkType")));
        cfg.setName(body.get("name"));
        setOptional(cfg, body);
        configRepo.save(cfg);
        return ResponseEntity.ok(toMap(cfg));
    }

    // ── Mise à jour ───────────────────────────────────────────────────────────

    @PutMapping("/configs/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails p,
            @RequestBody Map<String, String> body) {
        return configRepo.findByIdAndTenant(id, p.getTenant()).map(cfg -> {
            cfg.setName(body.get("name"));
            boolean credChanged = false;
            String clientId = body.get("clientId");
            String clientSecret = body.get("clientSecret");
            if (clientId != null && !clientId.isBlank()) {
                credChanged = !clientId.equals(cfg.getClientId());
                cfg.setClientId(clientId);
            }
            if (clientSecret != null && !clientSecret.isBlank()) {
                cfg.setClientSecret(clientSecret);
                credChanged = true;
            }
            String pageId = body.get("pageId");
            if (pageId != null && !pageId.isBlank()) cfg.setPageId(pageId);
            String handle = body.get("accountHandle");
            if (handle != null && !handle.isBlank()) cfg.setAccountHandle(handle);
            String activeStr = body.get("active");
            if (activeStr != null) cfg.setActive(Boolean.parseBoolean(activeStr));
            if (credChanged) {
                cfg.setAccessToken(null);
                cfg.setRefreshToken(null);
                cfg.setTokenExpiresAt(null);
            }
            configRepo.save(cfg);
            return ResponseEntity.ok(toMap(cfg));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @DeleteMapping("/configs/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal CustomUserDetails p) {
        var opt = configRepo.findByIdAndTenant(id, p.getTenant());
        if (opt.isEmpty()) return ResponseEntity.<Void>notFound().build();
        targetRepo.deleteByNetworkConfig(opt.get());
        configRepo.delete(opt.get());
        return ResponseEntity.<Void>ok().build();
    }

    // ── URL OAuth ─────────────────────────────────────────────────────────────

    @GetMapping("/configs/{id}/auth-url")
    public ResponseEntity<Map<String, String>> authUrl(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails p) {
        return configRepo.findByIdAndTenant(id, p.getTenant()).map(cfg -> {
            String state = oauthService.generateState(cfg);
            String url   = oauthService.buildAuthorizationUrl(cfg, state);
            return ResponseEntity.ok(Map.of("url", url));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setOptional(SocialNetworkConfig cfg, Map<String, String> body) {
        String clientId = body.get("clientId");
        if (clientId != null && !clientId.isBlank()) cfg.setClientId(clientId);
        String clientSecret = body.get("clientSecret");
        if (clientSecret != null && !clientSecret.isBlank()) cfg.setClientSecret(clientSecret);
        String pageId = body.get("pageId");
        if (pageId != null && !pageId.isBlank()) cfg.setPageId(pageId);
        String handle = body.get("accountHandle");
        if (handle != null && !handle.isBlank()) cfg.setAccountHandle(handle);
    }

    private Map<String, Object> toMap(SocialNetworkConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            cfg.getId());
        m.put("name",          cfg.getName());
        m.put("networkType",   cfg.getNetworkType().name());
        m.put("networkLabel",  cfg.getNetworkType().getLabel());
        m.put("networkIcon",   cfg.getNetworkType().getIcon());
        m.put("clientId",      cfg.getClientId());
        m.put("pageId",        cfg.getPageId());
        m.put("accountHandle", cfg.getAccountHandle());
        m.put("active",        cfg.isActive());
        m.put("connected",     cfg.isConnected());
        m.put("tokenExpired",  cfg.isTokenExpired());
        return m;
    }
}
