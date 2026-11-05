package com.logicielpourtous.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifie que {@link ReveilController#reveil()} repond {@code 202 Accepted} IMMEDIATEMENT (sans
 * attendre {@link OrchestrateurDeploiement#traiterReveil()}) et que le traitement est bien soumis
 * a l'{@link Executor} injecte plutot qu'execute en ligne — voir la javadoc de classe de {@link
 * ReveilController} sur le piege {@code @Async} par auto-invocation que cette construction evite.
 *
 * <p>Pas de {@code MockMvc}/contexte Spring ici : {@link ReveilController} est instancie
 * directement avec un {@link OrchestrateurDeploiement} mocke (Task 9 — la delegation reelle vers
 * {@link MaitreClient} etc. est testee dans {@code OrchestrateurDeploiementTest}, pas ici) et un
 * {@link Executor} de test — suffisant pour verifier le comportement de cette seule classe, meme
 * discipline de legerete que {@link MaitreClientTest} (aucune dependance de test ajoutee au-dela
 * de ce que {@code spring-boot-starter-test} fournit deja).
 */
class ReveilControllerTest {

    @Test
    void reveil_repond202SansAttendreLeTraitement() {
        OrchestrateurDeploiement orchestrateur = mock(OrchestrateurDeploiement.class);
        // Executor qui ne fait RIEN (simule un travail encore en cours au moment de la reponse) :
        // prouve que reveil() ne bloque pas sur l'execution de traiterReveil().
        Executor executorQuiNeFaitRien = runnable -> { /* jamais execute dans ce test */ };
        ReveilController controller = new ReveilController(orchestrateur, executorQuiNeFaitRien);

        ResponseEntity<Void> reponse = controller.reveil();

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(orchestrateur, never()).traiterReveil();
    }

    @Test
    void reveil_soumetLeTraitementALExecutor() {
        OrchestrateurDeploiement orchestrateur = mock(OrchestrateurDeploiement.class);
        // Executor synchrone (execute immediatement, meme thread) : suffisant pour verifier QUE le
        // travail soumis delegue bien a OrchestrateurDeploiement.traiterReveil().
        Executor executorSynchrone = Runnable::run;
        ReveilController controller = new ReveilController(orchestrateur, executorSynchrone);

        controller.reveil();

        verify(orchestrateur).traiterReveil();
    }

    @Test
    void reveil_uneExceptionDuTraitementNeRemonteJamais() {
        OrchestrateurDeploiement orchestrateur = mock(OrchestrateurDeploiement.class);
        doThrow(new RuntimeException("maitre injoignable")).when(orchestrateur).traiterReveil();
        Executor executorSynchrone = Runnable::run;
        ReveilController controller = new ReveilController(orchestrateur, executorSynchrone);

        // Ne doit pas lever : le Maitre a deja recu son 202, un echec du traitement ne doit avoir
        // d'autre effet qu'un log (voir ReveilController#traiterReveil).
        ResponseEntity<Void> reponse = controller.reveil();

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(orchestrateur, times(1)).traiterReveil();
    }

    // Securite scan, fix round 1 (finding 1) : des POST /reveil concurrents/rafale ne doivent
    // jamais faire tourner plusieurs traitements EN MEME TEMPS (chacun contactant le Maitre) —
    // chaque appel doit TOUJOURS repondre 202 (l'appelant n'a pas besoin de savoir).
    //
    // Deterministe via 2 CountDownLatch (pas de sleep/timing reel) : le thread du premier
    // traitement signale qu'il est bien ENTRE dans traiterReveil() avant que le test n'envoie le
    // second POST, puis le test le libere explicitement — garantit un chevauchement reel, pas une
    // coincidence de timing.
    @Test
    void reveil_secondCallWhileFirstStillRunning_doesNotRunConcurrently() throws InterruptedException {
        OrchestrateurDeploiement orchestrateur = mock(OrchestrateurDeploiement.class);
        CountDownLatch premierAppelEntreDansTraiterReveil = new CountDownLatch(1);
        CountDownLatch libererPremierAppel = new CountDownLatch(1);
        AtomicInteger nombreAppels = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (nombreAppels.incrementAndGet() == 1) {
                premierAppelEntreDansTraiterReveil.countDown();
                assertThat(libererPremierAppel.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(orchestrateur).traiterReveil();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ReveilController controller = new ReveilController(orchestrateur, executor);

            ResponseEntity<Void> premiereReponse = controller.reveil();
            assertThat(premierAppelEntreDansTraiterReveil.await(5, TimeUnit.SECONDS)).isTrue();

            // Le premier traitement est bel et bien EN COURS (bloque dans traiterReveil()) a cet
            // instant precis : un second reveil arrivant maintenant ne doit PAS declencher une 2e
            // execution CONCURRENTE (toujours une seule invocation EN COURS a cet instant).
            ResponseEntity<Void> secondeReponse = controller.reveil();
            assertThat(nombreAppels.get()).isEqualTo(1);

            libererPremierAppel.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(premiereReponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(secondeReponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        } finally {
            executor.shutdownNow();
        }
    }

    // Securite scan, fix round 2 (finding 2) : un reveil qui arrive PENDANT qu'un traitement tourne
    // ne doit plus etre silencieusement perdu — il doit declencher EXACTEMENT une execution
    // supplementaire une fois le traitement courant termine (coalescence), jamais zero (perte) et
    // jamais plus d'une (pas d'empilement). Reprend le meme patron deterministe par CountDownLatch
    // que le test precedent ; la 2e invocation du mock (la relance coalescee) retourne
    // immediatement, ce qui permet au test de se terminer sans latch supplementaire.
    //
    // Sert aussi de regression pour la securite scan, fix round 3 (finding A, fenetre TOCTOU) :
    // exerce exactement le chemin "cas NON racy" de traiterReveilPuisLibererLeFlag() — enCours est
    // libere puis relanceDemandee.get() && enCours.compareAndSet(false, true) reussit (personne
    // d'autre ne dispute enCours a cet instant) — ce qui redeclenche bien la boucle. Le second
    // sous-cas ("un autre reveil() gagne la reacquisition en premier") n'est PAS force ici de
    // facon deterministe : il faudrait suspendre le thread EXACTEMENT entre enCours.set(false) et
    // le compareAndSet() qui le suit immediatement dans le code reel, sans aucun point de
    // synchronisation existant pour s'y accrocher — l'ajouter demanderait d'instrumenter
    // ReveilController avec un seam reserve aux tests pour une fenetre de quelques instructions,
    // jugee disproportionnee ici (voir le rapport de ce fix round pour le raisonnement complet sur
    // ce sous-cas, qui reste correct par construction : si la reacquisition echoue, c'est qu'un
    // AUTRE appel independant va de toute facon interroger le Maitre pour son etat courant).
    @Test
    void reveil_secondCallWhileFirstStillRunning_coalescesIntoOneExtraExecutionAfterwards()
            throws InterruptedException {
        OrchestrateurDeploiement orchestrateur = mock(OrchestrateurDeploiement.class);
        CountDownLatch premierAppelEntreDansTraiterReveil = new CountDownLatch(1);
        CountDownLatch libererPremierAppel = new CountDownLatch(1);
        AtomicInteger nombreAppels = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (nombreAppels.incrementAndGet() == 1) {
                premierAppelEntreDansTraiterReveil.countDown();
                assertThat(libererPremierAppel.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(orchestrateur).traiterReveil();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ReveilController controller = new ReveilController(orchestrateur, executor);

            controller.reveil();
            assertThat(premierAppelEntreDansTraiterReveil.await(5, TimeUnit.SECONDS)).isTrue();

            // Reveil arrivant PENDANT que le premier traiterReveil() est toujours bloque : ne doit
            // pas etre perdu.
            controller.reveil();

            libererPremierAppel.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            // Coalescee en EXACTEMENT une execution supplementaire APRES la fin de la premiere
            // (jamais deux executions concurrentes, verifie par le test precedent ; jamais perdue,
            // verifie ici).
            verify(orchestrateur, times(2)).traiterReveil();
        } finally {
            executor.shutdownNow();
        }
    }

    // Non-regression : une fois le premier traitement TERMINE (flag "en cours" libere), un nouveau
    // reveil doit de nouveau declencher une execution reelle — le dedup ne doit pas bloquer
    // definitivement l'agent apres un premier cycle.
    @Test
    void reveil_afterPreviousRunCompletes_newCallTriggersANewExecution() {
        OrchestrateurDeploiement orchestrateur = mock(OrchestrateurDeploiement.class);
        Executor executorSynchrone = Runnable::run;
        ReveilController controller = new ReveilController(orchestrateur, executorSynchrone);

        controller.reveil();
        controller.reveil();

        verify(orchestrateur, times(2)).traiterReveil();
    }

    // Securite scan, fix round 2 : si executor.execute() leve LUI-MEME (ex. RejectedExecutionException
    // si l'executor est en cours d'arret ou sa file saturee — improbable avec l'applicationTaskExecutor
    // par defaut, mais pas impossible), la tache soumise n'a JAMAIS ete executee : son
    // traiterReveilPuisLibererLeFlag (et donc son finally qui libere enCours) ne tourne jamais. Sans
    // garde specifique, 2 choses casseraient a la fois : (1) l'exception remonterait hors de reveil()
    // AVANT le retour 202 (la garantie "toujours 202" serait violee), et (2) enCours resterait a true
    // POUR TOUJOURS — tout POST /reveil suivant serait silencieusement absorbe sans aucun chemin de
    // recuperation hors redemarrage du processus. Verifie les 2 a la fois : reponse 202 malgre
    // l'exception, ET le flag bien libere (prouve par un reveil ulterieur qui redeclenche normalement).
    @Test
    void reveil_executorRejectsSubmission_stillRespond202AndReleasesTheFlag() {
        OrchestrateurDeploiement orchestrateur = mock(OrchestrateurDeploiement.class);
        // Rejette UNIQUEMENT la premiere soumission, fonctionne normalement (synchrone) ensuite —
        // permet de prouver, sur la MEME instance de ReveilController (donc le MEME enCours), que
        // le flag a bien ete libere malgre le rejet : si ce n'etait pas le cas, le second
        // reveil() ci-dessous serait absorbe a tort et traiterReveil() ne serait jamais appele.
        AtomicBoolean rejeterLaProchaineSoumission = new AtomicBoolean(true);
        Executor executorInstable = runnable -> {
            if (rejeterLaProchaineSoumission.compareAndSet(true, false)) {
                throw new RejectedExecutionException("executor sature/en arret");
            }
            runnable.run();
        };
        ReveilController controller = new ReveilController(orchestrateur, executorInstable);

        ResponseEntity<Void> premiereReponse = controller.reveil();

        assertThat(premiereReponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(orchestrateur, never()).traiterReveil(); // le rejet a bien empeche toute execution

        ResponseEntity<Void> secondeReponse = controller.reveil();

        assertThat(secondeReponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // Le flag a bien ete libere par le 1er appel (rejete) : ce 2e appel redeclenche une
        // execution reelle, pas absorbe a tort par un enCours resterait bloque a true.
        verify(orchestrateur, times(1)).traiterReveil();
    }
}
