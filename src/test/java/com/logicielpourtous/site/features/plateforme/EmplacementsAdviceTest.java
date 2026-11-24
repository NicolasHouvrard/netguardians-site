package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** L'assistant de gabarits ne propage jamais une panne du service et ne journalise pas de trace d'appels. */
class EmplacementsAdviceTest {

	@Test
	void une_panne_du_service_donne_une_liste_vide_et_une_ligne_sans_trace() {
		EmplacementsService service = mock(EmplacementsService.class);
		when(service.elements(any(), any(), any())).thenThrow(new IllegalStateException("détail interne"));
		Logger journal = (Logger) LoggerFactory.getLogger(EmplacementsAdvice.UiSlots.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		journal.addAppender(appender);
		try {
			assertThat(new EmplacementsAdvice(service).uiSlots().elements("demo.fiche.onglets", "42")).isEmpty();
		}
		finally {
			journal.detachAppender(appender);
		}
		assertThat(appender.list).hasSize(1);
		ILoggingEvent ligne = appender.list.get(0);
		assertThat(ligne.getLevel()).isEqualTo(Level.WARN);
		assertThat(ligne.getThrowableProxy()).as("aucune trace d'appels").isNull();
		assertThat(ligne.getFormattedMessage()).contains("IllegalStateException").doesNotContain("détail interne");
	}

}
