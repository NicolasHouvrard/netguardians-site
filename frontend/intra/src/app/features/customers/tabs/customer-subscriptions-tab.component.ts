import { Component, inject, input, resource } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatTableModule }         from '@angular/material/table';
import { MatIconModule }          from '@angular/material/icon';
import { MatProgressBarModule }   from '@angular/material/progress-bar';
import { MatTooltipModule }       from '@angular/material/tooltip';
import { CustomerService }        from '../customer.service';

@Component({
  selector: 'app-customer-subscriptions-tab',
  standalone: true,
  templateUrl: './customer-subscriptions-tab.component.html',
  styleUrl:    './customer-subscriptions-tab.component.scss',
  imports: [CurrencyPipe, DatePipe, MatTableModule, MatIconModule, MatProgressBarModule, MatTooltipModule],
})
export class CustomerSubscriptionsTabComponent {
  private readonly svc = inject(CustomerService);

  readonly customerId = input.required<number>();

  readonly data = resource({
    params: () => this.customerId(),
    loader: ({ params }) => firstValueFrom(this.svc.getSubscriptions(params)),
  });

  readonly columns = ['name', 'billing', 'price', 'progress', 'dates', 'active'];

  progressColor(p: number): 'primary' | 'accent' | 'warn' {
    if (p >= 100) return 'warn';
    if (p >= 75)  return 'accent';
    return 'primary';
  }
}
