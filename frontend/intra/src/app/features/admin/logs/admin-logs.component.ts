import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { FormControl, ReactiveFormsModule }  from '@angular/forms';
import { DatePipe, NgClass }                 from '@angular/common';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { toSignal }                          from '@angular/core/rxjs-interop';
import { MatTableModule }                    from '@angular/material/table';
import { MatSortModule, Sort }               from '@angular/material/sort';
import { MatPaginatorModule, PageEvent }     from '@angular/material/paginator';
import { MatFormFieldModule }                from '@angular/material/form-field';
import { MatInputModule }                    from '@angular/material/input';
import { MatSelectModule }                   from '@angular/material/select';
import { MatIconModule }                     from '@angular/material/icon';
import { MatButtonModule }                   from '@angular/material/button';
import { MatProgressBarModule }              from '@angular/material/progress-bar';
import { MatTooltipModule }                  from '@angular/material/tooltip';
import { MatTabsModule }                     from '@angular/material/tabs';
import { MatChipsModule }                    from '@angular/material/chips';
import { AdminUserService }                  from '../services/admin-user.service';
import { AuditLog }                          from '../admin.model';

@Component({
  selector: 'app-admin-logs',
  standalone: true,
  imports: [
    ReactiveFormsModule, DatePipe, NgClass,
    MatTableModule, MatSortModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatIconModule, MatButtonModule, MatProgressBarModule,
    MatTooltipModule, MatTabsModule, MatChipsModule,
  ],
  templateUrl: './admin-logs.component.html',
  styleUrl: 'admin-logs.component.scss',
})
export class AdminLogsComponent implements OnInit {
  private readonly svc = inject(AdminUserService);

  readonly allLogs = signal<AuditLog[]>([]);
  readonly loading = signal(true);

  readonly emailCtrl = new FormControl('');
  readonly ipCtrl    = new FormControl('');
  readonly typeFilter = signal<string>('');

  private readonly emailQ = toSignal(
    this.emailCtrl.valueChanges.pipe(debounceTime(200), distinctUntilChanged()),
    { initialValue: '' }
  );
  private readonly ipQ = toSignal(
    this.ipCtrl.valueChanges.pipe(debounceTime(200), distinctUntilChanged()),
    { initialValue: '' }
  );

  readonly sortState  = signal<Sort>({ active: 'timestamp', direction: 'desc' });
  readonly pageIndex  = signal(0);
  readonly pageSize   = signal(25);

  onSort(s: Sort) { this.sortState.set(s); this.pageIndex.set(0); }
  onPage(e: PageEvent) { this.pageSize.set(e.pageSize); this.pageIndex.set(e.pageIndex); }

  readonly filtered = computed(() => {
    const tf    = this.typeFilter();
    const email = (this.emailQ() ?? '').toLowerCase();
    const ip    = (this.ipQ()    ?? '').toLowerCase();
    return this.allLogs().filter(l => {
      if (tf    && l.type !== tf)                                                   return false;
      if (email && !(l.email ?? '').toLowerCase().includes(email))                 return false;
      if (ip    && !(l.ip   ?? '').toLowerCase().includes(ip))                     return false;
      return true;
    });
  });

  readonly sorted = computed(() => {
    const { active, direction } = this.sortState();
    const rows = [...this.filtered()];
    if (!active || !direction) return rows;
    return rows.sort((a, b) => {
      const va = (a as any)[active] ?? '';
      const vb = (b as any)[active] ?? '';
      const cmp = String(va).localeCompare(String(vb), 'fr', { sensitivity: 'base' });
      return direction === 'asc' ? cmp : -cmp;
    });
  });

  readonly paged = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.sorted().slice(start, start + this.pageSize());
  });

  readonly loginLogs   = computed(() => this.filtered().filter(l => l.type !== 'PAGE_VISIT'));
  readonly pageLogs    = computed(() => this.filtered().filter(l => l.type === 'PAGE_VISIT'));

  readonly loginColumns = ['type', 'timestamp', 'email', 'ip', 'location', 'userAgent'];
  readonly pageColumns  = ['timestamp', 'email', 'ip', 'url', 'userAgent'];

  readonly loginPaged = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.loginLogs().slice(start, start + this.pageSize());
  });
  readonly pagePaged = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.pageLogs().slice(start, start + this.pageSize());
  });

  ngOnInit() { this.load(); }

  load() {
    this.loading.set(true);
    this.svc.getAuditLogs(undefined, undefined, undefined, 500).subscribe({
      next:  logs => { this.allLogs.set(logs); this.loading.set(false); },
      error: ()   => this.loading.set(false),
    });
  }

  flag(code: string | null): string {
    if (!code || code.length !== 2) return '🌐';
    return String.fromCodePoint(
      ...code.toUpperCase().split('').map(c => 0x1F1E6 + c.charCodeAt(0) - 65)
    );
  }

  shortAgent(ua: string | null): string {
    if (!ua) return '—';
    if (ua.includes('Mobile')) return '📱 Mobile';
    if (ua.includes('Firefox')) return '🦊 Firefox';
    if (ua.includes('Chrome'))  return '🌐 Chrome';
    if (ua.includes('Safari'))  return '🧭 Safari';
    return ua.substring(0, 30) + '…';
  }
}
