import { Routes } from '@angular/router';
import { PortailLayoutComponent } from './portail-layout.component';

export const PORTAIL_ROUTES: Routes = [
  {
    path: '',
    component: PortailLayoutComponent,
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./dashboard/portail-dashboard.component').then(m => m.PortailDashboardComponent),
      },
      {
        path: 'tickets',
        loadComponent: () =>
          import('./tickets/portail-tickets.component').then(m => m.PortailTicketsComponent),
      },
      {
        path: 'factures',
        loadComponent: () =>
          import('./factures/portail-factures.component').then(m => m.PortailFacturesComponent),
      },
      {
        path: 'commandes',
        loadComponent: () =>
          import('./commandes/portail-commandes.component').then(m => m.PortailCommandesComponent),
      },
    ],
  },
];
