package com.logicielpourtous.site.features.blog;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/intra/blog")
public class BlogController {

    private final BlogPostRepository blogPostRepository;

    public BlogController(BlogPostRepository blogPostRepository) {
        this.blogPostRepository = blogPostRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("posts",     blogPostRepository.findAllByOrderByCreatedAtDesc());
        model.addAttribute("drafts",    blogPostRepository.countByStatus(BlogPost.PostStatus.DRAFT));
        model.addAttribute("published", blogPostRepository.countByStatus(BlogPost.PostStatus.PUBLISHED));
        return "intra/blog/list";
    }

    @PostMapping
    public String create(@RequestParam String title,
                         @RequestParam(required = false) String excerpt,
                         @RequestParam(required = false) String content,
                         @RequestParam(required = false) String category,
                         RedirectAttributes ra) {
        BlogPost p = new BlogPost();
        p.setTitle(title);
        p.setExcerpt(excerpt);
        p.setContent(content);
        p.setCategory(category);
        p.setSlug(title.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        blogPostRepository.save(p);
        ra.addFlashAttribute("successMessage", "Article « " + title + " » créé.");
        return "redirect:/intra/blog";
    }

    @PostMapping("/{id}/publish")
    public String publish(@PathVariable Long id, RedirectAttributes ra) {
        blogPostRepository.findById(id).ifPresent(p -> {
            p.setStatus(BlogPost.PostStatus.PUBLISHED);
            p.setPublishedAt(LocalDateTime.now());
            blogPostRepository.save(p);
        });
        ra.addFlashAttribute("successMessage", "Article publié.");
        return "redirect:/intra/blog";
    }
}
