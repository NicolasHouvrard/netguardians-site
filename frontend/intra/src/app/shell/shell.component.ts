import { Component, inject, signal } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule }     from '@angular/material/icon';
import { MatButtonModule }   from '@angular/material/button';
import { MatDividerModule }  from '@angular/material/divider';
import { MatTooltipModule }  from '@angular/material/tooltip';
import { MatMenuModule }     from '@angular/material/menu';
import { MatListModule }     from '@angular/material/list';
import { AuthService }       from '../core/auth/auth.service';
import { SiteConfigService } from '../core/site-config.service';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive,
    MatIconModule, MatButtonModule, MatDividerModule,
    MatTooltipModule, MatMenuModule, MatListModule,
  ],
  template: `
    <div class="shell">

      <!-- Sidenav -->
      <aside class="sidenav" [class.collapsed]="collapsed()">
        <div class="sidenav-header">
          @if (!collapsed()) { <span class="brand">Intranet</span> }
          <button mat-icon-button class="toggle-btn" (click)="collapsed.set(!collapsed())"
                  [matTooltip]="collapsed() ? 'Développer' : 'Réduire'" matTooltipPosition="right">
            <mat-icon>{{ collapsed() ? 'menu' : 'menu_open' }}</mat-icon>
          </button>
        </div>

        <!-- ── Navigation ESN ──────────────────────────────── -->
        @if (auth.isEsnUser()) {
          <nav class="nav-section">
            <!-- Général : tout utilisateur ESN -->
            @if (auth.isEsnUser()) {
              <a class="nav-item" routerLink="/general" routerLinkActive="active"
                 [matTooltip]="collapsed() ? 'Général' : ''" matTooltipPosition="right">
                <mat-icon>grid_view</mat-icon>
                @if (!collapsed()) { <span>Général</span> }
              </a>
            }
            <!-- Clients : gestionnaires de compte + techniciens -->
            @if (auth.hasRole('ROLE_USER', 'ROLE_COMMERCIAL', 'ROLE_TECHNICIEN')) {
              <a class="nav-item" routerLink="/customers" routerLinkActive="active"
                 [matTooltip]="collapsed() ? 'Clients' : ''" matTooltipPosition="right">
                <mat-icon>business</mat-icon>
                @if (!collapsed()) { <span>Clients</span> }
              </a>
            }
          </nav>

          <mat-divider />

          <!-- Marketing : rôle dédié -->
          @if (auth.hasRole('ROLE_MARKETING')) {
            <nav class="nav-section">
              <a class="nav-item" routerLink="/marketing" routerLinkActive="active"
                 [matTooltip]="collapsed() ? 'Marketing' : ''" matTooltipPosition="right">
                <mat-icon>campaign</mat-icon>
                @if (!collapsed()) { <span>Marketing</span> }
              </a>
            </nav>
            <mat-divider />
          }

          <!-- Administration : admin uniquement -->
          @if (auth.isAdmin()) {
            <nav class="nav-section">
              <a class="nav-item" routerLink="/admin" routerLinkActive="active"
                 [matTooltip]="collapsed() ? 'Administration' : ''" matTooltipPosition="right">
                <mat-icon>admin_panel_settings</mat-icon>
                @if (!collapsed()) { <span>Administration</span> }
              </a>
            </nav>
            <mat-divider />
          }
        }

        <!-- ── Navigation portail client ─────────────────── -->
        @if (auth.isPortalUser()) {
          <nav class="nav-section">
            <a class="nav-item" routerLink="/portail/dashboard" routerLinkActive="active"
               [matTooltip]="collapsed() ? 'Tableau de bord' : ''" matTooltipPosition="right">
              <mat-icon>home</mat-icon>
              @if (!collapsed()) { <span>Tableau de bord</span> }
            </a>
            <a class="nav-item" routerLink="/portail/tickets" routerLinkActive="active"
               [matTooltip]="collapsed() ? 'Tickets' : ''" matTooltipPosition="right">
              <mat-icon>confirmation_number</mat-icon>
              @if (!collapsed()) { <span>Tickets</span> }
            </a>
            <a class="nav-item" routerLink="/portail/factures" routerLinkActive="active"
               [matTooltip]="collapsed() ? 'Factures' : ''" matTooltipPosition="right">
              <mat-icon>receipt_long</mat-icon>
              @if (!collapsed()) { <span>Factures</span> }
            </a>
            <a class="nav-item" routerLink="/portail/commandes" routerLinkActive="active"
               [matTooltip]="collapsed() ? 'Commandes' : ''" matTooltipPosition="right">
              <mat-icon>shopping_cart</mat-icon>
              @if (!collapsed()) { <span>Commandes</span> }
            </a>
            @if (auth.isGestionnaire()) {
              <a class="nav-item" routerLink="/portail/equipe" routerLinkActive="active"
                 [matTooltip]="collapsed() ? 'Mon équipe' : ''" matTooltipPosition="right">
                <mat-icon>group</mat-icon>
                @if (!collapsed()) { <span>Mon équipe</span> }
              </a>
            }
          </nav>
          <mat-divider />
        }

        @if (siteConfig.config.value(); as cfg) {
          <nav class="nav-section">
            <a class="nav-item" [href]="cfg.siteUrl"  rel="noopener"
               [matTooltip]="collapsed() ? cfg.siteName : ''" matTooltipPosition="right">
              <mat-icon>open_in_new</mat-icon>
              @if (!collapsed()) { <span>{{ cfg.siteName }}</span> }
            </a>
          </nav>
          <mat-divider />
        }

        <nav class="nav-section">
          <a class="nav-item" (click)="auth.logout()" style="cursor:pointer"
             [matTooltip]="collapsed() ? 'Déconnexion' : ''" matTooltipPosition="right">
            <mat-icon>logout</mat-icon>
            @if (!collapsed()) { <span>Déconnexion</span> }
          </a>
        </nav>
      </aside>

      <!-- Zone principale -->
      <div class="main">

        <!-- Header -->
        <header class="topbar">
          @if (auth.user()?.tenantName) {
            <div class="tenant-badge">
              <mat-icon>domain</mat-icon>
              <span>{{ auth.user()!.tenantName }}</span>
            </div>
          }
          <span class="spacer"></span>
          <button mat-button [matMenuTriggerFor]="accountMenu" class="account-btn">
            <div class="account-avatar">{{ initials() }}</div>
            <div class="account-info">
              <span class="account-name">{{ auth.user()?.fullName || auth.user()?.email }}</span>
              <span class="account-role">{{ auth.user()?.roles?.[0] || '' }}</span>
            </div>
            <mat-icon class="account-chevron">arrow_drop_down</mat-icon>
          </button>
          <mat-menu #accountMenu="matMenu" xPosition="before">
            <div class="menu-user-header" mat-menu-item disabled>
              <mat-icon>person</mat-icon>
              <div>
                <div class="menu-user-name">{{ auth.user()?.fullName || auth.user()?.email }}</div>
                <div class="menu-user-email">{{ auth.user()?.email }}</div>
              </div>
            </div>
            <mat-divider />
            <a mat-menu-item routerLink="/mon-compte">
              <mat-icon>manage_accounts</mat-icon> Mon compte
            </a>
            <mat-divider />
            <button mat-menu-item (click)="auth.logout()">
              <mat-icon>logout</mat-icon> Déconnexion
            </button>
          </mat-menu>
        </header>

        <!-- Contenu -->
        <div class="content-wrap">
          <router-outlet />
        </div>

      </div>
    </div>
  `,
  styles: [`
    /* Layout racine */
    .shell {
      display: flex;
      height: 100%;
      overflow: hidden;
    }

    /* Sidenav */
    .sidenav {
      width: 220px;
      min-width: 220px;
      transition: width 200ms ease, min-width 200ms ease;
      display: flex;
      flex-direction: column;
      background: #fff;
      border-right: 1px solid #e0e0e0;
      overflow: hidden;
      flex-shrink: 0;
      &.collapsed { width: 56px; min-width: 56px; }
    }

    .sidenav-header {
      height: 56px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 8px;
      border-bottom: 1px solid #e0e0e0;
      flex-shrink: 0;
    }
    .brand { font-size: 16px; font-weight: 600; white-space: nowrap; padding-left: 4px; }
    .toggle-btn { flex-shrink: 0; margin-left: auto; }
    .collapsed .sidenav-header { justify-content: center; }
    .collapsed .toggle-btn { margin-left: 0; }

    .nav-section { display: flex; flex-direction: column; }
    .nav-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 16px;
      font-size: 14px;
      color: #333;
      text-decoration: none;
      white-space: nowrap;
      transition: background 100ms;
      mat-icon { flex-shrink: 0; font-size: 20px; width: 20px; height: 20px; color: #555; }
      &:hover { background: #f5f5f5; }
      &.active { background: rgba(63,81,181,.08); color: #3f51b5;
                 mat-icon { color: #3f51b5; } }
    }
    .collapsed .nav-item { justify-content: center; padding: 10px 0; }

    /* Zone principale */
    .main {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    /* Header */
    .topbar {
      height: 56px;
      flex-shrink: 0;
      display: flex;
      align-items: center;
      padding: 0 16px;
      background: #fff;
      border-bottom: 1px solid #e0e0e0;
      gap: 8px;
    }
    .spacer { flex: 1; }

    .tenant-badge {
      display: flex; align-items: center; gap: 6px;
      background: #f0f4ff; border: 1px solid #c5d3f5;
      border-radius: 6px; padding: 4px 10px;
      font-size: 13px; font-weight: 500; color: #3f51b5;
      mat-icon { font-size: 16px; width: 16px; height: 16px; }
    }

    .account-btn {
      display: flex; align-items: center; gap: 8px;
      padding: 4px 8px; border-radius: 8px; height: auto;
      text-transform: none; letter-spacing: 0;
    }
    .account-avatar {
      width: 32px; height: 32px; border-radius: 50%;
      background: #3f51b5; color: #fff;
      display: flex; align-items: center; justify-content: center;
      font-size: 13px; font-weight: 600; flex-shrink: 0;
    }
    .account-info { display: flex; flex-direction: column; align-items: flex-start; }
    .account-name { font-size: 13px; font-weight: 500; color: #222; line-height: 1.3; }
    .account-role { font-size: 11px; color: #888; line-height: 1.3; }
    .account-chevron { color: #888; font-size: 18px; width: 18px; height: 18px; }

    .menu-user-header {
      display: flex; align-items: center; gap: 12px; padding: 12px 16px;
      opacity: 1 !important; cursor: default;
    }
    .menu-user-name { font-size: 14px; font-weight: 500; color: #222; }
    .menu-user-email { font-size: 12px; color: #888; }

    /* Contenu */
    .content-wrap {
      flex: 1;
      overflow: auto;
      padding: 24px;
    }
  `]
})
export class ShellComponent {
  readonly auth       = inject(AuthService);
  readonly siteConfig = inject(SiteConfigService);
  readonly collapsed  = signal(false);

  initials(): string {
    const u = this.auth.user();
    if (!u) return '?';
    if (u.fullName) {
      const parts = u.fullName.trim().split(' ');
      return (parts[0][0] + (parts[1]?.[0] ?? '')).toUpperCase();
    }
    return u.email?.[0]?.toUpperCase() ?? '?';
  }
}
