import { Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatTabsModule }        from '@angular/material/tabs';
import { MatCardModule }        from '@angular/material/card';
import { MatButtonModule }      from '@angular/material/button';
import { MatIconModule }        from '@angular/material/icon';
import { MatChipsModule }       from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { CustomerService }      from './customer.service';
import { Customer }             from './customer.model';
import { CustomerSitesTabComponent }         from './tabs/customer-sites-tab.component';
import { CustomerContactsTabComponent }      from './tabs/customer-contacts-tab.component';
import { CustomerTicketsTabComponent }       from './tabs/customer-tickets-tab.component';
import { CustomerSubscriptionsTabComponent } from './tabs/customer-subscriptions-tab.component';

@Component({
  selector: 'app-customer-detail',
  standalone: true,
  templateUrl: './customer-detail.component.html',
  styleUrl:    './customer-detail.component.scss',
  imports: [
    RouterLink,
    MatTabsModule, MatCardModule, MatButtonModule,
    MatIconModule, MatChipsModule, MatProgressBarModule,
    CustomerSitesTabComponent,
    CustomerContactsTabComponent,
    CustomerTicketsTabComponent,
    CustomerSubscriptionsTabComponent,
  ],
})
export class CustomerDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly svc   = inject(CustomerService);

  readonly customerId = signal<number>(0);
  readonly customer   = signal<Customer | null>(null);
  readonly loading    = signal(true);

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.customerId.set(id);
    this.svc.getById(id).subscribe({
      next:  c  => { this.customer.set(c); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
