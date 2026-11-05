import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { MatTabsModule }       from '@angular/material/tabs';
import { MatFormFieldModule }  from '@angular/material/form-field';
import { MatInputModule }      from '@angular/material/input';
import { MatButtonModule }     from '@angular/material/button';
import { MatIconModule }       from '@angular/material/icon';
import { MatCardModule }       from '@angular/material/card';
import { MatDividerModule }    from '@angular/material/divider';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { AuthService }   from '../../core/auth/auth.service';
import { AccountService } from './account.service';

@Component({
  selector: 'app-mon-compte',
  standalone: true,
  imports: [
    ReactiveFormsModule, RouterLink,
    MatTabsModule, MatFormFieldModule, MatInputModule,
    MatButtonModule, MatIconModule, MatCardModule,
    MatDividerModule, MatProgressBarModule,
  ],
  template: `
    <!-- En-tête utilisateur -->
    <div class="account-header">
      <div class="avatar">{{ initials }}</div>
      <div class="account-info">
        <div class="account-name">{{ auth.user()?.fullName || auth.user()?.email }}</div>
        <div class="account-email">{{ auth.user()?.email }}</div>
      </div>
    </div>

    <mat-tab-group class="tabs" animationDuration="0ms">

      <!-- ── Onglet Profil ──────────────────────────────────────────────── -->
      <mat-tab>
        <ng-template mat-tab-label>
          <mat-icon class="tab-icon">person</mat-icon> Profil
        </ng-template>

        <div class="tab-content">
          <mat-card appearance="outlined">
            <mat-card-header>
              <mat-card-title>Informations personnelles</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <form [formGroup]="profileForm" (ngSubmit)="saveProfile()" class="form-stack">
                <mat-form-field appearance="outline">
                  <mat-label>Nom complet</mat-label>
                  <mat-icon matPrefix>badge</mat-icon>
                  <input matInput formControlName="fullName" placeholder="Prénom Nom" />
                  <mat-error>Ce champ est requis.</mat-error>
                </mat-form-field>

                <mat-form-field appearance="outline">
                  <mat-label>Adresse e-mail</mat-label>
                  <mat-icon matPrefix>email</mat-icon>
                  <input matInput [value]="auth.user()?.email ?? ''" readonly />
                  <mat-hint>L'adresse e-mail sert d'identifiant et ne peut pas être modifiée ici.</mat-hint>
                </mat-form-field>

                @if (profileMsg()) {
                  <div class="inline-msg" [class.success]="profileMsg()!.type === 'success'"
                                         [class.error]="profileMsg()!.type === 'error'">
                    <mat-icon>{{ profileMsg()!.type === 'success' ? 'check_circle' : 'error' }}</mat-icon>
                    {{ profileMsg()!.text }}
                  </div>
                }

                <div class="form-actions">
                  <button mat-flat-button type="submit"
                    [disabled]="saving() || profileForm.invalid || profileForm.pristine">
                    <mat-icon>save</mat-icon> Enregistrer
                  </button>
                </div>
              </form>
            </mat-card-content>
          </mat-card>

          <!-- Accès rapides -->
          <mat-card appearance="outlined" class="mt-16">
            <mat-card-header>
              <mat-card-title>Mes espaces</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <div class="quick-links">
                <a href="/espace" mat-stroked-button>
                  <mat-icon>account_circle</mat-icon> Mon espace client
                </a>
                @if (auth.isAdmin()) {
                  <a routerLink="/admin" mat-stroked-button>
                    <mat-icon>admin_panel_settings</mat-icon> Console Admin
                  </a>
                }
              </div>
            </mat-card-content>
          </mat-card>
        </div>
      </mat-tab>

      <!-- ── Onglet Sécurité ────────────────────────────────────────────── -->
      <mat-tab>
        <ng-template mat-tab-label>
          <mat-icon class="tab-icon">lock</mat-icon> Sécurité
        </ng-template>

        <div class="tab-content">
          <mat-card appearance="outlined">
            <mat-card-header>
              <mat-card-title>Changer le mot de passe</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <form [formGroup]="pwdForm" (ngSubmit)="changePassword()" class="form-stack"
                    autocomplete="off">
                <mat-form-field appearance="outline">
                  <mat-label>Mot de passe actuel</mat-label>
                  <mat-icon matPrefix>key</mat-icon>
                  <input matInput type="password" formControlName="currentPassword"
                         autocomplete="current-password" />
                  <mat-error>Ce champ est requis.</mat-error>
                </mat-form-field>

                <mat-form-field appearance="outline">
                  <mat-label>Nouveau mot de passe</mat-label>
                  <mat-icon matPrefix>lock_reset</mat-icon>
                  <input matInput type="password" formControlName="newPassword"
                         autocomplete="new-password" />
                  <mat-hint>8 caractères minimum</mat-hint>
                  <mat-error>Minimum 8 caractères.</mat-error>
                </mat-form-field>

                <mat-form-field appearance="outline">
                  <mat-label>Confirmer le nouveau mot de passe</mat-label>
                  <mat-icon matPrefix>lock_reset</mat-icon>
                  <input matInput type="password" formControlName="confirmPassword"
                         autocomplete="new-password" />
                  <mat-error>Ce champ est requis.</mat-error>
                </mat-form-field>

                @if (pwdMsg()) {
                  <div class="inline-msg" [class.success]="pwdMsg()!.type === 'success'"
                                         [class.error]="pwdMsg()!.type === 'error'">
                    <mat-icon>{{ pwdMsg()!.type === 'success' ? 'check_circle' : 'error' }}</mat-icon>
                    {{ pwdMsg()!.text }}
                  </div>
                }

                <div class="form-actions">
                  <button mat-flat-button type="submit"
                    [disabled]="saving() || pwdForm.invalid">
                    <mat-icon>lock</mat-icon> Modifier le mot de passe
                  </button>
                </div>
              </form>
            </mat-card-content>
          </mat-card>
        </div>
      </mat-tab>

      <!-- ── Onglet Agenda ──────────────────────────────────────────────── -->
      <mat-tab>
        <ng-template mat-tab-label>
          <mat-icon class="tab-icon">calendar_month</mat-icon> Agenda
        </ng-template>

        <div class="tab-content">
          <mat-card appearance="outlined">
            <mat-card-header>
              <mat-card-title>Agendas connectés</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              @if (svc.calendars.isLoading()) {
                <mat-progress-bar mode="indeterminate" />
              }

              @if (!svc.calendars.isLoading() && (svc.calendars.value() ?? []).length === 0) {
                <div class="empty-state">
                  <mat-icon>event_busy</mat-icon>
                  <p>Aucun agenda connecté.</p>
                </div>
              }

              @for (acc of svc.calendars.value() ?? []; track acc.id) {
                <div class="calendar-item">
                  <div class="cal-badge" [class.google]="acc.authType === 'GOOGLE_OAUTH2'"
                                        [class.microsoft]="acc.authType === 'MICROSOFT_OAUTH2'">
                    <mat-icon>{{ acc.authType === 'GOOGLE_OAUTH2' ? 'mail' : 'calendar_month' }}</mat-icon>
                    {{ acc.authType === 'GOOGLE_OAUTH2' ? 'Google' : 'Microsoft' }}
                  </div>
                  <div class="cal-info">
                    <div class="cal-name">{{ acc.name }}</div>
                    @if (acc.username) {
                      <div class="cal-user">{{ acc.username }}</div>
                    }
                  </div>
                  <span class="status-dot" [class.ok]="acc.connected">
                    <mat-icon>{{ acc.connected ? 'check_circle' : 'warning' }}</mat-icon>
                    {{ acc.connected ? 'Connecté' : 'Non connecté' }}
                  </span>
                </div>
              }
            </mat-card-content>
          </mat-card>

          <mat-card appearance="outlined" class="mt-16">
            <mat-card-header>
              <mat-card-title>Connecter un agenda</mat-card-title>
              <mat-card-subtitle>
                Les agendas Google et Microsoft sont gérés via les comptes mail OAuth.
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <div class="quick-links">
                <a href="/intra/mail/personal/auth/google" mat-stroked-button>
                  <mat-icon>add</mat-icon> Connecter Google Calendar
                </a>
                <a href="/intra/mail/accounts" mat-stroked-button>
                  <mat-icon>settings</mat-icon> Gérer les comptes mail / agenda
                </a>
              </div>
            </mat-card-content>
          </mat-card>
        </div>
      </mat-tab>

    </mat-tab-group>
  `,
  styles: [`
    /* ── En-tête ─────────────────────────────────────────────────── */
    .account-header {
      display: flex; align-items: center; gap: 16px;
      margin-bottom: 24px;
    }
    .avatar {
      width: 56px; height: 56px; border-radius: 50%;
      background: #3f51b5; color: #fff;
      display: flex; align-items: center; justify-content: center;
      font-size: 20px; font-weight: 600; flex-shrink: 0;
    }
    .account-name  { font-size: 18px; font-weight: 600; }
    .account-email { font-size: 13px; color: #888; margin-top: 2px; }

    /* ── Onglets ─────────────────────────────────────────────────── */
    .tabs { max-width: 640px; }
    .tab-icon { font-size: 18px; width: 18px; height: 18px; margin-right: 4px; vertical-align: middle; }

    .tab-content { padding: 20px 0; }
    .mt-16 { margin-top: 16px; }

    /* ── Formulaires ─────────────────────────────────────────────── */
    .form-stack {
      display: flex; flex-direction: column; gap: 8px;
      padding: 8px 0;
      mat-form-field { width: 100%; }
    }
    .form-actions { display: flex; gap: 8px; margin-top: 4px; }

    /* ── Messages inline ─────────────────────────────────────────── */
    .inline-msg {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 14px; border-radius: 6px; font-size: 13px;
      mat-icon { font-size: 18px; width: 18px; height: 18px; }
      &.success { background: #e8f5e9; color: #2e7d32; }
      &.error   { background: #fdecea; color: #c62828; }
    }

    /* ── Accès rapides ───────────────────────────────────────────── */
    .quick-links { display: flex; gap: 8px; flex-wrap: wrap; padding: 8px 0; }

    /* ── Agenda ──────────────────────────────────────────────────── */
    .empty-state {
      display: flex; flex-direction: column; align-items: center;
      padding: 32px 0; color: #aaa;
      mat-icon { font-size: 40px; width: 40px; height: 40px; margin-bottom: 8px; }
      p { margin: 0; font-size: 14px; }
    }

    .calendar-item {
      display: flex; align-items: center; gap: 12px;
      padding: 10px 0; border-bottom: 1px solid #f0f0f0;
      &:last-of-type { border-bottom: none; }
    }

    .cal-badge {
      display: inline-flex; align-items: center; gap: 4px;
      padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600;
      mat-icon { font-size: 14px; width: 14px; height: 14px; }
      &.google    { background: #fce8e6; color: #c5221f; }
      &.microsoft { background: #e8eaf6; color: #3949ab; }
    }

    .cal-info   { flex: 1; }
    .cal-name   { font-size: 14px; font-weight: 500; }
    .cal-user   { font-size: 12px; color: #888; margin-top: 2px; }

    .status-dot {
      display: flex; align-items: center; gap: 4px;
      font-size: 12px; color: #888;
      mat-icon { font-size: 16px; width: 16px; height: 16px; color: #888; }
      &.ok { color: #2e7d32; mat-icon { color: #2e7d32; } }
    }
  `],
})
export class MonCompteComponent {
  readonly auth = inject(AuthService);
  readonly svc  = inject(AccountService);
  private  fb   = inject(FormBuilder);

  readonly saving     = signal(false);
  readonly profileMsg = signal<{ type: 'success' | 'error'; text: string } | null>(null);
  readonly pwdMsg     = signal<{ type: 'success' | 'error'; text: string } | null>(null);

  readonly profileForm = this.fb.group({
    fullName: [this.auth.user()?.fullName ?? '', Validators.required],
  });

  readonly pwdForm = this.fb.group({
    currentPassword: ['', Validators.required],
    newPassword:     ['', [Validators.required, Validators.minLength(8)]],
    confirmPassword: ['', Validators.required],
  });

  // ── Profil ──────────────────────────────────────────────────────────────

  saveProfile(): void {
    if (this.profileForm.invalid) return;
    const fullName = this.profileForm.value.fullName!.trim();
    this.saving.set(true);
    this.profileMsg.set(null);

    this.svc.saveProfile(fullName).subscribe({
      next: () => {
        this.saving.set(false);
        this.auth.updateProfile(fullName);
        this.profileForm.markAsPristine();
        this.profileMsg.set({ type: 'success', text: 'Profil mis à jour.' });
        setTimeout(() => this.profileMsg.set(null), 4000);
      },
      error: () => {
        this.saving.set(false);
        this.profileMsg.set({ type: 'error', text: 'Erreur lors de la mise à jour.' });
      },
    });
  }

  // ── Mot de passe ─────────────────────────────────────────────────────────

  changePassword(): void {
    if (this.pwdForm.invalid) return;
    const v = this.pwdForm.value;

    if (v.newPassword !== v.confirmPassword) {
      this.pwdMsg.set({ type: 'error', text: 'Les mots de passe ne correspondent pas.' });
      return;
    }

    this.saving.set(true);
    this.pwdMsg.set(null);

    this.svc.changePassword(v.currentPassword!, v.newPassword!, v.confirmPassword!).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.success) {
          this.pwdForm.reset();
          this.pwdMsg.set({ type: 'success', text: 'Mot de passe modifié avec succès.' });
          setTimeout(() => this.pwdMsg.set(null), 4000);
        } else {
          this.pwdMsg.set({ type: 'error', text: res.error ?? 'Erreur inconnue.' });
        }
      },
      error: () => {
        this.saving.set(false);
        this.pwdMsg.set({ type: 'error', text: 'Erreur lors du changement de mot de passe.' });
      },
    });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  get initials(): string {
    const u = this.auth.user();
    if (!u) return '?';
    if (u.fullName) {
      const parts = u.fullName.trim().split(' ');
      return (parts[0][0] + (parts[1]?.[0] ?? '')).toUpperCase();
    }
    return u.email?.[0]?.toUpperCase() ?? '?';
  }
}
