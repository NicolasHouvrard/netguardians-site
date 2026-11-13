package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** Application complète SANS {@code app.plateforme.actif} : aucun bean ni aucune route de la plateforme. */
@SpringBootTest
@AutoConfigureMockMvc
class PlateformeDesactiveeTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private MockMvc mvc;

	@Test
	void aucun_bean_de_plateforme_quand_desactivee() throws Exception {
		assertThat(context.getBeansOfType(PluginManager.class)).isEmpty();
		assertThat(context.getBeansOfType(PluginAdminController.class)).isEmpty();
		assertThat(context.getBeansOfType(RoutagePlugins.class)).isEmpty();
		assertThat(context.getBeansOfType(FiltreEnVol.class)).isEmpty();
		assertThat(context.getBeansOfType(PlateformeProperties.class)).isEmpty();

		int sansJeton = mvc.perform(get("/api/plateforme/plugins")).andReturn().getResponse().getStatus();
		int admin = mvc.perform(get("/api/plateforme/plugins").with(user("admin").roles("ADMIN")))
			.andReturn()
			.getResponse()
			.getStatus();
		assertThat(sansJeton).isIn(401, 404);
		assertThat(admin).isIn(401, 404);
	}

}
