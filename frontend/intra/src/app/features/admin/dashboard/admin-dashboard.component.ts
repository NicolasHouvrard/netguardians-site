import { Component, inject, signal, OnInit } from '@angular/core';
import { MatProgressBarModule }     from '@angular/material/progress-bar';
import { AdminStatsService }        from '../services/admin-stats.service';
import { AdminStats }               from '../admin.model';
import { UserStatsWidgetComponent } from './widgets/user-stats-widget.component';
import { RoleStatsWidgetComponent } from './widgets/role-stats-widget.component';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [MatProgressBarModule, UserStatsWidgetComponent, RoleStatsWidgetComponent],
  templateUrl: './admin-dashboard.component.html',
  styleUrl:    './admin-dashboard.component.scss',
})
export class AdminDashboardComponent implements OnInit {
  private readonly statsService = inject(AdminStatsService);

  readonly stats   = signal<AdminStats | null>(null);
  readonly loading = signal(true);

  ngOnInit() {
    this.statsService.getStats().subscribe({
      next:  s  => { this.stats.set(s);  this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
