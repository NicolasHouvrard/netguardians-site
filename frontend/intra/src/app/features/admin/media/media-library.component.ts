import { Component, inject, signal, OnInit } from '@angular/core';
import { MatButtonModule }  from '@angular/material/button';
import { MatIconModule }    from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MediaService } from './media.service';

@Component({
  selector: 'app-media-library',
  standalone: true,
  imports: [
    MatButtonModule, MatIconModule, MatProgressBarModule,
    MatDialogModule, MatSnackBarModule,
  ],
  template: `
    <div class="lib-header">
      <h2><mat-icon>photo_library</mat-icon> Médiathèque</h2>
      <div class="lib-actions">
        <button mat-flat-button color="primary" (click)="fileInput.click()">
          <mat-icon>add</mat-icon> Ajouter des images
        </button>
        <input #fileInput type="file" multiple accept="image/*"
               style="display:none" (change)="onFileChange($event)">
      </div>
    </div>

    @if (uploading()) {
      <mat-progress-bar mode="indeterminate" />
    }

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else {
      <div class="grid">
        @for (url of images(); track url) {
          <div class="thumb">
            <img [src]="url" [alt]="url" loading="lazy">
            <div class="thumb-overlay">
              <button mat-icon-button (click)="copyUrl(url)" title="Copier l'URL">
                <mat-icon>content_copy</mat-icon>
              </button>
              <button mat-icon-button color="warn" (click)="confirmDelete(url)" title="Supprimer">
                <mat-icon>delete</mat-icon>
              </button>
            </div>
          </div>
        }
        @if (images().length === 0) {
          <div class="empty">
            <mat-icon>image_not_supported</mat-icon>
            <p>Aucune image. Cliquez sur "Ajouter des images" pour commencer.</p>
          </div>
        }
      </div>
    }
  `,
  styles: [`
    .lib-header {
      display: flex; align-items: center; justify-content: space-between;
      margin-bottom: 20px;
      h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; }
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
      gap: 12px;
    }
    .thumb {
      position: relative; aspect-ratio: 1; border-radius: 8px;
      overflow: hidden; background: #f5f5f5; border: 1px solid #e0e0e0;
      &:hover .thumb-overlay { opacity: 1; }
      img { width: 100%; height: 100%; object-fit: cover; }
    }
    .thumb-overlay {
      position: absolute; inset: 0; background: rgba(0,0,0,.45);
      display: flex; align-items: center; justify-content: center; gap: 4px;
      opacity: 0; transition: opacity .2s;
      button { color: #fff; }
    }
    .empty {
      grid-column: 1/-1; text-align: center; color: #aaa; padding: 48px 0;
      mat-icon { font-size: 48px; width: 48px; height: 48px; }
    }
  `],
})
export class MediaLibraryComponent implements OnInit {
  private readonly media = inject(MediaService);
  private readonly snack = inject(MatSnackBar);

  readonly images    = signal<string[]>([]);
  readonly loading   = signal(true);
  readonly uploading = signal(false);

  ngOnInit() { this.load(); }

  onFileChange(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;
    this.uploading.set(true);
    this.media.uploadImages(Array.from(input.files)).subscribe({
      next: res => {
        this.uploading.set(false);
        this.snack.open(`${res.count} image(s) ajoutée(s)`, 'OK', { duration: 3000 });
        this.load();
        input.value = '';
      },
      error: () => {
        this.uploading.set(false);
        this.snack.open('Erreur upload', 'Fermer', { duration: 4000 });
      },
    });
  }

  copyUrl(url: string) {
    navigator.clipboard.writeText(window.location.origin + url);
    this.snack.open('URL copiée', '', { duration: 2000 });
  }

  confirmDelete(url: string) {
    const filename = url.split('/').pop()!;
    if (!confirm(`Supprimer "${filename}" ?`)) return;
    this.media.deleteImage(filename).subscribe({
      next: () => {
        this.images.update(list => list.filter(u => u !== url));
        this.snack.open('Image supprimée', '', { duration: 2000 });
      },
      error: () => this.snack.open('Erreur suppression', 'Fermer', { duration: 4000 }),
    });
  }

  private load() {
    this.loading.set(true);
    this.media.listImages().subscribe({
      next: urls => { this.images.set(urls); this.loading.set(false); },
      error: ()  => this.loading.set(false),
    });
  }
}
