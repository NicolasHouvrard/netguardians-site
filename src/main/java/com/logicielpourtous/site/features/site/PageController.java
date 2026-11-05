package com.logicielpourtous.site.features.site;

import com.logicielpourtous.site.features.pages.PageRenderService;
import com.logicielpourtous.site.features.pages.PublicPage;
import com.logicielpourtous.site.features.pages.PublicPageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Controller
public class PageController {

    private final PageRepository pageRepository;
    private final EditorJsRenderer editorJsRenderer;
    private final PublicPageRepository publicPageRepository;
    private final PageRenderService pageRenderService;

    public PageController(PageRepository pageRepository,
                          EditorJsRenderer editorJsRenderer,
                          PublicPageRepository publicPageRepository,
                          PageRenderService pageRenderService) {
        this.pageRepository = pageRepository;
        this.editorJsRenderer = editorJsRenderer;
        this.publicPageRepository = publicPageRepository;
        this.pageRenderService = pageRenderService;
    }

    @GetMapping("/")
    public String home(Model model) {
        Optional<PublicPage> homeOpt = publicPageRepository.findFirstByHomePageTrue();
        if (homeOpt.isPresent() && homeOpt.get().isPublished()) {
            PublicPage page = homeOpt.get();
            page.getBlocks().size();
            model.addAttribute("page", page);
            model.addAttribute("renderedBlocks", pageRenderService.renderBlocks(page.getBlocks()));
            return "public/page";
        }
        // Fallback: old system
        return pageRepository.findFirstByIsHomePageTrue().stream().findFirst()
                .map(page -> {
                    model.addAttribute("page", page);
                    model.addAttribute("renderedContent", editorJsRenderer.render(page.getContent()));
                    return "feature/site/display-page";
                })
                .orElse("public/no-home");
    }
}
    

