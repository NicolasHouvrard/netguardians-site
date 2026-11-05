package com.logicielpourtous.agent;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point de reveil appele par le Maitre ({@code DeploiementScheduler}, cote appli principale) a
 * chaque nouvelle version detectee sur GitHub : {@code POST /reveil}, SANS corps, DELIBEREMENT
 * NON AUTHENTIFIE (contrainte globale du plan — le signal de reveil n'est qu'un "va sonder le
 * Maitre", sans effet sensible en lui-meme ; la seule action qui compte reellement, {@link
 * MaitreClient#recupererOrdre()} (appelee par {@link OrchestrateurDeploiement}), est authentifiee
 * par la cle API licence lue localement).
 *
 * <p>Repond {@code 202 Accepted} IMMEDIATEMENT, AVANT tout appel reseau : le traitement (delegue a
 * {@link OrchestrateurDeploiement#traiterReveil()}, Task 9 — recupere l'ordre auprès du Maitre puis
 * pilote Docker/Nginx) est declenche en tache asynchrone via l'{@link Executor} {@code
 * applicationTaskExecutor}
 * autoconfigure par Spring Boot, pour que {@code DeploiementScheduler} (qui applique des delais
 * reseau COURTS pour son reveil fire-and-forget, voir sa javadoc) ne soit jamais retarde par le
 * temps necessaire pour contacter le Maitre.
 *
 * <p><b>Pourquoi pas {@code @Async}</b> : {@code @Async} sur une methode de CETTE MEME classe,
 * appelee depuis une AUTRE methode de cette classe ({@code reveil() -> traiterReveil()}), est un
 * piege Spring AOP classique (auto-invocation) — l'appel passerait par {@code this} directement,
 * jamais par le proxy qui intercepte {@code @Async}, et l'execution resterait SYNCHRONE en
 * production malgre l'annotation (silencieusement, sans erreur). Injecter l'{@link Executor} et
 * lui soumettre explicitement le travail evite ce piege par construction, et reste trivialement
 * testable (un {@link Executor} synchrone, {@code Runnable::run}, dans les tests).
 *
 * <p><b>Dedoublonnage des reveils concurrents</b> (securite scan, fix round 1, finding 1) : sans
 * garde, chaque {@code POST /reveil} soumettrait inconditionnellement un nouveau travail a
 * l'{@link Executor} — des POST concurrents/rafale (volontaires ou accidentels, la route n'etant
 * PAS authentifiee par design, voir ci-dessus) empileraient alors un nombre non borne
 * d'executions simultanees de {@link #traiterReveil()}, chacune avec son propre appel reseau
 * sortant vers le Maitre : risque d'epuisement de ressources independant de toute authentification.
 * {@link #enCours} (un simple {@link AtomicBoolean}, pas une file d'attente) garantit qu'au plus
 * UNE execution de {@link #traiterReveil()} est active a un instant donne.
 *
 * <p><b>Relance coalescee, jamais un reveil silencieusement perdu</b> (securite scan, fix round 2,
 * finding 2) : un reveil qui arrive pendant qu'une execution est deja en cours repond quand meme
 * {@code 202} (l'appelant n'a pas besoin de savoir), mais ne doit PAS pour autant etre perdu pour
 * de bon — le controle de sante seul (Task 9, {@code OrchestrateurDeploiement.FENETRE}) dure
 * jusqu'a 30s, largement assez pour qu'une nouvelle version soit detectee cote Maitre pendant ce
 * temps. Avant ce fix, un tel reveil etait simplement logue puis oublie : si {@code
 * DeploiementScheduler} ne renvoyait jamais spontanement un second reveil pour la MEME version
 * (il ne reveille qu'au moment ou il detecte un CHANGEMENT), le noeud restait silencieusement sur
 * l'ancienne version jusqu'au tag GitHub suivant, sans aucune erreur ni retry visible.
 * {@link #relanceDemandee} (un second {@link AtomicBoolean}) resout cela SANS file d'attente non
 * bornee : un reveil qui arrive alors que {@link #enCours} est deja {@code true} positionne ce
 * flag au lieu d'etre simplement absorbe ; {@link #traiterReveilPuisLibererLeFlag()} boucle alors
 * sur {@link #traiterReveil()} jusqu'a ce qu'une execution se termine SANS qu'une nouvelle demande
 * ne soit arrivee pendant qu'elle tournait. N'importe quel nombre de reveils arrives pendant une
 * execution se coalescent ainsi en EXACTEMENT une execution supplementaire, jamais plus (pas
 * d'empilement) et jamais zero (pas de perte).
 *
 * <p><b>Liberation puis reverification ATOMIQUES (pas de fenetre TOCTOU)</b> (securite scan, fix
 * round 3, finding A) : la version precedente liberait {@link #enCours} dans un {@code finally}
 * APRES avoir verifie {@link #relanceDemandee} dans la condition de la boucle — entre cette
 * verification (qui lit {@code false}, decidant de sortir de la boucle) et la liberation reelle de
 * {@link #enCours} par le {@code finally}, un reveil concurrent pouvait arriver, echouer son CAS
 * (le flag etait encore {@code true}), et positionner {@link #relanceDemandee} a son tour — une
 * ecriture que PERSONNE ne consulterait plus jamais, puisque la boucle avait deja decide de
 * s'arreter. Ce reveil etait alors perdu pour de bon (meme consequence que le probleme resolu par
 * le fix round 2 ci-dessus, via une fenetre plus etroite). {@link #traiterReveilPuisLibererLeFlag()}
 * ferme cette fenetre en liberant {@link #enCours} puis en reverifiant {@link #relanceDemandee} ET
 * en retentant de reacquerir {@link #enCours} en UNE SEULE etape ({@code
 * relanceDemandee.get() && enCours.compareAndSet(false, true)}) : si une demande est en attente et
 * que personne n'a reacquis {@link #enCours} dans l'intervalle, CE thread la reacquiert lui-meme et
 * boucle a nouveau — aucune fenetre, aucune perte. Si la reacquisition echoue (un AUTRE {@code
 * reveil()} a reacquis {@link #enCours} en premier, dans l'intervalle), ce thread s'arrete sans
 * relancer lui-meme : l'execution independante que cet autre appel va demarrer interrogera de toute
 * facon le Maitre pour son etat COURANT, ce qui satisfait intrinsequement toute demande en
 * attente — rien n'est perdu non plus dans ce cas.
 */
@RestController
public class ReveilController {

    private static final Logger log = LoggerFactory.getLogger(ReveilController.class);

    private final OrchestrateurDeploiement orchestrateur;
    private final Executor executor;
    private final AtomicBoolean enCours = new AtomicBoolean(false);

    /** Securite scan, fix round 2, finding 2 : positionne quand un reveil arrive alors qu'une
     * execution est deja en cours, voir la javadoc de classe sur la relance coalescee. */
    private final AtomicBoolean relanceDemandee = new AtomicBoolean(false);

    public ReveilController(OrchestrateurDeploiement orchestrateur,
                            @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME) Executor executor) {
        this.orchestrateur = orchestrateur;
        this.executor = executor;
    }

    @PostMapping("/reveil")
    public ResponseEntity<Void> reveil() {
        if (enCours.compareAndSet(false, true)) {
            try {
                executor.execute(this::traiterReveilPuisLibererLeFlag);
            } catch (RuntimeException e) {
                // Securite scan, fix round 2 : si execute() LUI-MEME leve (ex.
                // RejectedExecutionException, executor sature/en arret d'arret — improbable avec
                // l'applicationTaskExecutor par defaut, pas impossible), la tache soumise n'a
                // JAMAIS tourne : traiterReveilPuisLibererLeFlag (et sa propre liberation de
                // enCours) ne s'execute donc jamais. Sans ce catch, le flag resterait bloque a
                // `true` pour toujours (tout /reveil suivant serait silencieusement absorbe, sans
                // aucun chemin de recuperation hors redemarrage) — on le libere nous-memes ici.
                enCours.set(false);
                log.warn("Agent : soumission du traitement du reveil a l'executor refusee, nouvel "
                        + "essai possible au prochain reveil : {}", e.toString());
            }
        } else {
            // Securite scan, fix round 2, finding 2 : plus un simple log — positionne le flag de
            // relance, voir la javadoc de classe. Peut ecraser un `true` deja present (plusieurs
            // reveils arrives pendant la meme execution) : c'est volontaire, un seul booleen suffit
            // puisqu'une seule relance supplementaire est garantie, jamais plus.
            relanceDemandee.set(true);
            log.debug("Agent : reveil recu pendant un traitement en cours, relance demandee pour "
                    + "apres (pas perdu)");
        }
        // Contrat inchange dans tous les cas (succes, relance demandee, ou echec de soumission) :
        // le signal de reveil repond TOUJOURS 202, voir la javadoc de classe.
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * Soumis a l'{@link Executor} par {@link #reveil()} — boucle sur {@link #traiterReveil()}
     * jusqu'a ce qu'une execution se termine SANS qu'une nouvelle demande ({@link
     * #relanceDemandee}) ne soit arrivee pendant qu'elle tournait (securite scan, fix round 2,
     * finding 2 — voir la javadoc de classe sur la relance coalescee). Liberation de {@link
     * #enCours} puis reverification de {@link #relanceDemandee} faites ENSEMBLE a chaque tour
     * (securite scan, fix round 3, finding A — voir la javadoc de classe sur la fenetre TOCTOU
     * fermee par ce fix), jamais l'une dans un {@code finally} separe de l'autre : pas besoin de
     * {@code try/finally} ici, {@link #traiterReveil()} n'echoue jamais vers l'exterieur (voir son
     * propre {@code try/catch}).
     */
    private void traiterReveilPuisLibererLeFlag() {
        boolean continuer;
        do {
            // Efface la demande AVANT d'executer : si un nouveau reveil arrive PENDANT ce
            // traiterReveil(), il repositionnera le flag.
            relanceDemandee.set(false);
            traiterReveil();
            // Libere IMMEDIATEMENT, puis reverifie la demande ET retente de reacquerir en UNE
            // SEULE etape — aucune fenetre entre les deux (voir la javadoc de classe, fix round 3,
            // finding A). Si cette reacquisition echoue (un autre reveil() a reacquis enCours en
            // premier), cet autre appel demarrera sa propre execution independante, qui satisfait
            // de toute facon la demande en attente (elle interroge le Maitre pour son etat
            // courant) — rien n'est perdu dans ce cas non plus.
            enCours.set(false);
            continuer = relanceDemandee.get() && enCours.compareAndSet(false, true);
        } while (continuer);
    }

    /**
     * Soumis a l'{@link Executor} par {@link #traiterReveilPuisLibererLeFlag()} — jamais appele
     * directement en dehors des tests (voir leur javadoc de classe sur le piege {@code @Async}
     * evite).
     */
    void traiterReveil() {
        try {
            orchestrateur.traiterReveil();
        } catch (RuntimeException e) {
            // Le Maitre a deja recu son 202 Accepted : un echec ici ne doit jamais remonter plus
            // loin qu'un log — nouvel essai naturel au prochain reveil ou sondage.
            log.warn("Agent : traitement du reveil interrompu, nouvel essai au prochain reveil : {}",
                    e.toString());
        }
    }
}
