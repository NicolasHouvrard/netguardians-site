import { Component, inject, signal, OnInit } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { FormsModule }       from '@angular/forms';
import { MatButtonModule }   from '@angular/material/button';
import { MatIconModule }     from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { PageService, PageSummaryDto } from '../services/page.service';

@Component({
  selector: 'app-marketing-pages',
  standalone: true,
  imports: [
    FormsModule, MatButtonModule, MatIconModule, MatDialogModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSnackBarModule, MatTooltipModule,
  ],
  template: `
    <div class="pages-wrap">
      <div class="toolbar">
        <h2><mat-icon>article</mat-icon> Pages</h2>
        <button mat-flat-button color="primary" (click)="showNew = true">
          <mat-icon>add</mat-icon> Nouvelle page
        </button>
      </div>

      <!-- Formulaire nouvelle page -->
      @if (showNew) {
        <div class="new-panel">
          <mat-form-field>
            <mat-label>Titre de la page</mat-label>
            <input matInput [(ngModel)]="newTitle" name="newTitle" (keyup.enter)="createPage()">
          </mat-form-field>
          <mat-form-field>
            <mat-label>Type</mat-label>
            <mat-select [(ngModel)]="newType">
              <mat-option value="PAGE">Page</mat-option>
              <mat-option value="BLOG_POST">Article de blog</mat-option>
            </mat-select>
          </mat-form-field>
          <div class="new-actions">
            <button mat-button (click)="showNew = false; newTitle = ''">Annuler</button>
            <button mat-flat-button color="primary" (click)="createPage()" [disabled]="!newTitle.trim()">Créer</button>
          </div>
        </div>
      }

      <!-- Tableau des pages -->
      <table class="pages-table">
        <thead>
          <tr>
            <th>Titre</th>
            <th>Slug</th>
            <th>Type</th>
            <th>Statut</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (page of pages(); track page.id) {
            <tr>
              <td class="col-title">
                {{ page.title }}
                @if (page.homePage) { <span class="badge home">Accueil</span> }
              </td>
              <td class="col-slug">/{{ page.slug }}</td>
              <td>{{ page.pageType === 'BLOG_POST' ? 'Blog' : 'Page' }}</td>
              <td>
                <span class="badge" [class.published]="page.published" [class.draft]="!page.published">
                  {{ page.published ? 'Publié' : 'Brouillon' }}
                </span>
              </td>
              <td class="col-actions">
                <button mat-icon-button (click)="openEditor(page.id)" matTooltip="Éditer">
                  <mat-icon>edit</mat-icon>
                </button>
                <button mat-icon-button (click)="togglePublish(page)" [matTooltip]="page.published ? 'Dépublier' : 'Publier'">
                  <mat-icon>{{ page.published ? 'visibility_off' : 'visibility' }}</mat-icon>
                </button>
                @if (!page.homePage) {
                  <button mat-icon-button (click)="setHome(page)" matTooltip="Définir comme accueil">
                    <mat-icon>home</mat-icon>
                  </button>
                }
                <button mat-icon-button color="warn" (click)="deletePage(page)" matTooltip="Supprimer">
                  <mat-icon>delete</mat-icon>
                </button>
              </td>
            </tr>
          }
          @if (!loading() && pages().length === 0) {
            <tr><td colspan="5" class="empty">Aucune page. Cliquez sur "Nouvelle page" pour commencer.</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .pages-wrap { max-width: 900px; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px;
               h2 { display: flex; align-items: center; gap: 8px; margin: 0; } }
    .new-panel {
      display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
      background: #f8f9fa; border: 1px solid #e0e0e0; border-radius: 8px;
      padding: 16px; margin-bottom: 20px;
    }
    .new-actions { display: flex; gap: 8px; }
    .pages-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    th { text-align: left; font-size: 12px; font-weight: 600; color: #888; text-transform: uppercase;
         padding: 8px 12px; border-bottom: 2px solid #e0e0e0; }
    td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }
    .col-title { font-weight: 500; }
    .col-slug  { font-size: 12px; color: #888; font-family: monospace; }
    .col-actions { display: flex; gap: 0; white-space: nowrap; }
    .badge {
      display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: 10px; margin-left: 6px;
      &.published { background: #d4edda; color: #155724; }
      &.draft     { background: #fff3cd; color: #856404; }
      &.home      { background: #cce5ff; color: #004085; }
    }
    .empty { text-align: center; color: #aaa; padding: 32px; }
  `],
})
export class MarketingPagesComponent implements OnInit {
  private readonly pageService = inject(PageService);
  private readonly router      = inject(Router);
  private readonly route       = inject(ActivatedRoute);
  private readonly snack       = inject(MatSnackBar);

  readonly pages   = signal<PageSummaryDto[]>([]);
  readonly loading = signal(true);

  showNew  = false;
  newTitle = '';
  newType  = 'PAGE';

  ngOnInit() {
    this.load();
    // Support lien "Nouvelle page" depuis le menu
    this.route.queryParams.subscribe(p => { if (p['new']) this.showNew = true; });
  }

  load() {
    this.loading.set(true);
    this.pageService.list().subscribe({
      next: p => { this.pages.set(p); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  createPage() {
    if (!this.newTitle.trim()) return;
    this.pageService.create(this.newTitle.trim(), this.newType).subscribe({
      next: page => {
        this.showNew = false; this.newTitle = '';
        this.router.navigate(['/marketing/pages', page.id]);
      },
      error: () => this.snack.open('Erreur création', 'Fermer', { duration: 4000 }),
    });
  }

  openEditor(id: number) { this.router.navigate(['/marketing/pages', id]); }

  togglePublish(page: PageSummaryDto) {
    this.pageService.togglePublish(page.id).subscribe({
      next: updated => this.pages.update(list => list.map(p => p.id === page.id ? { ...p, ...updated } : p)),
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  setHome(page: PageSummaryDto) {
    this.pageService.setHome(page.id).subscribe({
      next: () => { this.load(); this.snack.open('Page d\'accueil définie', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  deletePage(page: PageSummaryDto) {
    if (!confirm(`Supprimer "${page.title}" ?`)) return;
    this.pageService.delete(page.id).subscribe({
      next: () => { this.pages.update(list => list.filter(p => p.id !== page.id)); this.snack.open('Supprimé', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }
}
