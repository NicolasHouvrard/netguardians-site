import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule }   from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'app-general-layout',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, MatButtonModule],
  template: `
    <div class="wrap">
      <nav class="menubar">
        <span class="menubar-title">Général</span>

        <a class="menu-item" routerLink="tickets" routerLinkActive="active">
          <mat-icon>confirmation_number</mat-icon> Tickets
        </a>

        <a class="menu-item" routerLink="calendrier" routerLinkActive="active">
          <mat-icon>calendar_month</mat-icon> Calendrier
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
      &:hover { background: #d4e3fc; }
      &.active { background: #d4e3fc; color: #1a73e8; font-weight: 500;
                 mat-icon { color: #1a73e8; } }
    }
    .content { flex: 1; padding: 24px; overflow: auto; }
  `],
})
export class GeneralLayoutComponent {}
