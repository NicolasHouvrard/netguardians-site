// Petit script pour transformer "Ma Page" en "ma-page"
// commun.js

// On déclare isDirty dans le scope global pour qu'il soit accessible partout
let isDirty = false;

document.addEventListener("DOMContentLoaded", function() {
    // On cible le formulaire qui se trouve dans la div #content pour éviter
    // de sélectionner le formulaire de déconnexion par erreur.
    const form = document.querySelector('#content form');

    // On vérifie si on est bien sur une page avec un formulaire
    if (form) {
        // Détecter les changements
        form.addEventListener('change', () => { isDirty = true; });

        // Empêcher l'alerte lors de la soumission réelle
        form.onsubmit = () => { isDirty = false; };
    }

    // Cette fonction doit être globale pour être appelée par le onclick="handleBack()"
    window.handleBack = function() {
        if (isDirty) {
            const confirmLeave = confirm("Vous avez des modifications non enregistrées. Voulez-vous vraiment quitter ?");
            if (confirmLeave) {
                history.back();
            }
        } else {
            history.back();
        }
    };
});


    window.addEventListener('beforeunload', function (e) {
    if (isDirty) {
        // Le message personnalisé est ignoré par la plupart des navigateurs modernes 
        // pour des raisons de sécurité, mais l'alerte standard s'affichera.
        e.preventDefault();
        e.returnValue = '';
    }
});

// Pour les Inputs et Textareas
function unlockInput(el) {
    el.readOnly = false;
    el.classList.replace('form-control-plaintext', 'form-control');
    el.focus();
}

function lockInput(el) {
    el.readOnly = true;
    el.classList.replace('form-control', 'form-control-plaintext');
}

// Pour les Selects : On utilise le changement de valeur pour reverrouiller
function unlockSelect(el) {
    if (!el) return;
    // On réactive les clics
    el.style.pointerEvents = "auto";
    // On change l'apparence
    el.classList.remove('form-control-plaintext');
    el.classList.add('form-control', 'bg-white', 'border-primary');
    // On ouvre le menu (optionnel, focus suffit souvent)
    el.focus();
}

function lockSelect(el) {
    // On re-bloque les clics
    el.style.pointerEvents = "none";
    // On remet l'apparence texte
    el.classList.remove('form-control', 'bg-white', 'border-primary');
    el.classList.add('form-control-plaintext');
}
// Pour les Radios (On débloque le conteneur)
function unlockRadios(group) {
    const container = group.querySelector('#radioContainer');
    container.style.pointerEvents = "auto";
    container.classList.remove('opacity-75', 'pe-none');
    group.classList.remove('bg-light');
    group.classList.add('border', 'border-primary');
}

document.addEventListener('input', function (e) {
    // On ne cible que les éléments avec notre classe spécifique
    if (e.target.classList.contains('autocomplete-input') && e.target.list) {
        const input = e.target;
        const list = input.list;
        const options = list.options;
        
        // On cherche l'input hidden le plus proche (souvent juste après)
        const hiddenInput = input.parentElement.querySelector('input[type="hidden"]:not([id^="id"])'); 
        // Note: l'exclusion [id^="id"] évite de prendre l'ID de l'entité par erreur

        let foundId = "";
        for (let i = 0; i < options.length; i++) {
            if (options[i].value === input.value) {
                foundId = options[i].getAttribute('data-id');
                break;
            }
        }
        
        if (hiddenInput) {
            hiddenInput.value = foundId;
            console.log("ID mis à jour pour " + input.id + " : " + foundId);
        }
    }
});

// À ajouter dans commun.js pour sécuriser les autocomplètes
document.querySelectorAll('.autocomplete-input').forEach(input => {
    input.addEventListener('change', function() {
        const hiddenInput = this.parentElement.querySelector('input[type="hidden"]');
        if (hiddenInput && hiddenInput.value === "") {
            // Si l'ID est vide, c'est que la saisie ne correspond à rien dans la liste
            this.value = ""; // On vide le texte pour forcer un choix valide
            this.placeholder = "⚠️ Veuillez choisir dans la liste";
            this.classList.add('is-invalid');
        } else {
            this.classList.remove('is-invalid');
        }
    });
});