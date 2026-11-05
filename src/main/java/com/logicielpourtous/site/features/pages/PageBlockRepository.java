package com.logicielpourtous.site.features.pages;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PageBlockRepository extends JpaRepository<PageBlock, Long> {

    void deleteByPageId(Long pageId);
}
