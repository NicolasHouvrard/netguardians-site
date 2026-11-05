import { Component, inject, Inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule }    from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatDialog }          from '@angular/material/dialog';
import { MatIconModule }      from '@angular/material/icon';
import { MediaPickerDialogComponent } from '../../../admin/media/media-picker-dialog.component';
import { BlockDto, BlockTypeDto }     from '../../services/page.service';

export interface BlockEditorData {
  block: BlockDto;
  blockTypes: BlockTypeDto[];
}

@Component({
  selector: 'app-block-editor-dialog',
  standalone: true,
  imports: [
    FormsModule, MatDialogModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatIconModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ label() }}</h2>
    <mat-dialog-content>

      @switch (block.blockType) {

        @case ('RICHTEXT') {
          <mat-form-field class="full">
            <mat-label>Contenu HTML</mat-label>
            <textarea matInput [(ngModel)]="c['html']" rows="8" placeholder="<p>Votre contenu…</p>"></textarea>
          </mat-form-field>
        }


        @case ('IMAGE') {
          <div class="img-row">
            @if (c['src']) { <img [src]="c['src']" class="preview"> }
            <button mat-stroked-button type="button" (click)="pickImage('src')">
              <mat-icon>photo_library</mat-icon> Médiathèque
            </button>
          </div>
          <mat-form-field class="full"><mat-label>URL image</mat-label>
            <input matInput [(ngModel)]="c['src']" placeholder="/uploads/images/…"></mat-form-field>
          <mat-form-field class="full"><mat-label>Texte alternatif</mat-label>
            <input matInput [(ngModel)]="c['alt']"></mat-form-field>
          <mat-form-field class="full"><mat-label>Légende</mat-label>
            <input matInput [(ngModel)]="c['caption']"></mat-form-field>
        }

        @case ('HERO') {
          <mat-form-field class="full"><mat-label>Titre</mat-label>
            <input matInput [(ngModel)]="c['title']" placeholder="Bienvenue !"></mat-form-field>
          <mat-form-field class="full"><mat-label>Sous-titre</mat-label>
            <input matInput [(ngModel)]="c['subtitle']"></mat-form-field>
          <mat-form-field class="full"><mat-label>Hauteur (ex: 500px)</mat-label>
            <input matInput [(ngModel)]="c['height']" placeholder="500px"></mat-form-field>
          <mat-form-field><mat-label>Type de fond</mat-label>
            <mat-select [(ngModel)]="c['bgType']">
              <mat-option value="color">Couleur</mat-option>
              <mat-option value="image">Image</mat-option>
            </mat-select>
          </mat-form-field>
          @if (c['bgType'] === 'image') {
            <div class="img-row">
              @if (c['bgImage']) { <img [src]="c['bgImage']" class="preview"> }
              <button mat-stroked-button type="button" (click)="pickImage('bgImage')">
                <mat-icon>photo_library</mat-icon> Image de fond
              </button>
            </div>
            <mat-form-field class="full"><mat-label>URL image de fond</mat-label>
              <input matInput [(ngModel)]="c['bgImage']"></mat-form-field>
            <mat-form-field><mat-label>Filtre sombre (0–1)</mat-label>
              <input matInput [(ngModel)]="c['bgOverlay']" placeholder="0.4"></mat-form-field>
          }
          <mat-form-field class="full"><mat-label>Texte bouton CTA</mat-label>
            <input matInput [(ngModel)]="c['ctaText']" placeholder="En savoir plus"></mat-form-field>
          <mat-form-field class="full"><mat-label>URL bouton CTA</mat-label>
            <input matInput [(ngModel)]="c['ctaUrl']" placeholder="/contact"></mat-form-field>
        }

        @case ('CTA') {
          <mat-form-field class="full"><mat-label>Titre</mat-label>
            <input matInput [(ngModel)]="c['title']" placeholder="Prêt à démarrer ?"></mat-form-field>
          <mat-form-field class="full"><mat-label>Texte</mat-label>
            <textarea matInput [(ngModel)]="c['text']" rows="3"></textarea></mat-form-field>
          <mat-form-field><mat-label>Texte du bouton</mat-label>
            <input matInput [(ngModel)]="c['btnText']" placeholder="Contactez-nous"></mat-form-field>
          <mat-form-field><mat-label>URL du bouton</mat-label>
            <input matInput [(ngModel)]="c['btnUrl']" placeholder="/contact"></mat-form-field>
          <mat-form-field><mat-label>Style bouton</mat-label>
            <mat-select [(ngModel)]="c['btnStyle']">
              @for (s of ['primary','secondary','success','warning','danger','info','dark']; track s) {
                <mat-option [value]="s">{{ s }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        }

        @case ('COLUMNS') {
          <mat-form-field class="full"><mat-label>Colonne 1 (HTML)</mat-label>
            <textarea matInput [(ngModel)]="c['col1Html']" rows="5"></textarea></mat-form-field>
          <mat-form-field class="full"><mat-label>Colonne 2 (HTML)</mat-label>
            <textarea matInput [(ngModel)]="c['col2Html']" rows="5"></textarea></mat-form-field>
          <mat-form-field class="full"><mat-label>Colonne 3 (HTML — optionnel)</mat-label>
            <textarea matInput [(ngModel)]="c['col3Html']" rows="5"></textarea></mat-form-field>
        }

        @case ('TEXT_IMAGE') {
          <mat-form-field class="full"><mat-label>Texte (HTML)</mat-label>
            <textarea matInput [(ngModel)]="c['html']" rows="6" placeholder="<p>Votre contenu…</p>"></textarea></mat-form-field>
          <div class="img-row">
            @if (c['src']) { <img [src]="c['src']" class="preview"> }
            <button mat-stroked-button type="button" (click)="pickImage('src')">
              <mat-icon>photo_library</mat-icon> Image
            </button>
          </div>
          <mat-form-field class="full"><mat-label>URL image</mat-label>
            <input matInput [(ngModel)]="c['src']"></mat-form-field>
          <mat-form-field class="full"><mat-label>Texte alternatif</mat-label>
            <input matInput [(ngModel)]="c['alt']"></mat-form-field>
          <mat-form-field class="full"><mat-label>Légende</mat-label>
            <input matInput [(ngModel)]="c['caption']"></mat-form-field>
        }

        @case ('IMAGE_TEXT') {
          <div class="img-row">
            @if (c['src']) { <img [src]="c['src']" class="preview"> }
            <button mat-stroked-button type="button" (click)="pickImage('src')">
              <mat-icon>photo_library</mat-icon> Image
            </button>
          </div>
          <mat-form-field class="full"><mat-label>URL image</mat-label>
            <input matInput [(ngModel)]="c['src']"></mat-form-field>
          <mat-form-field class="full"><mat-label>Texte alternatif</mat-label>
            <input matInput [(ngModel)]="c['alt']"></mat-form-field>
          <mat-form-field class="full"><mat-label>Légende</mat-label>
            <input matInput [(ngModel)]="c['caption']"></mat-form-field>
          <mat-form-field class="full"><mat-label>Texte (HTML)</mat-label>
            <textarea matInput [(ngModel)]="c['html']" rows="6"></textarea></mat-form-field>
        }

        @case ('CARDS') {
          <p class="hint">Tableau JSON — chaque carte&nbsp;: title, text, icon, link</p>
          <mat-form-field class="full"><mat-label>Cartes (tableau JSON)</mat-label>
            <textarea matInput [(ngModel)]="c['cards']" rows="8" style="font-family:monospace"></textarea>
          </mat-form-field>
        }

        @case ('WIDGET') {
          <mat-form-field class="full"><mat-label>ID du widget</mat-label>
            <input matInput [(ngModel)]="c['widgetId']" placeholder="ex: contact-form"></mat-form-field>
        }

        @case ('DIVIDER') {
          <mat-form-field><mat-label>Style</mat-label>
            <mat-select [(ngModel)]="c['style']">
              @for (s of ['solid','dashed','dotted']; track s) {
                <mat-option [value]="s">{{ s }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        }

        @case ('SPACER') {
          <mat-form-field><mat-label>Hauteur</mat-label>
            <mat-select [(ngModel)]="c['height']">
              @for (h of ['1rem','2rem','3rem','4rem','5rem']; track h) {
                <mat-option [value]="h">{{ h }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        }

        @case ('HTML') {
          <mat-form-field class="full"><mat-label>Code HTML brut</mat-label>
            <textarea matInput [(ngModel)]="c['code']" rows="10"
                      style="font-family:monospace" placeholder="<div>Mon HTML…</div>"></textarea>
          </mat-form-field>
        }

      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Annuler</button>
      <button mat-flat-button color="primary" (click)="confirm()">Appliquer</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: 540px; display: flex; flex-direction: column; gap: 0; padding-top: 8px; }
    mat-form-field { width: 100%; margin-bottom: 4px; }
    .full { width: 100%; }
    .img-row { display: flex; align-items: center; gap: 12px; margin: 8px 0; }
    .preview { height: 80px; border-radius: 4px; border: 1px solid #e0e0e0; object-fit: cover; }
    .hint { color: #888; font-size: 12px; margin: 4px 0 8px; }
    code { background: #f5f5f5; padding: 1px 4px; border-radius: 3px; font-size: 11px; }
  `],
})
export class BlockEditorDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<BlockEditorDialogComponent>);
  private readonly dialog    = inject(MatDialog);

  readonly block: BlockDto;
  readonly blockTypes: BlockTypeDto[];
  c: Record<string, any> = {};

  constructor(@Inject(MAT_DIALOG_DATA) data: BlockEditorData) {
    this.block      = { ...data.block };
    this.blockTypes = data.blockTypes;
    try { this.c = JSON.parse(data.block.content || '{}'); } catch { this.c = {}; }
  }

  label(): string {
    return this.blockTypes.find(b => b.type === this.block.blockType)?.label ?? this.block.blockType;
  }

  pickImage(field: string) {
    this.dialog.open(MediaPickerDialogComponent, { width: '720px' })
      .afterClosed().subscribe((url: string | undefined) => { if (url) this.c[field] = url; });
  }

  confirm() {
    this.dialogRef.close({ ...this.block, content: JSON.stringify(this.c) });
  }

  cancel() { this.dialogRef.close(null); }
}
