package com.logicielpourtous.site.features.clients;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClientsConfiguration {

    /** Horloge de l'application (injectee pour permettre de figer l'annee civile dans les tests). */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock horloge() {
        return Clock.systemDefaultZone();
    }
}
