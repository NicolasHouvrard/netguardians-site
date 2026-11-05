import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatTableModule }        from '@angular/material/table';
import { MatChipsModule }        from '@angular/material/chips';
import { MatFormFieldModule }    from '@angular/material/form-field';
import { MatInputModule }        from '@angular/material/input';
import { MatSelectModule }       from '@angular/material/select';
import { MatButtonModule }       from '@angular/material/button';
import { MatIconModule }         from '@angular/material/icon';
import { MatProgressBarModule }  from '@angular/material/progress-bar';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTooltipModule }      from '@angular/material/tooltip';
import { RouterLink }            from '@angular/router';
import { DatePipe }              from '@angular/common';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { TicketService }         from './ticket.service';

const STATUS_COLOR: Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  NEW:         'primary',
  IN_PROGRESS: 'accent',
  RESOLVED:    '',
  CLOSED:      '',
};

@Component({
  selector: 'app-ticket-list',
  standalone: true,
  templateUrl: './ticket-list.component.html',
  styleUrl:    './ticket-list.component.scss',
  imports: [
    ReactiveFormsModule, RouterLink, DatePipe,
    MatTableModule, MatChipsModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule,
    MatProgressBarModule, MatPaginatorModule, MatTooltipModule,
  ],
})
export class TicketListComponent {
  readonly svc = inject(TicketService);

  readonly columns = ['id', 'title', 'status', 'priority', 'customer', 'assignedTo', 'createdAt'];

  readonly searchCtrl   = new FormControl('');
  readonly statusCtrl   = new FormControl('');
  readonly priorityCtrl = new FormControl('');

  constructor() {
    this.searchCtrl.valueChanges.pipe(debounceTime(350), distinctUntilChanged()).subscribe(q => {
      this.svc.filter.update(f => ({ ...f, q: q || undefined, page: 0 }));
    });
    this.statusCtrl.valueChanges.subscribe(status => {
      this.svc.filter.update(f => ({ ...f, status: status || undefined, page: 0 }));
    });
    this.priorityCtrl.valueChanges.subscribe(priority => {
      this.svc.filter.update(f => ({ ...f, priority: priority || undefined, page: 0 }));
    });
  }

  onPage(e: PageEvent) {
    this.svc.filter.update(f => ({ ...f, page: e.pageIndex, size: e.pageSize }));
  }

  statusColor(status: string): 'primary' | 'accent' | 'warn' | '' {
    return STATUS_COLOR[status] ?? '';
  }
}
