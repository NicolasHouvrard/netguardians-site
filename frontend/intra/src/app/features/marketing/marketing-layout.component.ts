import { Component } from '@angular/core';
import { RouterOutlet, RouterLink } from '@angular/router';
import { MatIconModule }   from '@angular/material/icon';
import { MatMenuModule }   from '@angular/material/menu';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'app-marketing-layout',
  standalone: true,
  imports: [RouterOutlet, RouterLink, MatIconModule, MatMenuModule, MatButtonModule],
  template: `
    <div class="wrap">
      <nav class="menubar">
        <span class="menubar-title">Marketing</span>

        <button class="menu-item" [matMenuTriggerFor]="menuPages">
          <mat-icon>article</mat-icon> Pages
          <mat-icon class="chevron">arrow_drop_down</mat-icon>
        </button>
        <mat-menu #menuPages="matMenu">
          <a mat-menu-item routerLink="pages">
            <mat-icon>list</mat-icon> Toutes les pages
          </a>
          <a mat-menu-item routerLink="pages" [queryParams]="{new: '1'}">
            <mat-icon>add</mat-icon> Nouvelle page
          </a>
        </mat-menu>

        <button class="menu-item" [matMenuTriggerFor]="menuMenu">
          <mat-icon>menu</mat-icon> Menus
          <mat-icon class="chevron">arrow_drop_down</mat-icon>
        </button>
        <mat-menu #menuMenu="matMenu">
          <a mat-menu-item routerLink="menu" [queryParams]="{position: 'MAIN'}">
            <mat-icon>menu</mat-icon> Menu principal
          </a>
          <a mat-menu-item routerLink="menu" [queryParams]="{position: 'FOOTER'}">
            <mat-icon>more_horiz</mat-icon> Menu pied de page
          </a>
        </mat-menu>

        <a class="menu-item" routerLink="campaigns">
          <mat-icon>mark_email_unread</mat-icon> Campagnes e-mail
        </a>

        <a class="menu-item" routerLink="social">
          <mat-icon>share</mat-icon> Réseaux sociaux
        </a>

      </nav>
      <div class="content">
        <router-outlet />
      </div>
    </div>
  `,
  styles: [`
    .wrap { display: flex; flex-direction: column; height: 100%; }
    .menubar {
      display: flex; align-items: stretch;
      background: #f0f0f0; border-bottom: 1px solid #ccc;
      padding: 0 4px; user-select: none;
    }
    .menubar-title {
      font-size: 12px; font-weight: 600; color: #555;
      padding: 0 12px; display: flex; align-items: center;
      border-right: 1px solid #ccc; margin-right: 4px; letter-spacing: .3px;
    }
    .menu-item {
      display: inline-flex; align-items: center; gap: 4px;
      padding: 6px 10px; font-size: 13px; color: #222;
      text-decoration: none; background: none; border: none;
      cursor: pointer; border-radius: 2px; font-family: inherit;
      transition: background .1s;
      mat-icon { font-size: 16px; width: 16px; height: 16px; }
      .chevron { font-size: 18px; width: 18px; height: 18px; margin-left: -2px; color: #666; }
      &:hover { background: #d4e3fc; }
    }
    a[mat-menu-item] mat-icon { font-size: 16px; width: 16px; height: 16px; color: #555; }
    .content { flex: 1; padding: 24px; overflow: auto; }
  `],
})
export class MarketingLayoutComponent {}
