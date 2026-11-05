import { Component, inject, input, OnInit, OnDestroy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe }              from '@angular/common';
import { MatTableModule }        from '@angular/material/table';
import { MatChipsModule }        from '@angular/material/chips';
import { MatProgressBarModule }  from '@angular/material/progress-bar';
import { MatButtonModule }       from '@angular/material/button';
import { TicketService }         from '../../tickets/ticket.service';

@Component({
  selector: 'app-customer-tickets-tab',
  standalone: true,
  templateUrl: './customer-tickets-tab.component.html',
  styleUrl:    './customer-tickets-tab.component.scss',
  imports: [RouterLink, DatePipe, MatTableModule, MatChipsModule, MatProgressBarModule, MatButtonModule],
})
export class CustomerTicketsTabComponent implements OnInit, OnDestroy {
  readonly svc = inject(TicketService);

  readonly customerId = input.required<number>();

  readonly columns = ['id', 'title', 'status', 'priority', 'createdAt'];

  ngOnInit() {
    this.svc.filter.set({ page: 0, size: 50, customerId: this.customerId() });
  }

  ngOnDestroy() {
    // Remettre le filtre à zéro pour ne pas polluer la liste globale
    this.svc.filter.set({ page: 0, size: 20 });
  }
}
