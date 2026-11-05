# Agent de déploiement bleu-vert (Maître + Agent) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Donner à chaque serveur (Maître compris) la capacité de recevoir l'ordre d'une nouvelle version, de la déployer en bleu-vert sans interruption via un petit agent séparé, avec un contrôle de santé qui annule le basculement en cas d'échec.

**Architecture:** Le Maître détecte les nouvelles versions GitHub et envoie un signal de réveil (sans contenu) à l'agent de chaque serveur enrôlé ; l'agent rappelle le Maître par le canal sortant déjà authentifié du module licence pour récupérer l'ordre réel, puis pilote Docker (pull/start/health-check/stop) et Nginx localement. L'agent est un projet Maven séparé de l'application métier, qui tourne directement sur l'hôte (pas dans un conteneur basculé) pour piloter Docker via sa CLI sans avoir besoin d'un accès au socket Docker depuis un conteneur. Ce plan couvre la partie Java testable (Flyway, points d'API Maître, projet Agent) ; le pipeline CI/CD, les `compose.yaml`, Headscale et le relai public Nginx/SSL sur le VPS font l'objet d'un second plan séparé (artefacts d'infrastructure, pas de cycle TDD).

**Tech Stack:** Spring Boot 4.0.2, Java 25, Maven, PostgreSQL, Flyway, module `licence` existant (réutilisé pour l'authentification agent↔maître).

**Spec:** [docs/superpowers/specs/2026-11-02-deploiement-bleu-vert-docker-design.md](../specs/2026-11-02-deploiement-bleu-vert-docker-design.md)

## Global Constraints

- Java 25 / Spring Boot 4.0.2 (pom.xml existant, aucune modification de version).
- Une seule base PostgreSQL par serveur, partagée entre les deux couleurs — jamais touchée par le basculement applicatif lui-même.
- Flyway remplace `spring.jpa.hibernate.ddl-auto=update` pour ce chantier ; toute migration écrite doit rester compatible avec l'ancienne version de l'appli pendant la fenêtre de bascule (étendre avant de couper).
- L'agent est un programme séparé de l'application métier (projet Maven distinct, cycle de vie et de version indépendant) — jamais réécrit à chaque version de l'appli.
- L'agent s'authentifie auprès du Maître avec la **même clé API licence** que l'application principale du même serveur — aucun second mécanisme d'authentification.
- Le signal de réveil Maître → agent ne porte aucun contenu et n'est pas authentifié ; l'ordre réel ne circule que via le canal sortant agent → Maître déjà authentifié (en-tête `X-Licence-Api-Key`, classe `LicenceApiKeyAuthFilter`).
- Convention d'image Docker : `ghcr.io/<owner>/site:<tag>`.
- Ports bleu/vert = `8080` (actif par défaut) et `8081` (inactif), conformément au design.
- Fenêtre de contrôle de santé = 30 secondes avant d'abandonner (constante dans `OrchestrateurDeploiement`, Task 9) ; au-delà, l'agent annule et ne touche jamais Nginx.

## Review Focus

- Le Maître appelle plusieurs agents à la suite pour un même déploiement : l'échec de réveil d'un serveur (éteint, injoignable) ne doit jamais empêcher les autres d'être réveillés — chaque appel est isolé dans son propre `try/catch`, même patron que `LicenceDecouverteScheduler#decouvrir`.
- Le contrôle de santé n'est jamais atteint (la nouvelle version crashe ou boucle au démarrage) : l'agent doit arrêter la nouvelle instance et ne JAMAIS basculer Nginx — l'ancienne version doit continuer de répondre.
- Une migration Flyway échoue au démarrage : l'application ne doit pas démarrer dans un état à moitié migré, et ne doit jamais atteindre le contrôle de santé dans cet état (donc jamais déclencher de bascule) — couvert par le comportement standard de Spring Boot/Flyway (une migration en échec empêche le contexte de démarrer, donc Tomcat ne démarre jamais et `/actuator/health` n'est jamais atteignable), pas par un test dédié de ce plan.
- Une nouvelle version GitHub est détectée alors qu'un déploiement précédent est encore en cours sur certains nœuds : pas d'empilement d'un second ordre tant que le premier n'est pas marqué traité.
- L'agent démarre sur une installation où la clé API licence locale n'existe pas encore (serveur pas encore enrôlé) : il doit attendre/réessayer proprement, jamais lever une exception qui le fait crasher en boucle.

---

### Task 1: Point de contrôle de santé (Actuator) sur l'application principale

**Files:**
- Modify: `pom.xml` (ajout dépendance)
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/logicielpourtous/site/ActuatorHealthTest.java`

**Interfaces:**
- Produces: `GET /actuator/health` → `200 {"status":"UP"}` quand l'application et sa base sont opérationnelles — c'est le point que l'agent interrogera (Task 7).

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthTest {
    @LocalServerPort private int port;

    @Test
    void healthEndpointRepondUp() {
        var reponse = RestClient.create().get()
                .uri("http://localhost:" + port + "/actuator/health")
                .retrieve().toEntity(String.class);
        assertThat(reponse.getStatusCode().value()).isEqualTo(200);
        assertThat(reponse.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ActuatorHealthTest`
Expected: FAIL (404, pas de route `/actuator/health`)

- [ ] **Step 3: Ajouter `spring-boot-starter-actuator` à `pom.xml`, exposer uniquement `health`**

Dans `application.properties` : `management.endpoints.web.exposure.include=health`. Pas d'autre endpoint exposé (pas `env`, `beans`, etc. — surface minimale).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ActuatorHealthTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/java/com/logicielpourtous/site/ActuatorHealthTest.java
git commit -m "feat: expose /actuator/health pour le controle de sante du deploiement bleu-vert"
```

---

### Task 2: Migrations Flyway (remplace `ddl-auto=update`)

**Files:**
- Modify: `pom.xml` (ajout dépendances)
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/db/migration/V1__baseline.sql` (fichier vide, voir Step 3)
- Test: `src/test/java/com/logicielpourtous/site/FlywayMigrationTest.java`

**Interfaces:**
- Produces: convention `src/main/resources/db/migration/V<N>__<nom>.sql` pour toute migration future de ce chantier ou des suivants.

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest
class FlywayMigrationTest {
    @Autowired private Flyway flyway;

    @Test
    void flywayEstActifEtBaseline() {
        var info = flyway.info();
        assertThat(info.current()).isNotNull();
        assertThat(info.current().getState().isApplied()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FlywayMigrationTest`
Expected: FAIL (pas de bean `Flyway`, dépendance absente)

- [ ] **Step 3: Ajouter `flyway-core` + `flyway-database-postgresql` à `pom.xml`. Dans `application.properties` : `spring.flyway.enabled=true`, `spring.flyway.baseline-on-migrate=true`, `spring.flyway.baseline-version=1`. Changer `spring.jpa.hibernate.ddl-auto=update` en `validate`. Créer `V1__baseline.sql` vide**

`baseline-on-migrate=true` + `baseline-version=1` : sur une base existante (NAS, dev), Flyway marque le schéma actuel comme déjà à la version 1 sans rien exécuter, au lieu d'essayer de la recréer. `V1__baseline.sql` reste vide : le schéma réel a déjà été créé par Hibernate jusqu'ici, ce fichier existe seulement pour que Flyway ait une version 1 cohérente à comparer sur une base neuve (ex. un nouveau nœud client).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FlywayMigrationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/resources/db/migration/V1__baseline.sql src/test/java/com/logicielpourtous/site/FlywayMigrationTest.java
git commit -m "feat: remplace ddl-auto=update par Flyway pour les migrations de schema"
```

---

### Task 3: Point d'accès local à la clé API licence + modèle de l'ordre de déploiement

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/deploiement/AgentLocalKeyController.java`
- Create: `src/main/java/com/logicielpourtous/site/features/deploiement/OrdreDeploiement.java`
- Create: `src/main/java/com/logicielpourtous/site/features/deploiement/OrdreDeploiementRepository.java`
- Create: `src/main/java/com/logicielpourtous/site/features/deploiement/DeploiementController.java`
- Test: `src/test/java/com/logicielpourtous/site/features/deploiement/AgentLocalKeyControllerTest.java`
- Test: `src/test/java/com/logicielpourtous/site/features/deploiement/DeploiementControllerTest.java`

**Interfaces:**
- Consumes: `EtatLicenceLocaleRepository` (lecture de `cleApiLicence`, même usage que `LicenceClient#requireCleApi`), `LicenceApiKeyAuthFilter.HEADER`/`AUTHORITY` (déjà en place côté Maître pour authentifier les routes `/api/licence/**`).
- Produces (pour Task 6, le client Maître de l'agent) :
  - `GET /internal/agent/cle-api` → `200 {"cleApiLicence": "<valeur ou null>"}`, accessible uniquement depuis `localhost` (vérifié via `request.getRemoteAddr()`).
  - `GET /api/licence/ordre-deploiement` (chaîne de sécurité existante `licenceApiFilterChain`, même authentification que les autres routes `/api/licence/**`) → `200 {"version": "v1.2.3"}` s'il existe un ordre non consommé par le `LicenceServeur` authentifié, sinon `200 {"version": null}`. Marque l'ordre consommé pour ce serveur après l'avoir renvoyé.

```java
@Entity
@Table(name = "ordres_deploiement")
public class OrdreDeploiement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String version;              // tag Git / image, ex. "v1.2.3"
    private Instant creeA;
    // relation : OrdreDeploiementConsomme(ordreId, licenceServeurId) — voir Step 3
}
```

- [ ] **Step 1: Write the failing test (clé locale)**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentLocalKeyControllerTest {
    @LocalServerPort private int port;
    @Autowired private EtatLicenceLocaleRepository etatLicenceLocaleRepository;

    @Test
    void renvoieLaCleQuandElleExiste() {
        var etat = new EtatLicenceLocale();
        etat.setCleApiLicence("cle-test-123");
        etat = etatLicenceLocaleRepository.save(etat);

        var reponse = RestClient.create().get()
                .uri("http://localhost:" + port + "/internal/agent/cle-api")
                .retrieve().body(String.class);

        assertThat(reponse).contains("cle-test-123");
        etatLicenceLocaleRepository.deleteById(etat.getId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AgentLocalKeyControllerTest`
Expected: FAIL (404, contrôleur inexistant)

- [ ] **Step 3: Implémenter `AgentLocalKeyController`, `OrdreDeploiement`, `OrdreDeploiementRepository`, `DeploiementController`**

`AgentLocalKeyController#cleApi(HttpServletRequest request)` : si `request.getRemoteAddr()` n'est pas `127.0.0.1`/`::1`, renvoie `403`. Sinon lit `EtatLicenceLocaleRepository.findAll().stream().findFirst()` (même patron que `LicenceClient#requireCleApi`, sans lever d'exception si absent — renvoie `null`).

`DeploiementController#ordreDeploiement(Authentication authentication)` : le principal est l'id du `LicenceServeur` authentifié (même patron que les routes `/api/licence/**` existantes). Cherche le dernier `OrdreDeploiement` non consommé par ce `LicenceServeur`, le marque consommé (table de jointure simple `ordre_id, licence_serveur_id` ou colonne `derniereVersionServie` sur `LicenceServeur` — choix laissé à l'implémenteur, le plus simple suffit), renvoie sa version ou `null`. Monté sur la même chaîne de sécurité que les autres routes `/api/licence/**` (voir `SecurityConfig.licenceApiFilterChain`) — ajouter le chemin à cette chaîne, pas une nouvelle.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AgentLocalKeyControllerTest -Dtest=DeploiementControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/deploiement src/test/java/com/logicielpourtous/site/features/deploiement
git commit -m "feat: expose la cle API licence en local et l'ordre de deploiement par serveur"
```

---

### Task 4: Scheduler Maître — détection GitHub + réveil des agents

**Files:**
- Create: `src/main/java/com/logicielpourtous/site/features/deploiement/GitHubReleaseClient.java`
- Create: `src/main/java/com/logicielpourtous/site/features/deploiement/DeploiementScheduler.java`
- Test: `src/test/java/com/logicielpourtous/site/features/deploiement/DeploiementSchedulerTest.java`

**Interfaces:**
- Consumes: `LicenceServeurRepository` (liste des `LicenceServeur` au statut `ACTIF`, champ `adresseDeclaree`), `OrdreDeploiementRepository` (Task 3).
- Produces: `DeploiementScheduler.reveillerTousLesAgents(String version)` — appelé après détection d'une nouvelle version ; pour chaque `LicenceServeur ACTIF`, crée/met à jour l'`OrdreDeploiement` puis appelle `POST http://<adresseDeclaree>:<app.agent.port>/reveil` (sans corps, sans en-tête), chaque appel dans son propre `try/catch` — un échec n'empêche jamais les suivants.

- [ ] **Step 1: Write the failing test**

```java
class DeploiementSchedulerTest {
    @Test
    void unReveilEnEchecNEmpecheJamaisLesAutres() {
        var serveur1 = mock(LicenceServeur.class);
        when(serveur1.getAdresseDeclaree()).thenReturn("http://injoignable:9999");
        var serveur2 = mock(LicenceServeur.class);
        when(serveur2.getAdresseDeclaree()).thenReturn("http://localhost:" + portServeurTest);
        var repo = mock(LicenceServeurRepository.class);
        when(repo.findByStatut(LicenceServeur.LicenceServeurStatut.ACTIF))
                .thenReturn(List.of(serveur1, serveur2));

        var scheduler = new DeploiementScheduler(repo, ordreDeploiementRepository, restClientBuilder);
        assertThatCode(() -> scheduler.reveillerTousLesAgents("v1.2.3")).doesNotThrowAnyException();

        verify(serveurAppeleReellement).aEteReveille(); // serveur2 a bien ete contacte malgre l'echec de serveur1
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=DeploiementSchedulerTest`
Expected: FAIL (classe inexistante)

- [ ] **Step 3: Implémenter `GitHubReleaseClient` et `DeploiementScheduler`**

`GitHubReleaseClient.dernierTag()` : `GET https://api.github.com/repos/<owner>/site/tags` (pas d'authentification nécessaire pour un dépôt dont les releases sont publiques — l'image Docker l'est déjà), renvoie le premier tag de la liste.

`DeploiementScheduler`, annoté `@ConditionalOnProperty(name = "app.role", havingValue = "master")` (même garde que les composants Maître existants) : une méthode planifiée (`@Scheduled`, intervalle configurable `app.deploiement.intervalle-verification-ms`) compare `GitHubReleaseClient.dernierTag()` à la dernière version déjà traitée (nouvelle colonne ou table `DernierOrdreTraite` — choix libre) ; si différente, appelle `reveillerTousLesAgents(nouvelleVersion)` puis mémorise cette version comme traitée (répond à la 4e ligne du Review Focus : pas d'empilement d'un second ordre tant que la version courante n'a pas changé).

`reveillerTousLesAgents` : boucle sur `licenceServeurRepository.findByStatut(ACTIF)`, pour chacun crée/mets à jour l'`OrdreDeploiement`, puis `try { restClient.post().uri(serveur.getAdresseDeclaree() + ":" + agentPort + "/reveil")... } catch (Exception e) { log.warn(...); }` — jamais de `throw` qui sortirait de la boucle (même patron que `LicenceDecouverteScheduler#decouvrir`).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=DeploiementSchedulerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/logicielpourtous/site/features/deploiement/GitHubReleaseClient.java src/main/java/com/logicielpourtous/site/features/deploiement/DeploiementScheduler.java src/test/java/com/logicielpourtous/site/features/deploiement/DeploiementSchedulerTest.java
git commit -m "feat: detection de nouvelle version GitHub et reveil des agents cote maitre"
```

---

### Task 5: Projet Agent — scaffold, point de réveil, client Maître

**Files:**
- Create: `agent/pom.xml`
- Create: `agent/src/main/java/com/logicielpourtous/agent/AgentApplication.java`
- Create: `agent/src/main/java/com/logicielpourtous/agent/ReveilController.java`
- Create: `agent/src/main/java/com/logicielpourtous/agent/MaitreClient.java`
- Create: `agent/src/main/resources/application.properties`
- Test: `agent/src/test/java/com/logicielpourtous/agent/MaitreClientTest.java`

**Interfaces:**
- Consumes: Task 3's `GET /internal/agent/cle-api` (sur `localhost:8080` de l'appli principale du même hôte) et `GET /api/licence/ordre-deploiement` (sur l'URL du Maître, authentifié avec la clé lue).
- Produces: `MaitreClient.recupererOrdre() -> Optional<String>` (la version à déployer, vide si aucun ordre) — consommé par Task 9 (orchestrateur).

- [ ] **Step 1: Write the failing test**

```java
class MaitreClientTest {
    @Test
    void recupererOrdreUtiliseLaCleLueLocalement() {
        // serveur de test jouant l'appli locale : /internal/agent/cle-api -> {"cleApiLicence":"abc"}
        // serveur de test jouant le maitre : verifie l'en-tete X-Licence-Api-Key == "abc"
        //                                    sur /api/licence/ordre-deploiement
        var client = new MaitreClient("http://localhost:" + portAppLocale, "http://localhost:" + portMaitre);
        assertThat(client.recupererOrdre()).contains("v1.2.3");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent && mvn test -Dtest=MaitreClientTest`
Expected: FAIL (module/classe inexistants)

- [ ] **Step 3: Créer le module Maven `agent/` et implémenter `AgentApplication`, `ReveilController`, `MaitreClient`**

`agent/pom.xml` : projet Maven **indépendant** (pas un module parent/enfant du `pom.xml` racine — version et cycle de build séparés), `spring-boot-starter-parent` 4.0.2 (même version que l'appli principale), dépendance `spring-boot-starter-web` uniquement.

`ReveilController` : `POST /reveil` → `202 Accepted` immédiatement, déclenche en tâche asynchrone l'appel à `MaitreClient.recupererOrdre()` puis, si une version est renvoyée, la suite du traitement (Task 9). Pas de corps, pas d'en-tête vérifié (conforme à la contrainte globale : signal de réveil non authentifié).

`MaitreClient(String urlAppLocale, String urlMaitre)` : `recupererOrdre()` appelle d'abord `GET <urlAppLocale>/internal/agent/cle-api` pour lire `cleApiLicence` ; si elle est `null` (serveur pas encore enrôlé), renvoie `Optional.empty()` sans lever d'exception (répond à la 5e ligne du Review Focus) ; sinon appelle `GET <urlMaitre>/api/licence/ordre-deploiement` avec l'en-tête `X-Licence-Api-Key`, renvoie la version si non nulle.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent && mvn test -Dtest=MaitreClientTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/
git commit -m "feat: scaffold du projet agent, point de reveil et client vers le maitre"
```

---

### Task 6: Agent — pilotage Docker (pull / start / stop)

**Files:**
- Create: `agent/src/main/java/com/logicielpourtous/agent/DockerControl.java`
- Test: `agent/src/test/java/com/logicielpourtous/agent/DockerControlTest.java`

**Interfaces:**
- Consumes: rien d'amont.
- Produces (pour Task 9) :
  - `DockerControl.demarrerVersion(String image, int port) -> String` (id du conteneur démarré)
  - `DockerControl.arreterConteneur(String idConteneur)`

- [ ] **Step 1: Write the failing test**

```java
class DockerControlTest {
    @Test
    void demarrerVersionAppelleDockerRunAvecLeBonPort() {
        var executeur = mock(CommandeExecuteur.class); // wrapper autour de ProcessBuilder, injecte
        when(executeur.executer(any())).thenReturn(new ResultatCommande(0, "abc123\n", ""));

        var control = new DockerControl(executeur);
        String id = control.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081);

        assertThat(id).isEqualTo("abc123");
        verify(executeur).executer(List.of("docker", "run", "-d", "-p", "8081:8080",
                "ghcr.io/owner/site:v1.2.3"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent && mvn test -Dtest=DockerControlTest`
Expected: FAIL (classe inexistante)

- [ ] **Step 3: Implémenter `DockerControl` au-dessus d'un `CommandeExecuteur` injectable (wrapper `ProcessBuilder`)**

L'agent tourne directement sur l'hôte (pas dans un conteneur) : il pilote Docker en appelant la CLI `docker` installée sur l'hôte via `ProcessBuilder`, jamais via le socket Docker monté dans un conteneur — ce qui évite d'accorder cet accès à un conteneur applicatif (risque de sécurité déjà écarté dans le design). `demarrerVersion` : `docker run -d -p <port>:8080 <image>`, lit l'id de conteneur sur la sortie standard. `arreterConteneur` : `docker stop <id>`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent && mvn test -Dtest=DockerControlTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/logicielpourtous/agent/DockerControl.java agent/src/test/java/com/logicielpourtous/agent/DockerControlTest.java
git commit -m "feat: pilotage Docker (demarrage/arret) cote agent"
```

---

### Task 7: Agent — contrôle de santé

**Files:**
- Create: `agent/src/main/java/com/logicielpourtous/agent/ControleSante.java`
- Test: `agent/src/test/java/com/logicielpourtous/agent/ControleSanteTest.java`

**Interfaces:**
- Produces (pour Task 9): `ControleSante.attendreSante(int port, Duration fenetre) -> boolean` — interroge `http://localhost:<port>/actuator/health` (Task 1) toutes les secondes jusqu'à `200 {"status":"UP"}` ou expiration de `fenetre`.

- [ ] **Step 1: Write the failing test**

```java
class ControleSanteTest {
    @Test
    void renvoieFauxSiJamaisSainDansLaFenetre() {
        var controle = new ControleSante();
        boolean sain = controle.attendreSante(portServeurQuiNeRepondJamais, Duration.ofSeconds(2));
        assertThat(sain).isFalse();
    }

    @Test
    void renvoieVraiDesQueLeServeurRepondUp() {
        // serveur de test repondant 200 {"status":"UP"} sur /actuator/health
        var controle = new ControleSante();
        assertThat(controle.attendreSante(portServeurSain, Duration.ofSeconds(5))).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent && mvn test -Dtest=ControleSanteTest`
Expected: FAIL (classe inexistante)

- [ ] **Step 3: Implémenter `ControleSante`**

Boucle avec `Thread.sleep(1000)` entre chaque tentative, `RestClient` vers `/actuator/health`, `true` dès qu'une réponse `200` contient `"status":"UP"`, `false` si `fenetre` est dépassée. N'importe quelle exception réseau (connexion refusée pendant que le conteneur démarre) est une tentative ratée, pas une erreur fatale.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent && mvn test -Dtest=ControleSanteTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/logicielpourtous/agent/ControleSante.java agent/src/test/java/com/logicielpourtous/agent/ControleSanteTest.java
git commit -m "feat: controle de sante avec fenetre d'attente cote agent"
```

---

### Task 8: Agent — basculement Nginx

**Files:**
- Create: `agent/src/main/java/com/logicielpourtous/agent/BasculeNginx.java`
- Create: `agent/src/main/resources/nginx-upstream.conf.template`
- Test: `agent/src/test/java/com/logicielpourtous/agent/BasculeNginxTest.java`

**Interfaces:**
- Produces (pour Task 9): `BasculeNginx.basculerVers(int port)` — réécrit le fichier de config upstream local et recharge Nginx.

- [ ] **Step 1: Write the failing test**

```java
class BasculeNginxTest {
    @Test
    void ecritLeBonPortEtRechargeNginx(@TempDir Path tmp) throws IOException {
        Path fichierConfig = tmp.resolve("upstream.conf");
        var executeur = mock(CommandeExecuteur.class);

        var bascule = new BasculeNginx(fichierConfig, executeur);
        bascule.basculerVers(8081);

        assertThat(Files.readString(fichierConfig)).contains("server 127.0.0.1:8081;");
        verify(executeur).executer(List.of("nginx", "-s", "reload"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent && mvn test -Dtest=BasculeNginxTest`
Expected: FAIL (classe inexistante)

- [ ] **Step 3: Implémenter `BasculeNginx`**

Écrit `server 127.0.0.1:<port>;` dans le fichier de config upstream (chemin injecté, pas en dur), puis exécute `nginx -s reload` via le même `CommandeExecuteur` que Task 6. `nginx-upstream.conf.template` documente le format attendu pour le plan d'infrastructure (Task du 2e plan qui assemble le `compose.yaml`).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent && mvn test -Dtest=BasculeNginxTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/logicielpourtous/agent/BasculeNginx.java agent/src/main/resources/nginx-upstream.conf.template agent/src/test/java/com/logicielpourtous/agent/BasculeNginxTest.java
git commit -m "feat: basculement du port actif cote Nginx local de l'agent"
```

---

### Task 9: Agent — orchestrateur du déploiement bleu-vert

**Files:**
- Create: `agent/src/main/java/com/logicielpourtous/agent/OrchestrateurDeploiement.java`
- Modify: `agent/src/main/java/com/logicielpourtous/agent/ReveilController.java`
- Test: `agent/src/test/java/com/logicielpourtous/agent/OrchestrateurDeploiementTest.java`

**Interfaces:**
- Consumes: `MaitreClient.recupererOrdre()` (Task 5), `DockerControl.demarrerVersion`/`arreterConteneur` (Task 6), `ControleSante.attendreSante` (Task 7), `BasculeNginx.basculerVers` (Task 8).
- Produces: `OrchestrateurDeploiement.traiterReveil()` — appelé par `ReveilController` (Task 5) à chaque `POST /reveil`.

- [ ] **Step 1: Write the failing test (chemin du succès)**

```java
class OrchestrateurDeploiementTest {
    @Test
    void basculeVersLaNouvelleVersionQuandLeControleDeSanteReussit() {
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081)).thenReturn("abc123");
        when(controleSante.attendreSante(8081, FENETRE)).thenReturn(true);

        orchestrateur.traiterReveil();

        verify(basculeNginx).basculerVers(8081);
        verify(dockerControl).arreterConteneur(idAncienneVersion);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent && mvn test -Dtest=OrchestrateurDeploiementTest`
Expected: FAIL (classe inexistante)

- [ ] **Step 3: Write the failing test (chemin de l'échec / annulation)**

```java
    @Test
    void annuleSansToucherNginxQuandLeControleDeSanteEchoue() {
        when(maitreClient.recupererOrdre()).thenReturn(Optional.of("v1.2.3"));
        when(dockerControl.demarrerVersion(any(), eq(8081))).thenReturn("abc123");
        when(controleSante.attendreSante(8081, FENETRE)).thenReturn(false);

        orchestrateur.traiterReveil();

        verify(dockerControl).arreterConteneur("abc123");
        verifyNoInteractions(basculeNginx);
    }
```

- [ ] **Step 4: Run tests to verify both fail appropriately, then implement `OrchestrateurDeploiement`**

`traiterReveil()` : si `recupererOrdre()` est vide, ne fait rien. Sinon détermine le port inactif entre `8080`/`8081` (état local : quel port est actif actuellement — champ en mémoire ou petit fichier d'état, au choix de l'implémenteur), appelle `demarrerVersion` sur ce port, puis `attendreSante(port, Duration.ofSeconds(30))` (constante `FENETRE` du Global Constraints). Si vrai : `basculerVers` puis `arreterConteneur` de l'ancienne instance, met à jour l'état "port actif". Si faux : `arreterConteneur` de la nouvelle instance, n'appelle jamais `basculeNginx`. `ReveilController` appelle `traiterReveil()` de façon asynchrone après le `202 Accepted`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd agent && mvn test -Dtest=OrchestrateurDeploiementTest`
Expected: PASS (2/2)

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/java/com/logicielpourtous/agent/OrchestrateurDeploiement.java agent/src/main/java/com/logicielpourtous/agent/ReveilController.java agent/src/test/java/com/logicielpourtous/agent/OrchestrateurDeploiementTest.java
git commit -m "feat: orchestrateur bleu-vert avec annulation automatique sur echec de controle de sante"
```
