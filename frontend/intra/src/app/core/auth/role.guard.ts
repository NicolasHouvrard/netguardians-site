import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Guard factory — protège une route par rôle(s).
 * ROLE_ADMIN passe toujours.
 * Sans argument, accepte n'importe quel utilisateur ESN authentifié.
 *
 * Usage :
 *   canActivate: [roleGuard()]                              // tout ESN
 *   canActivate: [roleGuard('ROLE_MARKETING')]              // marketing + admin
 *   canActivate: [roleGuard('ROLE_USER','ROLE_COMMERCIAL')] // plusieurs rôles
 */
export const roleGuard = (...allowedRoles: string[]): CanActivateFn => () => {
  const auth   = inject(AuthService);
  const router = inject(Router);

  if (!auth.isLoggedIn()) return router.createUrlTree(['/login']);

  // Admin passe partout dans l'intranet
  if (auth.isAdmin()) return true;

  const userRoles = auth.user()?.roles ?? [];

  // Sans contrainte de rôle spécifique : tout utilisateur ESN
  if (allowedRoles.length === 0) {
    return auth.isEsnUser() ? true : router.createUrlTree(['/portail']);
  }

  // Vérifie qu'au moins un des rôles requis est présent
  return userRoles.some(r => allowedRoles.includes(r))
    ? true
    : router.createUrlTree(['/general']);
};
