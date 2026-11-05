import { Component, inject, signal, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule }        from '@angular/forms';
import { MatButtonModule }    from '@angular/material/button';
import { MatIconModule }      from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { CampaignService, CampaignDto, CampaignStatsDto } from '../services/campaign.service';

@Component({
  selector: 'app-marketing-campaigns',
  standalone: true,
  imports: [
    FormsModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatTooltipModule, MatSnackBarModule,
  ],
  template: `
    <div class="camp-wrap">
      <div class="toolbar">
        <h2><mat-icon>campaign</mat-icon> Campagnes e-mail</h2>
        <button mat-flat-button color="primary" (click)="showNew = !showNew">
          <mat-icon>add</mat-icon> Nouvelle campagne
        </button>
      </div>

      <!-- KPI bar -->
      @if (stats()) {
        <div class="kpi-bar">
          <div class="kpi">
            <span class="kpi-num">{{ stats()!.draft }}</span>
            <span class="kpi-lbl">Brouillons</span>
          </div>
          <div class="kpi scheduled">
            <span class="kpi-num">{{ stats()!.scheduled }}</span>
            <span class="kpi-lbl">Planifiées</span>
          </div>
          <div class="kpi sent">
            <span class="kpi-num">{{ stats()!.sent }}</span>
            <span class="kpi-lbl">Envoyées</span>
          </div>
          <div class="kpi failed">
            <span class="kpi-num">{{ stats()!.failed }}</span>
            <span class="kpi-lbl">Échecs</span>
          </div>
        </div>
      }

      <!-- Formulaire nouvelle campagne -->
      @if (showNew) {
        <div class="new-panel">
          <mat-form-field>
            <mat-label>Nom de la campagne *</mat-label>
            <input matInput [(ngModel)]="newForm.name" (keyup.enter)="createCampaign()">
          </mat-form-field>
          <mat-form-field>
            <mat-label>Objet de l'e-mail</mat-label>
            <input matInput [(ngModel)]="newForm.subject">
          </mat-form-field>
          <div class="new-actions">
            <button mat-button (click)="showNew = false">Annuler</button>
            <button mat-flat-button color="primary" (click)="createCampaign()" [disabled]="!newForm.name.trim()">
              Créer
            </button>
          </div>
        </div>
      }

      <!-- Tableau -->
      <table class="camp-table">
        <thead>
          <tr>
            <th>Nom</th>
            <th>Objet</th>
            <th>Statut</th>
            <th>Destinataires</th>
            <th>Ouvertures</th>
            <th>Clics</th>
            <th>Créée le</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (c of campaigns(); track c.id) {
            <tr>
              <td class="col-name" (click)="openEditor(c.id)" title="Éditer">{{ c.name }}</td>
              <td class="col-subject">{{ c.subject || '—' }}</td>
              <td>
                <span class="badge" [class]="statusClass(c.status)">
                  {{ statusLabel(c.status) }}
                </span>
              </td>
              <td class="col-num">{{ c.recipientCount }}</td>
              <td class="col-num">
                {{ c.openCount }}
                @if (c.recipientCount > 0) {
                  <span class="pct">({{ pct(c.openCount, c.recipientCount) }}%)</span>
                }
              </td>
              <td class="col-num">
                {{ c.clickCount }}
                @if (c.recipientCount > 0) {
                  <span class="pct">({{ pct(c.clickCount, c.recipientCount) }}%)</span>
                }
              </td>
              <td class="col-date">{{ formatDate(c.createdAt) }}</td>
              <td class="col-actions">
                <button mat-icon-button (click)="openEditor(c.id)" matTooltip="Éditer">
                  <mat-icon>edit</mat-icon>
                </button>
                <button mat-icon-button color="warn" (click)="deleteCampaign(c)" matTooltip="Supprimer">
                  <mat-icon>delete</mat-icon>
                </button>
              </td>
            </tr>
          }
          @if (!loading() && campaigns().length === 0) {
            <tr><td colspan="8" class="empty">Aucune campagne. Cliquez sur "Nouvelle campagne".</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .camp-wrap { max-width: 1100px; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px;
               h2 { display: flex; align-items: center; gap: 8px; margin: 0; } }

    .kpi-bar { display: flex; gap: 12px; margin-bottom: 20px; }
    .kpi {
      flex: 1; background: #f8f9fa; border: 1px solid #e0e0e0; border-radius: 8px;
      padding: 12px 16px; display: flex; flex-direction: column; align-items: center;
      &.scheduled { background: #fff8e1; border-color: #ffe082; }
      &.sent      { background: #e8f5e9; border-color: #a5d6a7; }
      &.failed    { background: #fce4ec; border-color: #f48fb1; }
    }
    .kpi-num { font-size: 28px; font-weight: 700; line-height: 1; }
    .kpi-lbl { font-size: 11px; color: #888; margin-top: 4px; text-transform: uppercase; letter-spacing: .5px; }

    .new-panel {
      display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
      background: #f8f9fa; border: 1px solid #e0e0e0; border-radius: 8px;
      padding: 16px; margin-bottom: 20px;
      mat-form-field { flex: 1; min-width: 220px; }
    }
    .new-actions { display: flex; gap: 8px; }

    .camp-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    th { text-align: left; font-size: 11px; font-weight: 600; color: #888; text-transform: uppercase;
         padding: 8px 10px; border-bottom: 2px solid #e0e0e0; }
    td { padding: 10px 10px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }
    .col-name { font-weight: 500; cursor: pointer; color: #3f51b5; &:hover { text-decoration: underline; } }
    .col-subject { color: #555; max-width: 220px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .col-num  { text-align: center; }
    .col-date { font-size: 12px; color: #888; white-space: nowrap; }
    .col-actions { display: flex; gap: 0; white-space: nowrap; }
    .pct { font-size: 11px; color: #aaa; margin-left: 2px; }
    .empty { text-align: center; color: #aaa; padding: 32px; }

    .badge {
      display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: 10px;
      &.draft     { background: #e0e0e0; color: #555; }
      &.scheduled { background: #fff3cd; color: #856404; }
      &.sent      { background: #d4edda; color: #155724; }
      &.failed    { background: #f8d7da; color: #721c24; }
    }
  `],
})
export class MarketingCampaignsComponent implements OnInit {
  private readonly campaignService = inject(CampaignService);
  private readonly router          = inject(Router);
  private readonly snack           = inject(MatSnackBar);

  readonly campaigns = signal<CampaignDto[]>([]);
  readonly stats     = signal<CampaignStatsDto | null>(null);
  readonly loading   = signal(true);

  showNew = false;
  newForm = { name: '', subject: '' };

  ngOnInit() { this.load(); }

  load() {
    this.loading.set(true);
    this.campaignService.list().subscribe({
      next: list => { this.campaigns.set(list); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
    this.campaignService.stats().subscribe(s => this.stats.set(s));
  }

  createCampaign() {
    if (!this.newForm.name.trim()) return;
    this.campaignService.create({ name: this.newForm.name.trim(), subject: this.newForm.subject })
      .subscribe({
        next: c => {
          this.showNew = false;
          this.newForm = { name: '', subject: '' };
          this.router.navigate(['/marketing/campaigns', c.id]);
        },
        error: () => this.snack.open('Erreur création', 'Fermer', { duration: 4000 }),
      });
  }

  openEditor(id: number) { this.router.navigate(['/marketing/campaigns', id]); }

  deleteCampaign(c: CampaignDto) {
    if (!confirm(`Supprimer "${c.name}" ?`)) return;
    this.campaignService.delete(c.id).subscribe({
      next: () => { this.campaigns.update(l => l.filter(x => x.id !== c.id)); this.load(); },
      error: () => this.snack.open('Erreur suppression', 'Fermer', { duration: 4000 }),
    });
  }

  statusClass(s: string) { return s.toLowerCase(); }
  statusLabel(s: string) {
    return { DRAFT: 'Brouillon', SCHEDULED: 'Planifiée', SENT: 'Envoyée', FAILED: 'Échec' }[s] ?? s;
  }
  pct(n: number, total: number) { return total > 0 ? Math.round(n / total * 100) : 0; }
  formatDate(d: string | null) {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }
}
