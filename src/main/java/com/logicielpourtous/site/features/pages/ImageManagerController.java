package com.logicielpourtous.site.features.pages;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
@RequestMapping("/intra/images")
public class ImageManagerController {

    @Value("${app.upload-dir}")
    private String uploadDir;

    private static final Set<String> ALLOWED = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "bmp"
    );

    @GetMapping
    public String list(Model model) {
        model.addAttribute("images", getImageUrls());
        return "intra/images/list";
    }

    @GetMapping("/picker")
    public String picker(Model model) {
        model.addAttribute("images", getImageUrls());
        return "intra/images/picker";
    }

    @GetMapping("/list")
    @ResponseBody
    public List<String> listJson() {
        return getImageUrls();
    }

    @PostMapping("/upload-ajax")
    @ResponseBody
    public Map<String, Object> uploadAjax(@RequestParam("files") MultipartFile[] files) throws IOException {
        Path dir = Paths.get(uploadDir, "images");
        Files.createDirectories(dir);
        int count = 0;
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String original = Objects.requireNonNullElse(file.getOriginalFilename(), "file");
            String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!ALLOWED.contains(ext)) continue;
            String safe = System.currentTimeMillis() + "_" + original.replaceAll("[^a-zA-Z0-9._-]", "_");
            Files.copy(file.getInputStream(), dir.resolve(safe), StandardCopyOption.REPLACE_EXISTING);
            count++;
        }
        return Map.of("ok", true, "count", count);
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("files") MultipartFile[] files,
                         @RequestParam(defaultValue = "") String returnTo,
                         RedirectAttributes ra) throws IOException {
        Path dir = Paths.get(uploadDir, "images");
        Files.createDirectories(dir);
        int count = 0;
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String original = Objects.requireNonNullElse(file.getOriginalFilename(), "file");
            String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!ALLOWED.contains(ext)) continue;
            String safe = System.currentTimeMillis() + "_" + original.replaceAll("[^a-zA-Z0-9._-]", "_");
            Files.copy(file.getInputStream(), dir.resolve(safe), StandardCopyOption.REPLACE_EXISTING);
            count++;
        }
        ra.addFlashAttribute("successMessage", count + " image(s) uploadée(s).");
        return "redirect:" + ("picker".equals(returnTo) ? "/intra/images/picker" : "/intra/images");
    }

    @PostMapping("/delete")
    public String delete(@RequestParam("name") String name, RedirectAttributes ra) throws IOException {
        String safeName = Paths.get(name).getFileName().toString();
        Files.deleteIfExists(Paths.get(uploadDir, "images", safeName));
        ra.addFlashAttribute("successMessage", "Image supprimée.");
        return "redirect:/intra/images";
    }

    private List<String> getImageUrls() {
        Path dir = Paths.get(uploadDir, "images");
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> isImage(p.getFileName().toString()))
                    .sorted(Comparator.comparingLong(p -> -p.toFile().lastModified()))
                    .map(p -> "/uploads/images/" + p.getFileName())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ALLOWED.contains(name.substring(dot + 1).toLowerCase());
    }
}
