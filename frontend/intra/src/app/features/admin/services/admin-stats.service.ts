import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { AdminStats } from '../admin.model';

@Injectable({ providedIn: 'root' })
export class AdminStatsService {
  private readonly http = inject(HttpClient);

  getStats() {
    return this.http.get<AdminStats>('/api/intra/admin/stats');
  }
}
