import { Component, inject, input, resource, signal, computed, effect } from '@angular/core';
import { toSignal }              from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { firstValueFrom }        from 'rxjs';
import { MatTableModule }        from '@angular/material/table';
import { MatSortModule, Sort }   from '@angular/material/sort';
import { MatIconModule }         from '@angular/material/icon';
import { MatButtonModule }       from '@angular/material/button';
import { MatProgressBarModule }  from '@angular/material/progress-bar';
import { MatFormFieldModule }    from '@angular/material/form-field';
import { MatInputModule }        from '@angular/material/input';
import { MatSelectModule }       from '@angular/material/select';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatDialog }             from '@angular/material/dialog';
import { CustomerService }       from '../customer.service';
import { Site, SaveSiteRequest } from '../customer.model';
import { SiteDialogComponent }   from './site-dialog.component';

@Component({
  selector: 'app-customer-sites-tab',
  standalone: true,
  templateUrl: './customer-sites-tab.component.html',
  styleUrl:    './customer-sites-tab.component.scss',
  imports: [
    ReactiveFormsModule,
    MatTableModule, MatSortModule, MatIconModule, MatButtonModule, MatProgressBarModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatPaginatorModule,
  ],
})
export class CustomerSitesTabComponent {
  private readonly svc    = inject(CustomerService);
  private readonly dialog = inject(MatDialog);

  readonly customerId = input.required<number>();

  readonly data = resource({
    params: () => this.customerId(),
    loader: ({ params }) => firstValueFrom(this.svc.getSites(params)),
  });

  // ── Tri ───────────────────────────────────────────────────────────────────
  readonly sortState = signal<Sort>({ active: 'name', direction: 'asc' });

  onSort(sort: Sort) {
    this.sortState.set(sort);
    this.pageIndex.set(0);
  }

  // ── Recherche & filtre ────────────────────────────────────────────────────
  readonly searchCtrl   = new FormControl('');
  readonly activeFilter = signal<'all' | 'active' | 'inactive'>('all');

  private readonly query = toSignal(
    this.searchCtrl.valueChanges.pipe(debounceTime(200), distinctUntilChanged()),
    { initialValue: '' }
  );

  // ── Pagination ────────────────────────────────────────────────────────────
  readonly pageIndex = signal(0);
  readonly pageSize  = signal(10);

  constructor() {
    effect(() => { this.query(); this.activeFilter(); this.pageIndex.set(0); });
  }

  // ── Pipeline : tri → filtre → page ───────────────────────────────────────
  readonly sorted = computed(() => {
    const { active, direction } = this.sortState();
    const rows = [...(this.data.value() ?? [])];
    if (!active || !direction) return rows;
    return rows.sort((a, b) => {
      const va = (a as any)[active] ?? '';
      const vb = (b as any)[active] ?? '';
      const cmp = typeof va === 'number'
        ? va - vb
        : String(va).localeCompare(String(vb), 'fr', { sensitivity: 'base' });
      return direction === 'asc' ? cmp : -cmp;
    });
  });

  readonly filtered = computed(() => {
    const q = (this.query() ?? '').toLowerCase();
    const f = this.activeFilter();
    return this.sorted().filter(s => {
      const matchQ = !q
        || s.name.toLowerCase().includes(q)
        || (s.address ?? '').toLowerCase().includes(q)
        || (s.city    ?? '').toLowerCase().includes(q);
      const matchF = f === 'all' || (f === 'active' ? s.active : !s.active);
      return matchQ && matchF;
    });
  });

  readonly paged = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.filtered().slice(start, start + this.pageSize());
  });

  // ── Actions ───────────────────────────────────────────────────────────────
  readonly columns = ['name', 'address', 'city', 'contacts', 'active', 'actions'];

  onPage(e: PageEvent) {
    this.pageSize.set(e.pageSize);
    this.pageIndex.set(e.pageIndex);
  }

  openDialog(site: Site | null) {
    const ref = this.dialog.open(SiteDialogComponent, { data: { site }, width: '480px' });
    ref.afterClosed().subscribe((result: SaveSiteRequest | undefined) => {
      if (!result) return;
      const call = site
        ? this.svc.updateSite(this.customerId(), site.id, result)
        : this.svc.createSite(this.customerId(), result);
      call.subscribe(() => this.data.reload());
    });
  }

  deleteSite(site: Site) {
    if (!confirm(`Supprimer le site "${site.name}" ?`)) return;
    this.svc.deleteSite(this.customerId(), site.id).subscribe(() => this.data.reload());
  }
}
