package com.logicielpourtous.site.features.theme;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Fichier de theme, public (comme le reste de {@code /css/**}) et revalide par ETag. */
@RestController
public class ThemeCssController {

    private final ThemeService service;

    public ThemeCssController(ThemeService service) {
        this.service = service;
    }

    @GetMapping("/css/theme.css")
    public ResponseEntity<byte[]> theme(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String siAucune) {
        ThemeService.Rendu rendu = service.rendu();
        String etag = "\"" + rendu.version() + "\"";
        if (correspond(siAucune, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/css;charset=UTF-8")
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(rendu.css().getBytes(StandardCharsets.UTF_8));
    }

    /** Comparaison faible de {@code If-None-Match} (liste, {@code W/}, {@code *}). */
    private static boolean correspond(String enTete, String etag) {
        if (enTete == null) {
            return false;
        }
        for (String candidat : enTete.split(",")) {
            String c = candidat.trim();
            if (c.equals("*")) {
                return true;
            }
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
