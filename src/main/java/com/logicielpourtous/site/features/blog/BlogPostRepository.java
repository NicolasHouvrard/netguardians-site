package com.logicielpourtous.site.features.blog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BlogPostRepository extends JpaRepository<BlogPost, Long> {
    List<BlogPost> findAllByOrderByCreatedAtDesc();
    List<BlogPost> findByStatusOrderByPublishedAtDesc(BlogPost.PostStatus status);
    Optional<BlogPost> findBySlug(String slug);
    long countByStatus(BlogPost.PostStatus status);
}
