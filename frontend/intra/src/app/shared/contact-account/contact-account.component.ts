import { Component, inject, input, output, signal, computed, OnInit } from '@angular/core';
import { FormBuilder, ReactiveFormsModule }  from '@angular/forms';
import { MatButtonModule }                   from '@angular/material/button';
import { MatIconModule }                     from '@angular/material/icon';
import { MatChipsModule }                    from '@angular/material/chips';
import { MatTooltipModule }                  from '@angular/material/tooltip';
import { MatCheckboxModule }                 from '@angular/material/checkbox';
import { MatProgressSpinnerModule }          from '@angular/material/progress-spinner';
import { MatDividerModule }                  from '@angular/material/divider';
import { AuthService }                       from '../../core/auth/auth.service';
import { CustomerService }                   from '../../features/customers/customer.service';
import { AdminRoleService }                  from '../../features/admin/services/admin-role.service';
import { AdminUserService }                  from '../../features/admin/services/admin-user.service';
import { ContactAccount, ESN_ROLES }         from '../../features/customers/customer.model';
import { RoleOption }                        from '../../features/admin/admin.model';

@Component({
  selector: 'app-contact-account',
  standalone: true,
  templateUrl: './contact-account.component.html',
  styleUrl:    './contact-account.component.scss',
  imports: [
    ReactiveFormsModule,
    MatButtonModule, MatIconModule, MatChipsModule, MatTooltipModule,
    MatCheckboxModule, MatProgressSpinnerModule, MatDividerModule,
  ],
})
export class ContactAccountComponent implements OnInit {
  private readonly svc       = inject(CustomerService);
  private readonly rolesSvc  = inject(AdminRoleService);
  private readonly usersSvc  = inject(AdminUserService);
  private readonly auth      = inject(AuthService);
  private readonly fb        = inject(FormBuilder);

  // ── Inputs / outputs ──────────────────────────────────────────────────────
  readonly customerId   = input.required<number>();
  readonly contactId    = input.required<number>();
  readonly contactEmail = input<string | null>(null);
  readonly isEsn        = input(false);
  readonly accountChanged = output<void>();

  // ── State ─────────────────────────────────────────────────────────────────
  readonly account  = signal<ContactAccount | null>(null);
  readonly loading  = signal(false);
  readonly busy     = signal(false);
  readonly error    = signal<string | null>(null);
  readonly success  = signal<string | null>(null);

  // ── Admin ─────────────────────────────────────────────────────────────────
  readonly isAdmin        = computed(() => this.auth.isAdmin());
  readonly showRolesPanel = signal(false);
  readonly allRoles       = signal<RoleOption[]>([]);
  readonly rolesGroups    = computed(() => {
    const map = new Map<string, RoleOption[]>();
    this.allRoles().forEach(r => {
      const key = r.type ?? 'Autres';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(r);
    });
    return [...map.entries()].map(([type, roles]) => ({ type, roles }));
  });
  adminRolesForm = this.fb.nonNullable.group({} as Record<string, boolean>);

  // ── ESN roles form ────────────────────────────────────────────────────────
  readonly esnRoles  = ESN_ROLES;
  readonly rolesForm = this.fb.nonNullable.group(
    Object.fromEntries(ESN_ROLES.map(r => [r.name, false]))
  );

  // ── Computed helpers ──────────────────────────────────────────────────────
  readonly hasAccount     = computed(() => this.account() !== null);
  readonly isActive       = computed(() => this.account()?.active ?? false);
  readonly isGestionnaire = computed(() => this.account()?.roles.includes('GESTIONNAIRE') ?? false);
  readonly emailVerified  = computed(() => this.account()?.emailVerified ?? false);

  ngOnInit() {
    this.loadAccount();
    if (this.auth.isAdmin()) this.loadAllRoles();
  }

  loadAccount() {
    this.loading.set(true);
    this.svc.getAccount(this.customerId(), this.contactId()).subscribe({
      next: acc => {
        this.account.set(acc);
        if (this.isEsn()) this.patchRolesForm(acc.roles);
        this.loading.set(false);
      },
      error: () => { this.account.set(null); this.loading.set(false); },
    });
  }

  private loadAllRoles() {
    this.rolesSvc.getRoles().subscribe(roles => {
      this.allRoles.set(roles);
      this.rebuildAdminRolesForm(roles);
    });
  }

  private rebuildAdminRolesForm(roles: RoleOption[]) {
    const current = this.account()?.roles ?? [];
    const controls = Object.fromEntries(roles.map(r => [r.name, current.includes(r.name)]));
    this.adminRolesForm = this.fb.nonNullable.group(controls);
  }

  toggleRolesPanel() {
    if (!this.showRolesPanel()) {
      // Resynchronise les cases avec les rôles actuels
      this.rebuildAdminRolesForm(this.allRoles());
    }
    this.showRolesPanel.update(v => !v);
  }

  saveAdminRoles() {
    const selected = Object.entries(this.adminRolesForm.getRawValue())
      .filter(([, v]) => v).map(([k]) => k);
    const userId = this.account()!.userId;
    this.busy.set(true);
    this.usersSvc.setRoles(userId, selected).subscribe({
      next: updated => {
        this.account.update(a => a ? { ...a, roles: updated.roles } : a);
        this.notify('Rôles mis à jour.');
        this.showRolesPanel.set(false);
        this.busy.set(false);
        this.accountChanged.emit();
      },
      error: e => {
        this.error.set(e?.error?.error ?? 'Erreur lors de la sauvegarde des rôles.');
        this.busy.set(false);
      },
    });
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  createAccount() {
    if (!this.contactEmail()) {
      this.error.set('Le contact doit avoir un email pour créer un compte.');
      return;
    }
    this.run(this.svc.createAccount(this.customerId(), this.contactId()),
             'Compte créé. Un email de vérification a été envoyé.');
  }

  revoke()   { this.run(this.svc.revokeAccount(this.customerId(), this.contactId()),   'Compte désactivé.'); }
  activate() { this.run(this.svc.activateAccount(this.customerId(), this.contactId()), 'Compte réactivé.'); }
  promote()  { this.run(this.svc.promoteAccount(this.customerId(), this.contactId()),  'Promu Gestionnaire.'); }
  demote()   { this.run(this.svc.demoteAccount(this.customerId(), this.contactId()),   'Rétrogradé Client.'); }

  sendVerification() {
    this.svc.sendVerification(this.customerId(), this.contactId()).subscribe({
      next: () => this.notify('Email de vérification envoyé.'),
      error: () => this.error.set('Erreur lors de l\'envoi.'),
    });
  }

  sendReset() {
    this.svc.sendPasswordReset(this.customerId(), this.contactId()).subscribe({
      next: () => this.notify('Email de réinitialisation envoyé.'),
      error: () => this.error.set('Erreur lors de l\'envoi.'),
    });
  }

  saveEsnRoles() {
    const selected = ESN_ROLES.filter(r => this.rolesForm.getRawValue()[r.name]).map(r => r.name);
    this.run(this.svc.setEsnRoles(this.customerId(), this.contactId(), selected), 'Rôles mis à jour.');
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private run(obs: ReturnType<typeof this.svc.createAccount>, msg: string) {
    this.busy.set(true);
    this.error.set(null);
    obs.subscribe({
      next: acc => {
        this.account.set(acc as ContactAccount);
        if (this.isEsn()) this.patchRolesForm((acc as ContactAccount).roles);
        if (this.isAdmin()) this.rebuildAdminRolesForm(this.allRoles());
        this.notify(msg);
        this.busy.set(false);
        this.accountChanged.emit();
      },
      error: e => { this.error.set(e?.error?.error ?? 'Une erreur est survenue.'); this.busy.set(false); },
    });
  }

  private notify(msg: string) {
    this.success.set(msg);
    setTimeout(() => this.success.set(null), 3000);
  }

  private patchRolesForm(roles: string[]) {
    const patch = Object.fromEntries(ESN_ROLES.map(r => [r.name, roles.includes(r.name)]));
    this.rolesForm.patchValue(patch);
  }
}
