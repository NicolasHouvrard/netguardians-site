import { Component, inject, resource } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { MatTableModule }       from '@angular/material/table';
import { MatChipsModule }       from '@angular/material/chips';
import { MatIconModule }        from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';

interface Invoice {
  id: number; number: string; date: string;
  totalTTC: number; status: string; statusLabel: string;
}

const STATUS_COLOR: Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  DRAFT: '', SENT: 'primary', PAID: 'accent', OVERDUE: 'warn', CANCELLED: '',
};

@Component({
  selector: 'app-portail-factures',
  standalone: true,
  imports: [DatePipe, CurrencyPipe, MatTableModule, MatChipsModule, MatIconModule, MatProgressBarModule],
  template: `
    <div class="page-header">
      <h2 class="page-title"><mat-icon>receipt_long</mat-icon> Mes factures</h2>
    </div>

    @if (invoices.isLoading()) { <mat-progress-bar mode="indeterminate" /> }

    <mat-table [dataSource]="invoices.value() ?? []" class="table">

      <ng-container matColumnDef="number">
        <mat-header-cell *matHeaderCellDef>N° facture</mat-header-cell>
        <mat-cell *matCellDef="let i">{{ i.number }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="date">
        <mat-header-cell *matHeaderCellDef>Date</mat-header-cell>
        <mat-cell *matCellDef="let i">{{ i.date | date:'dd/MM/yyyy' }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="total">
        <mat-header-cell *matHeaderCellDef>Montant TTC</mat-header-cell>
        <mat-cell *matCellDef="let i">{{ i.totalTTC | currency:'EUR':'symbol':'1.2-2':'fr' }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="status">
        <mat-header-cell *matHeaderCellDef>Statut</mat-header-cell>
        <mat-cell *matCellDef="let i">
          <mat-chip [color]="statusColor(i.status)" highlighted>{{ i.statusLabel }}</mat-chip>
        </mat-cell>
      </ng-container>

      <mat-header-row *matHeaderRowDef="cols" />
      <mat-row *matRowDef="let row; columns: cols;" />
      <tr class="mat-row" *matNoDataRow>
        <td class="mat-cell empty-row" [attr.colspan]="cols.length">Aucune facture trouvée.</td>
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
export class PortailFacturesComponent {
  private http = inject(HttpClient);

  readonly cols = ['number', 'date', 'total', 'status'];

  readonly invoices = resource<Invoice[], void>({
    loader: () => firstValueFrom(this.http.get<Invoice[]>('/api/espace/invoices')),
  });

  statusColor(s: string): 'primary' | 'accent' | 'warn' | '' {
    return STATUS_COLOR[s] ?? '';
  }
}
