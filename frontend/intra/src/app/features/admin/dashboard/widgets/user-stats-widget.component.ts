import { Component, input } from '@angular/core';
import { RouterLink }       from '@angular/router';
import { MatCardModule }    from '@angular/material/card';
import { MatButtonModule }  from '@angular/material/button';
import { MatIconModule }    from '@angular/material/icon';
import { AdminStats }       from '../../admin.model';

@Component({
  selector: 'app-user-stats-widget',
  standalone: true,
  imports: [RouterLink, MatCardModule, MatButtonModule, MatIconModule],
  template: `
    <mat-card class="widget-card">
      <mat-card-content>
        <div class="widget-icon-wrap">
          <mat-icon class="widget-icon">group</mat-icon>
        </div>
        <div class="widget-body">
          <div class="widget-value">{{ stats()?.total ?? '—' }}</div>
          <div class="widget-label">Utilisateurs au total</div>
          <div class="widget-sub">
            <span class="badge-interne">{{ stats()?.interne ?? 0 }} internes</span>
            <span class="badge-portail">{{ stats()?.portail ?? 0 }} portail</span>
            <span class="badge-active">{{ stats()?.active ?? 0 }} actifs</span>
          </div>
        </div>
      </mat-card-content>
      <mat-card-actions>
        <a mat-flat-button color="primary" routerLink="../users">
          <mat-icon>arrow_forward</mat-icon> Gérer les utilisateurs
        </a>
      </mat-card-actions>
    </mat-card>
  `,
  styleUrl: './stats-widget.scss',
})
export class UserStatsWidgetComponent {
  readonly stats = input<AdminStats | null>(null);
}
