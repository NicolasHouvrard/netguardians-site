package com.logicielpourtous.site.features.pages;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
public class PublicPageController {

    private final PublicPageRepository pageRepository;
    private final PageRenderService pageRenderService;
    private final MenuItemRepository menuItemRepository;

    public PublicPageController(PublicPageRepository pageRepository,
                                PageRenderService pageRenderService,
                                MenuItemRepository menuItemRepository) {
        this.pageRepository = pageRepository;
        this.pageRenderService = pageRenderService;
        this.menuItemRepository = menuItemRepository;
    }

    // ─── Angular SPA — /app et /app/ → /app/index.html ───────────────────────

    @GetMapping({"/app", "/app/"})
    public String angularApp() {
        return "redirect:/app/index.html";
    }

    // ─── Blog list ───────────────────────────────────────────────────────────

    @GetMapping("/blog")
    public String blogList(Model model) {
        List<PublicPage> posts = pageRepository
                .findByPageTypeAndPublishedTrueOrderByPublishedAtDesc(PageType.BLOG_POST);
        model.addAttribute("posts", posts);
        return "public/blog-list";
    }

    // ─── Blog post ───────────────────────────────────────────────────────────

    @GetMapping("/blog/{slug}")
    public String blogPost(@PathVariable String slug, Model model) {
        PublicPage page = pageRepository
                .findBySlugAndPublishedTrue(slug)
                .filter(p -> p.getPageType() == PageType.BLOG_POST)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article introuvable : " + slug));

        model.addAttribute("page", page);
        model.addAttribute("renderedBlocks", pageRenderService.renderBlocks(page.getBlocks()));
        return "public/page";
    }

    // ─── Generic page ────────────────────────────────────────────────────────

    @GetMapping("/{slug}")
    public String page(@PathVariable String slug, Model model) {
        PublicPage page = pageRepository
                .findBySlugAndPublishedTrue(slug)
                .filter(p -> p.getPageType() == PageType.PAGE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Page introuvable : " + slug));

        model.addAttribute("page", page);
        model.addAttribute("renderedBlocks", pageRenderService.renderBlocks(page.getBlocks()));
        return "public/page";
    }
}
