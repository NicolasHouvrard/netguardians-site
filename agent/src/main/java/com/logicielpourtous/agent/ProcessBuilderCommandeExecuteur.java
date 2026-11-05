package com.logicielpourtous.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Implementation reelle de {@link CommandeExecuteur} via {@link ProcessBuilder}. L'agent tourne
 * directement sur l'hote : il appelle la CLI installee sur l'hote, jamais le socket Docker.
 *
 * <p>Delai maximal unique pour toutes les commandes : un processus bloque (pull Docker suspendu,
 * demon fige) ne doit pas figer indefiniment le {@code OrchestrateurDeploiement} sous le garde
 * single-flight de {@code ReveilController}.
 */
@Component
public class ProcessBuilderCommandeExecuteur implements CommandeExecuteur {

    static final Duration DELAI_MAX = Duration.ofMinutes(5);

    private final Duration delaiMax;

    public ProcessBuilderCommandeExecuteur() {
        this(DELAI_MAX);
    }

    ProcessBuilderCommandeExecuteur(Duration delaiMax) {
        this.delaiMax = delaiMax;
    }

    @Override
    public ResultatCommande executer(List<String> commande) {
        Process processus = demarrer(commande);
        // Lecture concurrente stdout/stderr : evite le blocage si un flux remplit son tampon, et
        // laisse le delai maximal s'appliquer meme si le processus ne ferme jamais stdout.
        CompletableFuture<String> sortie = CompletableFuture.supplyAsync(
                () -> lire(processus.getInputStream()));
        CompletableFuture<String> erreur = CompletableFuture.supplyAsync(
                () -> lire(processus.getErrorStream()));
        try {
            if (!processus.waitFor(delaiMax.toMillis(), TimeUnit.MILLISECONDS)) {
                processus.destroyForcibly();
                throw new IllegalStateException("Delai depasse (" + delaiMax + ") pour " + commande);
            }
            return new ResultatCommande(processus.exitValue(), sortie.join(), erreur.join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            processus.destroyForcibly();
            throw new IllegalStateException("Interruption pendant " + commande, e);
        }
    }

    /** Lance le processus ; point de substitution pour les tests. */
    Process demarrer(List<String> commande) {
        try {
            return new ProcessBuilder(commande).start();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de lancer la commande " + commande, e);
        }
    }

    private static String lire(InputStream flux) {
        try (flux) {
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture de la sortie impossible", e);
        }
    }
}
