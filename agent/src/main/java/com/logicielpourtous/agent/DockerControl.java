package com.logicielpourtous.agent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pilotage Docker de l'Agent (Task 6) via la CLI {@code docker} de l'hote. Pas de socket Docker
 * monte dans un conteneur : l'Agent tourne directement sur l'hote, decision de securite du design.
 *
 * <p>Chaque couleur a un nom de conteneur deterministe, derive de son port ({@code site-<port>}) :
 * l'etat ne depend plus d'une memoire locale, donc un agent redemarre, un echec precedent ou un
 * conteneur lance hors de l'Agent (compose) ne peuvent plus bloquer les deploiements suivants.
 */
@Component
public class DockerControl {

    private static final String DOCKER = "docker";
    private static final int PORT_CONTENEUR = 8080;

    private final CommandeExecuteur executeur;
    /** Options supplementaires pour {@code docker run} (ex. --env-file, --network, -v). */
    private final List<String> optionsRun;
    /**
     * Mode "reseau hote" (paquet Synology) : le conteneur partage le reseau du NAS pour joindre
     * Postgres sur 127.0.0.1. {@code -p} n'a alors aucun effet : l'ecoute est fixee par
     * {@code SERVER_PORT}/{@code SERVER_ADDRESS}, comme pour la premiere instance du paquet.
     */
    private final boolean reseauHote;

    public DockerControl(CommandeExecuteur executeur) {
        this(executeur, "", false);
    }

    public DockerControl(CommandeExecuteur executeur, String optionsRun) {
        this(executeur, optionsRun, false);
    }

    @Autowired
    public DockerControl(CommandeExecuteur executeur,
            @Value("${app.agent.docker.run-options:}") String optionsRun,
            @Value("${app.agent.docker.reseau-hote:false}") boolean reseauHote) {
        this.reseauHote = reseauHote;
        this.executeur = executeur;
        this.optionsRun = optionsRun.isBlank()
                ? List.of()
                : List.of(optionsRun.trim().split("\\s+"));
    }

    /**
     * Demarre une version en arriere-plan, en remplacant TOUJOURS ce qui occupe deja le port : on
     * supprime d'abord tout conteneur lie au port (quel que soit son nom, p. ex. lance par compose),
     * puis le conteneur portant le nom {@code site-<port>}. Best-effort. Publie le port UNIQUEMENT
     * sur la boucle locale : le trafic externe ne doit atteindre l'appli que via Nginx.
     *
     * @return l'id du conteneur demarre
     * @throws IllegalStateException si {@code docker run} echoue
     */
    public String demarrerVersion(String image, int port) {
        libererPort(port);
        String nom = nomConteneur(port);
        supprimer(nom);

        List<String> commande = new ArrayList<>(List.of(DOCKER, "run", "--name", nom,
                "--restart", "unless-stopped", "-d"));
        if (reseauHote) {
            // Jamais 0.0.0.0 : seul le Nginx local de bascule doit atteindre l'application.
            commande.addAll(List.of("--network", "host",
                    "-e", "SERVER_PORT=" + port, "-e", "SERVER_ADDRESS=127.0.0.1"));
        } else {
            commande.addAll(List.of("-p", "127.0.0.1:" + port + ":" + PORT_CONTENEUR));
        }
        commande.addAll(optionsRun);
        commande.add(image);

        ResultatCommande resultat = executeur.executer(commande);
        verifier(resultat, "docker run " + image);
        return resultat.sortieStandard().trim();
    }

    /**
     * Arrete un conteneur ({@code docker stop <id>}). Accepte aussi bien un id qu'un nom.
     *
     * @throws IllegalStateException si {@code docker stop} echoue
     */
    public void arreterConteneur(String idConteneur) {
        ResultatCommande resultat = executeur.executer(List.of(DOCKER, "stop", idConteneur));
        verifier(resultat, "docker stop " + idConteneur);
    }

    static String nomConteneur(int port) {
        return "site-" + port;
    }

    /**
     * Supprime tout conteneur (id ou nom) actuellement lie au port hote. Un port libre donne une
     * sortie vide, ce qui n'est pas une erreur.
     */
    private void libererPort(int port) {
        for (String id : conteneursLiesAuPort(port)) {
            supprimer(id);
        }
    }

    private List<String> conteneursLiesAuPort(int port) {
        try {
            ResultatCommande resultat = executeur.executer(
                    List.of(DOCKER, "ps", "--filter", "publish=" + port, "-q"));
            if (resultat.codeSortie() != 0) {
                return List.of();
            }
            return resultat.sortieStandard().lines()
                    .map(String::trim)
                    .filter(ligne -> !ligne.isEmpty())
                    .toList();
        } catch (IllegalStateException ignoree) {
            return List.of();
        }
    }

    /** Best-effort : un echec (ex. "No such container") ne doit jamais faire echouer le deploiement. */
    private void supprimer(String reference) {
        try {
            executeur.executer(List.of(DOCKER, "rm", "-f", reference));
        } catch (IllegalStateException ignoree) {
            // Le docker run qui suit remontera l'erreur reelle si Docker est inutilisable.
        }
    }

    private static void verifier(ResultatCommande resultat, String description) {
        if (resultat.codeSortie() != 0) {
            throw new IllegalStateException(description + " a echoue (code "
                    + resultat.codeSortie() + ") : " + resultat.sortieErreur().trim());
        }
    }
}
