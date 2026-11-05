import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface CampaignDto {
  id: number;
  name: string;
  subject: string | null;
  htmlContent: string | null;
  status: 'DRAFT' | 'SCHEDULED' | 'SENT' | 'FAILED';
  createdByName: string | null;
  recipientCount: number;
  openCount: number;
  clickCount: number;
  scheduledAt: string | null;
  sentAt: string | null;
  createdAt: string | null;
}

export interface CampaignStatsDto {
  draft: number; scheduled: number; sent: number; failed: number; total: number;
}

@Injectable({ providedIn: 'root' })
export class CampaignService {
  private readonly http = inject(HttpClient);

  list()          { return this.http.get<CampaignDto[]>('/api/intra/campaigns'); }
  stats()         { return this.http.get<CampaignStatsDto>('/api/intra/campaigns/stats'); }
  get(id: number) { return this.http.get<CampaignDto>(`/api/intra/campaigns/${id}`); }

  create(req: { name: string; subject?: string; htmlContent?: string }) {
    return this.http.post<CampaignDto>('/api/intra/campaigns', req);
  }

  update(id: number, req: { name?: string; subject?: string; htmlContent?: string }) {
    return this.http.put<CampaignDto>(`/api/intra/campaigns/${id}`, req);
  }

  updateStatus(id: number, status: string) {
    return this.http.patch<CampaignDto>(`/api/intra/campaigns/${id}/status`, { status });
  }

  delete(id: number) { return this.http.delete<void>(`/api/intra/campaigns/${id}`); }
}
