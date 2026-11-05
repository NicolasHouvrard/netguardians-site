# Paquet Synology (DSM) pour le déploiement bleu-vert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produire un vrai paquet DSM (`.spk`) qui amorce un NAS Synology (le Maître "logipourtous" ou un NAS partenaire/client) : connexion Headscale, détection du rôle, installation de l'Agent/Postgres/Nginx local/première instance applicative sur des ports non standard, et porte licence bloquant tout usage sans compte/abonnement valide.

**Architecture:** La logique métier (détection de rôle, connexion Headscale avec réessai, sélection de ports + avertissement, amorçage Postgres/Docker/Nginx local avec garantie de préservation des données, installation du service systemd de l'Agent) est écrite en scripts shell POSIX testables indépendamment (bibliothèque `lib/*.sh`, testée avec un harnais maison, injection des commandes externes réelles — `docker`, `tailscale`, `systemctl`, `nginx`, `hostname` — via des fonctions d'exécution nommées que les tests masquent). Le paquet `.spk` lui-même (construit avec `spksrc`, l'outillage communautaire qui gère le mieux la compilation multi-architecture) n'est qu'une fine couche de scripts de cycle de vie (`preinst`/`postinst`/`prestart`/`preuninst`/`postuninst`/`postupgrade`) qui appelle cette bibliothèque dans le bon ordre. La porte licence ne nécessite aucun nouveau code : c'est le mécanisme d'enrôlement déjà construit (module `licence`) qui, une fois l'application démarrée, bloque déjà tout usage sans compte/abonnement valide — ce plan se contente de vérifier que l'intégration fonctionne réellement.

**Tech Stack:** Bash POSIX (scripts du paquet), harnais de test shell maison (voir Task 2 — pas de framework externe, `bats-core` écarté : son exécution est bloquée par la politique de sécurité "code externe" de cet environnement), `spksrc` (outillage de build SPK, multi-arch), Java 25/Spring Boot 4.0.2 (extension ponctuelle de l'Agent déjà construit), Docker, systemd, Postgres, Nginx.

**Spec:** [docs/superpowers/specs/2026-11-04-paquet-synology-nas-design.md](../specs/2026-11-04-paquet-synology-nas-design.md)

## Global Constraints

- Détection du rôle : nom d'hôte du NAS == `logipourtous` (comparaison insensible à la casse, sans suffixe de domaine) → Maître ; tout autre nom → tente de rejoindre un Maître déjà joignable.
- Deux portes indépendantes : Headscale (accès réseau) et licence/abonnement (droit d'usage) — aucune des deux ne doit jamais se substituer à l'autre.
- Partenaire vs client : **aucune logique à construire ici** — fixé entièrement par le type de compte licence utilisé à l'enrôlement (mécanisme déjà existant).
- Ports bleu/vert de l'application (actuellement codés en dur `8080`/`8081` dans `OrchestrateurDeploiement`, module `agent` déjà livré) : rendus configurables (additif, défaut inchangé = `8080`/`8081`, aucune régression sur les tests existants) ; ce paquet leur assigne des valeurs non standard : **`18080`** (actif par défaut) / **`18081`** (inactif).
- Port Postgres (hôte, mappé vers le `5432` du conteneur) : **`15432`**.
- Port d'écoute de l'Agent (déjà configurable via `app.agent.port`, défaut `8090`) : ce paquet lui assigne **`18090`**.
- Port d'**écoute** du Nginx local (celui sur lequel le VPS relaie le trafic public à travers Headscale — distinct des ports internes bleu/vert que `BasculeNginx`, déjà construit, bascule dans son bloc `upstream`) : **`8072`**, **fixe et identique sur tous les NAS** (Maître compris) — c'est volontairement une convention de projet, pas un choix par nœud, puisque le VPS route vers "le NAS" sans connaître de configuration spécifique par machine, juste son nom (Headscale) et ce port constant. Choisi pour éviter les ports DSM déjà pris par d'autres services web (interface DSM elle-même, autres paquets) sur un NAS qui peut héberger autre chose en parallèle.
- Exposition réseau, trois cas distincts (ne pas les traiter de façon identique) :
  - Ports des conteneurs Docker (app `18080`/`18081`, Postgres `15432`) : liés **uniquement au loopback (`127.0.0.1`)** — rien d'autre que l'Agent et l'application elle-même, sur CE MÊME hôte, n'y accède jamais directement (le Maître ne contacte jamais ces ports, voir le design bleu-vert : les contrôles de santé et la bascule sont déjà purement locaux).
  - Port de l'Agent (`18090`) : lié à **l'adresse IP de l'interface Tailscale/Headscale** (pas `0.0.0.0`, pas le loopback) — c'est justement ce port que le Maître doit joindre à distance pour le signal de réveil ; le lier en loopback le rendrait injoignable et casserait le mécanisme déjà construit.
  - Port d'écoute Nginx (`8072`) : lié à l'adresse IP de l'interface Tailscale/Headscale également — c'est le point d'entrée que le VPS relaie à travers le tunnel chiffré, jamais exposé sur le LAN du NAS (tout le TLS public est déjà géré côté VPS, pas besoin d'un 2e certificat ici).
- La clé de connexion Headscale est collée par l'utilisateur à l'installation (limitée dans le temps, pas récupérée via la licence).
- Les données Postgres et la configuration du paquet doivent survivre à une mise à jour (`postupgrade`) ET à une désinstallation (`preuninst`/`postuninst`) — stockées dans un dossier partagé dédié, jamais un chemin que DSM nettoie automatiquement avec le paquet.
- En cas d'échec de vérification (pas de Headscale, ou Headscale sans accès au Maître et ce n'est pas `logipourtous`) : réessai avec délai progressif (backoff), jamais un échec immédiat et définitif au premier essai.
- Canal d'avertissement (ports non standard, échec persistant) : mécanismes DSM natifs uniquement (texte de fin d'installation + journal DSM) — pas d'email, pas de notification push.
- Runtime Java de l'Agent : un JRE minimal **embarqué** dans le paquet (pas une dépendance à un paquet Java tiers du Centre de paquets).
- Chaque fonction de bibliothèque shell qui appelle une commande externe réelle (`docker`, `tailscale`, `systemctl`, `nginx`, `hostname`) le fait via une fonction d'exécution nommée (ex. `_exec_docker "$@"`) que les tests masquent en redéfinissant la même fonction avant de charger la bibliothèque — jamais d'appel direct à la commande dans la logique testée.
- Tests shell : harnais maison (`nas-package/tests/run_tests.sh`, créé en Task 2), pas `bats-core` — son exécution est du code tiers téléchargé, bloqué par la politique de sécurité de cet environnement. Chaque test est une simple fonction bash nommée `test_*` dans un fichier `test_*.sh` ; un test réussit si la fonction se termine en code 0 (assertions via `[ ... ]`/`[[ ... ]]`, pas de DSL `@test`/`run`/`$status`). Pour vérifier qu'une fonction de bibliothèque échoue (renvoie un code documenté comme 0/1), ne jamais écrire `! ma_fonction ...` seul : si la bibliothèque n'est pas encore chargée/la fonction n'existe pas, bash renvoie 127 (« command not found »), que `!` transforme en succès — un faux négatif qui masque l'absence réelle du code testé pendant la phase RED. Écrire plutôt `ma_fonction ...; [ "$?" -eq 1 ]`, qui exige le code exact documenté par l'interface et échoue correctement tant que la bibliothèque n'existe pas.
- Construction multi-architecture (x86_64 + ARM) via `spksrc`.
- Nouveau projet, racine `nas-package/` (sibling de `agent/` et du projet Maven racine, même dépôt git).

## Review Focus

- Le Maître devient injoignable alors que Headscale lui-même est bien connecté (panne temporaire, pas un problème réseau) : doit déclencher le réessai avec backoff, pas être confondu avec "Headscale non connecté du tout" ni échouer immédiatement.
- Le nom d'hôte du NAS contient une variation plausible de "logipourtous" (casse différente, suffixe `.local`, espaces) : ne doit jamais être mal classé — ni un vrai Maître pris pour un nœud secondaire, ni l'inverse.
- Une mise à jour du paquet (`postupgrade`) qui s'exécute alors que les conteneurs Docker tournent déjà : les données Postgres déjà présentes ne doivent jamais être recréées/écrasées.
- Une désinstallation du paquet : les données Postgres doivent être physiquement absentes du chemin que `preuninst`/`postuninst` nettoie, pas juste "normalement" préservées par chance.
- Un port non standard choisi par ce paquet est déjà occupé par un autre service du NAS : doit être détecté et signalé à l'utilisateur, jamais un échec de démarrage silencieux ou un conflit de port muet.

---

### Task 1: Ports bleu/vert configurables côté Agent

**Files:**
- Modify: `agent/src/main/java/com/logicielpourtous/agent/OrchestrateurDeploiement.java`
- Test: `agent/src/test/java/com/logicielpourtous/agent/OrchestrateurDeploiementTest.java`

**Interfaces:**
- Produces: `app.deploiement.port-bleu` / `app.deploiement.port-vert` (propriétés, défaut `8080`/`8081` si absentes — aucune régression sur le comportement déjà livré).

- [ ] **Step 1: Write the failing test**

```java
@Test
void utiliseLesPortsConfiguresAuLieuDesValeursParDefaut() {
    // construit l'orchestrateur avec portBleu=18080, portVert=18081 injectés
    // (constructeur de test existant, étendu avec 2 paramètres supplémentaires)
    // puis verifie que demarrerVersion est appelee avec 18081 (le port inactif
    // quand portActifActuel() renvoie 18080), pas 8081
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnw.cmd -f agent/pom.xml test -Dtest=OrchestrateurDeploiementTest#utiliseLesPortsConfiguresAuLieuDesValeursParDefaut`
Expected: FAIL (constructeur de test ne prend pas encore ces paramètres, ou les valeurs par défaut 8080/8081 sont utilisées)

- [ ] **Step 3: Rendre `PORT_BLEU`/`PORT_VERT` configurables**

Remplacer les constantes `static final int PORT_BLEU = 8080` / `PORT_VERT = 8081` par des champs d'instance, injectés via `@Value("${app.deploiement.port-bleu:8080}")` / `@Value("${app.deploiement.port-vert:8081}")` dans le constructeur `@Autowired`, et par deux paramètres supplémentaires dans le(s) constructeur(s) de test (valeur par défaut 8080/8081 pour ne pas casser les tests existants qui ne les précisent pas). Le reste de la logique (`portInactif = (portActifActuel == portBleu) ? portVert : portBleu`) ne change que dans la lecture des deux valeurs, jamais dans le raisonnement.

- [ ] **Step 4: Run test to verify it passes, then run the full agent suite**

Run: `mvnw.cmd -f agent/pom.xml test`
Expected: PASS (nouveau test + tous les tests existants déjà livrés, aucune régression)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/logicielpourtous/agent/OrchestrateurDeploiement.java agent/src/test/java/com/logicielpourtous/agent/OrchestrateurDeploiementTest.java
git commit -m "feat: rend les ports bleu/vert de l'Agent configurables (paquet Synology)"
```

---

### Task 2: Harnais de test maison + bibliothèque shell — détection du rôle

**Files:**
- Create: `nas-package/tests/run_tests.sh`
- Create: `nas-package/lib/role.sh`
- Test: `nas-package/tests/test_role.sh`

**Interfaces:**
- Produces (harnais, réutilisé par toutes les tâches suivantes) : `run_tests.sh <fichier_test.sh>` — charge le fichier, exécute chaque fonction shell dont le nom commence par `test_`, imprime `ok - <nom>` ou `FAIL - <nom>` pour chacune, le résumé `<reussis>/<total>` à la fin, et sort en code 0 seulement si tout a réussi.
- Produces (bibliothèque) : `nas_est_maitre()` — code de sortie 0 si le nom d'hôte courant est `logipourtous` (insensible à la casse, suffixe `.local`/domaine ignoré), 1 sinon. Utilise `_exec_hostname` (pas `hostname` directement) pour lire le nom.

- [ ] **Step 1: Écrire le harnais `run_tests.sh`**

```bash
#!/usr/bin/env bash
set -uo pipefail
fichier="$1"
source "$fichier"
total=0
echecs=0
for fn in $(declare -F | awk '{print $3}' | grep '^test_'); do
  total=$((total+1))
  if "$fn"; then
    echo "ok - $fn"
  else
    echo "FAIL - $fn"
    echecs=$((echecs+1))
  fi
done
echo "$((total-echecs))/$total"
[ "$echecs" -eq 0 ]
```

Pas de test dédié pour le harnais lui-même (il se prouve en faisant passer/échouer les tests des tâches suivantes) — seule exigence vérifiable directement : `run_tests.sh` exécuté sur un fichier sans aucune fonction `test_*` affiche `0/0` et sort en code 0 (aucun échec puisqu'aucun test).

- [ ] **Step 2: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_nas_est_maitre_reussit_si_logipourtous() {
  _exec_hostname() { echo "logipourtous"; }
  source "$NAS_PKG_DIR/lib/role.sh"
  nas_est_maitre
}

test_nas_est_maitre_echoue_pour_autre_nom() {
  _exec_hostname() { echo "nas-client-dupont"; }
  source "$NAS_PKG_DIR/lib/role.sh"
  nas_est_maitre
  [ "$?" -eq 1 ]
}

test_nas_est_maitre_ignore_la_casse_et_le_suffixe_domaine() {
  _exec_hostname() { echo "LogiPourtous.local"; }
  source "$NAS_PKG_DIR/lib/role.sh"
  nas_est_maitre
}
```

(`NAS_PKG_DIR` anchore le `source` au dossier `nas-package/` réel, quel que soit le répertoire courant depuis lequel `run_tests.sh` est invoqué — un `source lib/....sh` nu ne résout pas depuis la racine du worktree ; convention établie ici, reprise par toutes les tâches suivantes. Reflète ce qui a réellement été livré en Task 2.)

- [ ] **Step 3: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_role.sh`
Expected: FAIL (`lib/role.sh` inexistant, chaque test échoue)

- [ ] **Step 4: Implémenter `role.sh`**

`_exec_hostname()` par défaut fait `hostname`. `nas_est_maitre()` lit `_exec_hostname`, retire tout ce qui suit le premier `.`, passe en minuscules, compare à `logipourtous`.

- [ ] **Step 5: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_role.sh`
Expected: PASS (`3/3`)

- [ ] **Step 6: Commit**

```bash
git add nas-package/tests/run_tests.sh nas-package/lib/role.sh nas-package/tests/test_role.sh
git commit -m "feat(nas-package): harnais de test maison et detection du role par nom d'hote"
```

---

### Task 3: Bibliothèque shell — connexion Headscale avec réessai

**Files:**
- Create: `nas-package/lib/headscale.sh`
- Test: `nas-package/tests/test_headscale.sh`

**Interfaces:**
- Consumes: rien d'amont.
- Produces:
  - `headscale_est_connecte()` — code de sortie 0/1, via `_exec_tailscale status`.
  - `headscale_connecter(cle)` — via `_exec_tailscale up --authkey "$cle" --login-server <url_configuree>`.
  - `headscale_adresse_ip()` — affiche sur stdout l'adresse IP assignée par Headscale à ce nœud, via `_exec_tailscale ip -4` ; n'affiche rien et renvoie 1 si la commande échoue (NAS pas encore connecté).
  - `avec_reessai(fenetre_secondes, delai_initial_secondes, commande...)` — réessaie `commande` avec un délai qui double à chaque échec, jusqu'à expiration de `fenetre_secondes` ; renvoie 0 dès le premier succès, 1 si la fenêtre expire.

- [ ] **Step 1: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_headscale_est_connecte_reussit_quand_tailscale_status_renvoie_0() {
  _exec_tailscale() { return 0; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  headscale_est_connecte
}

test_avec_reessai_reussit_des_que_la_commande_reussit_sans_attendre_toute_la_fenetre() {
  source "$NAS_PKG_DIR/lib/headscale.sh"
  compteur=0
  commande_test() { compteur=$((compteur+1)); [ "$compteur" -ge 2 ]; }
  avec_reessai 10 1 commande_test
}

test_avec_reessai_echoue_si_la_fenetre_expire_sans_succes() {
  source "$NAS_PKG_DIR/lib/headscale.sh"
  avec_reessai 1 1 false
  [ "$?" -eq 1 ]
}

test_headscale_adresse_ip_affiche_l_adresse_renvoyee_par_tailscale_ip_4() {
  _exec_tailscale() { echo "100.64.0.12"; }
  source "$NAS_PKG_DIR/lib/headscale.sh"
  [ "$(headscale_adresse_ip)" = "100.64.0.12" ]
}
```

(`NAS_PKG_DIR`, même convention qu'établie en Task 2. Reflète ce qui a réellement été livré en Task 3 — y compris les 4 tests ajoutés après coup par la revue de sécurité : code d'échec exact de `headscale_est_connecte`/`headscale_adresse_ip`, cle jamais en argv de `headscale_connecter`, garde contre `delai_initial=0` dans `avec_reessai` — voir le ledger pour le détail.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_headscale.sh`
Expected: FAIL (`lib/headscale.sh` inexistant)

- [ ] **Step 3: Implémenter `headscale.sh`**

`_exec_tailscale()` par défaut fait `tailscale "$@"`. `avec_reessai` : boucle avec un délai qui double (`sleep` réel non mocké dans ce test — garder `delai_initial_secondes` petit, ex. 1, pour que le test reste rapide), s'arrête dès qu'un appel à `commande...` renvoie 0, abandonne (code 1) une fois `fenetre_secondes` dépassée. `headscale_adresse_ip` appelle `_exec_tailscale ip -4` et relaie sa sortie standard ; si la commande échoue, renvoie exactement 1 sans rien afficher (conforme à l'interface) — ne pas relayer le code de sortie brut de `tailscale`.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_headscale.sh`
Expected: PASS (`4/4`)

- [ ] **Step 5: Commit**

```bash
git add nas-package/lib/headscale.sh nas-package/tests/test_headscale.sh
git commit -m "feat(nas-package): connexion Headscale avec reessai a delai progressif"
```

---

### Task 4: Bibliothèque shell — accessibilité du Maître

**Files:**
- Create: `nas-package/lib/maitre.sh`
- Test: `nas-package/tests/test_maitre.sh`

**Interfaces:**
- Consumes: aucune (indépendant, appelé après `headscale_est_connecte` par le script d'orchestration, Task 10).
- Produces: `maitre_est_joignable(url_maitre)` — code 0/1, via `_exec_curl -sf "$url_maitre/actuator/health"` (l'endpoint déjà livré, Task 1 du chantier bleu-vert).

- [ ] **Step 1: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_maitre_est_joignable_reussit_si_curl_renvoie_0() {
  _exec_curl() { return 0; }
  source "$NAS_PKG_DIR/lib/maitre.sh"
  maitre_est_joignable "https://logipourtous.ts.net"
}

test_maitre_est_joignable_echoue_si_curl_renvoie_un_code_non_nul() {
  _exec_curl() { return 7; }
  source "$NAS_PKG_DIR/lib/maitre.sh"
  maitre_est_joignable "https://logipourtous.ts.net"
  [ "$?" -eq 1 ]
}
```

(`NAS_PKG_DIR` anchore le `source` au dossier `nas-package/` réel, quel que soit le répertoire courant depuis lequel `run_tests.sh` est invoqué — un `source lib/....sh` nu ne résout pas depuis la racine du worktree ; même convention que `test_role.sh`/`test_headscale.sh`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_maitre.sh`
Expected: FAIL (`lib/maitre.sh` inexistant)

- [ ] **Step 3: Implémenter `maitre.sh`**

`_exec_curl()` par défaut fait `curl "$@"`. `maitre_est_joignable` appelle `_exec_curl -sf "$1/actuator/health"` et normalise son résultat à 0 (succès) ou 1 (échec) — ne pas relayer le code de sortie brut de curl, qui n'est pas garanti binaire (ex. 22, 7...).

- [ ] **Step 4: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_maitre.sh`
Expected: PASS (`2/2`)

- [ ] **Step 5: Commit**

```bash
git add nas-package/lib/maitre.sh nas-package/tests/test_maitre.sh
git commit -m "feat(nas-package): verification d'accessibilite du Maitre via /actuator/health"
```

---

### Task 5: Bibliothèque shell — sélection de ports et avertissement

**Files:**
- Create: `nas-package/lib/ports.sh`
- Test: `nas-package/tests/test_ports.sh`

**Interfaces:**
- Produces:
  - `port_est_libre(port)` — code 0/1, via `_exec_ss -ltn` (liste les ports déjà en écoute sur le NAS).
  - `avertir_utilisateur(message)` — écrit `message` à la fois sur la sortie (texte de fin d'installation DSM) et dans le journal DSM via `_exec_synologset1` (ou équivalent DSM de journalisation — voir Task 10 pour le choix exact de la commande DSM réelle, ce module ne fait qu'appeler `_exec_log_dsm "$message"`).

- [ ] **Step 1: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_port_est_libre_echoue_si_le_port_figure_dans_la_liste_ss() {
  _exec_ss() { printf ':18080\n'; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 18080
  [ "$?" -eq 1 ]
}

test_port_est_libre_reussit_si_le_port_n_apparait_pas() {
  _exec_ss() { printf ':9999\n'; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  port_est_libre 18080
}

test_avertir_utilisateur_appelle_le_journal_dsm_avec_le_message() {
  recu=""
  _exec_log_dsm() { recu="$1"; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  avertir_utilisateur "ports non standard : 18080/18081/15432/18090"
  [ "$recu" = "ports non standard : 18080/18081/15432/18090" ]
}

test_avertir_utilisateur_affiche_aussi_le_message_sur_la_sortie() {
  _exec_log_dsm() { :; }
  source "$NAS_PKG_DIR/lib/ports.sh"
  [ "$(avertir_utilisateur "ports non standard : 18080/18081/15432/18090")" = "ports non standard : 18080/18081/15432/18090" ]
}
```

(`NAS_PKG_DIR`, même convention que les tâches précédentes. Le 4e test verifie l'autre moitie de l'interface — "a la fois sur la sortie ET dans le journal DSM" — qu'aucun des 3 premiers tests n'exerçait.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_ports.sh`
Expected: FAIL (`lib/ports.sh` inexistant)

- [ ] **Step 3: Implémenter `ports.sh`**

`_exec_ss()` par défaut fait `ss -ltn`. `port_est_libre` grep la sortie pour `:<port>` en fin de ligne/avant un espace. `_exec_log_dsm()` par défaut écrit sur stderr (la commande DSM réelle de journalisation reste à préciser en Task 10, voir sa javadoc/commentaire). `avertir_utilisateur` doit écrire `message` sur stdout (via `echo`/`printf`, pas seulement vers `_exec_log_dsm`) ET appeler `_exec_log_dsm "$message"` — les deux, pas l'un ou l'autre.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_ports.sh`
Expected: PASS (`4/4`)

- [ ] **Step 5: Commit**

```bash
git add nas-package/lib/ports.sh nas-package/tests/test_ports.sh
git commit -m "feat(nas-package): verification de port libre et avertissement utilisateur"
```

---

### Task 6: Bibliothèque shell — amorçage Postgres avec préservation des données

**Files:**
- Create: `nas-package/lib/postgres_bootstrap.sh`
- Test: `nas-package/tests/test_postgres_bootstrap.sh`

**Interfaces:**
- Produces: `demarrer_postgres(dossier_donnees_externe, port_hote)` — supprime d'abord tout conteneur `site-postgres` préexistant (`_exec_docker rm -f site-postgres`, idempotent même si absent) PUIS `_exec_docker run -d --name site-postgres -v "$dossier_donnees_externe:/var/lib/postgresql/data" -p "127.0.0.1:$port_hote:5432" postgres:<tag>`. `dossier_donnees_externe` est TOUJOURS un chemin hors de l'arborescence que le paquet DSM gère lui-même (ex. un dossier partagé dédié) — jamais un argument par défaut deviné dans ce fichier. Le `rm -f` du conteneur est sûr pour les données : Postgres ne garde rien dans la couche inscriptible du conteneur, tout est dans `dossier_donnees_externe` (jamais touché) — c'est exactement la même convention de recréation déterministe que `DockerControl` (Agent, chantier bleu-vert) applique déjà aux conteneurs applicatifs. Sans ce `rm -f`, une 2e invocation (Task 10, `postupgrade`) échouerait avec "nom de conteneur déjà utilisé".

- [ ] **Step 1: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_demarrer_postgres_monte_le_dossier_de_donnees_externe_et_le_port_sur_loopback() {
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "/volume1/site-data/postgres" 15432
  [[ "$recu" == *"-v /volume1/site-data/postgres:/var/lib/postgresql/data"* ]] \
    && [[ "$recu" == *"-p 127.0.0.1:15432:5432"* ]]
}

test_demarrer_postgres_ne_supprime_jamais_le_dossier_de_donnees_existant() {
  # le dossier existe deja avec un fichier dedans ; la fonction ne doit faire
  # aucun appel a rm/docker volume rm SUR LE DOSSIER, seulement docker rm/run
  # (le conteneur, lui, est jetable - voir le test de reinvocation ci-dessous)
  touch_marker=$(mktemp -d)/marker
  mkdir -p "$(dirname "$touch_marker")" && touch "$touch_marker"
  _exec_docker() { :; }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "$(dirname "$touch_marker")" 15432
  [ -f "$touch_marker" ]
}

test_demarrer_postgres_est_reinvocable_sans_erreur_de_nom_deja_pris() {
  # simule une 2e invocation (Task 10, postupgrade) : le conteneur precedent
  # doit etre supprime AVANT la recreation, jamais l'inverse
  appels=()
  _exec_docker() { appels+=("$*"); }
  source "$NAS_PKG_DIR/lib/postgres_bootstrap.sh"
  demarrer_postgres "/volume1/site-data/postgres" 15432
  [[ "${appels[0]}" == *"rm -f site-postgres"* ]] && [[ "${appels[1]}" == *"run"*"site-postgres"* ]]
}
```

(`NAS_PKG_DIR`, même convention que les tâches précédentes.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_postgres_bootstrap.sh`
Expected: FAIL (`lib/postgres_bootstrap.sh` inexistant)

- [ ] **Step 3: Implémenter `postgres_bootstrap.sh`**

`_exec_docker()` par défaut fait `docker "$@"`. `demarrer_postgres` appelle d'abord `_exec_docker rm -f site-postgres` (ignorer son code de sortie — absent la première fois, c'est normal), PUIS construit exactement la commande `docker run` ci-dessus (image Postgres taguée explicitement, pas `latest` — choisir une version précise cohérente avec le `pg_dump`/schéma déjà validés, ex. `postgres:18`). Ni l'un ni l'autre appel ne touche jamais au contenu de `dossier_donnees_externe` lui-même (aucun `rm`/initialisation destructive sur le DOSSIER — seul le conteneur est jetable, Postgres gère lui-même un volume déjà peuplé).

- [ ] **Step 4: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_postgres_bootstrap.sh`
Expected: PASS (`3/3`)

- [ ] **Step 5: Commit**

```bash
git add nas-package/lib/postgres_bootstrap.sh nas-package/tests/test_postgres_bootstrap.sh
git commit -m "feat(nas-package): amorcage Postgres avec donnees hors cycle de vie du paquet"
```

---

### Task 7: Bibliothèque shell — Nginx local de bascule et d'écoute

**Files:**
- Create: `nas-package/lib/nginx_bascule.sh`
- Test: `nas-package/tests/test_nginx_bascule.sh`

**Interfaces:**
- Consumes: le format déjà établi par `BasculeNginx` (Agent, chantier bleu-vert) : `server 127.0.0.1:<port>;` dans le fichier upstream ; `headscale_adresse_ip()` (Task 3).
- Produces:
  - `initialiser_nginx_local(fichier_upstream, port_actif_initial)` — écrit le fichier upstream initial (même format que `BasculeNginx` sait relire via `portActifActuel()`), SEULEMENT si le fichier n'existe pas déjà (ne jamais écraser une configuration issue d'une bascule déjà effectuée par l'Agent).
  - `configurer_ecoute_nginx(fichier_serveur, adresse_ip_liaison, port_ecoute)` — écrit un bloc `server { listen <adresse_ip_liaison>:<port_ecoute>; ... proxy_pass http://site_actif; }` (relaie vers l'`upstream` nommé `site_actif` défini par `initialiser_nginx_local`/`BasculeNginx`) dans `fichier_serveur`, SEULEMENT si absent — idempotent, comme ci-dessus.
  - `_exec_nginx -s reload` appelé une seule fois après les deux écritures (pas une fois par fonction), pour ne recharger qu'une fois par appel du script d'orchestration (Task 10).

- [ ] **Step 1: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_initialiser_nginx_local_cree_le_fichier_au_bon_format_si_absent() {
  dir=$(mktemp -d)
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  initialiser_nginx_local "$dir/upstream.conf" 18080
  grep -q "server 127.0.0.1:18080;" "$dir/upstream.conf"
}

test_initialiser_nginx_local_ne_touche_pas_un_fichier_deja_present() {
  dir=$(mktemp -d)
  printf 'server 127.0.0.1:18081;\n' > "$dir/upstream.conf"
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  initialiser_nginx_local "$dir/upstream.conf" 18080
  grep -q "server 127.0.0.1:18081;" "$dir/upstream.conf"
}

test_configurer_ecoute_nginx_cree_le_bloc_server_au_bon_format_si_absent() {
  dir=$(mktemp -d)
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  configurer_ecoute_nginx "$dir/server.conf" "100.64.0.12" 8072
  grep -q "listen 100.64.0.12:8072;" "$dir/server.conf" \
    && grep -q "proxy_pass http://site_actif;" "$dir/server.conf"
}

test_configurer_ecoute_nginx_ne_touche_pas_un_fichier_deja_present() {
  dir=$(mktemp -d)
  printf 'listen 100.64.0.99:9999;\n' > "$dir/server.conf"
  source "$NAS_PKG_DIR/lib/nginx_bascule.sh"
  configurer_ecoute_nginx "$dir/server.conf" "100.64.0.12" 8072
  grep -q "100.64.0.99:9999" "$dir/server.conf"
}
```

(`NAS_PKG_DIR`, même convention que les tâches précédentes.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_nginx_bascule.sh`
Expected: FAIL (`lib/nginx_bascule.sh` inexistant)

- [ ] **Step 3: Implémenter `nginx_bascule.sh`**

`_exec_nginx()` par défaut fait `nginx "$@"`. Les deux fonctions d'écriture testent l'existence de leur fichier avant d'écrire quoi que ce soit (idempotent, jamais destructif sur une bascule déjà appliquée par l'Agent, ou sur une config d'écoute déjà en place, lors d'une réinstallation/mise à jour) — ni l'une ni l'autre n'appelle `_exec_nginx` elle-même ; c'est le script d'orchestration (Task 10) qui appelle `_exec_nginx -s reload` une seule fois après les deux.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_nginx_bascule.sh`
Expected: PASS (`4/4`)

- [ ] **Step 5: Commit**

```bash
git add nas-package/lib/nginx_bascule.sh nas-package/tests/test_nginx_bascule.sh
git commit -m "feat(nas-package): initialisation idempotente du Nginx local de bascule et d'ecoute"
```

---

### Task 8: Bibliothèque shell — service systemd de l'Agent

**Files:**
- Create: `nas-package/lib/agent_service.sh`
- Create: `nas-package/systemd/site-agent.service.template`
- Test: `nas-package/tests/test_agent_service.sh`

**Interfaces:**
- Consumes: `headscale_adresse_ip()` (Task 3) — l'IP à laquelle lier le port `18090` de l'Agent (voir Global Constraints : l'Agent doit être joignable depuis le Maître, contrairement aux conteneurs Docker en loopback).
- Produces: `installer_service_agent(chemin_jre, chemin_jar, adresse_ip_liaison, port_ecoute, fichier_unite_cible)` — écrit `fichier_unite_cible` à partir de `systemd/site-agent.service.template` (remplace les placeholders `__JRE__`/`__JAR__`/`__SERVER_ADDRESS__`/`__AGENT_PORT__`), puis `_exec_systemctl enable --now site-agent`. `port_ecoute` est un PARAMÈTRE, jamais une valeur `18090` codée en dur dans cette bibliothèque — même convention que `port_hote` dans `demarrer_postgres` (Task 6) et `demarrer_premiere_instance` (Task 9) : c'est Task 10 (orchestration) qui fournira `18090` explicitement, seul endroit où les valeurs fixes du projet (`18080`/`18081`/`15432`/`18090`/`8072`) sont littéralement écrites. Sans ce paramètre, l'Agent démarrerait sur son port Spring Boot par défaut (`8090`, via `server.port=${AGENT_PORT:8090}`, déjà existant dans `agent/src/main/resources/application.properties.example`) au lieu de `18090` — le Maître ne pourrait alors jamais le joindre pour le signal de réveil, cassant silencieusement tout le mécanisme bleu-vert sur ce NAS.

- [ ] **Step 1: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_installer_service_agent_remplace_les_placeholders_et_active_le_service() {
  dir=$(mktemp -d)
  appele=""
  _exec_systemctl() { appele="$*"; }
  source "$NAS_PKG_DIR/lib/agent_service.sh"
  installer_service_agent "/usr/local/jre/bin/java" "/volume1/site-data/agent.jar" "100.64.0.12" 18090 "$dir/site-agent.service"
  grep -q "/usr/local/jre/bin/java" "$dir/site-agent.service" \
    && grep -q "/volume1/site-data/agent.jar" "$dir/site-agent.service" \
    && grep -q "100.64.0.12" "$dir/site-agent.service" \
    && grep -q "AGENT_PORT=18090" "$dir/site-agent.service" \
    && [[ "$appele" == *"enable"* && "$appele" == *"site-agent"* ]]
}
```

(`NAS_PKG_DIR`, même convention que les tâches précédentes. Note : `installer_service_agent` lit `systemd/site-agent.service.template` relatif à `NAS_PKG_DIR` lui aussi, pas au répertoire courant — même raisonnement.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_agent_service.sh`
Expected: FAIL (`lib/agent_service.sh` inexistant)

- [ ] **Step 3: Implémenter `agent_service.sh` et le template**

`systemd/site-agent.service.template` : unité minimale (`[Unit]`/`[Service]`/`[Install]`) avec `ExecStart=__JRE__ -jar __JAR__`, `Environment=SERVER_ADDRESS=__SERVER_ADDRESS__` ET `Environment=AGENT_PORT=__AGENT_PORT__` (liaison relâchée Spring Boot : les variables d'environnement `SERVER_ADDRESS`/`AGENT_PORT` alimentent directement `server.address`/`server.port` — `server.port=${AGENT_PORT:8090}` déjà présent dans `agent/src/main/resources/application.properties.example` — sans code Java supplémentaire). `_exec_systemctl()` par défaut fait `systemctl "$@"`. `installer_service_agent` fait un `sed`/substitution des quatre placeholders puis `_exec_systemctl enable --now site-agent`.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_agent_service.sh`
Expected: PASS (`1/1`)

- [ ] **Step 5: Commit**

```bash
git add nas-package/lib/agent_service.sh nas-package/systemd/site-agent.service.template nas-package/tests/test_agent_service.sh
git commit -m "feat(nas-package): installation du service systemd de l'Agent"
```

---

### Task 9: Bibliothèque shell — première instance applicative

**Files:**
- Create: `nas-package/lib/docker_bootstrap.sh`
- Test: `nas-package/tests/test_docker_bootstrap.sh`

**Interfaces:**
- Consumes: la convention d'image déjà établie (`ghcr.io/<owner>/site:<tag>`, chantier bleu-vert).
- Produces: `demarrer_premiere_instance(image, port_hote)` — via `_exec_docker run -d --name site-18080 --restart unless-stopped -p 127.0.0.1:<port_hote>:8080 "$image"` (même convention de nommage déterministe `site-<port>` que `DockerControl`, Agent déjà livré — pour qu'une bascule ultérieure de l'Agent reconnaisse ce conteneur).

- [ ] **Step 1: Write the failing test**

```bash
NAS_PKG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_demarrer_premiere_instance_nomme_le_conteneur_site_port_et_le_lie_en_loopback() {
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "ghcr.io/owner/site:v1.0.0" 18080
  [[ "$recu" == *"--name site-18080"* ]] \
    && [[ "$recu" == *"-p 127.0.0.1:18080:8080"* ]] \
    && [[ "$recu" == *"ghcr.io/owner/site:v1.0.0"* ]] \
    && [[ "$recu" == *"--restart unless-stopped"* ]]
}

test_demarrer_premiere_instance_derive_le_nom_et_le_port_hote_du_parametre_pas_dune_valeur_fixe() {
  # port different de 18080 ci-dessus : une implementation qui coderait "site-18080" en dur
  # au lieu de deriver "site-$port_hote" passerait le test precedent sans etre detectee
  recu=""
  _exec_docker() { recu="$*"; }
  source "$NAS_PKG_DIR/lib/docker_bootstrap.sh"
  demarrer_premiere_instance "ghcr.io/owner/site:v1.0.0" 19091
  [[ "$recu" == *"--name site-19091"* ]] && [[ "$recu" == *"-p 127.0.0.1:19091:8080"* ]]
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_docker_bootstrap.sh`
Expected: FAIL (`lib/docker_bootstrap.sh` inexistant)

- [ ] **Step 3: Implémenter `docker_bootstrap.sh`**

Même convention de nom de conteneur (`site-<port>`) que `DockerControl.nomConteneur` (Agent déjà livré, voir sa javadoc) — pour que l'Agent, une fois démarré, reconnaisse et puisse gérer ce conteneur lors de la première vraie bascule.

- [ ] **Step 4: Run test to verify it passes**

Run: `bash nas-package/tests/run_tests.sh nas-package/tests/test_docker_bootstrap.sh`
Expected: PASS (`2/2`)

- [ ] **Step 5: Commit**

```bash
git add nas-package/lib/docker_bootstrap.sh nas-package/tests/test_docker_bootstrap.sh
git commit -m "feat(nas-package): demarrage de la premiere instance applicative, convention de nommage partagee avec l'Agent"
```

---

### Task 10: Paquet `.spk` — scripts de cycle de vie et build réel

**Files:**
- Create: `nas-package/spk/INFO.sh`
- Create: `nas-package/spk/Makefile`
- Create: `nas-package/spk/src/scripts/preinst.sh`
- Create: `nas-package/spk/src/scripts/postinst.sh`
- Create: `nas-package/spk/src/scripts/prestart.sh`
- Create: `nas-package/spk/src/scripts/preuninst.sh`
- Create: `nas-package/spk/src/scripts/postuninst.sh`
- Create: `nas-package/spk/src/scripts/postupgrade.sh`
- Create: le(s) fichier(s) d'assistant d'installation DSM nécessaires pour que l'utilisateur saisisse la clé Headscale pendant l'installation (convention `spksrc`/DSM exacte — `WIZARD_UIFILES` ou équivalent — laissée à la recherche documentaire du porteur de cette tâche, comme `INFO.sh`/`Makefile` ; la spec note explicitement que ce format est un détail ouvert, mais le FICHIER LUI-MÊME n'est pas optionnel : sans lui, aucune valeur n'alimente jamais `$CLE_SAISIE` à l'étape 2 ci-dessous, et le paquet n'a alors aucun moyen réel de collecter la clé).
- Create: `nas-package/README.md`

**Interfaces:**
- Consumes: toutes les fonctions des Tasks 2-9, avec leurs signatures RÉELLEMENT livrées (certaines ont changé depuis leur description initiale dans ce plan — se fier aux fichiers `nas-package/lib/*.sh` réels, pas seulement à la prose ci-dessous) : `nas_est_maitre()`, `avec_reessai(fenetre, delai, cmd...)`, `headscale_est_connecte()`, `headscale_connecter(cle)` (lit `HEADSCALE_URL` depuis l'environnement — à exporter avant l'appel, voir étape 2), `headscale_adresse_ip()`, `maitre_est_joignable(url)`, `port_est_libre(port)`, `avertir_utilisateur(message)`, `demarrer_postgres(dossier_donnees_externe, port_hote)`, `initialiser_nginx_local(fichier_upstream, port_actif_initial)`, `configurer_ecoute_nginx(fichier_serveur, adresse_ip_liaison, port_ecoute)`, `installer_service_agent(chemin_jre, chemin_jar, adresse_ip_liaison, port_ecoute, fichier_unite_cible)` **(5 paramètres, pas 4 — `port_ecoute` a été ajouté après la livraison initiale de la Task 8 ; omettre ce paramètre ferait démarrer l'Agent sur son port Spring Boot par défaut 8090 au lieu de 18090, le rendant injoignable par le Maître)**, `demarrer_premiere_instance(image, port_hote)`.
- Produces: un `.spk` installable, résultat final de ce plan.

- [ ] **Step 1: Écrire `postinst.sh` à partir des bibliothèques déjà testées**

Ordre exact (Review Focus de ce plan), avec les valeurs fixes du projet (Global Constraints) littéralement écrites ici — c'est le SEUL endroit du plan où elles le sont, chaque bibliothèque les reçoit en paramètre :

1. `avec_reessai <fenêtre> <délai initial> headscale_est_connecte` (si échec final : `avertir_utilisateur` et sortir sans installer le reste — rien n'a encore été démarré) ;
2. si `headscale_est_connecte` échoue initialement : `export HEADSCALE_URL="<URL du serveur Headscale — à déterminer/câbler par le porteur de cette tâche, probablement une valeur fixe du projet comme les autres>"` (requis par `headscale_connecter`, qui échoue sinon) puis `headscale_connecter "$CLE_SAISIE"` (`CLE_SAISIE` vient du champ de l'assistant d'installation DSM créé ci-dessus — câbler ici la variable que ce fichier d'assistant expose réellement) puis réessayer l'étape 1 ;
3. si `nas_est_maitre` : pas de vérification d'accessibilité du Maître (ce NAS EST le Maître) ; sinon `avec_reessai <fenêtre> <délai initial> maitre_est_joignable "$URL_MAITRE"` (échec final → `avertir_utilisateur`, backoff géré par `avec_reessai` lui-même, jamais un abandon au premier essai). **`URL_MAITRE` n'est précisé nulle part ailleurs dans ce plan — décision à prendre et à documenter explicitement dans `README.md` (avec la raison) : soit une convention fixe dérivée du nom d'hôte `logipourtous` déjà établi (Task 2) — cohérent avec la philosophie "convention de projet" déjà appliquée au port Nginx `8072` — soit un champ supplémentaire de l'assistant. Ne pas deviner silencieusement sans l'écrire dans le README.**
4. pour chacun des 4 ports non standard (Global Constraints — `18080`, `18081`, `15432`, `18090` ; le port Nginx `8072` est géré séparément à l'étape 6) : `port_est_libre <port>` ; tout port déjà occupé → `avertir_utilisateur` et **abandonner l'installation** (ne pas deviner un autre port à la place : un port réassigné silencieusement romprait la convention partagée avec l'Agent déjà livré — `site-<port>`, `BasculeNginx` — pour un gain marginal) ;
5. `demarrer_postgres "<dossier de données dans le dossier partagé dédié — voir Step 2>" 15432` ;
6. `initialiser_nginx_local "<fichier upstream>" 18080` (port actif initial = `18080`, convention du projet) puis `configurer_ecoute_nginx "<fichier serveur>" "$(headscale_adresse_ip)" 8072` puis **un seul** appel à `_exec_nginx -s reload` ;
7. réutiliser l'adresse déjà obtenue à l'étape précédente (ne pas rappeler `headscale_adresse_ip` une 2e fois) puis `installer_service_agent "<chemin JRE embarqué>" "<chemin jar, à l'intérieur de package.tgz>" "<adresse Headscale>" 18090 "<chemin de l'unité systemd cible>"` — **5 arguments, le 4e (`18090`) est obligatoire, voir Interfaces ci-dessus** ;
8. `demarrer_premiere_instance "ghcr.io/<owner>/site:<tag>" 18080` (même convention d'image que le chantier bleu-vert, même port actif initial que l'étape 6).

- [ ] **Step 2: Garantir la survie des données à la mise à jour et à la désinstallation**

`postupgrade.sh` : ne ré-exécute PAS l'étape 5 de `postinst.sh` si le dossier de données existe déjà (même test d'idempotence que `demarrer_postgres`/`initialiser_nginx_local`, Tasks 6-7). `preuninst.sh`/`postuninst.sh` : vérifier explicitement, par un test d'intégration réel (voir Step 4), que le dossier de données externe n'est jamais sous un chemin que DSM associe au cycle de vie du paquet (typiquement sous `/var/packages/<nom>/` — à éviter ; utiliser un dossier partagé dédié, ex. `/volume1/site-data/`, documenté dans `README.md`).

- [ ] **Step 3: Écrire `INFO.sh`, `Makefile`, `preinst.sh`, `prestart.sh`**

`prestart.sh` relance les vérifications 1 et 3 de Step 1 (sans réinstaller quoi que ce soit) à chaque démarrage du NAS — c'est le point d'ancrage officiel Synology pour "le paquet peut-il démarrer ?" (voir la spec, section "Ce qui existe déjà"). `INFO.sh`/`Makefile` : suivre les conventions `spksrc` (gabarit de paquet communautaire) pour la construction multi-architecture — le détail exact de la syntaxe `spksrc` est laissé au porteur de cette tâche (recherche documentaire au moment de l'implémentation, la spec l'a explicitement laissé ouvert).

- [ ] **Step 4: Construire un `.spk` réel et vérifier son installation**

Run: build via `spksrc` pour au moins une architecture (x86_64), puis installation réelle sur une DSM (VM ou matériel de test) ou, à défaut, une inspection du `.spk` produit (archive tar POSIX ustar valide, contenu `INFO` cohérent, scripts exécutables présents).
Expected: le paquet s'installe sans l'erreur "Invalid file format" documentée dans la spec (vérifier le format `ustar`, pas `PAX`, de l'archive produite) ; après un déclenchement manuel de `postinst.sh` sur l'environnement de test, les conteneurs/service démarrent sur les ports `18080`/`15432`/`18090`, le port `18090` (Agent) et le port `8072` (Nginx) répondent depuis l'adresse Tailscale du NAS (pas seulement en local), et une requête HTTP sur `8072` est bien relayée jusqu'à l'application (via l'`upstream` actif) ; en déclenchant ensuite `postupgrade.sh` alors que les conteneurs tournent déjà, le dossier de données Postgres n'est ni recréé ni modifié (même contenu avant/après, horodatage de modification inchangé) ; et après une désinstallation (`preuninst.sh`/`postuninst.sh`) suivie d'une réinstallation, les données sont toujours là. Vérifier aussi que l'application démarrée refuse bien tout accès fonctionnel sans compte licence valide (comportement déjà existant, pas de code nouveau — simple confirmation que l'intégration ne le contourne pas).

- [ ] **Step 5: Commit**

```bash
git add nas-package/spk nas-package/README.md
git commit -m "feat(nas-package): scripts de cycle de vie du paquet DSM et build reel"
```
