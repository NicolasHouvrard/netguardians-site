# Créer un partenaire avec un compte existant comme premier manager

Date : 2026-11-25. Demande du propriétaire : « il faut qu'on puisse créer à partir d'une adresse existante » (il est à la fois administrateur du
Maître et partenaire, avec le même compte ; la création d'un partenaire refuse aujourd'hui toute adresse déjà utilisée).

## Intention
L'administrateur du Maître doit pouvoir créer un partenaire dont le premier manager est un compte qui existe déjà, sans créer de second compte.

## Conception retenue (approuvée par le propriétaire)
1. Formulaire « Créer un partenaire » (plugin `partenaires`) : case « Rattacher le compte existant si cette adresse a déjà un compte »,
   décochée par défaut.
2. Adresse inconnue : comportement actuel (nouveau compte dans le tenant du partenaire, mot de passe aléatoire à réinitialiser).
3. Adresse connue et case cochée : aucun compte n'est créé ; le compte existant est rattaché à la nouvelle société comme manager
   (`ManagersService.ajouterUtilisateur`, premier membre donc manager). Ses rôles globaux, son tenant, son nom et son mot de passe ne changent pas.
4. Adresse connue et case décochée : refus `EMAIL_DEJA_UTILISE` inchangé, message du formulaire complété (« cochez … »). Compte inactif, ou adresse
   utilisée sans compte utilisateur correspondant : refus comme avant, rien n'est écrit.
5. Contrat `GestionPartenairesContrat.CreationPartenaire` : nouveau composant booléen `rattacherCompteExistant` ; l'ancien constructeur à 5
   paramètres est CONSERVÉ (même descripteur) et vaut `false`, pour ne pas casser le plugin `partenaires` 1.0.0 déjà installé.
6. Plugin `partenaires` passe en 1.1.0 (case et message), à signer et publier par le propriétaire.

## Hors périmètre
- Rattacher un compte existant à un partenaire déjà créé (autre fonction).
- Nouvelle action de journal dédiée (le journal existant `PARTENAIRE_CREE` reste utilisé).
- Toute modification des droits ou des rôles globaux du compte rattaché.

## Approches considérées
Rattachement explicite par case (retenu) ; rattachement automatique dès que l'adresse existe (écarté : une faute de frappe ferait d'un inconnu le
manager du partenaire) ; ajouter plus tard un écran « rattacher » sur un partenaire existant (hors périmètre).
