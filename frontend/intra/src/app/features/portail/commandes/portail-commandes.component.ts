import { Component, inject, resource } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { MatTableModule }       from '@angular/material/table';
import { MatChipsModule }       from '@angular/material/chips';
import { MatIconModule }        from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';

interface Order {
  id: number; reference: string; createdAt: string;
  totalTTC: number; status: string; statusLabel: string;
}

const STATUS_COLOR: Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  PENDING_VALIDATION: 'primary',
  PENDING_PAYMENT:    'accent',
  CONFIRMED:          'accent',
  SHIPPED:            '',
  DELIVERED:          '',
  CANCELLED:          'warn',
};

@Component({
  selector: 'app-portail-commandes',
  standalone: true,
  imports: [DatePipe, CurrencyPipe, MatTableModule, MatChipsModule, MatIconModule, MatProgressBarModule],
  template: `
    <div class="page-header">
      <h2 class="page-title"><mat-icon>shopping_cart</mat-icon> Mes commandes</h2>
    </div>

    @if (orders.isLoading()) { <mat-progress-bar mode="indeterminate" /> }

    <mat-table [dataSource]="orders.value() ?? []" class="table">

      <ng-container matColumnDef="reference">
        <mat-header-cell *matHeaderCellDef>Référence</mat-header-cell>
        <mat-cell *matCellDef="let o">{{ o.reference || '#' + o.id }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="date">
        <mat-header-cell *matHeaderCellDef>Date</mat-header-cell>
        <mat-cell *matCellDef="let o">{{ o.createdAt | date:'dd/MM/yyyy' }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="total">
        <mat-header-cell *matHeaderCellDef>Montant TTC</mat-header-cell>
        <mat-cell *matCellDef="let o">{{ o.totalTTC | currency:'EUR':'symbol':'1.2-2':'fr' }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="status">
        <mat-header-cell *matHeaderCellDef>Statut</mat-header-cell>
        <mat-cell *matCellDef="let o">
          <mat-chip [color]="statusColor(o.status)" highlighted>{{ o.statusLabel }}</mat-chip>
        </mat-cell>
      </ng-container>

      <mat-header-row *matHeaderRowDef="cols" />
      <mat-row *matRowDef="let row; columns: cols;" />
      <tr class="mat-row" *matNoDataRow>
        <td class="mat-cell empty-row" [attr.colspan]="cols.length">Aucune commande trouvée.</td>
      </tr>
    </mat-table>
  `,
  styles: [`
    .page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
    .page-title  { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; font-weight: 500;
                   mat-icon { color: #3f51b5; } }
    .table       { width: 100%; }
    mat-header-cell, mat-cell { padding: 8px 12px; font-size: 13px; }
    .empty-row   { text-align: center; padding: 32px; color: #888; }
  `],
})
export class PortailCommandesComponent {
  private http = inject(HttpClient);

  readonly cols = ['reference', 'date', 'total', 'status'];

  readonly orders = resource<Order[], void>({
    loader: () => firstValueFrom(this.http.get<Order[]>('/api/espace/orders')),
  });

  statusColor(s: string): 'primary' | 'accent' | 'warn' | '' {
    return STATUS_COLOR[s] ?? '';
  }
}
