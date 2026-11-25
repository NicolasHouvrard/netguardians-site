package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** L'en-tete des pages connectees : « Mon Espace » mene a /accueil. */
class EnteteConnecteTest {

    @Test
    void lienMonEspace_pointeVersAccueil() throws Exception {
        String html = new String(new ClassPathResource("templates/header/connected.html").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.DOTALL).matcher(html);
        int trouves = 0;
        while (m.find()) {
            if (m.group(2).contains("Mon Espace")) {
                trouves++;
                assertThat(m.group(1)).contains("href=\"/accueil\"").doesNotContain("href=\"/espace\"");
            }
        }
        assertThat(trouves).isGreaterThanOrEqualTo(1);
    }
}
