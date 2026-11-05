import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatTableModule }       from '@angular/material/table';
import { MatFormFieldModule }   from '@angular/material/form-field';
import { MatInputModule }       from '@angular/material/input';
import { MatSelectModule }      from '@angular/material/select';
import { MatButtonModule }      from '@angular/material/button';
import { MatIconModule }        from '@angular/material/icon';
import { MatChipsModule }       from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTooltipModule }     from '@angular/material/tooltip';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { CustomerService }      from './customer.service';

@Component({
  selector: 'app-customer-list',
  standalone: true,
  templateUrl: './customer-list.component.html',
  styleUrl:    './customer-list.component.scss',
  imports: [
    ReactiveFormsModule, RouterLink,
    MatTableModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatChipsModule,
    MatProgressBarModule, MatPaginatorModule, MatTooltipModule,
  ],
})
export class CustomerListComponent {
  readonly svc = inject(CustomerService);

  readonly columns = ['companyName', 'contactName', 'email', 'phone', 'types', 'active', 'actions'];

  readonly searchCtrl = new FormControl('');
  readonly typeCtrl   = new FormControl('');

  readonly typeOptions = [
    { value: '',         label: 'Tous' },
    { value: 'customer', label: 'Clients' },
    { value: 'prospect', label: 'Prospects' },
    { value: 'supplier', label: 'Fournisseurs' },
    { value: 'esn',      label: 'ESN partenaires' },
  ];

  constructor() {
    this.searchCtrl.valueChanges.pipe(debounceTime(350), distinctUntilChanged()).subscribe(q => {
      this.svc.filter.update(f => ({ ...f, q: q || undefined, page: 0 }));
    });
    this.typeCtrl.valueChanges.subscribe(type => {
      this.svc.filter.update(f => ({ ...f, type: type || undefined, page: 0 }));
    });
  }

  onPage(e: PageEvent) {
    this.svc.filter.update(f => ({ ...f, page: e.pageIndex, size: e.pageSize }));
  }
}
