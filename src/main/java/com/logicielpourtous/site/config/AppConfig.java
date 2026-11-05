package com.logicielpourtous.site.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    @Bean(name = "syncExecutor")
    public Executor syncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("supplier-sync-");
        executor.initialize();
        return executor;
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("sync-scheduler-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Pas de {@code RestClient.Builder} auto-configure par Spring Boot dans ce projet (verifie en
     * Task 4 : tout {@code @Component} non conditionnel qui en demande un en constructeur echoue au
     * demarrage de CHAQUE contexte {@code @SpringBootTest}, {@code UnsatisfiedDependencyException})
     * — latent jusqu'ici car la seule classe existante qui en injecte un EN CONSTRUCTEUR ({@code
     * LicenceBootstrap} ; {@code LicenceClient} construit lui-meme son {@code RestClient} via {@code
     * RestClient.builder()} statique, sans injection) est {@code @ConditionalOnProperty} sur une
     * propriete jamais active dans un MEME contexte de test (seulement via un processus separe, voir
     * {@code EndToEndLicenceTest}). Beneficie aussi a {@code GitHubReleaseClient}/{@code
     * DeploiementScheduler} (Task 4, inconditionnels), qui l'exposaient en premier.
     *
     * <p>{@code prototype} (pas {@code singleton}, le defaut) : {@code RestClient.Builder} est
     * MUTABLE (ex. {@code .baseUrl(...)} modifie l'instance ET la renvoie), exactement comme le
     * bean equivalent de {@code RestClientAutoConfiguration} de Spring Boot — un singleton partage
     * entre PLUSIEURS consommateurs (ici au moins {@code GitHubReleaseClient} et {@code
     * DeploiementScheduler}, potentiellement {@code LicenceBootstrap} a l'avenir) se corromprait
     * mutuellement au gre de l'ordre de creation des beans.
     */
    @Bean
    @ConditionalOnMissingBean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
