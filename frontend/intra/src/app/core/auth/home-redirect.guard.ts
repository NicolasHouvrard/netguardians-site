import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Racine : non connecté -> login ; sinon accueil à tuiles (la zone, partenaire ou client, est choisie par l'accueil). */
export const homeRedirectGuard: CanActivateFn = () => {
  const auth   = inject(AuthService);
  const router = inject(Router);
  if (!auth.isLoggedIn()) return router.createUrlTree(['/login']);
  return router.createUrlTree(['/accueil']);
};
