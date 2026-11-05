import { Component, inject, signal, resource } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { MatTableModule }        from '@angular/material/table';
import { MatFormFieldModule }    from '@angular/material/form-field';
import { MatSelectModule }       from '@angular/material/select';
import { MatChipsModule }        from '@angular/material/chips';
import { MatIconModule }         from '@angular/material/icon';
import { MatProgressBarModule }  from '@angular/material/progress-bar';
import { MatButtonModule }       from '@angular/material/button';

interface Ticket {
  id: number; reference: string; title: string;
  status: string; statusLabel: string;
  priority: string | null; createdAt: string;
}

const STATUS_COLOR: Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  NEW: 'primary', IN_PROGRESS: 'accent', CLOSED: '', CANCELLED: 'warn',
};

@Component({
  selector: 'app-portail-tickets',
  standalone: true,
  imports: [
    ReactiveFormsModule, DatePipe,
    MatTableModule, MatFormFieldModule, MatSelectModule,
    MatChipsModule, MatIconModule, MatProgressBarModule, MatButtonModule,
  ],
  template: `
    <div class="page-header">
      <h2 class="page-title"><mat-icon>confirmation_number</mat-icon> Mes tickets support</h2>
      <div class="filters">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Statut</mat-label>
          <mat-select [formControl]="statusCtrl">
            <mat-option value="">Tous</mat-option>
            <mat-option value="NEW">Nouveau</mat-option>
            <mat-option value="IN_PROGRESS">En cours</mat-option>
            <mat-option value="CLOSED">Fermé</mat-option>
          </mat-select>
        </mat-form-field>
      </div>
    </div>

    @if (tickets.isLoading()) { <mat-progress-bar mode="indeterminate" /> }

    <mat-table [dataSource]="tickets.value() ?? []" class="table">

      <ng-container matColumnDef="reference">
        <mat-header-cell *matHeaderCellDef>Réf.</mat-header-cell>
        <mat-cell *matCellDef="let t">{{ t.reference || '#' + t.id }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="title">
        <mat-header-cell *matHeaderCellDef>Sujet</mat-header-cell>
        <mat-cell *matCellDef="let t">{{ t.title }}</mat-cell>
      </ng-container>

      <ng-container matColumnDef="status">
        <mat-header-cell *matHeaderCellDef>Statut</mat-header-cell>
        <mat-cell *matCellDef="let t">
          <mat-chip [color]="statusColor(t.status)" highlighted>{{ t.statusLabel }}</mat-chip>
        </mat-cell>
      </ng-container>

      <ng-container matColumnDef="date">
        <mat-header-cell *matHeaderCellDef>Date</mat-header-cell>
        <mat-cell *matCellDef="let t">{{ t.createdAt | date:'dd/MM/yyyy' }}</mat-cell>
      </ng-container>

      <mat-header-row *matHeaderRowDef="cols" />
      <mat-row *matRowDef="let row; columns: cols;" />
      <tr class="mat-row" *matNoDataRow>
        <td class="mat-cell empty-row" [attr.colspan]="cols.length">Aucun ticket trouvé.</td>
      </tr>
    </mat-table>
  `,
  styles: [`
    .page-header { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
    .page-title  { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; font-weight: 500;
                   mat-icon { color: #3f51b5; } }
    .filters     { margin-left: auto; }
    .table       { width: 100%; }
    mat-header-cell, mat-cell { padding: 8px 12px; font-size: 13px; }
    .empty-row   { text-align: center; padding: 32px; color: #888; }
  `],
})
export class PortailTicketsComponent {
  private http = inject(HttpClient);

  readonly statusCtrl = new FormControl('');
  readonly cols = ['reference', 'title', 'status', 'date'];

  readonly tickets = resource<Ticket[], string>({
    params: () => this.statusCtrl.value ?? '',
    loader: ({ params: status }) => {
      let p = new HttpParams();
      if (status) p = p.set('status', status);
      return firstValueFrom(this.http.get<Ticket[]>('/api/espace/tickets', { params: p }));
    },
  });

  statusColor(s: string): 'primary' | 'accent' | 'warn' | '' {
    return STATUS_COLOR[s] ?? '';
  }
}
