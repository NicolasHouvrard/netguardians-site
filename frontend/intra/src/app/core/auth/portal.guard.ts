import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const portalGuard: CanActivateFn = () => {
  const auth   = inject(AuthService);
  const router = inject(Router);
  if (!auth.isLoggedIn()) return router.createUrlTree(['/login']);
  // Autorisé si CLIENT ou GESTIONNAIRE (même si aussi ESN)
  if (!auth.isPortalUser()) return router.createUrlTree(['/general']);
  return true;
};
