import { Component, inject, signal, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { SlicePipe } from '@angular/common';
import { MatButtonModule }    from '@angular/material/button';
import { MatIconModule }      from '@angular/material/icon';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { SocialPostService, SocialPostDto, SocialStatsDto } from '../services/social-post.service';

@Component({
  selector: 'app-social-posts',
  standalone: true,
  imports: [SlicePipe, MatButtonModule, MatIconModule, MatTooltipModule, MatSnackBarModule],
  template: `
    <div class="wrap">
      <div class="toolbar">
        <h2><mat-icon>share</mat-icon> Publications réseaux sociaux</h2>
        <button mat-flat-button color="primary" (click)="router.navigate(['/marketing/social/new'])">
          <mat-icon>add</mat-icon> Nouvelle publication
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
            <span class="kpi-lbl">Planifiés</span>
          </div>
          <div class="kpi published">
            <span class="kpi-num">{{ stats()!.published }}</span>
            <span class="kpi-lbl">Publiés</span>
          </div>
          <div class="kpi failed">
            <span class="kpi-num">{{ stats()!.failed }}</span>
            <span class="kpi-lbl">Échecs</span>
          </div>
        </div>
      }

      <!-- Tableau -->
      <table class="posts-table">
        <thead>
          <tr>
            <th>Titre</th>
            <th>Réseaux</th>
            <th>Statut</th>
            <th>Planifié le</th>
            <th>Publié le</th>
            <th>Créé le</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (post of posts(); track post.id) {
            <tr>
              <td class="col-title" (click)="openDetail(post.id)">
                <span class="title">{{ post.title }}</span>
                <span class="excerpt">{{ post.content | slice:0:80 }}{{ post.content.length > 80 ? '…' : '' }}</span>
              </td>
              <td class="col-networks">
                @for (t of post.targets; track t.id) {
                  <span class="network-badge" [matTooltip]="t.label + ' — ' + t.statusLabel">
                    <mat-icon class="net-icon">{{ networkIcon(t.network) }}</mat-icon>
                  </span>
                }
              </td>
              <td>
                <span class="badge" [class]="statusClass(post.status)">{{ post.statusLabel }}</span>
              </td>
              <td class="col-date">{{ formatDate(post.scheduledAt) }}</td>
              <td class="col-date">{{ formatDate(post.publishedAt) }}</td>
              <td class="col-date">{{ formatDate(post.createdAt) }}</td>
              <td class="col-actions">
                <button mat-icon-button (click)="openDetail(post.id)" matTooltip="Voir">
                  <mat-icon>visibility</mat-icon>
                </button>
                @if (post.status === 'DRAFT') {
                  <button mat-icon-button color="warn" (click)="deletePost(post)" matTooltip="Supprimer">
                    <mat-icon>delete</mat-icon>
                  </button>
                }
              </td>
            </tr>
          }
          @if (!loading() && posts().length === 0) {
            <tr><td colspan="7" class="empty">Aucune publication. Cliquez sur "Nouvelle publication".</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .wrap { max-width: 1100px; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px;
               h2 { display: flex; align-items: center; gap: 8px; margin: 0; } }

    .kpi-bar { display: flex; gap: 12px; margin-bottom: 20px; }
    .kpi {
      flex: 1; background: #f8f9fa; border: 1px solid #e0e0e0; border-radius: 8px;
      padding: 12px 16px; display: flex; flex-direction: column; align-items: center;
      &.scheduled { background: #fff8e1; border-color: #ffe082; }
      &.published { background: #e8f5e9; border-color: #a5d6a7; }
      &.failed    { background: #fce4ec; border-color: #f48fb1; }
    }
    .kpi-num { font-size: 28px; font-weight: 700; line-height: 1; }
    .kpi-lbl { font-size: 11px; color: #888; margin-top: 4px; text-transform: uppercase; letter-spacing: .5px; }

    .posts-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    th { text-align: left; font-size: 11px; font-weight: 600; color: #888; text-transform: uppercase;
         padding: 8px 10px; border-bottom: 2px solid #e0e0e0; }
    td { padding: 10px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }

    .col-title { cursor: pointer;
      .title { display: block; font-weight: 500; color: #3f51b5; }
      .title:hover { text-decoration: underline; }
      .excerpt { display: block; font-size: 12px; color: #888; }
    }
    .col-networks { white-space: nowrap; }
    .network-badge { display: inline-flex; align-items: center; margin-right: 4px; }
    .net-icon { font-size: 18px; width: 18px; height: 18px; color: #555; }
    .col-date { font-size: 12px; color: #888; white-space: nowrap; }
    .col-actions { display: flex; gap: 0; white-space: nowrap; }

    .badge {
      display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: 10px;
      &.draft      { background: #e0e0e0; color: #555; }
      &.scheduled  { background: #fff3cd; color: #856404; }
      &.publishing { background: #fff3cd; color: #856404; }
      &.published  { background: #d4edda; color: #155724; }
      &.partial    { background: #fff3cd; color: #856404; }
      &.failed     { background: #f8d7da; color: #721c24; }
    }
    .empty { text-align: center; color: #aaa; padding: 32px; }
  `],
})
export class SocialPostsComponent implements OnInit {
  readonly router  = inject(Router);
  private readonly svc   = inject(SocialPostService);
  private readonly snack = inject(MatSnackBar);

  readonly posts   = signal<SocialPostDto[]>([]);
  readonly stats   = signal<SocialStatsDto | null>(null);
  readonly loading = signal(true);

  ngOnInit() { this.load(); }

  load() {
    this.loading.set(true);
    this.svc.list().subscribe({
      next: l => { this.posts.set(l); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
    this.svc.stats().subscribe(s => this.stats.set(s));
  }

  openDetail(id: number) { this.router.navigate(['/marketing/social', id]); }

  deletePost(post: SocialPostDto) {
    if (!confirm(`Supprimer "${post.title}" ?`)) return;
    this.svc.delete(post.id).subscribe({
      next: () => { this.posts.update(l => l.filter(p => p.id !== post.id)); this.svc.stats().subscribe(s => this.stats.set(s)); },
      error: () => this.snack.open('Erreur suppression', 'Fermer', { duration: 4000 }),
    });
  }

  statusClass(s: string) { return s.toLowerCase(); }
  formatDate(d: string | null) {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
  networkIcon(network: string): string {
    const map: Record<string, string> = {
      LINKEDIN: 'business', LINKEDIN_PAGE: 'business', FACEBOOK: 'thumb_up',
      INSTAGRAM: 'photo_camera', X: 'chat',
    };
    return map[network] ?? 'share';
  }
}
