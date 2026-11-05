import { Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule }   from '@angular/forms';
import { CdkDragDrop, CdkDropList, CdkDrag, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatButtonModule }   from '@angular/material/button';
import { MatIconModule }     from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatTabsModule }      from '@angular/material/tabs';
import { MatDividerModule }   from '@angular/material/divider';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PageService, PageDto, BlockDto, BlockTypeDto } from '../../services/page.service';
import { BlockEditorDialogComponent } from './block-editor-dialog.component';
import { MediaPickerDialogComponent } from '../../../admin/media/media-picker-dialog.component';

@Component({
  selector: 'app-marketing-page-editor',
  standalone: true,
  imports: [
    FormsModule, RouterLink,
    CdkDropList, CdkDrag,
    MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTabsModule, MatDividerModule,
    MatDialogModule, MatSnackBarModule, MatTooltipModule, MatProgressBarModule,
  ],
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else if (page()) {
      <div class="editor-wrap">

        <!-- Barre supérieure -->
        <div class="topbar">
          <a mat-icon-button routerLink="/marketing/pages" matTooltip="Retour aux pages">
            <mat-icon>arrow_back</mat-icon>
          </a>
          <input class="title-input" [(ngModel)]="page()!.title" placeholder="Titre de la page" (blur)="autoSave()">
          <span class="slug-display">/{{ page()!.slug }}</span>
          <span class="spacer"></span>
          <button mat-stroked-button (click)="togglePublish()" [color]="page()!.published ? 'warn' : 'primary'">
            <mat-icon>{{ page()!.published ? 'visibility_off' : 'visibility' }}</mat-icon>
            {{ page()!.published ? 'Dépublier' : 'Publier' }}
          </button>
          <button mat-flat-button color="primary" (click)="save()" [disabled]="saving()">
            <mat-icon>save</mat-icon> {{ saving() ? 'Enregistrement…' : 'Enregistrer' }}
          </button>
        </div>

        <div class="editor-body">

          <!-- Panneau gauche : blocs disponibles -->
          <aside class="block-palette">
            <div class="palette-title">Blocs</div>
            @for (bt of blockTypes(); track bt.type) {
              <button class="palette-btn" (click)="addBlock(bt.type)" [matTooltip]="bt.label">
                <mat-icon>{{ bt.icon }}</mat-icon>
                <span>{{ bt.label }}</span>
              </button>
            }
          </aside>

          <!-- Canvas central : blocs de la page -->
          <main class="canvas">
            <div cdkDropList (cdkDropListDropped)="dropBlock($event)" class="block-list">
              @for (block of page()!.blocks; track block.sortOrder; let i = $index) {
                <div cdkDrag class="block-card">
                  <div class="block-header">
                    <mat-icon cdkDragHandle class="drag-handle">drag_indicator</mat-icon>
                    <mat-icon class="block-type-icon">{{ iconFor(block.blockType) }}</mat-icon>
                    <span class="block-type-label">{{ labelFor(block.blockType) }}</span>
                    <span class="spacer"></span>
                    <button mat-icon-button (click)="editBlock(i)" matTooltip="Éditer">
                      <mat-icon>edit</mat-icon>
                    </button>
                    <button mat-icon-button color="warn" (click)="removeBlock(i)" matTooltip="Supprimer">
                      <mat-icon>delete</mat-icon>
                    </button>
                  </div>
                  <div class="block-preview">{{ blockPreview(block) }}</div>
                </div>
              }
              @if (page()!.blocks.length === 0) {
                <div class="empty-canvas">
                  <mat-icon>add_box</mat-icon>
                  <p>Cliquez sur un type de bloc à gauche pour commencer</p>
                </div>
              }
            </div>
          </main>

          <!-- Panneau droit : métadonnées -->
          <aside class="meta-panel">
            <mat-tab-group>
              <mat-tab label="Général">
                <div class="meta-fields">
                  <mat-form-field class="full">
                    <mat-label>Slug (URL)</mat-label>
                    <input matInput [(ngModel)]="page()!.slug" (blur)="autoSave()">
                  </mat-form-field>
                  <mat-form-field class="full">
                    <mat-label>Type</mat-label>
                    <mat-select [(ngModel)]="page()!.pageType">
                      <mat-option value="PAGE">Page</mat-option>
                      <mat-option value="BLOG_POST">Article de blog</mat-option>
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field class="full">
                    <mat-label>Extrait</mat-label>
                    <textarea matInput [(ngModel)]="page()!.excerpt" rows="3"></textarea>
                  </mat-form-field>
                  <div class="img-preview-row">
                    @if (page()!.featuredImageUrl) {
                      <img [src]="page()!.featuredImageUrl" class="feat-preview">
                    }
                    <button mat-stroked-button type="button" (click)="pickFeaturedImage()">
                      <mat-icon>photo_library</mat-icon> Image à la une
                    </button>
                  </div>
                </div>
              </mat-tab>

              <mat-tab label="SEO">
                <div class="meta-fields">
                  <mat-form-field class="full">
                    <mat-label>Meta titre</mat-label>
                    <input matInput [(ngModel)]="page()!.metaTitle">
                  </mat-form-field>
                  <mat-form-field class="full">
                    <mat-label>Meta description</mat-label>
                    <textarea matInput [(ngModel)]="page()!.metaDescription" rows="3"></textarea>
                  </mat-form-field>
                  <mat-form-field class="full">
                    <mat-label>Mots-clés</mat-label>
                    <input matInput [(ngModel)]="page()!.metaKeywords">
                  </mat-form-field>
                  <mat-form-field class="full">
                    <mat-label>Image Open Graph</mat-label>
                    <input matInput [(ngModel)]="page()!.metaOgImage">
                  </mat-form-field>
                </div>
              </mat-tab>
            </mat-tab-group>
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
    .title-input {
      flex: 1; font-size: 18px; font-weight: 500; border: none; outline: none;
      padding: 4px 8px; border-radius: 4px;
      &:focus { background: #f5f5f5; }
    }
    .slug-display { font-size: 12px; color: #999; font-family: monospace; }
    .spacer { flex: 1; }

    .editor-body { display: flex; flex: 1; overflow: hidden; }

    .block-palette {
      width: 160px; flex-shrink: 0; border-right: 1px solid #e0e0e0;
      overflow-y: auto; padding: 8px; background: #fafafa;
    }
    .palette-title { font-size: 11px; font-weight: 600; color: #888; text-transform: uppercase;
                     letter-spacing: .5px; padding: 4px 4px 8px; }
    .palette-btn {
      display: flex; align-items: center; gap: 6px; width: 100%;
      padding: 6px 8px; margin-bottom: 2px; border: 1px solid #e0e0e0;
      border-radius: 4px; background: #fff; cursor: pointer; font-size: 12px; color: #333;
      mat-icon { font-size: 16px; width: 16px; height: 16px; color: #666; }
      &:hover { background: #e8f0fe; border-color: #3f51b5; color: #3f51b5; }
    }

    .canvas { flex: 1; overflow-y: auto; padding: 16px; background: #f5f5f5; }
    .block-list { display: flex; flex-direction: column; gap: 8px; min-height: 100px; }
    .block-card {
      background: #fff; border: 1px solid #e0e0e0; border-radius: 6px; overflow: hidden;
      &.cdk-drag-preview { box-shadow: 0 4px 16px rgba(0,0,0,.15); }
      &.cdk-drag-placeholder { opacity: .3; }
    }
    .block-header {
      display: flex; align-items: center; gap: 6px; padding: 6px 8px;
      border-bottom: 1px solid #f0f0f0; background: #fafafa;
    }
    .drag-handle { cursor: grab; color: #ccc; font-size: 20px; }
    .block-type-icon { font-size: 16px; width: 16px; height: 16px; color: #888; }
    .block-type-label { font-size: 12px; font-weight: 600; color: #555; text-transform: uppercase; letter-spacing: .3px; }
    .block-preview { padding: 8px 12px; font-size: 12px; color: #888;
                     white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .empty-canvas {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      height: 200px; color: #bbb; border: 2px dashed #e0e0e0; border-radius: 8px;
      mat-icon { font-size: 48px; width: 48px; height: 48px; margin-bottom: 8px; }
    }

    .meta-panel { width: 280px; flex-shrink: 0; border-left: 1px solid #e0e0e0; overflow-y: auto; background: #fff; }
    .meta-fields { padding: 12px; display: flex; flex-direction: column; gap: 0; }
    .full { width: 100%; }
    .img-preview-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .feat-preview { height: 60px; border-radius: 4px; border: 1px solid #e0e0e0; object-fit: cover; }
  `],
})
export class MarketingPageEditorComponent implements OnInit {
  private readonly route       = inject(ActivatedRoute);
  private readonly router      = inject(Router);
  private readonly pageService = inject(PageService);
  private readonly dialog      = inject(MatDialog);
  private readonly snack       = inject(MatSnackBar);

  readonly page       = signal<PageDto | null>(null);
  readonly blockTypes = signal<BlockTypeDto[]>([]);
  readonly loading    = signal(true);
  readonly saving     = signal(false);

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.pageService.blockTypes().subscribe(bt => this.blockTypes.set(bt));
    this.pageService.get(id).subscribe({
      next: p => { this.page.set(p); this.loading.set(false); },
      error: () => { this.loading.set(false); this.router.navigate(['/marketing/pages']); },
    });
  }

  addBlock(type: string) {
    const p = this.page();
    if (!p) return;
    const newBlock: BlockDto = {
      blockType: type, content: this.defaultContent(type),
      sortOrder: p.blocks.length,
    };
    this.page.set({ ...p, blocks: [...p.blocks, newBlock] });
  }

  editBlock(idx: number) {
    const p = this.page();
    if (!p) return;
    this.dialog.open(BlockEditorDialogComponent, {
      width: '600px',
      data: { block: p.blocks[idx], blockTypes: this.blockTypes() },
    }).afterClosed().subscribe((result: BlockDto | null) => {
      if (!result) return;
      const blocks = [...p.blocks];
      blocks[idx] = result;
      this.page.set({ ...p, blocks });
    });
  }

  removeBlock(idx: number) {
    const p = this.page();
    if (!p || !confirm('Supprimer ce bloc ?')) return;
    this.page.set({ ...p, blocks: p.blocks.filter((_, i) => i !== idx) });
  }

  dropBlock(event: CdkDragDrop<BlockDto[]>) {
    const p = this.page();
    if (!p) return;
    const blocks = [...p.blocks];
    moveItemInArray(blocks, event.previousIndex, event.currentIndex);
    this.page.set({ ...p, blocks });
  }

  pickFeaturedImage() {
    this.dialog.open(MediaPickerDialogComponent, { width: '720px' })
      .afterClosed().subscribe((url: string | undefined) => {
        const p = this.page();
        if (url && p) this.page.set({ ...p, featuredImageUrl: url });
      });
  }

  autoSave() { /* déclenché sur blur — on laisse save() manuel */ }

  save() {
    const p = this.page();
    if (!p) return;
    this.saving.set(true);
    this.pageService.save(p.id, p).subscribe({
      next: saved => { this.page.set(saved); this.saving.set(false); this.snack.open('Enregistré', '', { duration: 2000 }); },
      error: () => { this.saving.set(false); this.snack.open('Erreur enregistrement', 'Fermer', { duration: 4000 }); },
    });
  }

  togglePublish() {
    const p = this.page();
    if (!p) return;
    this.pageService.togglePublish(p.id).subscribe({
      next: updated => { this.page.set({ ...p, ...updated }); this.snack.open(updated.published ? 'Publié' : 'Dépublié', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  iconFor(type: string) { return this.blockTypes().find(b => b.type === type)?.icon ?? 'widgets'; }
  labelFor(type: string) { return this.blockTypes().find(b => b.type === type)?.label ?? type; }

  blockPreview(block: BlockDto): string {
    try {
      const c = JSON.parse(block.content || '{}');
      return c.title || c.html?.replace(/<[^>]+>/g,'')?.substring(0,80)
           || c.src || c.code?.substring(0,60) || c.widgetId || block.blockType;
    } catch { return block.blockType; }
  }

  private defaultContent(type: string): string {
    // Doit correspondre EXACTEMENT au format de l'éditeur Thymeleaf /intra/pages
    const defaults: Record<string, object> = {
      RICHTEXT:   { html: '' },
      IMAGE:      { src: '', alt: '', caption: '' },
      HERO:       { title: '', subtitle: '', bgType: 'color', bgColor: '#1e40af',
                    bgImage: '', bgOverlay: '0.4', textColor: '#ffffff',
                    ctaText: '', ctaUrl: '', height: '500px' },
      CTA:        { title: '', text: '', btnText: '', btnUrl: '', btnStyle: 'primary', bgColor: '#f0f9ff' },
      COLUMNS:    { col1Html: '', col2Html: '', col3Html: '' },
      TEXT_IMAGE: { html: '', src: '', alt: '', caption: '' },
      IMAGE_TEXT: { html: '', src: '', alt: '', caption: '' },
      CARDS:      { cards: '[]' },
      WIDGET:     { widgetId: '' },
      DIVIDER:    { style: 'solid', color: '#dee2e6' },
      SPACER:     { height: '2rem' },
      HTML:       { code: '' },
    };
    return JSON.stringify(defaults[type] ?? {});
  }
}
