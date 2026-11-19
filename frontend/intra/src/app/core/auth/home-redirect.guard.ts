import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';
import { NavigationService } from '../navigation/navigation.service';

/**
 * Racine : non connecté -> login ; sinon accueil à tuiles SI le serveur en renvoie ; sans tuile ou si l'appel échoue
 * (plateforme de plugins inactive : 404), repli sur l'ancienne redirection (jamais une page vide ou en erreur).
 */
export const homeRedirectGuard: CanActivateFn = () => {
  const auth       = inject(AuthService);
  const router     = inject(Router);
  const navigation = inject(NavigationService);
  if (!auth.isLoggedIn()) return router.createUrlTree(['/login']);
  const ancienne = (): UrlTree => {
    // ESN / Admin en priorité — un ESN peut aussi avoir des rôles portail
    if (auth.isEsnUser())    return router.createUrlTree(['/general']);
    if (auth.isPortalUser()) return router.createUrlTree(['/portail']);
    return router.createUrlTree(['/general']);
  };
  return navigation.tuiles(navigation.zoneCourante()).pipe(
    map(tuiles => (tuiles.length > 0 ? router.createUrlTree(['/accueil']) : ancienne())),
    catchError(() => of(ancienne())),
  );
};
