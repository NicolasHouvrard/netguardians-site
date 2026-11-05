import { Component, inject, resource } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MatCardModule }        from '@angular/material/card';
import { MatIconModule }        from '@angular/material/icon';
import { MatButtonModule }      from '@angular/material/button';
import { MatChipsModule }       from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuthService }          from '../../../core/auth/auth.service';

interface Dashboard {
  openTickets:    number;
  unpaidInvoices: number;
  pendingOrders:  number;
  recentTickets:  RecentTicket[];
}

interface RecentTicket {
  id: number; reference: string; title: string;
  status: string; statusLabel: string; createdAt: string;
}

const STATUS_COLOR: Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  NEW: 'primary', IN_PROGRESS: 'accent', CLOSED: '', CANCELLED: 'warn',
};

@Component({
  selector: 'app-portail-dashboard',
  standalone: true,
  imports: [
    RouterLink, DatePipe,
    MatCardModule, MatIconModule, MatButtonModule, MatChipsModule, MatProgressBarModule,
  ],
  template: `
    <div class="page-header">
      <h2 class="page-title">
        <mat-icon>home</mat-icon>
        Bonjour, {{ auth.user()?.fullName || auth.user()?.email }}
      </h2>
    </div>

    @if (data.isLoading()) { <mat-progress-bar mode="indeterminate" /> }

    <!-- Cartes de stats -->
    <div class="stats-grid">
      <mat-card appearance="outlined" class="stat-card" routerLink="../tickets">
        <mat-card-content>
          <div class="stat-icon tickets"><mat-icon>confirmation_number</mat-icon></div>
          <div class="stat-value">{{ data.value()?.openTickets ?? '—' }}</div>
          <div class="stat-label">Tickets ouverts</div>
        </mat-card-content>
      </mat-card>

      <mat-card appearance="outlined" class="stat-card" routerLink="../factures">
        <mat-card-content>
          <div class="stat-icon invoices"><mat-icon>receipt_long</mat-icon></div>
          <div class="stat-value">{{ data.value()?.unpaidInvoices ?? '—' }}</div>
          <div class="stat-label">Factures à régler</div>
        </mat-card-content>
      </mat-card>

      <mat-card appearance="outlined" class="stat-card" routerLink="../commandes">
        <mat-card-content>
          <div class="stat-icon orders"><mat-icon>shopping_cart</mat-icon></div>
          <div class="stat-value">{{ data.value()?.pendingOrders ?? '—' }}</div>
          <div class="stat-label">Commandes en attente</div>
        </mat-card-content>
      </mat-card>
    </div>

    <!-- Tickets récents -->
    <mat-card appearance="outlined" class="recent-card">
      <mat-card-header>
        <mat-card-title>Tickets récents</mat-card-title>
        <a mat-button routerLink="../tickets" style="margin-left:auto">Tous les tickets</a>
      </mat-card-header>
      <mat-card-content>
        @if ((data.value()?.recentTickets ?? []).length === 0 && !data.isLoading()) {
          <div class="empty-msg">Aucun ticket pour le moment.</div>
        }
        @for (t of data.value()?.recentTickets ?? []; track t.id) {
          <div class="ticket-row" routerLink="../tickets">
            <div class="ticket-ref">{{ t.reference || '#' + t.id }}</div>
            <div class="ticket-title">{{ t.title }}</div>
            <mat-chip [color]="statusColor(t.status)" highlighted class="ticket-status">
              {{ t.statusLabel }}
            </mat-chip>
            <div class="ticket-date">{{ t.createdAt | date:'dd MMM' }}</div>
          </div>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
    .page-title  {
      display: flex; align-items: center; gap: 8px;
      margin: 0; font-size: 20px; font-weight: 500;
      mat-icon { color: #3f51b5; }
    }

    .stats-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 16px; margin-bottom: 24px;
    }
    .stat-card { cursor: pointer; transition: box-shadow .15s;
      &:hover { box-shadow: 0 4px 16px rgba(0,0,0,.12); }
      mat-card-content { display: flex; flex-direction: column; align-items: center; padding: 20px; }
    }
    .stat-icon {
      width: 48px; height: 48px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      margin-bottom: 12px;
      mat-icon { font-size: 24px; width: 24px; height: 24px; }
      &.tickets   { background: #e3f2fd; mat-icon { color: #1565c0; } }
      &.invoices  { background: #fff8e1; mat-icon { color: #f57f17; } }
      &.orders    { background: #e8f5e9; mat-icon { color: #2e7d32; } }
    }
    .stat-value { font-size: 32px; font-weight: 700; color: #1a1a1a; }
    .stat-label { font-size: 13px; color: #888; margin-top: 4px; }

    .recent-card mat-card-header { padding-bottom: 4px; }

    .ticket-row {
      display: flex; align-items: center; gap: 12px;
      padding: 10px 0; border-bottom: 1px solid #f0f0f0; cursor: pointer;
      font-size: 13px;
      &:last-child { border-bottom: none; }
      &:hover { background: #fafafa; }
    }
    .ticket-ref   { font-weight: 500; color: #666; min-width: 80px; }
    .ticket-title { flex: 1; }
    .ticket-status { flex-shrink: 0; }
    .ticket-date  { color: #aaa; min-width: 60px; text-align: right; }
    .empty-msg    { text-align: center; padding: 32px; color: #aaa; font-size: 14px; }
  `],
})
export class PortailDashboardComponent {
  readonly auth = inject(AuthService);
  private  http = inject(HttpClient);

  readonly data = resource<Dashboard, void>({
    loader: () => firstValueFrom(this.http.get<Dashboard>('/api/espace/dashboard')),
  });

  statusColor(s: string): 'primary' | 'accent' | 'warn' | '' {
    return STATUS_COLOR[s] ?? '';
  }
}
