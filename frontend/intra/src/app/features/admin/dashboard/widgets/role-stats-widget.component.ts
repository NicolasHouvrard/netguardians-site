import { Component, input } from '@angular/core';
import { RouterLink }       from '@angular/router';
import { MatCardModule }    from '@angular/material/card';
import { MatButtonModule }  from '@angular/material/button';
import { MatIconModule }    from '@angular/material/icon';
import { AdminStats }       from '../../admin.model';

@Component({
  selector: 'app-role-stats-widget',
  standalone: true,
  imports: [RouterLink, MatCardModule, MatButtonModule, MatIconModule],
  template: `
    <mat-card class="widget-card">
      <mat-card-content>
        <div class="widget-icon-wrap widget-icon-roles">
          <mat-icon class="widget-icon widget-icon-roles-color">shield</mat-icon>
        </div>
        <div class="widget-body">
          <div class="widget-value">{{ stats()?.roles ?? '—' }}</div>
          <div class="widget-label">Rôles définis</div>
          <div class="widget-sub">
            <span class="badge-info">Contrôle des accès</span>
          </div>
        </div>
      </mat-card-content>
      <mat-card-actions>
        <a mat-flat-button color="accent" routerLink="../roles">
          <mat-icon>arrow_forward</mat-icon> Gérer les rôles
        </a>
      </mat-card-actions>
    </mat-card>
  `,
  styleUrl: './stats-widget.scss',
})
export class RoleStatsWidgetComponent {
  readonly stats = input<AdminStats | null>(null);
}
