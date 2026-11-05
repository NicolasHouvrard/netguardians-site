import { Routes } from '@angular/router';
import { GeneralLayoutComponent } from './general-layout.component';
import { CalendrierComponent }    from './calendrier/calendrier.component';

export const GENERAL_ROUTES: Routes = [
  {
    path: '',
    component: GeneralLayoutComponent,
    children: [
      { path: '', redirectTo: 'tickets', pathMatch: 'full' },
      {
        path: 'tickets',
        loadChildren: () =>
          import('../tickets/tickets.routes').then(m => m.TICKETS_ROUTES),
      },
      { path: 'calendrier', component: CalendrierComponent },
    ],
  },
];
