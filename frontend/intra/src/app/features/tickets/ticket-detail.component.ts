import { Component, inject, signal, resource, effect, computed } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';

import { MatCardModule }        from '@angular/material/card';
import { MatChipsModule }       from '@angular/material/chips';
import { MatIconModule }        from '@angular/material/icon';
import { MatButtonModule }      from '@angular/material/button';
import { MatSelectModule }      from '@angular/material/select';
import { MatFormFieldModule }   from '@angular/material/form-field';
import { MatInputModule }       from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule }     from '@angular/material/divider';
import { MatTooltipModule }     from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

import { TicketService, AssignRequest } from './ticket.service';
import { Ticket }                       from './ticket.model';

const STATUS_COLOR:   Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  NEW: 'primary', IN_PROGRESS: 'accent', CLOSED: '', CANCELLED: 'warn',
};
const PRIORITY_COLOR: Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  LOW: '', MEDIUM: 'primary', HIGH: 'accent', URGENT: 'warn',
};

@Component({
  selector: 'app-ticket-detail',
  standalone: true,
  imports: [
    RouterLink, ReactiveFormsModule, DatePipe,
    MatCardModule, MatChipsModule, MatIconModule, MatButtonModule,
    MatSelectModule, MatFormFieldModule, MatInputModule,
    MatProgressBarModule, MatDividerModule, MatTooltipModule,
    MatSlideToggleModule,
  ],
  template: `
    <div class="breadcrumb">
      <a mat-button routerLink="..">
        <mat-icon>arrow_back</mat-icon> Tickets
      </a>
    </div>

    @if (ticket.isLoading()) { <mat-progress-bar mode="indeterminate" /> }

    @if (ticket.value(); as t) {

      <!-- ── Fiche client (au-dessus du titre) ──────────────────── -->
      <div class="client-banner">
        <mat-icon class="client-icon">business</mat-icon>
        <div class="client-info">
          <div class="client-name">{{ t.customerName ?? 'Aucun client rattaché' }}</div>
          <div class="client-sub">
            @if (t.siteName)    { <span><mat-icon>place</mat-icon>{{ t.siteName }}</span> }
            @if (t.contactName) { <span><mat-icon>person</mat-icon>{{ t.contactName }}</span> }
            @if (t.contactEmail) { <span><mat-icon>email</mat-icon>{{ t.contactEmail }}</span> }
            @if (!t.siteName && !t.contactName && !t.contactEmail && t.senderEmail) {
              <span><mat-icon>email</mat-icon>{{ t.senderEmail }}</span>
            }
          </div>
        </div>
      </div>

      <!-- En-tête -->
      <div class="ticket-header">
        <span class="ticket-id">#{{ t.id }}</span>
        <h1 class="ticket-title">{{ t.title }}</h1>
        <div class="header-chips">
          <mat-chip [color]="statusColor(t.status)" highlighted>{{ t.statusLabel }}</mat-chip>
          @if (t.priorityLabel) {
            <mat-chip [color]="priorityColor(t.priority!)" highlighted>{{ t.priorityLabel }}</mat-chip>
          }
          @if (t.typeLabel) { <mat-chip>{{ t.typeLabel }}</mat-chip> }
        </div>
      </div>

      <!-- Alerte email non rattaché -->
      @if (t.senderEmail && !t.contactId) {
        <div class="email-alert">
          <mat-icon>warning</mat-icon>
          E-mail <strong>{{ t.senderEmail }}</strong> non rattaché à un contact.
          <button mat-button (click)="showAssign.set(true)">Rattacher →</button>
        </div>
      }

      <div class="ticket-layout">

        <!-- ── Colonne principale ──────────────────────────────── -->
        <div class="main-col">

          <!-- Description -->
          @if (t.description) {
            <mat-card appearance="outlined" class="mb-16">
              <mat-card-header><mat-card-title>Description</mat-card-title></mat-card-header>
              <mat-card-content>
                <pre class="description">{{ t.description }}</pre>
              </mat-card-content>
            </mat-card>
          }

          <!-- Pièces jointes -->
          <mat-card appearance="outlined" class="mb-16">
            <mat-card-header>
              <mat-card-title>Pièces jointes</mat-card-title>
              <button mat-stroked-button (click)="fileInput.click()"
                      [disabled]="uploading()" style="margin-left:auto">
                <mat-icon>attach_file</mat-icon> Ajouter
              </button>
              <input #fileInput type="file" hidden (change)="onFileSelected($event, t.id)" />
            </mat-card-header>
            <mat-card-content>
              @if (uploading()) { <mat-progress-bar mode="indeterminate" /> }
              @if (t.attachments.length === 0 && !uploading()) {
                <p class="empty-block">Aucune pièce jointe.</p>
              }
              <div class="attach-grid">
                @for (a of t.attachments; track a.id) {
                  <div class="attach-item">
                    <a [href]="a.url" target="_blank" class="attach-link">
                      <mat-icon>{{ a.image ? 'image' : 'description' }}</mat-icon>
                      <div class="attach-meta">
                        <span class="attach-name">{{ a.originalName }}</span>
                        <span class="attach-size">{{ a.displaySize }}</span>
                      </div>
                    </a>
                    <button mat-icon-button (click)="removeAttachment(t.id, a.id)"
                            matTooltip="Supprimer">
                      <mat-icon>delete</mat-icon>
                    </button>
                  </div>
                }
              </div>
            </mat-card-content>
          </mat-card>

          <!-- Timeline commentaires -->
          <mat-card appearance="outlined" class="mb-16">
            <mat-card-header><mat-card-title>Activité</mat-card-title></mat-card-header>
            <mat-card-content>
              @if (t.comments.length === 0) {
                <p class="empty-block">Aucun commentaire pour le moment.</p>
              }
              @for (c of t.comments; track c.id) {
                <div class="comment-item" [class.private]="!c.isPublic">
                  <div class="comment-meta">
                    <mat-icon class="comment-icon">{{ c.isPublic ? 'chat' : 'lock' }}</mat-icon>
                    <span class="comment-author">{{ c.authorName ?? 'Système' }}</span>
                    <span class="comment-date">{{ c.createdAt | date:'dd/MM/yyyy HH:mm' }}</span>
                    @if (!c.isPublic) { <span class="private-badge">Privé</span> }
                  </div>
                  <p class="comment-content">{{ c.content }}</p>
                  @if (c.sourceEmail) {
                    <div class="comment-source"><mat-icon>email</mat-icon> {{ c.sourceEmail }}</div>
                  }
                </div>
              }

              <mat-divider class="comment-divider" />
              <form [formGroup]="commentForm" (ngSubmit)="submitComment(t.id)" class="comment-form">
                <mat-form-field appearance="outline" style="width:100%">
                  <mat-label>Ajouter un commentaire</mat-label>
                  <textarea matInput formControlName="content" rows="3"
                            placeholder="Écrivez un commentaire..."></textarea>
                </mat-form-field>
                <div class="comment-actions">
                  <mat-slide-toggle formControlName="isPublic" color="primary">
                    {{ commentForm.value.isPublic ? 'Public (visible client)' : 'Privé (interne)' }}
                  </mat-slide-toggle>
                  <button mat-flat-button type="submit" [disabled]="commentForm.invalid || saving()">
                    <mat-icon>send</mat-icon> Envoyer
                  </button>
                </div>
              </form>
            </mat-card-content>
          </mat-card>

        </div>

        <!-- ── Colonne latérale ────────────────────────────────── -->
        <aside class="side-col">

          <!-- Statut + infos -->
          <mat-card appearance="outlined" class="mb-16">
            <mat-card-content class="info-card-content">
              <div class="info-label">Statut</div>
              <mat-form-field appearance="outline" subscriptSizing="dynamic" style="width:100%">
                <mat-select [formControl]="statusCtrl" (selectionChange)="changeStatus(t.id)">
                  @for (s of svc.meta.value()?.statuses || []; track s.value) {
                    <mat-option [value]="s.value">{{ s.label }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>

              <mat-divider class="info-divider" />

              <dl class="info-grid">
                @if (t.priority) {
                  <dt>Priorité</dt>
                  <dd><mat-chip [color]="priorityColor(t.priority)" highlighted>{{ t.priorityLabel }}</mat-chip></dd>
                }
                @if (t.typeLabel) { <dt>Type</dt> <dd>{{ t.typeLabel }}</dd> }
                <dt>Créé le</dt>
                <dd>{{ t.createdAt | date:'dd/MM/yyyy HH:mm' }}</dd>
              </dl>
            </mat-card-content>
          </mat-card>

          <!-- Assignation -->
          <mat-card appearance="outlined" class="mb-16">
            <mat-card-header>
              <mat-card-title>Assignation</mat-card-title>
              <button mat-icon-button (click)="showAssign.set(!showAssign())"
                      [matTooltip]="showAssign() ? 'Fermer' : 'Modifier'" style="margin-left:auto">
                <mat-icon>{{ showAssign() ? 'close' : 'edit' }}</mat-icon>
              </button>
            </mat-card-header>
            <mat-card-content>
              @if (!showAssign()) {
                <dl class="info-grid">
                  <dt>Client</dt>     <dd>{{ t.customerName ?? '—' }}</dd>
                  @if (t.siteName)    { <dt>Site</dt> <dd>{{ t.siteName }}</dd> }
                  @if (t.contactName) { <dt>Contact</dt> <dd>{{ t.contactName }}</dd> }
                  <dt>Équipe</dt>     <dd>{{ t.teamName ?? '—' }}</dd>
                  <dt>Technicien</dt> <dd>{{ t.assignedToName ?? '—' }}</dd>
                </dl>
              } @else {
                <form [formGroup]="assignForm" (ngSubmit)="submitAssign(t.id)" class="assign-form">
                  <mat-form-field appearance="outline" subscriptSizing="dynamic">
                    <mat-label>Client</mat-label>
                    <mat-select formControlName="customerId" (selectionChange)="onCustomerChange($event.value)">
                      <mat-option [value]="null">—</mat-option>
                      @for (c of customers(); track c.id) {
                        <mat-option [value]="c.id">{{ c.name }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>

                  @if (assignForm.value.customerId) {
                    <mat-form-field appearance="outline" subscriptSizing="dynamic">
                      <mat-label>Site</mat-label>
                      <mat-select formControlName="siteId" (selectionChange)="onSiteChange($event.value)">
                        <mat-option [value]="null">—</mat-option>
                        @for (s of sites(); track s.id) {
                          <mat-option [value]="s.id">{{ s.name }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline" subscriptSizing="dynamic">
                      <mat-label>Contact</mat-label>
                      <mat-select formControlName="contactId">
                        <mat-option [value]="null">—</mat-option>
                        @for (c of contacts(); track c.id) {
                          <mat-option [value]="c.id">
                            {{ c.fullName }} @if (c.email) { ({{ c.email }}) }
                          </mat-option>
                        }
                      </mat-select>
                    </mat-form-field>
                  }

                  <mat-divider />

                  <!-- Équipe → met à jour la liste des techniciens -->
                  <mat-form-field appearance="outline" subscriptSizing="dynamic">
                    <mat-label>Équipe</mat-label>
                    <mat-select formControlName="teamId" (selectionChange)="onTeamChange($event.value)">
                      <mat-option [value]="null">—</mat-option>
                      @for (team of teams(); track team.id) {
                        <mat-option [value]="team.id">{{ team.name }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>

                  <mat-form-field appearance="outline" subscriptSizing="dynamic">
                    <mat-label>Technicien</mat-label>
                    <mat-select formControlName="assignedToId">
                      <mat-option [value]="null">—</mat-option>
                      @for (u of availableUsers(); track u.id) {
                        <mat-option [value]="u.id">{{ u.fullName }}</mat-option>
                      }
                    </mat-select>
                    @if (assignForm.value.teamId) {
                      <mat-hint>Membres de l'équipe sélectionnée</mat-hint>
                    }
                  </mat-form-field>

                  <button mat-flat-button type="submit" [disabled]="saving()">
                    <mat-icon>save</mat-icon> Enregistrer
                  </button>
                </form>
              }
            </mat-card-content>
          </mat-card>

          <!-- Interventions liées -->
          <mat-card appearance="outlined" class="mb-16">
            <mat-card-header><mat-card-title>Interventions</mat-card-title></mat-card-header>
            <mat-card-content>
              @if (t.interventions.length === 0) {
                <p class="empty-block">Aucune intervention liée.</p>
              }
              @for (i of t.interventions; track i.id) {
                <a class="linked-item" [href]="'/intra/interventions/' + i.id" target="_blank">
                  <mat-icon>build</mat-icon>
                  <div class="linked-meta">
                    <span class="linked-title">{{ i.reference || '#' + i.id }} — {{ i.title }}</span>
                    @if (i.statusLabel) { <span class="linked-sub">{{ i.statusLabel }}</span> }
                  </div>
                </a>
              }
              <a class="add-link" [href]="'/intra/tickets/' + t.id" target="_blank">
                <mat-icon>add</mat-icon> Créer une intervention
              </a>
            </mat-card-content>
          </mat-card>

          @if (saveMsg()) {
            <div class="save-msg" [class.ok]="saveMsg() === 'ok'" [class.err]="saveMsg() === 'err'">
              <mat-icon>{{ saveMsg() === 'ok' ? 'check_circle' : 'error' }}</mat-icon>
              {{ saveMsg() === 'ok' ? 'Enregistré.' : 'Erreur lors de la sauvegarde.' }}
            </div>
          }

        </aside>
      </div>
    }

    @if (!ticket.isLoading() && !ticket.value()) {
      <div class="not-found">
        <mat-icon>help_outline</mat-icon>
        <p>Ticket introuvable.</p>
        <a mat-stroked-button routerLink="..">Retour à la liste</a>
      </div>
    }
  `,
  styles: [`
    .breadcrumb { margin-bottom: 8px; }

    /* Fiche client */
    .client-banner {
      display: flex; align-items: center; gap: 12px;
      padding: 12px 16px; margin-bottom: 16px;
      background: #f0f4ff; border: 1px solid #c5d3f5; border-radius: 8px;
    }
    .client-icon { color: #3f51b5; font-size: 28px; width: 28px; height: 28px; }
    .client-info { min-width: 0; }
    .client-name { font-size: 16px; font-weight: 600; color: #1a237e; }
    .client-sub  {
      display: flex; gap: 14px; flex-wrap: wrap; margin-top: 2px;
      font-size: 12px; color: #5c6bc0;
      span { display: inline-flex; align-items: center; gap: 3px; }
      mat-icon { font-size: 13px; width: 13px; height: 13px; }
    }

    /* En-tête */
    .ticket-header {
      display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; margin-bottom: 16px;
    }
    .ticket-id    { font-size: 13px; color: #888; font-weight: 500; padding-top: 6px; }
    .ticket-title { margin: 0; font-size: 22px; font-weight: 600; flex: 1; min-width: 0; }
    .header-chips { display: flex; gap: 6px; flex-wrap: wrap; padding-top: 4px; }

    .email-alert {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
      padding: 10px 14px; margin-bottom: 16px;
      background: #fff3e0; border-left: 4px solid #ff9800;
      border-radius: 0 6px 6px 0; font-size: 13px;
      mat-icon { color: #ff9800; }
    }

    .ticket-layout {
      display: grid; grid-template-columns: 1fr 300px; gap: 20px; align-items: start;
    }
    .mb-16 { margin-bottom: 16px; }

    pre.description {
      white-space: pre-wrap; word-break: break-word;
      font-family: inherit; font-size: 14px; line-height: 1.6; margin: 0; color: #333;
    }

    .empty-block { text-align: center; color: #aaa; padding: 12px 0; font-size: 13px; }

    /* Pièces jointes */
    .attach-grid { display: flex; flex-direction: column; gap: 6px; }
    .attach-item {
      display: flex; align-items: center; gap: 4px;
      border: 1px solid #eee; border-radius: 6px; padding: 6px 8px;
    }
    .attach-link {
      display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0;
      text-decoration: none; color: inherit;
      mat-icon { color: #5c6bc0; }
    }
    .attach-meta { display: flex; flex-direction: column; min-width: 0; }
    .attach-name { font-size: 13px; font-weight: 500; white-space: nowrap;
                   overflow: hidden; text-overflow: ellipsis; }
    .attach-size { font-size: 11px; color: #999; }

    /* Timeline */
    .comment-item {
      padding: 12px 0; border-bottom: 1px solid #f0f0f0;
      &:last-of-type { border-bottom: none; }
      &.private { background: #fffde7; padding: 10px 12px; border-radius: 6px;
                   margin-bottom: 4px; border-bottom: none; }
    }
    .comment-meta {
      display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap;
      font-size: 12px; color: #888;
    }
    .comment-icon   { font-size: 16px; width: 16px; height: 16px; }
    .comment-author { font-weight: 500; color: #444; }
    .private-badge  { background: #fff9c4; color: #f57f17; border-radius: 4px;
                      padding: 1px 6px; font-size: 11px; font-weight: 500; }
    .comment-content { margin: 0; font-size: 13px; line-height: 1.5; white-space: pre-wrap; }
    .comment-source {
      display: flex; align-items: center; gap: 4px; font-size: 11px; color: #999; margin-top: 4px;
      mat-icon { font-size: 12px; width: 12px; height: 12px; }
    }
    .comment-divider { margin: 16px 0 12px; }
    .comment-form    { display: flex; flex-direction: column; gap: 8px; }
    .comment-actions {
      display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px;
    }

    /* Panneau info */
    .info-card-content { padding: 12px 16px !important; }
    .info-label   { font-size: 12px; font-weight: 500; color: #666; margin-bottom: 6px; }
    .info-divider { margin: 12px 0; }
    .info-grid {
      display: grid; grid-template-columns: 80px 1fr; gap: 8px 12px; font-size: 13px; margin: 0;
      dt { font-weight: 500; color: #666; }
      dd { margin: 0; word-break: break-word; }
    }

    .assign-form {
      display: flex; flex-direction: column; gap: 8px; padding-top: 4px;
      mat-form-field { width: 100%; }
    }

    /* Liens interventions */
    .linked-item {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 0; border-bottom: 1px solid #f0f0f0;
      text-decoration: none; color: inherit;
      &:last-of-type { border-bottom: none; }
      mat-icon { color: #fd7e14; }
    }
    .linked-meta  { display: flex; flex-direction: column; min-width: 0; }
    .linked-title { font-size: 13px; font-weight: 500; }
    .linked-sub   { font-size: 11px; color: #999; }
    .add-link {
      display: flex; align-items: center; gap: 4px; margin-top: 8px;
      font-size: 13px; color: #3f51b5; text-decoration: none;
      mat-icon { font-size: 18px; width: 18px; height: 18px; }
    }

    .save-msg {
      display: flex; align-items: center; gap: 6px; padding: 8px 12px; border-radius: 6px; font-size: 13px;
      mat-icon { font-size: 16px; width: 16px; height: 16px; }
      &.ok  { background: #e8f5e9; color: #2e7d32; }
      &.err { background: #fdecea; color: #c62828; }
    }

    .not-found {
      display: flex; flex-direction: column; align-items: center; padding: 64px 0; color: #aaa;
      mat-icon { font-size: 48px; width: 48px; height: 48px; margin-bottom: 12px; }
    }

    @media (max-width: 900px) {
      .ticket-layout { grid-template-columns: 1fr; }
    }
  `],
})
export class TicketDetailComponent {
  readonly svc   = inject(TicketService);
  private  route = inject(ActivatedRoute);
  private  fb    = inject(FormBuilder);

  private readonly id = Number(this.route.snapshot.paramMap.get('id'));

  readonly ticket = resource<Ticket, number>({
    params: () => this.id,
    loader: ({ params }) => firstValueFrom(this.svc.getById(params)),
  });

  // ── State ──────────────────────────────────────────────────────────────────

  readonly saving     = signal(false);
  readonly uploading  = signal(false);
  readonly saveMsg    = signal<'ok' | 'err' | null>(null);
  readonly showAssign = signal(false);

  readonly sites    = signal<{ id: number; name: string }[]>([]);
  readonly contacts = signal<{ id: number; fullName: string; email: string }[]>([]);

  readonly customers = computed(() => this.svc.formMeta.value()?.customers ?? []);
  readonly teams     = computed(() => this.svc.formMeta.value()?.teams ?? []);
  readonly users     = computed(() => this.svc.formMeta.value()?.users ?? []);

  // ── Formulaires ────────────────────────────────────────────────────────────

  readonly statusCtrl  = new FormControl('');

  readonly commentForm = this.fb.group({
    content:  ['', Validators.required],
    isPublic: [true],
  });

  readonly assignForm = this.fb.group({
    customerId:   [null as number | null],
    siteId:       [null as number | null],
    contactId:    [null as number | null],
    teamId:       [null as number | null],
    assignedToId: [null as number | null],
  });

  /** Équipe sélectionnée dans le formulaire (réactif). */
  private readonly teamIdSig = toSignal(this.assignForm.controls.teamId.valueChanges, {
    initialValue: this.assignForm.controls.teamId.value,
  });

  /** Liste des techniciens filtrée selon l'équipe choisie. */
  readonly availableUsers = computed(() => {
    const teamId = this.teamIdSig();
    const all    = this.users();
    if (!teamId) return all;
    const team = this.teams().find(t => t.id === teamId);
    if (!team) return all;
    return all.filter(u => team.memberIds.includes(u.id));
  });

  constructor() {
    effect(() => {
      const t = this.ticket.value();
      if (!t) return;
      this.statusCtrl.setValue(t.status, { emitEvent: false });
      this.assignForm.patchValue({
        customerId:   t.customerId,
        siteId:       t.siteId,
        contactId:    t.contactId,
        teamId:       t.teamId,
        assignedToId: t.assignedToId,
      }, { emitEvent: false });
      if (t.customerId) {
        this.loadSites(t.customerId);
        this.loadContacts(t.customerId, t.siteId ?? undefined);
      }
    });
  }

  // ── Actions ─────────────────────────────────────────────────────────────────

  changeStatus(ticketId: number) {
    const status = this.statusCtrl.value;
    if (!status) return;
    this.saving.set(true);
    this.svc.updateStatus(ticketId, status).subscribe({
      next: () => { this.saving.set(false); this.ticket.reload(); this.showMsg('ok'); },
      error: () => { this.saving.set(false); this.showMsg('err'); },
    });
  }

  submitComment(ticketId: number) {
    if (this.commentForm.invalid) return;
    const { content, isPublic } = this.commentForm.value;
    this.saving.set(true);
    this.svc.addComment(ticketId, content!, isPublic ?? true).subscribe({
      next: () => {
        this.saving.set(false);
        this.commentForm.reset({ content: '', isPublic: true });
        this.ticket.reload();
        this.showMsg('ok');
      },
      error: () => { this.saving.set(false); this.showMsg('err'); },
    });
  }

  submitAssign(ticketId: number) {
    const v = this.assignForm.value;
    const req: AssignRequest = {
      customerId:   v.customerId,
      siteId:       v.siteId,
      contactId:    v.contactId,
      teamId:       v.teamId,
      assignedToId: v.assignedToId,
    };
    this.saving.set(true);
    this.svc.assign(ticketId, req).subscribe({
      next: () => {
        this.saving.set(false);
        this.showAssign.set(false);
        this.ticket.reload();
        this.showMsg('ok');
      },
      error: () => { this.saving.set(false); this.showMsg('err'); },
    });
  }

  onCustomerChange(customerId: number | null) {
    this.assignForm.patchValue({ siteId: null, contactId: null });
    this.sites.set([]);
    this.contacts.set([]);
    if (customerId) { this.loadSites(customerId); this.loadContacts(customerId); }
  }

  onSiteChange(siteId: number | null) {
    this.assignForm.patchValue({ contactId: null });
    const customerId = this.assignForm.value.customerId;
    if (customerId) this.loadContacts(customerId, siteId ?? undefined);
  }

  onTeamChange(teamId: number | null) {
    // Si le technicien sélectionné n'est pas dans la nouvelle équipe, on le réinitialise
    const currentUser = this.assignForm.value.assignedToId;
    if (teamId && currentUser) {
      const team = this.teams().find(t => t.id === teamId);
      if (team && !team.memberIds.includes(currentUser)) {
        this.assignForm.patchValue({ assignedToId: null });
      }
    }
  }

  onFileSelected(event: Event, ticketId: number) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploading.set(true);
    this.svc.uploadAttachment(ticketId, file).subscribe({
      next: () => { this.uploading.set(false); this.ticket.reload(); input.value = ''; },
      error: () => { this.uploading.set(false); this.showMsg('err'); input.value = ''; },
    });
  }

  removeAttachment(ticketId: number, attachId: number) {
    this.svc.deleteAttachment(ticketId, attachId).subscribe({
      next: () => { this.ticket.reload(); this.showMsg('ok'); },
      error: () => this.showMsg('err'),
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private loadSites(customerId: number) {
    this.svc.getSites(customerId).subscribe(s => this.sites.set(s));
  }

  private loadContacts(customerId: number, siteId?: number) {
    this.svc.getContacts(customerId, siteId).subscribe(c => this.contacts.set(c));
  }

  private showMsg(t: 'ok' | 'err') {
    this.saveMsg.set(t);
    setTimeout(() => this.saveMsg.set(null), 3000);
  }

  statusColor(s: string): 'primary' | 'accent' | 'warn' | '' {
    return STATUS_COLOR[s] ?? '';
  }

  priorityColor(p: string): 'primary' | 'accent' | 'warn' | '' {
    return PRIORITY_COLOR[p] ?? '';
  }
}
