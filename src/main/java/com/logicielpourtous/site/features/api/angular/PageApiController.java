package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.pages.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/intra/pages")
public class PageApiController {

    private final PublicPageRepository pageRepository;

    public PageApiController(PublicPageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record PageSummaryDto(
            Long id, String title, String slug, String pageType,
            boolean published, boolean homePage,
            String createdAt, String publishedAt
    ) {
        static PageSummaryDto from(PublicPage p) {
            return new PageSummaryDto(
                    p.getId(), p.getTitle(), p.getSlug(),
                    p.getPageType() != null ? p.getPageType().name() : "PAGE",
                    p.isPublished(), p.isHomePage(),
                    p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                    p.getPublishedAt() != null ? p.getPublishedAt().toString() : null
            );
        }
    }

    public record BlockDto(Long id, String blockType, String content, int sortOrder) {
        static BlockDto from(PageBlock b) {
            return new BlockDto(b.getId(),
                    b.getBlockType() != null ? b.getBlockType().name() : "RICHTEXT",
                    b.getContent() != null ? b.getContent() : "{}",
                    b.getSortOrder());
        }
    }

    public record PageDto(
            Long id, String title, String slug, String pageType,
            boolean published, boolean homePage,
            String excerpt, String featuredImageUrl,
            String metaTitle, String metaDescription,
            String metaKeywords, String metaOgImage,
            String blogCategory, String blogTags,
            String createdAt, String publishedAt,
            List<BlockDto> blocks
    ) {
        static PageDto from(PublicPage p) {
            List<BlockDto> blocks = p.getBlocks() == null ? List.of()
                    : p.getBlocks().stream().map(BlockDto::from).toList();
            return new PageDto(
                    p.getId(), p.getTitle(), p.getSlug(),
                    p.getPageType() != null ? p.getPageType().name() : "PAGE",
                    p.isPublished(), p.isHomePage(),
                    p.getExcerpt(), p.getFeaturedImageUrl(),
                    p.getMetaTitle(), p.getMetaDescription(),
                    p.getMetaKeywords(), p.getMetaOgImage(),
                    p.getBlogCategory(), p.getBlogTags(),
                    p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                    p.getPublishedAt() != null ? p.getPublishedAt().toString() : null,
                    blocks
            );
        }
    }

    public record CreatePageRequest(String title, String pageType) {}

    public record SavePageRequest(
            String title, String slug, String pageType, String excerpt,
            String featuredImageUrl, String metaTitle, String metaDescription,
            String metaKeywords, String metaOgImage,
            String blogCategory, String blogTags,
            List<BlockDto> blocks
    ) {}

    public record BlockTypeDto(String type, String label, String icon) {}

    // ── GET /api/intra/pages/block-types ─────────────────────────────────────

    @GetMapping("/block-types")
    public List<BlockTypeDto> blockTypes() {
        return List.of(
                new BlockTypeDto("RICHTEXT",   "Texte riche",        "notes"),
                new BlockTypeDto("IMAGE",      "Image",              "image"),
                new BlockTypeDto("HERO",       "Bannière hero",      "panorama"),
                new BlockTypeDto("CTA",        "Appel à l'action",   "campaign"),
                new BlockTypeDto("COLUMNS",    "Colonnes",           "view_column"),
                new BlockTypeDto("TEXT_IMAGE", "Texte + Image",      "article"),
                new BlockTypeDto("IMAGE_TEXT", "Image + Texte",      "article"),
                new BlockTypeDto("CARDS",      "Cartes",             "grid_view"),
                new BlockTypeDto("HTML",       "HTML brut",          "code"),
                new BlockTypeDto("DIVIDER",    "Séparateur",         "horizontal_rule"),
                new BlockTypeDto("SPACER",     "Espacement",         "space_bar"),
                new BlockTypeDto("WIDGET",     "Widget",             "widgets")
        );
    }

    // ── GET /api/intra/pages ──────────────────────────────────────────────────

    @GetMapping
    public List<PageSummaryDto> list() {
        return pageRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(PageSummaryDto::from).toList();
    }

    // ── POST /api/intra/pages ─────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<PageDto> create(@RequestBody CreatePageRequest req) {
        PublicPage page = new PublicPage();
        page.setTitle(req.title() != null ? req.title() : "Nouvelle page");
        page.setSlug(generateSlug(req.title()));
        page.setPageType(parseType(req.pageType()));
        page.setPublished(false);
        return ResponseEntity.ok(PageDto.from(pageRepository.save(page)));
    }

    // ── GET /api/intra/pages/{id} ─────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<PageDto> get(@PathVariable Long id) {
        return pageRepository.findById(id)
                .map(p -> { p.getBlocks().size(); return ResponseEntity.ok(PageDto.from(p)); })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PUT /api/intra/pages/{id} ─────────────────────────────────────────────

    @Transactional
    @PutMapping("/{id}")
    public ResponseEntity<PageDto> save(@PathVariable Long id,
                                        @RequestBody SavePageRequest req) {
        return pageRepository.findById(id)
                .<ResponseEntity<PageDto>>map(page -> {
                    if (req.title()            != null) page.setTitle(req.title());
                    if (req.slug()             != null) page.setSlug(req.slug());
                    if (req.pageType()         != null) page.setPageType(parseType(req.pageType()));
                    page.setExcerpt(req.excerpt());
                    page.setFeaturedImageUrl(req.featuredImageUrl());
                    page.setMetaTitle(req.metaTitle());
                    page.setMetaDescription(req.metaDescription());
                    page.setMetaKeywords(req.metaKeywords());
                    page.setMetaOgImage(req.metaOgImage());
                    page.setBlogCategory(req.blogCategory());
                    page.setBlogTags(req.blogTags());

                    if (req.blocks() != null) {
                        page.getBlocks().clear();
                        pageRepository.saveAndFlush(page); // flush le clear avant d'ajouter
                        int order = 0;
                        for (BlockDto bd : req.blocks()) {
                            PageBlock block = new PageBlock();
                            block.setPage(page);
                            block.setBlockType(parseBlockType(bd.blockType()));
                            block.setContent(bd.content() != null ? bd.content() : "{}");
                            block.setSortOrder(order++);
                            page.getBlocks().add(block);
                        }
                    }
                    pageRepository.saveAndFlush(page);
                    page.getBlocks().size();
                    return ResponseEntity.ok(PageDto.from(page));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /api/intra/pages/{id} ──────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!pageRepository.existsById(id)) return ResponseEntity.notFound().build();
        pageRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── PATCH /api/intra/pages/{id}/publish ───────────────────────────────────

    @PatchMapping("/{id}/publish")
    public ResponseEntity<PageSummaryDto> togglePublish(@PathVariable Long id) {
        return pageRepository.findById(id)
                .<ResponseEntity<PageSummaryDto>>map(page -> {
                    boolean nowPublished = !page.isPublished();
                    page.setPublished(nowPublished);
                    if (nowPublished && page.getPublishedAt() == null) {
                        page.setPublishedAt(LocalDateTime.now());
                    }
                    return ResponseEntity.ok(PageSummaryDto.from(pageRepository.save(page)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /api/intra/pages/{id}/set-home ─────────────────────────────────

    @PatchMapping("/{id}/set-home")
    public ResponseEntity<PageSummaryDto> setHome(@PathVariable Long id) {
        return pageRepository.findById(id)
                .<ResponseEntity<PageSummaryDto>>map(page -> {
                    pageRepository.clearHomePageFlags();
                    page.setHomePage(true);
                    return ResponseEntity.ok(PageSummaryDto.from(pageRepository.save(page)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String generateSlug(String title) {
        if (title == null) return "page-" + System.currentTimeMillis();
        String base = title.toLowerCase()
                .replaceAll("[éèêë]", "e").replaceAll("[àâä]", "a")
                .replaceAll("[ùûü]", "u").replaceAll("[îï]", "i")
                .replaceAll("[ôö]", "o").replaceAll("[ç]", "c")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isBlank()) base = "page";
        String slug = base;
        int i = 2;
        while (pageRepository.findBySlug(slug).isPresent()) slug = base + "-" + i++;
        return slug;
    }

    private PageType parseType(String s) {
        if (s == null) return PageType.PAGE;
        try { return PageType.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return PageType.PAGE; }
    }

    private BlockType parseBlockType(String s) {
        if (s == null) return BlockType.RICHTEXT;
        try { return BlockType.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return BlockType.RICHTEXT; }
    }
}
