import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatTableModule }        from '@angular/material/table';
import { MatSortModule, Sort }   from '@angular/material/sort';
import { MatButtonModule }       from '@angular/material/button';
import { MatIconModule }         from '@angular/material/icon';
import { MatFormFieldModule }    from '@angular/material/form-field';
import { MatInputModule }        from '@angular/material/input';
import { MatSelectModule }       from '@angular/material/select';
import { MatProgressBarModule }  from '@angular/material/progress-bar';
import { MatTooltipModule }      from '@angular/material/tooltip';
import { MatDividerModule }      from '@angular/material/divider';
import { MatChipsModule }        from '@angular/material/chips';
import { AdminRoleService }      from '../services/admin-role.service';
import { RoleOption }            from '../admin.model';

@Component({
  selector: 'app-admin-roles',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatTableModule, MatSortModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatProgressBarModule, MatTooltipModule, MatDividerModule, MatChipsModule,
  ],
  templateUrl: './admin-roles.component.html',
  styleUrl:    './admin-roles.component.scss',
})
export class AdminRolesComponent implements OnInit {
  private readonly svc = inject(AdminRoleService);
  private readonly fb  = inject(FormBuilder);

  readonly roles   = signal<RoleOption[]>([]);
  readonly loading = signal(true);
  readonly error   = signal<string | null>(null);

  // ── Tri ───────────────────────────────────────────────────────────────────
  readonly sortState = signal<Sort>({ active: 'label', direction: 'asc' });

  readonly sorted = computed(() => {
    const { active, direction } = this.sortState();
    const rows = [...this.roles()];
    if (!active || !direction) return rows;
    return rows.sort((a, b) => {
      const va = (a as any)[active] ?? '';
      const vb = (b as any)[active] ?? '';
      const cmp = String(va).localeCompare(String(vb), 'fr', { sensitivity: 'base' });
      return direction === 'asc' ? cmp : -cmp;
    });
  });

  onSort(s: Sort) { this.sortState.set(s); }

  // ── Formulaire création ───────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly createForm = this.fb.nonNullable.group({
    name:  ['', [Validators.required, Validators.pattern(/^[A-Z0-9_]+$/)]],
    label: ['', Validators.required],
    type:  ['ESN'],
  });
  readonly createError = signal<string | null>(null);
  readonly creating    = signal(false);

  // ── Édition inline ────────────────────────────────────────────────────────
  readonly editingId = signal<number | null>(null);
  readonly editForm  = this.fb.nonNullable.group({
    label: ['', Validators.required],
    type:  ['ESN'],
  });

  // ── Colonne ───────────────────────────────────────────────────────────────
  readonly columns = ['name', 'label', 'type', 'actions'];

  ngOnInit() { this.load(); }

  load() {
    this.loading.set(true);
    this.svc.getRoles().subscribe({
      next:  r  => { this.roles.set(r); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  // ── Création ──────────────────────────────────────────────────────────────
  submitCreate() {
    if (this.createForm.invalid) { this.createForm.markAllAsTouched(); return; }
    this.creating.set(true);
    this.createError.set(null);
    this.svc.createRole(this.createForm.getRawValue()).subscribe({
      next: r => {
        this.roles.update(list => [...list, r]);
        this.createForm.reset({ name: '', label: '', type: 'ESN' });
        this.showCreateForm.set(false);
        this.creating.set(false);
      },
      error: e => {
        this.createError.set(e?.error?.error ?? 'Erreur lors de la création.');
        this.creating.set(false);
      },
    });
  }

  // ── Édition ───────────────────────────────────────────────────────────────
  startEdit(role: RoleOption) {
    this.editingId.set(role.id);
    this.editForm.setValue({ label: role.label, type: role.type ?? 'ESN' });
  }

  cancelEdit() { this.editingId.set(null); }

  submitEdit(role: RoleOption) {
    if (this.editForm.invalid) return;
    this.svc.updateRole(role.id, this.editForm.getRawValue()).subscribe({
      next: updated => {
        this.roles.update(list => list.map(r => r.id === updated.id ? updated : r));
        this.editingId.set(null);
      },
      error: e => this.error.set(e?.error?.error ?? 'Erreur lors de la mise à jour.'),
    });
  }

  // ── Suppression ───────────────────────────────────────────────────────────
  deleteRole(role: RoleOption) {
    if (!confirm(`Supprimer le rôle "${role.label}" ?`)) return;
    this.svc.deleteRole(role.id).subscribe({
      next: () => this.roles.update(list => list.filter(r => r.id !== role.id)),
      error: e => this.error.set(e?.error?.error ?? 'Impossible de supprimer ce rôle.'),
    });
  }
}
