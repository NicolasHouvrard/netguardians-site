import { Component, inject, signal, OnInit } from '@angular/core';
import { FormsModule }        from '@angular/forms';
import { MatButtonModule }    from '@angular/material/button';
import { MatIconModule }      from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatCheckboxModule }  from '@angular/material/checkbox';
import { MatTooltipModule }   from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { HttpClient } from '@angular/common/http';

interface UserDto    { id: number; fullName: string; email: string; }
interface RequestTypeDto { name: string; label: string; }
interface MemberDto  { id: number; fullName: string; }
interface TeamDto {
  id: number; name: string; description: string | null;
  members: MemberDto[]; requestTypes: RequestTypeDto[];
}

@Component({
  selector: 'app-admin-teams',
  standalone: true,
  imports: [
    FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule,
    MatInputModule, MatCheckboxModule, MatTooltipModule, MatSnackBarModule,
  ],
  template: `
    <div class="wrap">
      <div class="toolbar">
        <h2><mat-icon>groups</mat-icon> Équipes</h2>
        <button mat-flat-button color="primary" (click)="openForm()">
          <mat-icon>add</mat-icon> Nouvelle équipe
        </button>
      </div>

      <!-- Table -->
      <table class="teams-table">
        <thead>
          <tr>
            <th>Équipe</th>
            <th>Membres</th>
            <th>Types de demande</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (team of teams(); track team.id) {
            <tr>
              <td class="col-name">
                <span class="name">{{ team.name }}</span>
                @if (team.description) {
                  <span class="desc">{{ team.description }}</span>
                }
              </td>
              <td class="col-members">
                @if (team.members.length === 0) {
                  <span class="empty-hint">Aucun membre</span>
                } @else {
                  <div class="chips">
                    @for (m of team.members; track m.id) {
                      <span class="chip">{{ m.fullName }}</span>
                    }
                  </div>
                }
              </td>
              <td class="col-types">
                @if (team.requestTypes.length === 0) {
                  <span class="empty-hint">Tous types</span>
                } @else {
                  <div class="chips">
                    @for (rt of team.requestTypes; track rt.name) {
                      <span class="chip type">{{ rt.label }}</span>
                    }
                  </div>
                }
              </td>
              <td class="col-actions">
                <button mat-icon-button (click)="openForm(team)" matTooltip="Modifier">
                  <mat-icon>edit</mat-icon>
                </button>
                <button mat-icon-button color="warn" (click)="deleteTeam(team)" matTooltip="Supprimer">
                  <mat-icon>delete</mat-icon>
                </button>
              </td>
            </tr>
          }
          @if (!loading() && teams().length === 0) {
            <tr><td colspan="4" class="empty">Aucune équipe créée.</td></tr>
          }
        </tbody>
      </table>

      <!-- Formulaire inline -->
      @if (showForm()) {
        <div class="form-panel">
          <h3>{{ editId() ? 'Modifier l\'équipe' : 'Nouvelle équipe' }}</h3>

          <div class="form-row">
            <mat-form-field class="full">
              <mat-label>Nom *</mat-label>
              <input matInput [(ngModel)]="form.name" placeholder="Nom de l'équipe">
            </mat-form-field>
            <mat-form-field class="full">
              <mat-label>Description</mat-label>
              <input matInput [(ngModel)]="form.description" placeholder="Optionnelle">
            </mat-form-field>
          </div>

          <div class="section-label">Membres</div>
          <div class="checkbox-grid">
            @for (u of users(); track u.id) {
              <mat-checkbox [(ngModel)]="selectedMembers[u.id]">
                {{ u.fullName || u.email }}
              </mat-checkbox>
            }
            @if (users().length === 0) {
              <span class="empty-hint">Aucun utilisateur interne trouvé.</span>
            }
          </div>

          <div class="section-label">Types de demande pris en charge <small>(vide = tous)</small></div>
          <div class="checkbox-grid">
            @for (rt of requestTypes(); track rt.name) {
              <mat-checkbox [(ngModel)]="selectedTypes[rt.name]">
                {{ rt.label }}
              </mat-checkbox>
            }
          </div>

          <div class="form-actions">
            <button mat-button (click)="cancelForm()">Annuler</button>
            <button mat-flat-button color="primary" (click)="saveForm()" [disabled]="!form.name.trim()">
              Enregistrer
            </button>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .wrap { max-width: 1000px; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px;
               h2 { display: flex; align-items: center; gap: 8px; margin: 0; } }

    .teams-table { width: 100%; border-collapse: collapse; font-size: 14px; margin-bottom: 24px; }
    th { text-align: left; font-size: 11px; font-weight: 600; color: #888; text-transform: uppercase;
         padding: 8px 10px; border-bottom: 2px solid #e0e0e0; }
    td { padding: 10px; border-bottom: 1px solid #f0f0f0; vertical-align: top; }

    .col-name {
      .name { display: block; font-weight: 500; }
      .desc { display: block; font-size: 12px; color: #888; }
    }
    .col-members, .col-types { max-width: 300px; }
    .col-actions { display: flex; gap: 0; white-space: nowrap; }

    .chips { display: flex; flex-wrap: wrap; gap: 4px; }
    .chip {
      font-size: 12px; padding: 2px 8px; border-radius: 10px;
      background: #f0f0f0; color: #444; border: 1px solid #e0e0e0;
      &.type { background: #e8eaf6; color: #3f51b5; border-color: #c5cae9; }
    }
    .empty-hint { font-size: 12px; color: #bbb; font-style: italic; }
    .empty { text-align: center; color: #aaa; padding: 32px; }

    .form-panel {
      background: #f8f9fa; border: 1px solid #e0e0e0; border-radius: 10px; padding: 24px;
      h3 { margin: 0 0 20px; font-size: 16px; }
    }
    .full { width: 100%; }
    .form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
    .section-label {
      font-size: 12px; font-weight: 600; color: #888; text-transform: uppercase;
      letter-spacing: .5px; margin: 8px 0 8px;
      small { font-size: 11px; text-transform: none; letter-spacing: 0; font-weight: 400; color: #bbb; }
    }
    .checkbox-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 4px 16px; margin-bottom: 16px; }
    .form-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
  `],
})
export class AdminTeamsComponent implements OnInit {
  private readonly http  = inject(HttpClient);
  private readonly snack = inject(MatSnackBar);

  readonly teams        = signal<TeamDto[]>([]);
  readonly users        = signal<UserDto[]>([]);
  readonly requestTypes = signal<RequestTypeDto[]>([]);
  readonly loading      = signal(true);
  readonly showForm     = signal(false);
  readonly editId       = signal<number | null>(null);

  form = { name: '', description: '' };
  selectedMembers: Record<number, boolean> = {};
  selectedTypes:   Record<string, boolean> = {};

  ngOnInit() {
    this.load();
    this.http.get<UserDto[]>('/api/intra/admin/teams/users').subscribe(u => this.users.set(u));
    this.http.get<RequestTypeDto[]>('/api/intra/admin/teams/request-types').subscribe(rt => this.requestTypes.set(rt));
  }

  load() {
    this.loading.set(true);
    this.http.get<TeamDto[]>('/api/intra/admin/teams').subscribe({
      next: t => { this.teams.set(t); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openForm(team?: TeamDto) {
    this.editId.set(team?.id ?? null);
    this.form = { name: team?.name ?? '', description: team?.description ?? '' };
    this.selectedMembers = {};
    this.selectedTypes   = {};
    if (team) {
      team.members.forEach(m => this.selectedMembers[m.id] = true);
      team.requestTypes.forEach(rt => this.selectedTypes[rt.name] = true);
    }
    this.showForm.set(true);
    setTimeout(() => document.querySelector('.form-panel')?.scrollIntoView({ behavior: 'smooth' }), 50);
  }

  cancelForm() { this.showForm.set(false); this.editId.set(null); }

  saveForm() {
    const body = {
      name: this.form.name.trim(),
      description: this.form.description.trim() || null,
      memberIds: Object.entries(this.selectedMembers).filter(([, v]) => v).map(([k]) => Number(k)),
      requestTypes: Object.entries(this.selectedTypes).filter(([, v]) => v).map(([k]) => k),
    };
    const id = this.editId();
    const obs = id
      ? this.http.put<TeamDto>(`/api/intra/admin/teams/${id}`, body)
      : this.http.post<TeamDto>('/api/intra/admin/teams', body);
    obs.subscribe({
      next: () => { this.cancelForm(); this.load(); this.snack.open('Enregistré', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }

  deleteTeam(team: TeamDto) {
    if (!confirm(`Supprimer l'équipe "${team.name}" ?`)) return;
    this.http.delete(`/api/intra/admin/teams/${team.id}`).subscribe({
      next: () => { this.teams.update(l => l.filter(t => t.id !== team.id)); this.snack.open('Supprimé', '', { duration: 2000 }); },
      error: () => this.snack.open('Erreur', 'Fermer', { duration: 4000 }),
    });
  }
}
