package com.logicielpourtous.site.features.pages;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PublicPageRepository extends JpaRepository<PublicPage, Long> {

    Optional<PublicPage> findBySlug(String slug);

    Optional<PublicPage> findBySlugAndPublishedTrue(String slug);

    List<PublicPage> findByPageTypeAndPublishedTrueOrderByPublishedAtDesc(PageType type);

    List<PublicPage> findAllByOrderByCreatedAtDesc();

    List<PublicPage> findByPageTypeOrderByTitleAsc(PageType type);

    Optional<PublicPage> findFirstByHomePageTrue();

    @Modifying
    @Query("UPDATE PublicPage p SET p.homePage = false")
    void clearHomePageFlags();
}
