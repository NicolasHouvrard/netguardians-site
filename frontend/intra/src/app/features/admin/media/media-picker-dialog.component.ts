import { Component, inject, signal, OnInit, ElementRef, ViewChild } from '@angular/core';
import { MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule }  from '@angular/material/button';
import { MatIconModule }    from '@angular/material/icon';
import { MatTabsModule }    from '@angular/material/tabs';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MediaService } from './media.service';

@Component({
  selector: 'app-media-picker-dialog',
  standalone: true,
  imports: [
    MatDialogModule, MatButtonModule, MatIconModule,
    MatTabsModule, MatProgressBarModule,
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>photo_library</mat-icon> Médiathèque
    </h2>

    <mat-dialog-content>
      <mat-tab-group>

        <mat-tab label="Choisir une image">
          @if (loading()) {
            <mat-progress-bar mode="indeterminate" />
          }
          <div class="grid">
            @for (url of images(); track url) {
              <div class="thumb" (click)="select(url)" [title]="url">
                <img [src]="url" [alt]="url" loading="lazy">
              </div>
            }
            @if (!loading() && images().length === 0) {
              <p class="empty">Aucune image. Uploadez-en une dans l'onglet suivant.</p>
            }
          </div>
        </mat-tab>

        <mat-tab label="Ajouter une image">
          <div class="upload-zone" (click)="fileInput.click()" (dragover)="$event.preventDefault()"
               (drop)="onDrop($event)">
            <mat-icon>cloud_upload</mat-icon>
            <p>Cliquez ou glissez-déposez vos images ici</p>
            <small>JPG, PNG, GIF, WEBP, SVG — max 10 Mo</small>
          </div>
          <input #fileInput type="file" multiple accept="image/*"
                 style="display:none" (change)="onFileChange($event)">
          @if (uploading()) {
            <mat-progress-bar mode="indeterminate" />
            <p class="status">Upload en cours…</p>
          }
          @if (uploadDone()) {
            <p class="status success">{{ uploadDone() }}</p>
          }
        </mat-tab>

      </mat-tab-group>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button (click)="dialogRef.close()">Annuler</button>
    </mat-dialog-actions>
  `,
  styles: [`
    h2 { display: flex; align-items: center; gap: 8px; }
    mat-dialog-content { min-width: 640px; min-height: 360px; }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
      gap: 8px; padding: 12px 0;
    }
    .thumb {
      aspect-ratio: 1; border-radius: 6px; overflow: hidden;
      border: 2px solid transparent; cursor: pointer;
      background: #f5f5f5;
      &:hover { border-color: #3f51b5; }
      img { width: 100%; height: 100%; object-fit: cover; }
    }
    .empty { color: #999; padding: 24px 0; }
    .upload-zone {
      border: 2px dashed #ccc; border-radius: 8px;
      padding: 40px; text-align: center; cursor: pointer; margin: 16px 0;
      color: #888;
      &:hover { border-color: #3f51b5; color: #3f51b5; }
      mat-icon { font-size: 48px; width: 48px; height: 48px; display: block; margin: 0 auto 8px; }
    }
    .status { text-align: center; color: #888; }
    .success { color: #2e7d32; }
  `],
})
export class MediaPickerDialogComponent implements OnInit {
  readonly dialogRef = inject(MatDialogRef<MediaPickerDialogComponent>);
  private readonly media = inject(MediaService);

  readonly images    = signal<string[]>([]);
  readonly loading   = signal(true);
  readonly uploading = signal(false);
  readonly uploadDone = signal('');

  ngOnInit() {
    this.loadImages();
  }

  select(url: string) {
    this.dialogRef.close(url);
  }

  onFileChange(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) this.doUpload(Array.from(input.files));
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    const files = Array.from(event.dataTransfer?.files ?? []).filter(f => f.type.startsWith('image/'));
    if (files.length) this.doUpload(files);
  }

  private doUpload(files: File[]) {
    this.uploading.set(true);
    this.uploadDone.set('');
    this.media.uploadImages(files).subscribe({
      next: res => {
        this.uploading.set(false);
        this.uploadDone.set(`${res.count} image(s) ajoutée(s).`);
        this.loadImages();
      },
      error: () => {
        this.uploading.set(false);
        this.uploadDone.set('Erreur lors de l\'upload.');
      },
    });
  }

  private loadImages() {
    this.loading.set(true);
    this.media.listImages().subscribe({
      next: urls => { this.images.set(urls); this.loading.set(false); },
      error: ()  => this.loading.set(false),
    });
  }
}
