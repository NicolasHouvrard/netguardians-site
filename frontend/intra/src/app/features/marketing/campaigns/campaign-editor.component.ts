import { Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule }        from '@angular/forms';
import { MatButtonModule }    from '@angular/material/button';
import { MatIconModule }      from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { MatDividerModule }   from '@angular/material/divider';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { CampaignService, CampaignDto } from '../services/campaign.service';

@Component({
  selector: 'app-campaign-editor',
  standalone: true,
  imports: [
    FormsModule, RouterLink,
    MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTooltipModule, MatDividerModule,
    MatProgressBarModule, MatSnackBarModule,
  ],
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else if (campaign()) {
      <div class="editor-wrap">

        <!-- Top bar -->
        <div class="topbar">
          <a mat-icon-button routerLink="/marketing/campaigns" matTooltip="Retour">
            <mat-icon>arrow_back</mat-icon>
          </a>
          <input class="name-input" [(ngModel)]="campaign()!.name" placeholder="Nom de la campagne">
          <div class="badge" [class]="statusClass(campaign()!.status)">
            {{ statusLabel(campaign()!.status) }}
          </div>
          <span class="spacer"></span>
          <mat-form-field class="status-select" subscriptSizing="dynamic">
            <mat-label>Statut</mat-label>
            <mat-select [(ngModel)]="campaign()!.status" (ngModelChange)="changeStatus($event)">
              <mat-option value="DRAFT">Brouillon</mat-option>
              <mat-option value="SCHEDULED">Planifiée</mat-option>
              <mat-option value="SENT">Envoyée</mat-option>
              <mat-option value="FAILED">Échec</mat-option>
            </mat-select>
          </mat-form-field>
          <button mat-flat-button color="primary" (click)="save()" [disabled]="saving()">
            <mat-icon>save</mat-icon> {{ saving() ? 'Enregistrement…' : 'Enregistrer' }}
          </button>
        </div>

        <div class="editor-body">

          <!-- Colonne gauche : méta -->
          <aside class="meta-panel">
            <h3>Paramètres</h3>
            <mat-form-field class="full">
              <mat-label>Objet de l'e-mail</mat-label>
              <input matInput [(ngModel)]="campaign()!.subject" placeholder="Votre objet d'e-mail…">
            </mat-form-field>

            <mat-divider />
            <h3 class="mt">Statistiques</h3>
            <div class="stat-row">
              <mat-icon>group</mat-icon>
              <span>{{ campaign()!.recipientCount }} destinataire(s)</span>
            </div>
            <div class="stat-row">
              <mat-icon>mail_open</mat-icon>
              <span>{{ campaign()!.openCount }} ouverture(s)
                @if (campaign()!.recipientCount > 0) {
                  ({{ pct(campaign()!.openCount, campaign()!.recipientCount) }}%)
                }
              </span>
            </div>
            <div class="stat-row">
              <mat-icon>ads_click</mat-icon>
              <span>{{ campaign()!.clickCount }} clic(s)
                @if (campaign()!.recipientCount > 0) {
                  ({{ pct(campaign()!.clickCount, campaign()!.recipientCount) }}%)
                }
              </span>
            </div>
            @if (campaign()!.createdByName) {
              <mat-divider />
              <p class="meta-info">Créée par {{ campaign()!.createdByName }}</p>
            }
            @if (campaign()!.sentAt) {
              <p class="meta-info">Envoyée le {{ formatDate(campaign()!.sentAt) }}</p>
            }
          </aside>

          <!-- Zone centrale : éditeur HTML -->
          <main class="html-editor">
            <div class="editor-label">
              <mat-icon>code</mat-icon> Contenu HTML de l'e-mail
              <span class="hint">Éditeur HTML — utilisez les balises standard pour la mise en forme</span>
            </div>
            <textarea class="html-textarea"
                      [(ngModel)]="campaign()!.htmlContent"
                      placeholder="&lt;h1&gt;Titre de votre campagne&lt;/h1&gt;&#10;&lt;p&gt;Votre contenu…&lt;/p&gt;"
                      spellcheck="false"></textarea>
          </main>

          <!-- Colonne droite : aperçu -->
          <aside class="preview-panel">
            <div class="editor-label"><mat-icon>preview</mat-icon> Aperçu</div>
            <div class="preview-frame" [innerHTML]="campaign()!.htmlContent || ''"></div>
          </aside>

        </div>
      </div>
    }
  `,
  styles: [`
    .editor-wrap { display: flex; flex-direction: column; height: 100%; overflow: hidden; }

    .topbar {
      display: flex; align-items: center; gap: 8px; padding: 8px 16px;
      border-bottom: 1px solid #e0e0e0; background: #fff; flex-shrink: 0;
    }
    .name-input {
      flex: 1; font-size: 17px; font-weight: 500; border: none; outline: none;
      padding: 4px 8px; border-radius: 4px;
      &:focus { background: #f5f5f5; }
    }
    .status-select { width: 140px; }
    .spacer { flex: 1; }

    .badge {
      font-size: 11px; padding: 3px 10px; border-radius: 10px;
      &.draft     { background: #e0e0e0; color: #555; }
      &.scheduled { background: #fff3cd; color: #856404; }
      &.sent      { background: #d4edda; color: #155724; }
      &.failed    { background: #f8d7da; color: #721c24; }
    }

    .editor-body { display: flex; flex: 1; overflow: hidden; }

    .meta-panel {
      width: 220px; flex-shrink: 0; border-right: 1px solid #e0e0e0;
      padding: 16px; overflow-y: auto; background: #fafafa;
      h3 { font-size: 12px; font-weight: 600; color: #888; text-transform: uppercase;
           letter-spacing: .5px; margin: 0 0 12px; }
      .mt { margin-top: 16px; }
    }
    .full { width: 100%; }
    .stat-row { display: flex; align-items: center; gap: 8px; font-size: 13px; margin-bottom: 8px;
                mat-icon { font-size: 16px; width: 16px; height: 16px; color: #888; } }
    .meta-info { font-size: 12px; color: #aaa; margin-top: 8px; }

    .html-editor {
      flex: 1; display: flex; flex-direction: column; border-right: 1px solid #e0e0e0;
    }
    .editor-label {
      display: flex; align-items: center; gap: 6px; padding: 8px 12px;
      font-size: 12px; font-weight: 600; color: #888; text-transform: uppercase;
      border-bottom: 1px solid #e0e0e0; background: #fafafa;
      mat-icon { font-size: 15px; width: 15px; height: 15px; }
      .hint { font-weight: 400; text-transform: none; margin-left: 4px; color: #bbb; }
    }
    .html-textarea {
      flex: 1; resize: none; border: none; outline: none; padding: 16px;
      font-family: 'Courier New', monospace; font-size: 13px; line-height: 1.6;
      background: #1e1e1e; color: #d4d4d4;
    }

    .preview-panel {
      width: 320px; flex-shrink: 0; display: flex; flex-direction: column;
      overflow: hidden;
    }
    .preview-frame {
      flex: 1; overflow-y: auto; padding: 16px; background: #fff;
      font-size: 14px; line-height: 1.6;
    }
  `],
})
export class CampaignEditorComponent implements OnInit {
  private readonly route           = inject(ActivatedRoute);
  private readonly router          = inject(Router);
  private readonly campaignService = inject(CampaignService);
  private readonly snack           = inject(MatSnackBar);

  readonly campaign = signal<CampaignDto | null>(null);
  readonly loading  = signal(true);
  readonly saving   = signal(false);

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.campaignService.get(id).subscribe({
      next: c => { this.campaign.set(c); this.loading.set(false); },
      error: () => { this.loading.set(false); this.router.navigate(['/marketing/campaigns']); },
    });
  }

  save() {
    const c = this.campaign();
    if (!c) return;
    this.saving.set(true);
    this.campaignService.update(c.id, { name: c.name, subject: c.subject ?? '', htmlContent: c.htmlContent ?? '' })
      .subscribe({
        next: saved => { this.campaign.set(saved); this.saving.set(false); this.snack.open('Enregistré', '', { duration: 2000 }); },
        error: () => { this.saving.set(false); this.snack.open('Erreur', 'Fermer', { duration: 4000 }); },
      });
  }

  changeStatus(status: string) {
    const c = this.campaign();
    if (!c) return;
    this.campaignService.updateStatus(c.id, status).subscribe({
      next: saved => this.campaign.set(saved),
      error: () => this.snack.open('Erreur statut', 'Fermer', { duration: 4000 }),
    });
  }

  statusClass(s: string) { return s.toLowerCase(); }
  statusLabel(s: string) {
    return { DRAFT: 'Brouillon', SCHEDULED: 'Planifiée', SENT: 'Envoyée', FAILED: 'Échec' }[s] ?? s;
  }
  pct(n: number, t: number) { return t > 0 ? Math.round(n / t * 100) : 0; }
  formatDate(d: string | null) {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }
}
