import { Routes } from '@angular/router';
import { authGuard }         from './core/auth/auth.guard';
import { portalGuard }       from './core/auth/portal.guard';
import { homeRedirectGuard } from './core/auth/home-redirect.guard';
import { roleGuard }         from './core/auth/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login.component').then(m => m.LoginComponent),
  },
  {
    path: '',
    loadComponent: () =>
      import('./shell/shell.component').then(m => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      // Redirection racine selon le rôle
      { path: '', canActivate: [homeRedirectGuard], children: [] },

      // ── Intranet ESN ──────────────────────────────────────────────────────
      {
        path: 'general',
        canActivate: [roleGuard()],   // tout utilisateur ESN
        loadChildren: () =>
          import('./features/general/general.routes').then(m => m.GENERAL_ROUTES),
      },
      {
        path: 'customers',
        canActivate: [roleGuard()],
        loadChildren: () =>
          import('./features/customers/customers.routes').then(m => m.CUSTOMERS_ROUTES),
      },
      {
        path: 'marketing',
        canActivate: [roleGuard('ROLE_MARKETING')],
        loadChildren: () =>
          import('./features/marketing/marketing.routes').then(m => m.MARKETING_ROUTES),
      },
      {
        path: 'admin',
        canActivate: [roleGuard('ROLE_ADMIN')],
        loadChildren: () =>
          import('./features/admin/admin.routes').then(m => m.ADMIN_ROUTES),
      },

      // ── Portail client ────────────────────────────────────────────────────
      {
        path: 'portail',
        canActivate: [portalGuard],
        loadChildren: () =>
          import('./features/portail/portail.routes').then(m => m.PORTAIL_ROUTES),
      },

      // ── Commun (tous les utilisateurs authentifiés) ───────────────────────
      {
        path: 'mon-compte',
        loadComponent: () =>
          import('./features/mon-compte/mon-compte.component').then(m => m.MonCompteComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
