package com.logicielpourtous.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entree de l'Agent de deploiement bleu-vert (Task 5+ du plan {@code
 * 2026-11-02-agent-deploiement-bleu-vert}) : processus Spring Boot INDEPENDANT (projet Maven
 * separe, voir {@code agent/pom.xml}) destine a tourner sur CHAQUE hote (y compris le Maitre),
 * pour piloter Docker/Nginx lors d'un changement de version (Task 6+, hors perimetre de cette
 * tache — ici seulement le scaffold, le point de reveil {@link ReveilController} et le client
 * {@link MaitreClient}).
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
