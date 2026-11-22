// Socle du front Thymeleaf + HTMX : comportements communs. Sans dependance, sans innerHTML.
// Script classique (pas de module) ; la logique est dans des fonctions pures, testees avec Node
// (voir src/test/js/README.md).
(function () {
  'use strict';

  var VERBES_SURS = ['get', 'head', 'options', 'trace'];

  // Vrai seulement si l'adresse, resolue contre base, a pour origine "origine". Illisible -> faux.
  function memeSite(chemin, base, origine) {
    try {
      if (typeof chemin !== 'string' || chemin === '' || typeof origine !== 'string' || origine === '') {
        return false;
      }
      return new URL(chemin, base).origin === origine;
    } catch (e) {
      return false;
    }
  }

  // Jeton CSRF uniquement sur les verbes modifiants et jamais vers une autre origine.
  function doitAjouterJeton(verbe, chemin, base, origine) {
    var v = String(verbe || 'get').toLowerCase();
    if (VERBES_SURS.indexOf(v) !== -1) {
      return false;
    }
    return memeSite(chemin, base, origine);
  }

  function meta(doc, nom) {
    var el = doc.querySelector('meta[name="' + nom + '"]');
    return el ? el.getAttribute('content') : null;
  }

  // Le jeton vient des balises meta du layout ; nul si l'une des deux manque ou est vide.
  function lireJeton(doc) {
    var entete = meta(doc, '_csrf_header');
    var valeur = meta(doc, '_csrf');
    return entete && valeur ? { entete: entete, valeur: valeur } : null;
  }

  // Message d'erreur generique : le texte est pose par textContent, jamais interprete comme HTML.
  function creerMessage(doc, texte) {
    var toast = doc.createElement('div');
    toast.className = 'toast show align-items-center text-bg-danger border-0';
    toast.setAttribute('role', 'alert');
    var corps = doc.createElement('div');
    corps.className = 'toast-body';
    corps.textContent = texte;
    toast.appendChild(corps);
    return toast;
  }

  if (typeof document !== 'undefined' && typeof window !== 'undefined') {
    document.addEventListener('htmx:configRequest', function (evt) {
      var d = evt.detail || {};
      if (!doitAjouterJeton(d.verb, d.path, window.location.href, window.location.origin)) {
        return;
      }
      var jeton = lireJeton(document);
      if (jeton) {
        d.headers[jeton.entete] = jeton.valeur;
      }
    });

    // Jamais le corps de la reponse (peut contenir des details internes).
    var afficherErreur = function (texte) {
      var zone = document.getElementById('toasts');
      if (!zone) {
        return;
      }
      var toast = creerMessage(document, texte);
      zone.appendChild(toast);
      window.setTimeout(function () {
        if (toast.parentNode) {
          toast.parentNode.removeChild(toast);
        }
      }, 8000);
    };

    document.addEventListener('htmx:responseError', function () {
      afficherErreur("L'opération a échoué. Veuillez réessayer.");
    });
    document.addEventListener('htmx:sendError', function () {
      afficherErreur('Connexion impossible. Vérifiez votre réseau puis réessayez.');
    });
    // HX-Redirect (session expiree) : suivi nativement par HTMX, rien a faire ici.
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { memeSite: memeSite, doitAjouterJeton: doitAjouterJeton, lireJeton: lireJeton, creerMessage: creerMessage };
  }
})();
