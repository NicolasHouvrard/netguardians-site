import { Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule }        from '@angular/forms';
import { MatButtonModule }    from '@angular/material/button';
import { MatIconModule }      from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatCheckboxModule }  from '@angular/material/checkbox';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { MatDividerModule }   from '@angular/material/divider';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { SocialPostService, SocialPostDto, SocialConfigDto } from '../services/social-post.service';

@Component({
  selector: 'app-social-post-editor',
  standalone: true,
  imports: [
    FormsModule, RouterLink,
    MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatTooltipModule, MatDividerModule,
    MatProgressBarModule, MatSnackBarModule,
  ],
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else if (isNew || post()) {

      <div class="editor-wrap">
        <!-- Top bar -->
        <div class="topbar">
          <a mat-icon-button routerLink="/marketing/social" matTooltip="Retour">
            <mat-icon>arrow_back</mat-icon>
          </a>
          <span class="topbar-title">
            {{ isNew ? 'Nouvelle publication' : post()!.title }}
          </span>
          @if (!isNew && post()) {
            <span class="badge" [class]="statusClass(post()!.status)">{{ post()!.statusLabel }}</span>
          }
          <span class="spacer"></span>
          @if (!isNew && post() && canPublish(post()!.status)) {
            <button mat-stroked-button color="accent" (click)="publish()" [disabled]="saving()">
              <mat-icon>send</mat-icon> Publier maintenant
            </button>
          }
          @if (!isNew && post()?.status === 'SCHEDULED') {
            <button mat-stroked-button (click)="cancelSchedule()" [disabled]="saving()">
              <mat-icon>cancel</mat-icon> Annuler planification
            </button>
          }
          @if (isNew) {
            <button mat-flat-button color="primary" (click)="create()" [disabled]="saving() || !form.title || !form.content">
              <mat-icon>save</mat-icon> {{ saving() ? 'Enregistrement…' : 'Enregistrer' }}
            </button>
          }
        </div>

        @if (saving()) { <mat-progress-bar mode="indeterminate" /> }

        <div class="editor-body">

          <!-- Colonne principale -->
          <main class="main-col">

            @if (configs().length === 0 && isNew) {
              <div class="warn-box">
                <mat-icon>warning</mat-icon>
                Aucun réseau configuré. Configurez d'abord un réseau dans les paramètres.
              </div>
            }

            <mat-form-field class="full">
              <mat-label>Titre interne *</mat-label>
              <input matInput [(ngModel)]="form.title" [disabled]="!isNew" placeholder="Référence interne du post">
            </mat-form-field>

            <div class="textarea-wrap">
              <mat-form-field class="full">
                <mat-label>Contenu *</mat-label>
                <textarea matInput [(ngModel)]="form.content" [disabled]="!isNew"
                          rows="8" placeholder="Texte de votre publication…"
                          (input)="updateCounters()"></textarea>
              </mat-form-field>
              <div class="counters">
                <span [class.over]="form.content.length > 3000">LinkedIn {{ form.content.length }}/3000</span>
                <span [class.over]="form.content.length > 2200">Instagram {{ form.content.length }}/2200</span>
                <span [class.over]="form.content.length > 63206">Facebook {{ form.content.length }}/63206</span>
              </div>
            </div>

            @if (isNew || hasX()) {
              <mat-form-field class="full">
                <mat-label>Contenu X (280 car. max)</mat-label>
                <textarea matInput [(ngModel)]="form.xContent" [disabled]="!isNew"
                          rows="3" maxlength="280"
                          placeholder="Version courte pour X — si vide, le contenu sera tronqué"
                          (input)="updateCounters()"></textarea>
                <mat-hint align="end">{{ form.xContent.length }}/280</mat-hint>
              </mat-form-field>
            }

            @if (isNew) {
              <div class="file-row">
                <mat-icon>image</mat-icon>
                <span>Image <small class="hint">— Obligatoire pour Instagram</small></span>
                <input type="file" #fileInput accept="image/*" (change)="onFileChange($event)" style="display:none">
                <button mat-stroked-button (click)="fileInput.click()">
                  {{ imageFile ? imageFile.name : 'Choisir une image' }}
                </button>
              </div>
            }

            @if (!isNew && post()?.imagePath) {
              <div class="image-preview">
                <img [src]="'/uploads/' + post()!.imagePath" alt="Image du post">
              </div>
            }

          </main>

          <!-- Colonne latérale -->
          <aside class="side-col">

            @if (isNew) {
              <div class="side-card">
                <h3><mat-icon>share</mat-icon> Publier sur</h3>
                @if (configs().length === 0) {
                  <p class="empty-hint">Aucun réseau configuré.</p>
                }
                @for (cfg of configs(); track cfg.id) {
                  <div class="network-row">
                    <mat-checkbox [(ngModel)]="selectedConfigs[cfg.id]" [disabled]="!cfg.connected">
                      <span class="net-name">{{ cfg.name }}</span>
                      @if (!cfg.connected) {
                        <span class="not-connected">Non connecté</span>
                      }
                    </mat-checkbox>
                    <span class="chars-hint">{{ cfg.maxChars.toLocaleString() }} car.</span>
                  </div>
                }
              </div>
            }

            @if (isNew) {
              <div class="side-card">
                <h3><mat-icon>schedule</mat-icon> Planification</h3>
                <mat-form-field class="full">
                  <mat-label>Date et heure</mat-label>
                  <input matInput type="datetime-local" [(ngModel)]="form.scheduledAt">
                  <mat-hint>Vide = brouillon</mat-hint>
                </mat-form-field>
              </div>
            }

            @if (!isNew && post()) {
              <div class="side-card">
                <h3><mat-icon>share</mat-icon> Cibles de publication</h3>
                @for (t of post()!.targets; track t.id) {
                  <div class="target-row">
                    <mat-icon class="net-icon">{{ networkIcon(t.network) }}</mat-icon>
                    <div class="target-info">
                      <span class="target-label">{{ t.label }}</span>
                      <span class="badge sm" [class]="t.status.toLowerCase()">{{ t.statusLabel }}</span>
                    </div>
                    @if (t.publishedAt) {
                      <span class="target-date">{{ formatDate(t.publishedAt) }}</span>
                    }
                  </div>
                  @if (t.error) {
                    <div class="target-error">{{ t.error }}</div>
                  }
                }
                @if (post()!.targets.length === 0) {
                  <p class="empty-hint">Aucun réseau cible.</p>
                }
              </div>

              <div class="side-card meta">
                @if (post()!.scheduledAt) {
                  <div class="meta-row"><mat-icon>schedule</mat-icon> Planifié le {{ formatDate(post()!.scheduledAt) }}</div>
                }
                @if (post()!.publishedAt) {
                  <div class="meta-row"><mat-icon>check_circle</mat-icon> Publié le {{ formatDate(post()!.publishedAt) }}</div>
                }
                <div class="meta-row"><mat-icon>calendar_today</mat-icon> Créé le {{ formatDate(post()!.createdAt) }}</div>
              </div>
            }

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
    .topbar-title { font-size: 16px; font-weight: 500; }
    .spacer { flex: 1; }

    .badge {
      font-size: 11px; padding: 3px 10px; border-radius: 10px;
      &.draft      { background: #e0e0e0; color: #555; }
      &.scheduled  { background: #fff3cd; color: #856404; }
      &.publishing { background: #fff3cd; color: #856404; }
      &.published  { background: #d4edda; color: #155724; }
      &.partial    { background: #fff3cd; color: #856404; }
      &.failed     { background: #f8d7da; color: #721c24; }
      &.sm { font-size: 10px; padding: 1px 6px; }
      &.pending { background: #e0e0e0; color: #555; }
      &.skipped { background: #fff3cd; color: #856404; }
    }

    .editor-body { display: flex; flex: 1; overflow: hidden; gap: 0; }

    .main-col {
      flex: 1; overflow-y: auto; padding: 20px;
      display: flex; flex-direction: column; gap: 12px;
    }
    .full { width: 100%; }
    .textarea-wrap { display: flex; flex-direction: column; }
    .counters { display: flex; gap: 16px; font-size: 11px; color: #aaa; margin-top: -8px; margin-bottom: 4px;
                .over { color: #e53935; font-weight: 600; } }

    .file-row {
      display: flex; align-items: center; gap: 8px; padding: 8px 0;
      mat-icon { color: #888; }
      .hint { color: #aaa; }
    }

    .image-preview { img { max-width: 100%; max-height: 300px; border-radius: 6px; border: 1px solid #e0e0e0; } }

    .warn-box {
      display: flex; align-items: center; gap: 8px;
      background: #fff8e1; border: 1px solid #ffe082; border-radius: 6px;
      padding: 10px 14px; font-size: 13px; color: #856404;
      mat-icon { font-size: 18px; width: 18px; height: 18px; }
    }

    .side-col {
      width: 280px; flex-shrink: 0; border-left: 1px solid #e0e0e0;
      overflow-y: auto; background: #fafafa;
    }
    .side-card {
      padding: 16px; border-bottom: 1px solid #e0e0e0;
      h3 { font-size: 12px; font-weight: 600; color: #888; text-transform: uppercase;
           letter-spacing: .5px; margin: 0 0 12px; display: flex; align-items: center; gap: 6px;
           mat-icon { font-size: 15px; width: 15px; height: 15px; } }
    }
    .network-row {
      display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px;
      .net-name { font-size: 13px; }
      .not-connected { font-size: 10px; color: #e17055; background: #ffeaa7; padding: 1px 5px; border-radius: 8px; margin-left: 4px; }
      .chars-hint { font-size: 11px; color: #aaa; }
    }

    .target-row {
      display: flex; align-items: center; gap: 8px; margin-bottom: 8px;
      .net-icon { font-size: 18px; width: 18px; height: 18px; color: #555; flex-shrink: 0; }
      .target-info { flex: 1; display: flex; flex-direction: column; gap: 2px; }
      .target-label { font-size: 13px; }
      .target-date { font-size: 11px; color: #aaa; }
    }
    .target-error { font-size: 11px; color: #e53935; background: #fce4ec; border-radius: 4px; padding: 4px 8px; margin: -4px 0 8px 26px; }

    .meta { .meta-row { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #888; margin-bottom: 6px;
                        mat-icon { font-size: 15px; width: 15px; height: 15px; } } }
    .empty-hint { font-size: 12px; color: #aaa; margin: 0; }
  `],
})
export class SocialPostEditorComponent implements OnInit {
  private readonly route  = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly svc    = inject(SocialPostService);
  private readonly snack  = inject(MatSnackBar);

  readonly post    = signal<SocialPostDto | null>(null);
  readonly configs = signal<SocialConfigDto[]>([]);
  readonly loading = signal(true);
  readonly saving  = signal(false);

  isNew = false;
  imageFile: File | null = null;
  selectedConfigs: Record<number, boolean> = {};

  form = { title: '', content: '', xContent: '', scheduledAt: '' };

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    this.isNew = id === 'new';

    this.svc.configs().subscribe(c => this.configs.set(c));

    if (this.isNew) {
      this.loading.set(false);
    } else {
      this.svc.get(Number(id)).subscribe({
        next: p => { this.post.set(p); this.loading.set(false); },
        error: () => { this.loading.set(false); this.router.navigate(['/marketing/social']); },
      });
    }
  }

  updateCounters() {}

  onFileChange(event: Event) {
    const input = event.target as HTMLInputElement;
    this.imageFile = input.files?.[0] ?? null;
  }

  create() {
    if (!this.form.title || !this.form.content) return;
    this.saving.set(true);

    const fd = new FormData();
    fd.append('title',   this.form.title);
    fd.append('content', this.form.content);
    if (this.form.xContent) fd.append('xContent', this.form.xContent);
    if (this.form.scheduledAt) fd.append('scheduledAt', this.form.scheduledAt);
    if (this.imageFile) fd.append('image', this.imageFile);

    Object.entries(this.selectedConfigs)
      .filter(([, v]) => v)
      .forEach(([id]) => fd.append('configIds', id));

    this.svc.create(fd).subscribe({
      next: res => {
        this.saving.set(false);
        this.snack.open('Publication créée', '', { duration: 2000 });
        this.router.navigate(['/marketing/social', res.id]);
      },
      error: () => { this.saving.set(false); this.snack.open('Erreur', 'Fermer', { duration: 4000 }); },
    });
  }

  publish() {
    const p = this.post();
    if (!p) return;
    this.saving.set(true);
    this.svc.publish(p.id).subscribe({
      next: () => { this.saving.set(false); this.snack.open('Publication lancée', '', { duration: 2000 }); this.reload(); },
      error: () => { this.saving.set(false); this.snack.open('Erreur publication', 'Fermer', { duration: 4000 }); },
    });
  }

  cancelSchedule() {
    const p = this.post();
    if (!p) return;
    this.saving.set(true);
    this.svc.cancel(p.id).subscribe({
      next: () => { this.saving.set(false); this.snack.open('Planification annulée', '', { duration: 2000 }); this.reload(); },
      error: () => { this.saving.set(false); this.snack.open('Erreur', 'Fermer', { duration: 4000 }); },
    });
  }

  private reload() {
    const p = this.post();
    if (!p) return;
    this.svc.get(p.id).subscribe(updated => this.post.set(updated));
  }

  canPublish(status: string) {
    return status === 'DRAFT' || status === 'SCHEDULED' || status === 'FAILED';
  }
  hasX() {
    return this.post()?.targets.some(t => t.network === 'X') ?? false;
  }
  statusClass(s: string) { return s.toLowerCase(); }
  networkIcon(network: string): string {
    const map: Record<string, string> = {
      LINKEDIN: 'business', LINKEDIN_PAGE: 'business', FACEBOOK: 'thumb_up',
      INSTAGRAM: 'photo_camera', X: 'chat',
    };
    return map[network] ?? 'share';
  }
  formatDate(d: string | null) {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
}
