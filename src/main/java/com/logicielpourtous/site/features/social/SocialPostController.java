package com.logicielpourtous.site.features.social;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/intra/social/posts")
public class SocialPostController {

    private final SocialPostRepository        postRepo;
    private final SocialPostTargetRepository  targetRepo;
    private final SocialNetworkConfigRepository configRepo;
    private final SocialPublisherService      publisherService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public SocialPostController(SocialPostRepository postRepo,
                                 SocialPostTargetRepository targetRepo,
                                 SocialNetworkConfigRepository configRepo,
                                 SocialPublisherService publisherService) {
        this.postRepo         = postRepo;
        this.targetRepo       = targetRepo;
        this.configRepo       = configRepo;
        this.publisherService = publisherService;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        List<SocialPost> posts = postRepo.findByTenantOrderByCreatedAtDesc(tenant);

        long drafts    = posts.stream().filter(p -> p.getStatus() == SocialPost.PostStatus.DRAFT).count();
        long scheduled = posts.stream().filter(p -> p.getStatus() == SocialPost.PostStatus.SCHEDULED).count();
        long published = posts.stream().filter(p -> p.getStatus() == SocialPost.PostStatus.PUBLISHED
                                                 || p.getStatus() == SocialPost.PostStatus.PARTIAL).count();
        long failed    = posts.stream().filter(p -> p.getStatus() == SocialPost.PostStatus.FAILED).count();

        model.addAttribute("posts",     posts);
        model.addAttribute("drafts",    drafts);
        model.addAttribute("scheduled", scheduled);
        model.addAttribute("published", published);
        model.addAttribute("failed",    failed);
        return "intra/social/posts/list";
    }

    // ── Formulaire nouveau post ───────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("configs",      configRepo.findByTenantAndActiveTrueOrderByNetworkTypeAsc(tenant));
        model.addAttribute("networkTypes", SocialNetworkType.values());
        return "intra/social/posts/form";
    }

    // ── Création ──────────────────────────────────────────────────────────────

    @PostMapping
    @Transactional
    public String create(@AuthenticationPrincipal CustomUserDetails principal,
                         @RequestParam String title,
                         @RequestParam String content,
                         @RequestParam(required = false) String xContent,
                         @RequestParam(required = false) String scheduledAt,
                         @RequestParam(required = false) List<Long> configIds,
                         @RequestParam(required = false) MultipartFile image,
                         RedirectAttributes ra) throws IOException {
        Tenant tenant = principal.getTenant();
        SocialPost post = new SocialPost();
        post.setTenant(tenant);
        post.setTitle(title);
        post.setContent(content);
        post.setCreatedById(principal.getUserId());

        // Upload image
        if (image != null && !image.isEmpty()) {
            String imagePath = saveImage(image, tenant.getId());
            post.setImagePath(imagePath);
        }

        // Planification
        if (scheduledAt != null && !scheduledAt.isBlank()) {
            post.setScheduledAt(LocalDateTime.parse(scheduledAt, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
            post.setStatus(SocialPost.PostStatus.SCHEDULED);
        }

        postRepo.save(post);

        // Cibles par réseau
        if (configIds != null) {
            for (Long configId : configIds) {
                configRepo.findById(configId).ifPresent(cfg -> {
                    SocialPostTarget target = new SocialPostTarget();
                    target.setPost(post);
                    target.setNetworkConfig(cfg);
                    // Override contenu X si réseau X et contenu spécifique fourni
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

        ra.addFlashAttribute("successMessage", "Publication créée.");
        return "redirect:/intra/social/posts/" + post.getId();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         Model model) {
        SocialPost post = postRepo.findByIdAndTenant(id, principal.getTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("post",    post);
        model.addAttribute("targets", targetRepo.findByPostOrderByIdAsc(post));
        return "intra/social/posts/detail";
    }

    // ── Publication immédiate ─────────────────────────────────────────────────

    @PostMapping("/{id}/publish")
    @Transactional
    public String publish(@PathVariable Long id,
                          @AuthenticationPrincipal CustomUserDetails principal,
                          RedirectAttributes ra) {
        SocialPost post = postRepo.findByIdAndTenant(id, principal.getTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (post.getStatus() != SocialPost.PostStatus.DRAFT
                && post.getStatus() != SocialPost.PostStatus.SCHEDULED
                && post.getStatus() != SocialPost.PostStatus.FAILED) {
            ra.addFlashAttribute("errorMessage", "Ce post ne peut pas être republié.");
            return "redirect:/intra/social/posts/" + id;
        }
        try {
            publisherService.publishPost(post);
            ra.addFlashAttribute("successMessage", "Publication lancée.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur : " + e.getMessage());
        }
        return "redirect:/intra/social/posts/" + id;
    }

    // ── Annulation planification ──────────────────────────────────────────────

    @PostMapping("/{id}/cancel")
    @Transactional
    public String cancel(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        SocialPost post = postRepo.findByIdAndTenant(id, principal.getTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (post.getStatus() == SocialPost.PostStatus.SCHEDULED) {
            post.setStatus(SocialPost.PostStatus.DRAFT);
            post.setScheduledAt(null);
            postRepo.save(post);
            ra.addFlashAttribute("successMessage", "Planification annulée, post remis en brouillon.");
        }
        return "redirect:/intra/social/posts/" + id;
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        postRepo.findByIdAndTenant(id, principal.getTenant()).ifPresent(post -> {
            if (post.getStatus() == SocialPost.PostStatus.DRAFT) {
                targetRepo.deleteByPost(post);
                postRepo.delete(post);
                ra.addFlashAttribute("successMessage", "Publication supprimée.");
            } else {
                ra.addFlashAttribute("errorMessage", "Seuls les brouillons peuvent être supprimés.");
            }
        });
        return "redirect:/intra/social/posts";
    }

    // ── Helper upload ─────────────────────────────────────────────────────────

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
