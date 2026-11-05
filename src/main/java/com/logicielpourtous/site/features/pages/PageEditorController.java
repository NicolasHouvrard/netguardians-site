package com.logicielpourtous.site.features.pages;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.pages.widget.WidgetRegistry;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/intra/pages")
public class PageEditorController {

    private static final Logger log = LoggerFactory.getLogger(PageEditorController.class);

    private final PublicPageRepository pageRepository;
    private final PageBlockRepository blockRepository;
    private final PageRenderService pageRenderService;
    private final WidgetRegistry widgetRegistry;
    private final ObjectMapper objectMapper;

    public PageEditorController(PublicPageRepository pageRepository,
                                PageBlockRepository blockRepository,
                                PageRenderService pageRenderService,
                                WidgetRegistry widgetRegistry,
                                ObjectMapper objectMapper) {
        this.pageRepository = pageRepository;
        this.blockRepository = blockRepository;
        this.pageRenderService = pageRenderService;
        this.widgetRegistry = widgetRegistry;
        this.objectMapper = objectMapper;
    }

    // ─── List ────────────────────────────────────────────────────────────────

    @GetMapping({"", "/"})
    public String list(Model model) {
        model.addAttribute("pages", pageRepository.findAllByOrderByCreatedAtDesc());
        return "intra/pages/list";
    }

    // ─── Create ──────────────────────────────────────────────────────────────

    @PostMapping("/new")
    public String createPage(@RequestParam String title, RedirectAttributes ra) {
        PublicPage page = new PublicPage();
        page.setTitle(title.trim());
        page.setSlug(slugify(title));
        page.setPageType(PageType.PAGE);
        page.setPublished(false);
        pageRepository.save(page);
        return "redirect:/intra/pages/" + page.getId() + "/edit";
    }

    // ─── Editor ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    public String editor(@PathVariable Long id, Model model) {
        PublicPage page = pageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Page introuvable : " + id));
        // Force-load blocks to avoid lazy loading issues in template
        page.getBlocks().size();
        model.addAttribute("page", page);
        model.addAttribute("widgets", widgetRegistry.getAll());
        model.addAttribute("blockTypes", BlockType.values());
        return "intra/pages/editor";
    }

    // ─── Preview (renders draft without requiring published=true) ────────────

    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Long id, Model model) {
        PublicPage page = pageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Page introuvable : " + id));
        page.getBlocks().size();
        model.addAttribute("page", page);
        model.addAttribute("renderedBlocks", pageRenderService.renderBlocks(page.getBlocks()));
        model.addAttribute("previewMode", true);
        return "public/page";
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/save")
    @Transactional
    public String save(@PathVariable Long id,
                       @RequestParam(required = false) String title,
                       @RequestParam(required = false) String slug,
                       @RequestParam(required = false) String metaTitle,
                       @RequestParam(required = false) String metaDescription,
                       @RequestParam(required = false) String metaKeywords,
                       @RequestParam(required = false) String metaOgImage,
                       @RequestParam(required = false) String blocksJson,
                       @RequestParam(defaultValue = "false") boolean published,
                       RedirectAttributes ra) {
        PublicPage page = pageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Page introuvable : " + id));

        if (title != null && !title.isBlank())       page.setTitle(title.trim());
        if (slug != null && !slug.isBlank())         page.setSlug(slugify(slug));
        page.setMetaTitle(metaTitle);
        page.setMetaDescription(metaDescription);
        page.setMetaKeywords(metaKeywords);
        page.setMetaOgImage(metaOgImage);

        boolean wasPublished = page.isPublished();
        page.setPublished(published);
        if (published && !wasPublished) {
            page.setPublishedAt(LocalDateTime.now());
        }

        // Clear existing blocks, then rebuild from JSON
        page.getBlocks().clear();
        pageRepository.saveAndFlush(page);

        if (blocksJson != null && !blocksJson.isBlank() && !blocksJson.equals("[]")) {
            try {
                List<Map<String, Object>> blockDtos = objectMapper.readValue(
                        blocksJson, new TypeReference<List<Map<String, Object>>>() {});
                List<PageBlock> newBlocks = new ArrayList<>();
                for (int i = 0; i < blockDtos.size(); i++) {
                    Map<String, Object> dto = blockDtos.get(i);
                    String blockTypeName = String.valueOf(dto.getOrDefault("blockType", "HTML"));
                    Object contentObj = dto.getOrDefault("content", "{}");
                    String contentJson;
                    if (contentObj instanceof String s) {
                        contentJson = s;
                    } else {
                        contentJson = objectMapper.writeValueAsString(contentObj);
                    }
                    PageBlock block = new PageBlock();
                    block.setPage(page);
                    block.setBlockType(BlockType.valueOf(blockTypeName));
                    block.setContent(contentJson);
                    block.setSortOrder(i);
                    newBlocks.add(block);
                }
                page.getBlocks().addAll(newBlocks);
            } catch (Exception e) {
                log.error("Error parsing blocks JSON", e);
                ra.addFlashAttribute("errorMessage", "Erreur lors de la sauvegarde des blocs : " + e.getMessage());
                return "redirect:/intra/pages/" + id + "/edit";
            }
        }

        pageRepository.save(page);
        ra.addFlashAttribute("successMessage", "Page sauvegardée avec succès.");
        return "redirect:/intra/pages/" + id + "/edit";
    }

    // ─── Set as home page ────────────────────────────────────────────────────

    @PostMapping("/{id}/set-home")
    @Transactional
    public String setHome(@PathVariable Long id, RedirectAttributes ra) {
        pageRepository.clearHomePageFlags();
        pageRepository.findById(id).ifPresent(p -> {
            p.setHomePage(true);
            pageRepository.save(p);
        });
        ra.addFlashAttribute("successMessage", "Page d'accueil mise à jour.");
        return "redirect:/intra/pages";
    }

    // ─── Delete ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        pageRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Page supprimée.");
        return "redirect:/intra/pages";
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String slugify(String input) {
        if (input == null) return "";
        return input.trim()
                .toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9\\-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }
}
