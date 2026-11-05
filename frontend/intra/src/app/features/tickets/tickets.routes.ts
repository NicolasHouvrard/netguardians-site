import { Routes } from '@angular/router';

export const TICKETS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./ticket-list.component').then(m => m.TicketListComponent),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./ticket-detail.component').then(m => m.TicketDetailComponent),
  },
];
