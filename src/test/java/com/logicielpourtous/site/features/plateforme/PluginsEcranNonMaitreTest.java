package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Serveur annexe (non Maître) : l'écran des plugins n'existe pas, même pour un admin. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "app.plateforme.actif=true", "app.role=PARTNER" })
class PluginsEcranNonMaitreTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApplicationContext contexte;

	@Test
	void aucune_route_ni_bean_sur_un_serveur_non_maitre() throws Exception {
		assertThat(contexte.getBeansOfType(PluginsEcranController.class)).isEmpty();
		int get = mvc.perform(get("/admin/plugins").with(user("a").roles("ADMIN"))).andReturn().getResponse()
			.getStatus();
		int post = mvc.perform(post("/admin/plugins/alpha/desinstaller").with(user("a").roles("ADMIN")).with(csrf()))
			.andReturn().getResponse().getStatus();
		assertThat(get).isEqualTo(404);
		assertThat(post).isIn(404, 405);
	}

}
