package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** /css/theme.css sur la chaine complete (securite, intercepteurs, gestionnaire de statiques). */
@SpringBootTest
@AutoConfigureMockMvc
class ThemeCssControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ThemeService service;
    @Autowired private JdbcTemplate jdbc;

    private Map<String, String> sauvegarde;

    @BeforeEach
    void sauver() {
        sauvegarde = new HashMap<>();
        jdbc.query("SELECT cle, valeur FROM theme_reglages", rs -> { sauvegarde.put(rs.getString(1), rs.getString(2)); });
        jdbc.update("DELETE FROM theme_reglages");
    }

    @AfterEach
    void restaurer() {
        jdbc.update("DELETE FROM theme_journal WHERE modifie_par = 910004");
        jdbc.update("DELETE FROM theme_reglages");
        sauvegarde.forEach((c, v) -> jdbc.update("INSERT INTO theme_reglages (cle, valeur) VALUES (?, ?)", c, v));
    }

    @Test
    void anonyme_recoit200AvecLesEntetes() throws Exception {
        MvcResult r = mockMvc.perform(get("/css/theme.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/css;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        String etag = r.getResponse().getHeader("ETag");
        assertThat(etag).startsWith("\"").endsWith("\"").contains(service.versionCss());
        assertThat(r.getResponse().getContentAsString()).startsWith(":root{").contains("--socle-couleur-primaire:");
    }

    @Test
    void ifNoneMatch_repond304() throws Exception {
        String etag = mockMvc.perform(get("/css/theme.css")).andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(get("/css/theme.css").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
        mockMvc.perform(get("/css/theme.css").header("If-None-Match", "W/" + etag))
                .andExpect(status().isNotModified());
        mockMvc.perform(get("/css/theme.css").header("If-None-Match", "\"autre\""))
                .andExpect(status().isOk());
    }

    @Test
    void etagDifferentApresDefinir() throws Exception {
        String avant = mockMvc.perform(get("/css/theme.css")).andReturn().getResponse().getHeader("ETag");
        service.definir(910004L, "couleur-fond", "#010203");
        MvcResult apres = mockMvc.perform(get("/css/theme.css").header("If-None-Match", avant))
                .andExpect(status().isOk()).andReturn();
        assertThat(apres.getResponse().getHeader("ETag")).isNotEqualTo(avant);
        assertThat(apres.getResponse().getContentAsString()).contains("--socle-couleur-fond:#010203;");
    }
}
