import { Component, inject, input, resource, signal, computed, effect } from '@angular/core';
import { toSignal }                from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { firstValueFrom }          from 'rxjs';
import { MatTableModule }          from '@angular/material/table';
import { MatSortModule, Sort }     from '@angular/material/sort';
import { MatIconModule }           from '@angular/material/icon';
import { MatButtonModule }         from '@angular/material/button';
import { MatProgressBarModule }    from '@angular/material/progress-bar';
import { MatFormFieldModule }      from '@angular/material/form-field';
import { MatInputModule }          from '@angular/material/input';
import { MatSelectModule }         from '@angular/material/select';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatDialog }               from '@angular/material/dialog';
import { CustomerService }         from '../customer.service';
import { Contact, SaveContactRequest } from '../customer.model';
import { ContactDialogComponent }               from './contact-dialog.component';
import { ContactAccountDialogComponent }        from '../../../shared/contact-account/contact-account-dialog.component';

@Component({
  selector: 'app-customer-contacts-tab',
  standalone: true,
  templateUrl: './customer-contacts-tab.component.html',
  styleUrl:    './customer-contacts-tab.component.scss',
  imports: [
    ReactiveFormsModule,
    MatTableModule, MatSortModule, MatIconModule, MatButtonModule, MatProgressBarModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatPaginatorModule,
  ],
})
export class CustomerContactsTabComponent {
  private readonly svc    = inject(CustomerService);
  private readonly dialog = inject(MatDialog);

  readonly customerId = input.required<number>();

  readonly data = resource({
    params: () => this.customerId(),
    loader: ({ params }) => firstValueFrom(this.svc.getContacts(params)),
  });

  readonly sites = resource({
    params: () => this.customerId(),
    loader: ({ params }) => firstValueFrom(this.svc.getSites(params)),
  });

  // ── Tri ───────────────────────────────────────────────────────────────────
  readonly sortState = signal<Sort>({ active: 'fullName', direction: 'asc' });

  onSort(sort: Sort) {
    this.sortState.set(sort);
    this.pageIndex.set(0);
  }

  // ── Recherche & filtre ────────────────────────────────────────────────────
  readonly searchCtrl = new FormControl('');
  readonly siteFilter = signal<number | 'all'>('all');

  private readonly query = toSignal(
    this.searchCtrl.valueChanges.pipe(debounceTime(200), distinctUntilChanged()),
    { initialValue: '' }
  );

  // ── Pagination ────────────────────────────────────────────────────────────
  readonly pageIndex = signal(0);
  readonly pageSize  = signal(10);

  constructor() {
    effect(() => { this.query(); this.siteFilter(); this.pageIndex.set(0); });
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
    const q  = (this.query() ?? '').toLowerCase();
    const sf = this.siteFilter();
    return this.sorted().filter(c => {
      const matchQ = !q
        || c.fullName.toLowerCase().includes(q)
        || (c.email    ?? '').toLowerCase().includes(q)
        || (c.phone    ?? '').toLowerCase().includes(q)
        || (c.position ?? '').toLowerCase().includes(q);
      const matchS = sf === 'all' || c.siteId === sf;
      return matchQ && matchS;
    });
  });

  readonly paged = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.filtered().slice(start, start + this.pageSize());
  });

  // ── Actions ───────────────────────────────────────────────────────────────
  readonly columns = ['fullName', 'position', 'email', 'phone', 'site', 'account', 'actions'];

  onPage(e: PageEvent) {
    this.pageSize.set(e.pageSize);
    this.pageIndex.set(e.pageIndex);
  }

  openDialog(contact: Contact | null) {
    const ref = this.dialog.open(ContactDialogComponent, {
      data: { contact, sites: this.sites.value() ?? [] },
      width: '480px',
    });
    ref.afterClosed().subscribe((result: SaveContactRequest | undefined) => {
      if (!result) return;
      const call = contact
        ? this.svc.updateContact(this.customerId(), contact.id, result)
        : this.svc.createContact(this.customerId(), result);
      call.subscribe(() => this.data.reload());
    });
  }

  openAccountDialog(contact: Contact) {
    this.dialog.open(ContactAccountDialogComponent, {
      data: {
        customerId:   this.customerId(),
        contactId:    contact.id,
        contactName:  contact.fullName,
        contactEmail: contact.email,
        isEsn:        false,
      },
      width: '500px',
    });
  }

  deleteContact(contact: Contact) {
    if (!confirm(`Supprimer le contact "${contact.fullName}" ?`)) return;
    this.svc.deleteContact(this.customerId(), contact.id).subscribe(() => this.data.reload());
  }
}
