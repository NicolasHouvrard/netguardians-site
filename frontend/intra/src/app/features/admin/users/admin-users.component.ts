import { Component, inject, signal, computed, effect, OnInit, LOCALE_ID } from '@angular/core';
import { DatePipe }                from '@angular/common';
import { toSignal }              from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { MatTableModule }        from '@angular/material/table';
import { MatSortModule, Sort }   from '@angular/material/sort';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule }    from '@angular/material/form-field';
import { MatInputModule }        from '@angular/material/input';
import { MatSelectModule }       from '@angular/material/select';
import { MatChipsModule }        from '@angular/material/chips';
import { MatIconModule }         from '@angular/material/icon';
import { MatButtonModule }       from '@angular/material/button';
import { MatProgressBarModule }  from '@angular/material/progress-bar';
import { MatTooltipModule }      from '@angular/material/tooltip';
import { MatDialog }             from '@angular/material/dialog';
import { AdminUserService }      from '../services/admin-user.service';
import { AdminUser }             from '../admin.model';
import { RolesDialogComponent }  from './roles-dialog.component';

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [
    ReactiveFormsModule, DatePipe,
    MatTableModule, MatSortModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatChipsModule, MatIconModule, MatButtonModule,
    MatProgressBarModule, MatTooltipModule,
  ],
  templateUrl: './admin-users.component.html',
  styleUrl:    'admin-users.component.scss',
})
export class AdminUsersComponent implements OnInit {
  private readonly svc    = inject(AdminUserService);
  private readonly dialog = inject(MatDialog);

  readonly allUsers = signal<AdminUser[]>([]);
  readonly loading  = signal(true);

  // ── Recherche & filtres ───────────────────────────────────────────────────
  readonly searchCtrl  = new FormControl('');
  readonly typeFilter  = signal<string>('');
  readonly activeFilter = signal<string>('');

  private readonly query = toSignal(
    this.searchCtrl.valueChanges.pipe(debounceTime(200), distinctUntilChanged()),
    { initialValue: '' }
  );

  // ── Tri ───────────────────────────────────────────────────────────────────
  readonly sortState = signal<Sort>({ active: 'fullName', direction: 'asc' });

  onSort(s: Sort) { this.sortState.set(s); this.pageIndex.set(0); }

  // ── Pagination ────────────────────────────────────────────────────────────
  readonly pageIndex = signal(0);
  readonly pageSize  = signal(15);

  constructor() {
    effect(() => { this.query(); this.typeFilter(); this.activeFilter(); this.pageIndex.set(0); });
  }

  // ── Pipeline ─────────────────────────────────────────────────────────────
  readonly sorted = computed(() => {
    const { active, direction } = this.sortState();
    const rows = [...this.allUsers()];
    if (!active || !direction) return rows;
    return rows.sort((a, b) => {
      const va = (a as any)[active] ?? '';
      const vb = (b as any)[active] ?? '';
      const cmp = typeof va === 'boolean'
        ? (va === vb ? 0 : va ? -1 : 1)
        : String(va).localeCompare(String(vb), 'fr', { sensitivity: 'base' });
      return direction === 'asc' ? cmp : -cmp;
    });
  });

  readonly filtered = computed(() => {
    const q  = (this.query() ?? '').toLowerCase();
    const tf = this.typeFilter();
    const af = this.activeFilter();
    return this.sorted().filter(u => {
      if (q  && !u.fullName.toLowerCase().includes(q) && !u.email.toLowerCase().includes(q)) return false;
      if (tf && u.type !== tf) return false;
      if (af === 'true'  && !u.active) return false;
      if (af === 'false' &&  u.active) return false;
      return true;
    });
  });

  readonly paged = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.filtered().slice(start, start + this.pageSize());
  });

  readonly columns = ['status', 'fullName', 'email', 'type', 'tenant', 'client', 'roles', 'createdAt', 'lastLoginIp', 'actions'];

  onPage(e: PageEvent) { this.pageSize.set(e.pageSize); this.pageIndex.set(e.pageIndex); }

  // ── Chargement ───────────────────────────────────────────────────────────
  ngOnInit() { this.load(); }

  load() {
    this.loading.set(true);
    this.svc.getUsers().subscribe({
      next:  u  => { this.allUsers.set(u); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  toggleActive(user: AdminUser) {
    this.svc.toggleActive(user.id).subscribe(updated => {
      this.allUsers.update(list => list.map(u => u.id === updated.id ? updated : u));
    });
  }

  openRolesDialog(user: AdminUser) {
    const ref = this.dialog.open(RolesDialogComponent, {
      data: { user },
      width: '420px',
    });
    ref.afterClosed().subscribe((updated: AdminUser | undefined) => {
      if (updated) {
        this.allUsers.update(list => list.map(u => u.id === updated.id ? updated : u));
      }
    });
  }
}
