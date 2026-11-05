import { Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormsModule }        from '@angular/forms';
import { MatButtonModule }    from '@angular/material/button';
import { MatIconModule }      from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatCheckboxModule }  from '@angular/material/checkbox';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { MatDividerModule }   from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { HttpClient } from '@angular/common/http';

interface NetworkType { name: string; label: string; icon: string; maxChars: number; }
interface SocialConfig {
  id: number; name: string; networkType: string; networkLabel: string; networkIcon: string;
  clientId: string | null; pageId: string | null; accountHandle: string | null;
  active: boolean; connected: boolean; tokenExpired: boolean;
}

@Component({
  selector: 'app-admin-social-config',
  standalone: true,
  imports: [
    FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatTooltipModule, MatDividerModule, MatSnackBarModule,
  ],
  template: `
    <div class="wrap">
      <div class="toolbar">
        <h2><mat-icon>share</mat-icon> Réseaux sociaux — Configuration</h2>
        <button mat-flat-button color="primary" (click)="openForm()">
          <mat-icon>add</mat-icon> Ajouter un réseau
        </button>
      </div>

      <!-- Grille des configs -->
      @if (!loading() && configs().length === 0 && !showForm()) {
        <div class="empty-card">
          <mat-icon>share</mat-icon>
          <p>Aucun réseau configuré. Ajoutez vos comptes LinkedIn, Facebook, Instagram ou X.</p>
          <button mat-flat-button color="primary" (click)="openForm()">
            <mat-icon>add</mat-icon> Ajouter un réseau
          </button>
        </div>
      }

      <div class="grid">
        @for (cfg of configs(); track cfg.id) {
          <div class="cfg-card" [class.inactive]="!cfg.active">
            <div class="cfg-header">
              <mat-icon class="net-icon">{{ matIcon(cfg.networkType) }}</mat-icon>
              <div class="cfg-meta">
                <span class="cfg-name">{{ cfg.name }}</span>
                <span class="cfg-type">{{ cfg.networkLabel }}</span>
              </div>
              <span class="conn-badge" [class.connected]="cfg.connected && !cfg.tokenExpired"
                    [class.expired]="cfg.tokenExpired">
                {{ cfg.tokenExpired ? 'Token expiré' : cfg.connected ? 'Connecté' : 'Non connecté' }}
              </span>
            </div>

            <div class="cfg-details">
              @if (cfg.accountHandle) { <span><mat-icon>alternate_email</mat-icon> {{ cfg.accountHandle }}</span> }
              @if (cfg.pageId) { <span><mat-icon>tag</mat-icon> {{ cfg.pageId }}</span> }
              @if (!cfg.active) { <span class="inactive-hint"><mat-icon>pause</mat-icon> Désactivé</span> }
            </div>

            <div class="cfg-actions">
              <button mat-stroked-button (click)="openForm(cfg)">
                <mat-icon>edit</mat-icon> Modifier
              </button>
              <button mat-stroked-button color="primary" (click)="initiateOAuth(cfg)"
                      [disabled]="!cfg.clientId" matTooltip="Autoriser via OAuth">
                <mat-icon>key</mat-icon> Connecter
              </button>
              <button mat-icon-button color="warn" (click)="deleteConfig(cfg)" matTooltip="Supprimer">
                <mat-icon>delete</mat-icon>
              </button>
            </div>
          </div>
        }
      </div>

      <!-- Formulaire inline -->
      @if (showForm()) {
        <div class="form-panel">
          <h3>{{ editId() ? 'Modifier la configuration' : 'Nouveau réseau social' }}</h3>

          @if (!editId()) {
            <mat-form-field class="full">
              <mat-label>Réseau *</mat-label>
              <mat-select [(ngModel)]="form.networkType" name="networkType">
                <mat-option value="">— Choisir —</mat-option>
                @for (t of networkTypes(); track t.name) {
                  <mat-option [value]="t.name">{{ t.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          } @else {
            <div class="readonly-field">
              <mat-icon>{{ matIcon(form.networkType) }}</mat-icon>
              <span>{{ networkLabel(form.networkType) }}</span>
            </div>
          }

          <mat-form-field class="full">
            <mat-label>Nom *</mat-label>
            <input matInput [(ngModel)]="form.name" placeholder="ex : Page Facebook Entreprise">
          </mat-form-field>

          <div class="form-row">
            <mat-form-field class="full">
              <mat-label>Client ID</mat-label>
              <input matInput [(ngModel)]="form.clientId" placeholder="Laisser vide pour plus tard">
            </mat-form-field>
            <mat-form-field class="full">
              <mat-label>Client Secret</mat-label>
              <input matInput type="password" [(ngModel)]="form.clientSecret"
                     placeholder="{{ editId() ? 'Vide = inchangé' : 'Laisser vide pour plus tard' }}">
            </mat-form-field>
          </div>

          <mat-form-field class="full">
            <mat-label>Page ID / Business Account ID</mat-label>
            <input matInput [(ngModel)]="form.pageId" placeholder="Requis pour Facebook et Instagram">
          </mat-form-field>

          <mat-form-field class="full">
            <mat-label>Handle (compte)</mat-label>
            <input matInput [(ngModel)]="form.accountHandle" placeholder="&#64;NomDuCompte">
          </mat-form-field>

          @if (editId()) {
            <mat-checkbox [(ngModel)]="form.active">Actif</mat-checkbox>
          }

          <div class="form-actions">
            <button mat-button (click)="cancelForm()">Annuler</button>
            <button mat-flat-button color="primary" (click)="saveForm()"
                    [disabled]="!form.name || (!editId() && !form.networkType)">
              Enregistrer
            </button>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .wrap { max-width: 1000px; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px;
               h2 { display: flex; align-items: center; gap: 8px; margin: 0; } }

    .empty-card {
      text-align: center; padding: 48px 24px; border: 2px dashed #e0e0e0; border-radius: 12px;
      mat-icon { font-size: 48px; width: 48px; height: 48px; color: #bbb; }
      p { color: #888; margin: 12px 0 20px; }
    }

    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; margin-bottom: 24px; }

    .cfg-card {
      background: #fff; border: 1px solid #e0e0e0; border-radius: 10px; padding: 16px;
      display: flex; flex-direction: column; gap: 10px;
      &.inactive { opacity: .7; }
    }
    .cfg-header { display: flex; align-items: center; gap: 10px; }
    .net-icon { font-size: 28px; width: 28px; height: 28px; color: #3f51b5; flex-shrink: 0; }
    .cfg-meta { flex: 1; display: flex; flex-direction: column; }
    .cfg-name { font-weight: 600; font-size: 14px; }
    .cfg-type { font-size: 12px; color: #888; }

    .conn-badge {
      font-size: 11px; padding: 2px 8px; border-radius: 10px;
      background: #e0e0e0; color: #555;
      &.connected { background: #d4edda; color: #155724; }
      &.expired   { background: #f8d7da; color: #721c24; }
    }

    .cfg-details {
      display: flex; flex-direction: column; gap: 2px;
      font-size: 12px; color: #888;
      span { display: flex; align-items: center; gap: 4px;
             mat-icon { font-size: 13px; width: 13px; height: 13px; } }
      .inactive-hint { color: #e17055; }
    }

    .cfg-actions { display: flex; gap: 6px; flex-wrap: wrap; }

    .form-panel {
      background: #f8f9fa; border: 1px solid #e0e0e0; border-radius: 10px; padding: 24px;
      max-width: 640px;
      h3 { margin: 0 0 20px; font-size: 16px; }
    }
    .full { width: 100%; }
    .form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
    .readonly-field {
      display: flex; align-items: center; gap: 8px;
      font-size: 14px; font-weight: 500; color: #333;
      background: #f0f0f0; border-radius: 6px; padding: 10px 14px; margin-bottom: 16px;
      mat-icon { color: #3f51b5; }
    }
    .form-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
  `],
})
export class AdminSocialConfigComponent implements OnInit {
  private readonly http  = inject(HttpClient);
  private readonly snack = inject(MatSnackBar);
  private readonly route = inject(ActivatedRoute);

  readonly configs      = signal<SocialConfig[]>([]);
  readonly networkTypes = signal<NetworkType[]>([]);
  readonly loading      = signal(true);
  readonly showForm     = signal(false);
  readonly editId       = signal<number | null>(null);

  form = this.emptyForm();

  ngOnInit() {
    this.load();
    this.http.get<NetworkType[]>('/api/intra/social/admin/network-types')
      .subscribe(t => this.networkTypes.set(t));

    // Résultat OAuth retour
    this.route.queryParams.subscribe(p => {
      if (p['oauthResult'] === 'success') {
        this.snack.open((p['network'] || 'Réseau') + ' connecté avec succès !', '', { duration: 4000 });
      } else if (p['oauthResult'] === 'error') {
        this.snack.open('Erreur OAuth : ' + (p['msg'] || ''), 'Fermer', { duration: 6000 });
      }
    });
  }

  load() {
    this.loading.set(true);
    this.http.get<SocialConfig[]>('/api/intra/social/admin/configs').subscribe({
      next: c => { this.configs.set(c); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openForm(cfg?: SocialConfig) {
    this.editId.set(cfg?.id ?? null);
    this.form = cfg ? {
      networkType: cfg.networkType, name: cfg.name,
      clientId: cfg.clientId ?? '', clientSecret: '',
      pageId: cfg.pageId ?? '', accountHandle: cfg.accountHandle ?? '',
      active: cfg.active,
    } : this.emptyForm();
    this.showForm.set(true);
    setTimeout(() => document.querySelector('.form-panel')?.scrollIntoView({ behavior: 'smooth' }), 50);
  }

  cancelForm() { this.showForm.set(false); this.editId.set(null); }

  saveForm() {
    const id = this.editId();
    const body: Record<string, string> = {
      networkType: this.form.networkType, name: this.form.name,
      clientId: this.form.clientId, clientSecret: this.form.clientSecret,
      pageId: this.form.pageId, accountHandle: this.form.accountHandle,
      active: String(this.form.active),
    };
    const obs = id
      ? this.http.put<SocialConfig>(`/api/intra/social/admin/configs/${id}`, body)
      : this.http.post<SocialConfig>('/api/intra/social/admin/configs', body);
    obs.subscribe({
      next: () => { this.cancelForm(); this.load(); this.snack.open('Enregistré', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  deleteConfig(cfg: SocialConfig) {
    if (!confirm(`Supprimer la configuration "${cfg.name}" ?`)) return;
    this.http.delete(`/api/intra/social/admin/configs/${cfg.id}`).subscribe({
      next: () => { this.configs.update(l => l.filter(c => c.id !== cfg.id)); this.snack.open('Supprimé', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  initiateOAuth(cfg: SocialConfig) {
    this.http.get<{ url: string }>(`/api/intra/social/admin/configs/${cfg.id}/auth-url`).subscribe({
      next: res => window.location.href = res.url,
      error: () => this.snack.open('Impossible de générer l\'URL OAuth', 'Fermer', { duration: 4000 }),
    });
  }

  networkLabel(type: string) { return this.networkTypes().find(t => t.name === type)?.label ?? type; }
  matIcon(type: string): string {
    const map: Record<string, string> = {
      LINKEDIN: 'business', LINKEDIN_PAGE: 'business',
      FACEBOOK: 'thumb_up', INSTAGRAM: 'photo_camera', X: 'chat',
    };
    return map[type] ?? 'share';
  }

  private emptyForm() {
    return { networkType: '', name: '', clientId: '', clientSecret: '', pageId: '', accountHandle: '', active: true };
  }
}
