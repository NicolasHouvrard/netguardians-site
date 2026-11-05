import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule }   from '@angular/material/icon';
import { MatMenuModule }   from '@angular/material/menu';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, MatMenuModule, MatButtonModule],
  template: `
    <div class="admin-wrap">

      <!-- Barre de menus style Windows -->
      <nav class="menubar">
        <span class="menubar-title">Administration</span>

        <!-- Tableau de bord (lien direct) -->
        <a class="menu-item" routerLink="dashboard" routerLinkActive="menu-active">
          <mat-icon>dashboard</mat-icon> Tableau de bord
        </a>

        <!-- Menu Utilisateurs -->
        <button class="menu-item" [matMenuTriggerFor]="menuUsers">
          <mat-icon>group</mat-icon> Utilisateurs <mat-icon class="chevron">arrow_drop_down</mat-icon>
        </button>
        <mat-menu #menuUsers="matMenu">
          <a mat-menu-item routerLink="users" routerLinkActive="menu-active">
            <mat-icon>manage_accounts</mat-icon> Utilisateurs
          </a>
          <a mat-menu-item routerLink="roles" routerLinkActive="menu-active">
            <mat-icon>shield</mat-icon> Rôles
          </a>
          <a mat-menu-item routerLink="teams" routerLinkActive="menu-active">
            <mat-icon>groups</mat-icon> Équipes
          </a>
          <a mat-menu-item routerLink="logs" routerLinkActive="menu-active">
            <mat-icon>history</mat-icon> Logs d'activité
          </a>
        </mat-menu>

        <!-- Menu Site -->
        <button class="menu-item" [matMenuTriggerFor]="menuSite">
          <mat-icon>language</mat-icon> Site <mat-icon class="chevron">arrow_drop_down</mat-icon>
        </button>
        <mat-menu #menuSite="matMenu">
          <a mat-menu-item routerLink="site-settings" routerLinkActive="menu-active">
            <mat-icon>tune</mat-icon> Paramétrage du site
          </a>
          <a mat-menu-item routerLink="media" routerLinkActive="menu-active">
            <mat-icon>photo_library</mat-icon> Médiathèque
          </a>
        </mat-menu>

        <!-- Menu Marketing -->
        <button class="menu-item" [matMenuTriggerFor]="menuMarketing">
          <mat-icon>campaign</mat-icon> Marketing <mat-icon class="chevron">arrow_drop_down</mat-icon>
        </button>
        <mat-menu #menuMarketing="matMenu">
          <a mat-menu-item routerLink="marketing/social" routerLinkActive="menu-active">
            <mat-icon>share</mat-icon> Réseaux sociaux
          </a>
        </mat-menu>

      </nav>

      <div class="admin-content">
        <router-outlet />
      </div>
    </div>
  `,
  styles: [`
    .admin-wrap { display: flex; flex-direction: column; height: 100%; }

    .menubar {
      display: flex; align-items: stretch;
      background: #f0f0f0;
      border-bottom: 1px solid #ccc;
      padding: 0 4px;
      user-select: none;
    }

    .menubar-title {
      font-size: 12px; font-weight: 600; color: #555;
      padding: 0 12px; display: flex; align-items: center;
      border-right: 1px solid #ccc; margin-right: 4px;
      letter-spacing: .3px;
    }

    .menu-item {
      display: inline-flex; align-items: center; gap: 4px;
      padding: 6px 10px; font-size: 13px; color: #222;
      text-decoration: none; background: none; border: none;
      cursor: pointer; border-radius: 2px;
      font-family: inherit;
      transition: background .1s;
      mat-icon { font-size: 16px; width: 16px; height: 16px; }
      .chevron { font-size: 18px; width: 18px; height: 18px; margin-left: -2px; color: #666; }
      &:hover, &.cdk-focused { background: #d4e3fc; }
    }

    .menu-active {
      background: #d4e3fc !important;
      font-weight: 500;
    }

    /* Items dans les dropdowns */
    a[mat-menu-item] {
      display: flex; align-items: center; gap: 8px;
      font-size: 13px;
      mat-icon { font-size: 16px; width: 16px; height: 16px; color: #555; }
    }

    .admin-content { flex: 1; padding: 24px; overflow: auto; }
  `],
})
export class AdminLayoutComponent {}
