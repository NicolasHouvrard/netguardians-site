import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const esnGuard: CanActivateFn = () => {
  const auth   = inject(AuthService);
  const router = inject(Router);
  if (!auth.isLoggedIn())  return router.createUrlTree(['/login']);
  if (!auth.isEsnUser())   return router.createUrlTree(['/portail']);
  return true;
};
