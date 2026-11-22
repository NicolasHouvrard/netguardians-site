# Tests JavaScript du socle

Tests de `src/main/resources/static/js/socle.js` avec le lanceur intégré de Node (v24, aucune
dépendance : pas de `package.json`, pas de `npm install`). Ils ne sont branchés ni sur Maven ni sur
aucun build ; on les lance à la main depuis la racine du dépôt :

```
node --test "src/test/js/**/*.test.js"
```

(Avec Node 24, passer le seul dossier `src/test/js/` ne fonctionne pas : il faut le motif ci-dessus
ou le fichier `src/test/js/socle.test.js`.)

## Couvert

- Logique du jeton CSRF : verbes modifiants seulement, lecture des balises `_csrf_header` / `_csrf`
  (nul si l'une manque ou est vide).
- Contrôle de même site : adresses relatives, absolues, `//hôte`, `javascript:`, trucs d'identifiant
  (`https://site.example@evil.com/`), hôtes ressemblants, entrées illisibles.
- Messages d'erreur sans HTML : le texte passe par `textContent` uniquement.

## Non couvert

- Le comportement réel dans un navigateur : déclenchement des événements HTMX
  (`htmx:configRequest`, `htmx:responseError`, `htmx:sendError`), affichage des toasts, minuterie.
  Le faux `document` des tests est écrit à la main et ne reproduit pas le DOM réel.
