package com.logicielpourtous.site.features.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class SocialPublisherService {

    private static final Logger log = LoggerFactory.getLogger(SocialPublisherService.class);

    private final SocialPostRepository       postRepo;
    private final SocialPostTargetRepository targetRepo;
    private final SocialOAuthService         oauthService;
    private final ObjectMapper               objectMapper;
    private final WebClient                  webClient;

    @Value("${shop.base-url:http://localhost:8080}")
    private String baseUrl;

    public SocialPublisherService(SocialPostRepository postRepo,
                                   SocialPostTargetRepository targetRepo,
                                   SocialOAuthService oauthService,
                                   ObjectMapper objectMapper) {
        this.postRepo      = postRepo;
        this.targetRepo    = targetRepo;
        this.oauthService  = oauthService;
        this.objectMapper  = objectMapper;
        this.webClient     = WebClient.builder().build();
    }

    @Transactional
    public void publishPost(SocialPost post) {
        post.setStatus(SocialPost.PostStatus.PUBLISHING);
        postRepo.save(post);

        List<SocialPostTarget> targets = targetRepo.findByPostAndStatus(post, SocialPostTarget.TargetStatus.PENDING);
        if (targets.isEmpty()) {
            post.setStatus(SocialPost.PostStatus.FAILED);
            postRepo.save(post);
            return;
        }

        for (SocialPostTarget target : targets) {
            target.setAttempts(target.getAttempts() + 1);
            try {
                oauthService.refreshTokenIfNeeded(target.getNetworkConfig());
                String externalId = switch (target.getNetworkConfig().getNetworkType()) {
                    case LINKEDIN      -> publishToLinkedIn(target);
                    case LINKEDIN_PAGE -> publishToLinkedInPage(target);
                    case FACEBOOK      -> publishToFacebook(target);
                    case INSTAGRAM     -> publishToInstagram(target);
                    case X             -> publishToX(target);
                };
                target.setStatus(SocialPostTarget.TargetStatus.PUBLISHED);
                target.setExternalPostId(externalId);
                target.setPublishedAt(LocalDateTime.now());
                target.setError(null);
            } catch (Exception e) {
                log.error("Publish failed for target id={} network={}: {}",
                        target.getId(), target.getNetworkConfig().getNetworkType(), e.getMessage());
                target.setStatus(SocialPostTarget.TargetStatus.FAILED);
                target.setError(e.getMessage());
            }
            targetRepo.save(target);
        }

        // Calcul statut global du post
        List<SocialPostTarget> all = targetRepo.findByPostOrderByIdAsc(post);
        long published = all.stream().filter(t -> t.getStatus() == SocialPostTarget.TargetStatus.PUBLISHED).count();
        long failed    = all.stream().filter(t -> t.getStatus() == SocialPostTarget.TargetStatus.FAILED).count();

        if (published == all.size()) {
            post.setStatus(SocialPost.PostStatus.PUBLISHED);
        } else if (published > 0) {
            post.setStatus(SocialPost.PostStatus.PARTIAL);
        } else {
            post.setStatus(SocialPost.PostStatus.FAILED);
        }
        post.setPublishedAt(LocalDateTime.now());
        postRepo.save(post);
    }

    // ── LinkedIn ──────────────────────────────────────────────────────────────

    private String publishToLinkedIn(SocialPostTarget target) throws Exception {
        SocialNetworkConfig config = target.getNetworkConfig();
        String content = effectiveContent(target);
        String authorUrn = getLinkedInAuthorUrn(config);

        Map<String, Object> body = Map.of(
                "author", authorUrn,
                "lifecycleState", "PUBLISHED",
                "specificContent", Map.of(
                        "com.linkedin.ugc.ShareContent", Map.of(
                                "shareCommentary", Map.of("text", content),
                                "shareMediaCategory", "NONE"
                        )
                ),
                "visibility", Map.of("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC")
        );

        String bodyJson = objectMapper.writeValueAsString(body);
        log.info("LinkedIn ugcPosts request — author: {} body: {}", authorUrn, bodyJson);

        // L'API retourne 201 avec l'ID du post dans le header X-RestLi-Id
        var response = webClient.post()
                .uri("https://api.linkedin.com/v2/ugcPosts")
                .header("Authorization", "Bearer " + config.getAccessToken())
                .header("X-Restli-Protocol-Version", "2.0.0")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bodyJson)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).map(errorBody -> {
                            log.error("LinkedIn ugcPosts error {} — body: {}",
                                    clientResponse.statusCode(), errorBody);
                            return new RuntimeException(
                                    clientResponse.statusCode() + " — " + errorBody);
                        })
                )
                .toEntity(String.class)
                .block();

        if (response == null) return "published";
        String postId = response.getHeaders().getFirst("X-RestLi-Id");
        return postId != null ? postId : "published";
    }

    private String getLinkedInAuthorUrn(SocialNetworkConfig config) throws Exception {
        // Page LinkedIn (organisation)
        if (config.getPageId() != null && !config.getPageId().isBlank()) {
            return "urn:li:organization:" + config.getPageId();
        }
        // URN membre stocké lors de l'échange OAuth (via /userinfo OIDC)
        if (config.getAccountHandle() != null && !config.getAccountHandle().isBlank()) {
            return "urn:li:person:" + config.getAccountHandle();
        }
        // Fallback : appel /oidc/userinfo pour obtenir le sub (compatible openid+profile)
        String userInfo = webClient.get()
                .uri("https://api.linkedin.com/oidc/userinfo")
                .header("Authorization", "Bearer " + config.getAccessToken())
                .retrieve()
                .bodyToMono(String.class)
                .block();
        String sub = objectMapper.readTree(userInfo).path("sub").asText();
        if (sub.isBlank()) throw new IllegalStateException("Impossible de résoudre le sub LinkedIn depuis /oidc/userinfo");
        return "urn:li:person:" + sub;
    }

    // ── LinkedIn Page entreprise ──────────────────────────────────────────────

    private String publishToLinkedInPage(SocialPostTarget target) throws Exception {
        SocialNetworkConfig config = target.getNetworkConfig();
        if (config.getPageId() == null || config.getPageId().isBlank())
            throw new IllegalStateException("Organization ID LinkedIn manquant — renseignez le Page ID dans la configuration.");

        String content    = effectiveContent(target);
        String authorUrn  = "urn:li:organization:" + config.getPageId();

        Map<String, Object> body = Map.of(
                "author", authorUrn,
                "lifecycleState", "PUBLISHED",
                "specificContent", Map.of(
                        "com.linkedin.ugc.ShareContent", Map.of(
                                "shareCommentary", Map.of("text", content),
                                "shareMediaCategory", "NONE"
                        )
                ),
                "visibility", Map.of("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC")
        );

        String bodyJson = objectMapper.writeValueAsString(body);
        log.info("LinkedIn Page ugcPosts request — author: {} body: {}", authorUrn, bodyJson);

        var response = webClient.post()
                .uri("https://api.linkedin.com/v2/ugcPosts")
                .header("Authorization", "Bearer " + config.getAccessToken())
                .header("X-Restli-Protocol-Version", "2.0.0")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bodyJson)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).map(errorBody -> {
                            log.error("LinkedIn Page ugcPosts error {} — body: {}",
                                    clientResponse.statusCode(), errorBody);
                            return new RuntimeException(clientResponse.statusCode() + " — " + errorBody);
                        })
                )
                .toEntity(String.class)
                .block();

        if (response == null) return "published";
        String postId = response.getHeaders().getFirst("X-RestLi-Id");
        return postId != null ? postId : "published";
    }

    // ── Facebook ──────────────────────────────────────────────────────────────

    private String publishToFacebook(SocialPostTarget target) throws Exception {
        SocialNetworkConfig config = target.getNetworkConfig();
        String pageId = config.getPageId();
        if (pageId == null || pageId.isBlank())
            throw new IllegalStateException("Page ID Facebook manquant dans la configuration.");

        String content = effectiveContent(target);
        Map<String, String> body = Map.of("message", content, "access_token", config.getAccessToken());

        String response = webClient.post()
                .uri("https://graph.facebook.com/v19.0/" + pageId + "/feed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return objectMapper.readTree(response).path("id").asText("unknown");
    }

    // ── Instagram ─────────────────────────────────────────────────────────────

    private String publishToInstagram(SocialPostTarget target) throws Exception {
        SocialNetworkConfig config = target.getNetworkConfig();
        String igUserId = config.getPageId();
        if (igUserId == null || igUserId.isBlank())
            throw new IllegalStateException("Instagram Business Account ID manquant.");

        SocialPost post = target.getPost();
        if (post.getImagePath() == null || post.getImagePath().isBlank())
            throw new IllegalStateException("Instagram requiert une image.");

        String imageUrl = baseUrl + "/uploads/" + post.getImagePath();
        String content  = effectiveContent(target);

        // Étape 1 : créer le container média
        Map<String, String> mediaBody = Map.of(
                "image_url", imageUrl,
                "caption", content,
                "access_token", config.getAccessToken()
        );
        String mediaResponse = webClient.post()
                .uri("https://graph.facebook.com/v19.0/" + igUserId + "/media")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(mediaBody))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String creationId = objectMapper.readTree(mediaResponse).path("id").asText();

        // Étape 2 : publier
        Map<String, String> publishBody = Map.of(
                "creation_id", creationId,
                "access_token", config.getAccessToken()
        );
        String publishResponse = webClient.post()
                .uri("https://graph.facebook.com/v19.0/" + igUserId + "/media_publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(publishBody))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return objectMapper.readTree(publishResponse).path("id").asText("unknown");
    }

    // ── X (Twitter) ───────────────────────────────────────────────────────────

    private String publishToX(SocialPostTarget target) throws Exception {
        SocialNetworkConfig config = target.getNetworkConfig();
        String content = effectiveContent(target);

        // Tronquer à 280 chars si nécessaire (ne devrait pas arriver si UI valide)
        if (content.length() > 280) content = content.substring(0, 277) + "...";

        Map<String, String> body = Map.of("text", content);

        String response = webClient.post()
                .uri("https://api.twitter.com/2/tweets")
                .header("Authorization", "Bearer " + config.getAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return objectMapper.readTree(response).path("data").path("id").asText("unknown");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String effectiveContent(SocialPostTarget target) {
        if (target.getCustomContent() != null && !target.getCustomContent().isBlank())
            return target.getCustomContent();
        return target.getPost().getContent();
    }
}
