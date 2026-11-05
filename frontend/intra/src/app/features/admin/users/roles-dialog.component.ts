import { Component, inject, signal, OnInit } from '@angular/core';
import { FormBuilder, ReactiveFormsModule }    from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatCheckboxModule }     from '@angular/material/checkbox';
import { MatButtonModule }       from '@angular/material/button';
import { MatDividerModule }      from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AdminRoleService }      from '../services/admin-role.service';
import { AdminUserService }      from '../services/admin-user.service';
import { AdminUser, RoleOption } from '../admin.model';

export interface RolesDialogData {
  user: AdminUser;
}

@Component({
  selector: 'app-roles-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule, MatCheckboxModule, MatButtonModule,
    MatDividerModule, MatProgressSpinnerModule,
  ],
  template: `
    <h2 mat-dialog-title>Rôles — {{ data.user.fullName }}</h2>

    <mat-dialog-content>
      @if (loading()) {
        <div class="loading"><mat-spinner diameter="24" /></div>
      } @else {
        @if (error()) {
          <p class="err">{{ error() }}</p>
        }

        @for (group of groups(); track group.type) {
          <div class="group">
            <div class="group-title">{{ group.type ?? 'Autres' }}</div>
            <div class="group-roles" [formGroup]="form">
              @for (r of group.roles; track r.name) {
                <mat-checkbox [formControlName]="r.name">
                  {{ r.label }}
                </mat-checkbox>
              }
            </div>
          </div>
          <mat-divider />
        }
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button (click)="dialogRef.close()">Annuler</button>
      <button mat-flat-button color="primary"
              [disabled]="loading() || saving()"
              (click)="save()">
        Enregistrer
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: 340px; max-height: 70vh; }
    .loading     { display: flex; justify-content: center; padding: 24px; }
    .err         { color: #c62828; font-size: 13px; }
    .group       { padding: 12px 0 4px; }
    .group-title { font-size: 11px; font-weight: 600; text-transform: uppercase;
                   letter-spacing: .5px; color: #888; margin-bottom: 8px; }
    .group-roles { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; }
  `],
})
export class RolesDialogComponent implements OnInit {
  readonly dialogRef = inject(MatDialogRef<RolesDialogComponent>);
  readonly data: RolesDialogData = inject(MAT_DIALOG_DATA);
  private readonly rolesSvc = inject(AdminRoleService);
  private readonly usersSvc = inject(AdminUserService);
  private readonly fb  = inject(FormBuilder);

  readonly loading = signal(true);
  readonly saving  = signal(false);
  readonly error   = signal<string | null>(null);
  readonly groups  = signal<{ type: string | null; roles: RoleOption[] }[]>([]);

  form = this.fb.nonNullable.group({} as Record<string, boolean>);

  ngOnInit() {
    this.rolesSvc.getRoles().subscribe({
      next: roles => {
        // Grouper par type
        const map = new Map<string, RoleOption[]>();
        roles.forEach(r => {
          const key = r.type ?? 'Autres';
          if (!map.has(key)) map.set(key, []);
          map.get(key)!.push(r);
        });
        this.groups.set([...map.entries()].map(([type, rs]) => ({ type, roles: rs })));

        // Construire le formulaire avec les rôles actifs cochés
        const controls: Record<string, boolean> = {};
        roles.forEach(r => {
          controls[r.name] = this.data.user.roles.includes(r.name);
        });
        this.form = this.fb.nonNullable.group(controls);
        this.loading.set(false);
      },
      error: () => { this.error.set('Impossible de charger les rôles.'); this.loading.set(false); },
    });
  }

  save() {
    this.saving.set(true);
    const selected = Object.entries(this.form.getRawValue())
      .filter(([, v]) => v)
      .map(([k]) => k);
    this.usersSvc.setRoles(this.data.user.id, selected).subscribe({
      next: updated => this.dialogRef.close(updated),
      error: e => {
        this.error.set(e?.error?.error ?? 'Erreur lors de la sauvegarde.');
        this.saving.set(false);
      },
    });
  }
}
