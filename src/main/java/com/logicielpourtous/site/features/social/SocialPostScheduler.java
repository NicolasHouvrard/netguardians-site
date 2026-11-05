package com.logicielpourtous.site.features.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class SocialPostScheduler {

    private static final Logger log = LoggerFactory.getLogger(SocialPostScheduler.class);

    private final SocialPostRepository    postRepo;
    private final SocialPublisherService  publisherService;

    public SocialPostScheduler(SocialPostRepository postRepo, SocialPublisherService publisherService) {
        this.postRepo         = postRepo;
        this.publisherService = publisherService;
    }

    @Scheduled(fixedRate = 60_000)
    public void checkAndPublish() {
        // Posts planifiés dont l'heure est passée
        List<SocialPost> due = postRepo.findDueForPublishing(LocalDateTime.now());

        // Posts restés en PUBLISHING lors d'un crash → reprise
        List<SocialPost> stuck = postRepo.findStuckInPublishing();

        for (SocialPost post : due) {
            log.info("Scheduled publish — post id={} title='{}'", post.getId(), post.getTitle());
            tryPublish(post);
        }
        for (SocialPost post : stuck) {
            log.warn("Crash recovery publish — post id={} title='{}'", post.getId(), post.getTitle());
            tryPublish(post);
        }
    }

    private void tryPublish(SocialPost post) {
        try {
            publisherService.publishPost(post);
        } catch (Exception e) {
            log.error("Scheduler publish error for post id={}: {}", post.getId(), e.getMessage());
        }
    }
}
