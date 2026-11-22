// Socle du front Thymeleaf + HTMX : comportements communs. Sans dependance, sans innerHTML.
(function () {
  'use strict';

  function meta(nom) {
    var el = document.querySelector('meta[name="' + nom + '"]');
    return el ? el.getAttribute('content') : null;
  }

  // Jeton CSRF sur toute requete HTMX modifiante (le jeton vient des balises meta du layout).
  document.addEventListener('htmx:configRequest', function (evt) {
    var verbe = String((evt.detail && evt.detail.verb) || 'get').toLowerCase();
    if (verbe === 'get') {
      return;
    }
    // Jamais le jeton vers une autre origine : cible illisible ou differente -> pas d'en-tete.
    try {
      if (new URL(evt.detail.path, window.location.href).origin !== window.location.origin) {
        return;
      }
    } catch (e) {
      return;
    }
    var entete = meta('_csrf_header');
    var jeton = meta('_csrf');
    if (entete && jeton) {
      evt.detail.headers[entete] = jeton;
    }
  });

  // Message d'erreur generique : jamais le corps de la reponse (peut contenir des details internes).
  function afficherErreur(texte) {
    var zone = document.getElementById('toasts');
    if (!zone) {
      return;
    }
    var toast = document.createElement('div');
    toast.className = 'toast show align-items-center text-bg-danger border-0';
    toast.setAttribute('role', 'alert');
    var corps = document.createElement('div');
    corps.className = 'toast-body';
    corps.textContent = texte;
    toast.appendChild(corps);
    zone.appendChild(toast);
    window.setTimeout(function () {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 8000);
  }

  document.addEventListener('htmx:responseError', function () {
    afficherErreur("L'opération a échoué. Veuillez réessayer.");
  });
  document.addEventListener('htmx:sendError', function () {
    afficherErreur('Connexion impossible. Vérifiez votre réseau puis réessayez.');
  });
  // HX-Redirect (session expiree) : suivi nativement par HTMX, rien a faire ici.
})();
