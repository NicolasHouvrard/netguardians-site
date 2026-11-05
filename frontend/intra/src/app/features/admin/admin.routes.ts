import { Routes } from '@angular/router';
import { AdminLayoutComponent }        from './admin-layout.component';
import { AdminDashboardComponent }     from './dashboard/admin-dashboard.component';
import { AdminUsersComponent }         from './users/admin-users.component';
import { AdminRolesComponent }         from './roles/admin-roles.component';
import { AdminLogsComponent }          from './logs/admin-logs.component';
import { AdminSiteSettingsComponent }  from './site-settings/admin-site-settings.component';
import { MediaLibraryComponent }       from './media/media-library.component';
import { AdminSocialConfigComponent }  from './social/admin-social-config.component';
import { AdminTeamsComponent }         from './teams/admin-teams.component';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: AdminLayoutComponent,
    children: [
      { path: '',               redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'dashboard',      component: AdminDashboardComponent },
      { path: 'users',          component: AdminUsersComponent },
      { path: 'roles',          component: AdminRolesComponent },
      { path: 'logs',           component: AdminLogsComponent },
      { path: 'teams',          component: AdminTeamsComponent },
      { path: 'site-settings',  component: AdminSiteSettingsComponent },
      { path: 'media',          component: MediaLibraryComponent },
      { path: 'marketing/social', component: AdminSocialConfigComponent },
    ],
  },
];
