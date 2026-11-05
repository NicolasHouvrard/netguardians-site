import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const homeRedirectGuard: CanActivateFn = () => {
  const auth   = inject(AuthService);
  const router = inject(Router);
  if (!auth.isLoggedIn())  return router.createUrlTree(['/login']);
  // ESN / Admin en priorité — un ESN peut aussi avoir des rôles portail
  if (auth.isEsnUser())    return router.createUrlTree(['/general']);
  if (auth.isPortalUser()) return router.createUrlTree(['/portail']);
  return router.createUrlTree(['/general']);
};
