# Task 7 : federation face a un plugin en maintenance (503 + Retry-After)

## Etape 1 : etat existant
- `RestSyncClient` et `LicenceClient` : aucun traitement explicite de 503/Retry-After. Autres clients sortants (Qonto, Stripe, GitHub...) hors perimetre federation.
- Constat important : `RestClient.builder()` choisit Apache HttpClient 5 (transitif, scope compile), dont la strategie par defaut rejoue UNE fois toute reponse 503/429 (meme un POST, meme sans Retry-After : 1 s) et attend `Retry-After` SANS BORNE (prouve par journal : "re-executed in 86400 SECONDS"). Cela contredisait la regle « 503 sans Retry-After = comportement inchange / attente <= 30 s ». Corrige en desactivant ce rejeu implicite (strategie par defaut conservee pour les erreurs d'E/S) et en le remplacant par l'utilitaire borne.
  - Consequence : un 503 sans Retry-After n'est plus rejoue implicitement une fois (c'etait un artefact de la bibliotheque) ; il remonte immediatement en `HttpServerErrorException`. Les appelants (planificateurs) ont deja leur propre cadence de reprise.

## Implementation
- `features/commun/ReessaiRetryAfter` (module `commun`, partage par `sync` et `licence`, pas de nouvelle dependance de module) : `executer(Supplier)`, `fabriqueRequetes()`, `Attente` injectable, `Clock` injectable.
- Regles : 503 uniquement ; Retry-After entier >= 0 ou date HTTP (RFC 1123) non passee ; sinon ignore (garbage, negatif, vide, decimal) ; attente <= 30 s ; 2 reessais max ; ensuite derniere exception relancee telle quelle ; interruption => relance de l'exception d'origine. Rien n'est journalise (pas de cle API).
- Appels couverts : `RestSyncClient.push` (POST, idempotent : le maitre dedoublonne par syncId/version) et `pull` (GET) ; `LicenceClient.listerNouveauxServeurs` (GET) et `pousserCodePartenaire` (POST idempotent : le maitre ecrase le code et pose un drapeau). 
- NON couvert : `LicenceClient.genererJeton` (POST non idempotent : cree un jeton a chaque appel). Un 503 de maintenance veut dire « non traite », donc le rejeu serait en pratique sur, mais un 503 emis par un intermediaire apres traitement ne peut pas etre exclu ; restreint par prudence (teste : un seul appel).
- `SourceMaitreHttp` (tireur de plugins) non modifie : il n'est pas dans les fichiers du brief et ne fait que du telechargement avec sa propre politique (tireur ne doit pas boucler ni bloquer). A reevaluer si le Maitre peut etre en maintenance ; dit ici, non fait.

## Tests
- `ReessaiRetryAfterTest` (7) : reessaie_apres_503_avec_retry_after, n_attend_pas_plus_de_30_secondes, date HTTP, max 2 reessais, 503 sans Retry-After inchange, garbage/negatif ignores, autre statut jamais rejoue.
- `LicenceClientRetryAfterTest` (3) et `RestSyncClientRetryAfterTest` (3) : vrai serveur HTTP JDK, attente injectee (aucune attente reelle). RED observe (compilation) puis, apres un premier vert faux, la decouverte du rejeu Apache a fait corriger l'implementation.
- Non-regression : voir resume final (ModularityTests, LicenceClientIntegrationTest, RestSyncClientIntegrationTest, LicenceDecouverteSchedulerTest, GestionServeursControllerTest).

## Non-regression (une classe a la fois)
- ModularityTests 1/1 OK ; LicenceClientIntegrationTest 2/2 OK ; LicenceDecouverteSchedulerTest 8/8 OK ; GestionServeursControllerTest : 0 test execute (classe sans test actif).
- RestSyncClientIntegrationTest 2/3 : `pullReadsPayloads...` echoue DANS L'ARRANGEMENT du test (`comments.author_id` NOT NULL en base, insertion d'un Comment sans auteur, avant tout appel client) : ecart de schema de la base partagee/test, sans rapport avec ce changement (non verifie sur BASE par stash, cout ~2 min). push/invalidKey OK.

## Fix round 1 (revue)
- Important #1 : `LicenceDecouverteScheduler.decouvrir()` abandonne le cycle (`break`) a la premiere 503 qui survit aux reessais de `pousserCodePartenaire` ; les entrees restantes (non notifiees cote maitre) sont resondees au cycle suivant. Attente maximale par cycle : 2 x 30 s pour le push + celle du listage, au lieu de N x 60 s. Test `LicenceDecouverteSchedulerBorneTest` (4 entrees, maitre en 503 permanent + Retry-After 10) : RED (plus de 3 POST) puis GREEN (3 POST, 2 attentes).
- `SyncOutboxScheduler` : meme forme NON retrouvee (un seul `push` groupe puis un seul `pull` par cycle, pas de boucle d'appels reseau) ; au pire 2 x 60 s par cycle, non modifie.
- Minor #2 : un Retry-After entier de 10+ chiffres est plafonne a 30 s (plus ignore) ; test `delta_enorme_est_plafonne_a_30_secondes` (RED puis GREEN).
- Minor #3 : tests ajoutes `push_reessaie_apres_503_avec_le_meme_corps`, `pousserCodePartenaire_reessaie_apres_503_avec_le_meme_corps`, `reessai_sur_erreur_d_es_conserve` (connexion coupee puis rejeu par le client Apache).
- Minor #4 : `echoue_tout_de_suite_sur_503_sans_retry_after` (nom du brief en commentaire) dans les 3 classes ; ecart assume : un 503 sans Retry-After n'est plus rejoue (ni 429).
- Hors perimetre, note : `SourceMaitreHttp` et les autres `RestClient` restent sur le rejeu Apache non borne (suivi recommande avec `fabriqueRequetes()`).
