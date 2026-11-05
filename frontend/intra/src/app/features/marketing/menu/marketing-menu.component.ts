import { Component, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormsModule }          from '@angular/forms';
import { CdkDragDrop, CdkDropList, CdkDrag, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatButtonModule }  from '@angular/material/button';
import { MatIconModule }    from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatCheckboxModule }  from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MenuService, MenuItemDto, PageLinkDto, SaveMenuItemRequest } from '../services/menu.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-marketing-menu',
  standalone: true,
  imports: [
    FormsModule, CdkDropList, CdkDrag,
    MatButtonModule, MatIconModule, MatDialogModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule,
    MatSnackBarModule,
  ],
  template: `
    <div class="menu-wrap">
      <div class="toolbar">
        <h2><mat-icon>menu</mat-icon> Menu {{ position() === 'FOOTER' ? 'pied de page' : 'principal' }}</h2>
        <button mat-flat-button color="primary" (click)="openForm()">
          <mat-icon>add</mat-icon> Ajouter un élément
        </button>
      </div>

      <!-- Liste drag & drop -->
      <div cdkDropList (cdkDropListDropped)="drop($event)" class="item-list">
        @for (item of items(); track item.id) {
          <div cdkDrag class="menu-item-row">
            <mat-icon cdkDragHandle class="drag-handle">drag_indicator</mat-icon>
            <div class="item-info">
              <span class="item-label">{{ item.label }}</span>
              <span class="item-url">{{ item.linkedPageSlug ? '/' + item.linkedPageSlug : item.url || '#' }}</span>
              @if (!item.active) { <span class="badge-inactive">Inactif</span> }
            </div>
            <div class="item-actions">
              <button mat-icon-button (click)="openForm(item)" title="Modifier">
                <mat-icon>edit</mat-icon>
              </button>
              <button mat-icon-button color="warn" (click)="deleteItem(item)" title="Supprimer">
                <mat-icon>delete</mat-icon>
              </button>
            </div>

            <!-- Sous-éléments -->
            @if (item.children.length) {
              <div class="children">
                @for (child of item.children; track child.id) {
                  <div class="child-row">
                    <mat-icon>subdirectory_arrow_right</mat-icon>
                    <span class="item-label">{{ child.label }}</span>
                    <span class="item-url">{{ child.linkedPageSlug ? '/' + child.linkedPageSlug : child.url || '#' }}</span>
                    <div class="item-actions">
                      <button mat-icon-button (click)="openForm(child)" title="Modifier">
                        <mat-icon>edit</mat-icon>
                      </button>
                      <button mat-icon-button color="warn" (click)="deleteItem(child)">
                        <mat-icon>delete</mat-icon>
                      </button>
                    </div>
                  </div>
                }
              </div>
            }
          </div>
        }
        @if (!loading() && items().length === 0) {
          <div class="empty">Aucun élément. Cliquez sur "Ajouter" pour commencer.</div>
        }
      </div>

      <!-- Panneau de formulaire (inline) -->
      @if (showForm()) {
        <div class="form-panel">
          <h3>{{ editItem()?.id ? 'Modifier' : 'Ajouter' }} un élément</h3>
          <div class="form-grid">
            <mat-form-field>
              <mat-label>Libellé *</mat-label>
              <input matInput [(ngModel)]="form.label" name="label">
            </mat-form-field>
            <mat-form-field>
              <mat-label>URL externe</mat-label>
              <input matInput [(ngModel)]="form.url" name="url" placeholder="https://…">
            </mat-form-field>
            <mat-form-field>
              <mat-label>Page interne</mat-label>
              <mat-select [(ngModel)]="form.linkedPageId" name="linkedPageId">
                <mat-option [value]="null">— aucune —</mat-option>
                @for (p of pages(); track p.id) {
                  <mat-option [value]="p.id">{{ p.title }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Parent</mat-label>
              <mat-select [(ngModel)]="form.parentId" name="parentId">
                <mat-option [value]="null">— aucun —</mat-option>
                @for (p of items(); track p.id) {
                  @if (p.id !== editItem()?.id) {
                    <mat-option [value]="p.id">{{ p.label }}</mat-option>
                  }
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Ouverture</mat-label>
              <mat-select [(ngModel)]="form.target" name="target">
                <mat-option value="_self">Même onglet</mat-option>
                <mat-option value="_blank">Nouvel onglet</mat-option>
              </mat-select>
            </mat-form-field>
            <div class="checkbox-row">
              <mat-checkbox [(ngModel)]="form.active" name="active">Actif</mat-checkbox>
            </div>
          </div>
          <div class="form-actions">
            <button mat-button (click)="cancelForm()">Annuler</button>
            <button mat-flat-button color="primary" (click)="saveForm()" [disabled]="!form.label">
              {{ editItem()?.id ? 'Enregistrer' : 'Ajouter' }}
            </button>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .menu-wrap { max-width: 800px; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px;
               h2 { display: flex; align-items: center; gap: 8px; margin: 0; } }
    .item-list { display: flex; flex-direction: column; gap: 4px; min-height: 40px; }
    .menu-item-row {
      background: #fff; border: 1px solid #e0e0e0; border-radius: 6px;
      padding: 8px 12px; display: flex; align-items: flex-start; gap: 8px; flex-wrap: wrap;
      &.cdk-drag-preview { box-shadow: 0 4px 12px rgba(0,0,0,.15); }
      &.cdk-drag-placeholder { opacity: .3; }
    }
    .drag-handle { cursor: grab; color: #bbb; margin-top: 4px; }
    .item-info { flex: 1; display: flex; flex-direction: column; gap: 2px; }
    .item-label { font-size: 14px; font-weight: 500; }
    .item-url   { font-size: 12px; color: #888; }
    .badge-inactive { font-size: 10px; background: #ffeaa7; color: #e17055; padding: 1px 6px; border-radius: 10px; }
    .item-actions { display: flex; gap: 0; }
    .children { width: 100%; padding-left: 24px; display: flex; flex-direction: column; gap: 4px; margin-top: 4px; }
    .child-row {
      display: flex; align-items: center; gap: 8px;
      background: #f9f9f9; border: 1px solid #eee; border-radius: 4px; padding: 4px 8px;
      .item-actions { margin-left: auto; }
    }
    .empty { color: #aaa; text-align: center; padding: 32px; border: 2px dashed #e0e0e0; border-radius: 8px; }
    .form-panel {
      margin-top: 24px; background: #f8f9fa; border-radius: 8px;
      border: 1px solid #e0e0e0; padding: 20px;
      h3 { margin: 0 0 16px; font-size: 16px; }
    }
    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
    .checkbox-row { display: flex; align-items: center; padding: 8px 0; }
    .form-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
  `],
})
export class MarketingMenuComponent implements OnInit, OnDestroy {
  private readonly menuService = inject(MenuService);
  private readonly snack       = inject(MatSnackBar);
  private readonly route       = inject(ActivatedRoute);
  private sub?: Subscription;

  readonly items    = signal<MenuItemDto[]>([]);
  readonly pages    = signal<PageLinkDto[]>([]);
  readonly loading  = signal(true);
  readonly showForm = signal(false);
  readonly editItem = signal<MenuItemDto | null>(null);
  readonly position = signal('MAIN');

  form: SaveMenuItemRequest = this.emptyForm();

  ngOnInit() {
    this.sub = this.route.queryParams.subscribe(p => {
      this.position.set(p['position'] || 'MAIN');
      this.load();
    });
    this.menuService.pages().subscribe(p => this.pages.set(p));
  }

  ngOnDestroy() { this.sub?.unsubscribe(); }

  load() {
    this.loading.set(true);
    this.menuService.list(this.position()).subscribe({
      next: items => { this.items.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openForm(item?: MenuItemDto) {
    this.editItem.set(item ?? null);
    this.form = item ? {
      label: item.label, url: item.url, linkedPageId: item.linkedPageId,
      parentId: item.parentId, position: this.position(), target: item.target, active: item.active,
    } : this.emptyForm();
    this.showForm.set(true);
  }

  cancelForm() { this.showForm.set(false); this.editItem.set(null); }

  saveForm() {
    const req = { ...this.form, position: this.position() };
    const item = this.editItem();
    const obs = item?.id
      ? this.menuService.update(item.id, req)
      : this.menuService.create(req);
    obs.subscribe({
      next: () => { this.cancelForm(); this.load(); this.snack.open('Enregistré', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  deleteItem(item: MenuItemDto) {
    if (!confirm(`Supprimer "${item.label}" ?`)) return;
    this.menuService.delete(item.id).subscribe({
      next: () => { this.load(); this.snack.open('Supprimé', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  drop(event: CdkDragDrop<MenuItemDto[]>) {
    const list = [...this.items()];
    moveItemInArray(list, event.previousIndex, event.currentIndex);
    this.items.set(list);
    const order = list.map((item, idx) => ({ id: item.id, parentId: item.parentId, sortOrder: idx }));
    this.menuService.saveOrder(order).subscribe();
  }

  private emptyForm(): SaveMenuItemRequest {
    return { label: '', url: null, linkedPageId: null, parentId: null,
             position: this.position(), target: '_self', active: true };
  }
}
