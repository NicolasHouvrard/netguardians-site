import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface SocialConfigDto {
  id: number;
  name: string;
  networkType: string;
  label: string;
  icon: string;
  maxChars: number;
  connected: boolean;
}

export interface SocialTargetDto {
  id: number;
  network: string;
  label: string;
  icon: string;
  status: string;
  statusLabel: string;
  customContent: string | null;
  error: string | null;
  publishedAt: string | null;
}

export interface SocialPostDto {
  id: number;
  title: string;
  content: string;
  imagePath: string | null;
  status: string;
  statusLabel: string;
  scheduledAt: string | null;
  publishedAt: string | null;
  createdAt: string | null;
  targets: SocialTargetDto[];
}

export interface SocialStatsDto {
  draft: number;
  scheduled: number;
  published: number;
  failed: number;
}

@Injectable({ providedIn: 'root' })
export class SocialPostService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/intra/social';

  configs()      { return this.http.get<SocialConfigDto[]>(`${this.base}/configs`); }
  stats()        { return this.http.get<SocialStatsDto>(`${this.base}/posts/stats`); }
  list()         { return this.http.get<SocialPostDto[]>(`${this.base}/posts`); }
  get(id: number){ return this.http.get<SocialPostDto>(`${this.base}/posts/${id}`); }

  create(form: FormData) {
    return this.http.post<{ id: number }>(`${this.base}/posts`, form);
  }

  publish(id: number) {
    return this.http.patch<{ ok: boolean }>(`${this.base}/posts/${id}/publish`, {});
  }

  cancel(id: number) {
    return this.http.patch<{ ok: boolean }>(`${this.base}/posts/${id}/cancel`, {});
  }

  delete(id: number) {
    return this.http.delete<void>(`${this.base}/posts/${id}`);
  }
}
