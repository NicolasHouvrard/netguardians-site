package com.logicielpourtous.site.features.api.angular;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/intra/media/images")
public class MediaApiController {

    @Value("${app.upload-dir}")
    private String uploadDir;

    private static final Set<String> ALLOWED = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "bmp"
    );

    @GetMapping
    public List<String> list() {
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

    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("files") MultipartFile[] files) throws IOException {
        Path dir = Paths.get(uploadDir, "images");
        Files.createDirectories(dir);
        List<String> urls = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String original = Objects.requireNonNullElse(file.getOriginalFilename(), "file");
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!ALLOWED.contains(ext)) continue;
            String safe = System.currentTimeMillis() + "_"
                    + original.replaceAll("[^a-zA-Z0-9._-]", "_");
            Files.copy(file.getInputStream(), dir.resolve(safe), StandardCopyOption.REPLACE_EXISTING);
            urls.add("/uploads/images/" + safe);
        }
        return ResponseEntity.ok(Map.of("urls", urls, "count", urls.size()));
    }

    @DeleteMapping("/{filename}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String filename) throws IOException {
        String safe = Paths.get(filename).getFileName().toString();
        Files.deleteIfExists(Paths.get(uploadDir, "images", safe));
        return ResponseEntity.noContent().build();
    }

    private boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ALLOWED.contains(name.substring(dot + 1).toLowerCase());
    }
}
