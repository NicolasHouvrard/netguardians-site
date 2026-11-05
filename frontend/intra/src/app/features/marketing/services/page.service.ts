import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface PageSummaryDto {
  id: number;
  title: string;
  slug: string;
  pageType: string;
  published: boolean;
  homePage: boolean;
  createdAt: string | null;
  publishedAt: string | null;
}

export interface BlockDto {
  id?: number;
  blockType: string;
  content: string;
  sortOrder: number;
}

export interface BlockTypeDto { type: string; label: string; icon: string; }

export interface PageDto extends PageSummaryDto {
  excerpt: string | null;
  featuredImageUrl: string | null;
  metaTitle: string | null;
  metaDescription: string | null;
  metaKeywords: string | null;
  metaOgImage: string | null;
  blogCategory: string | null;
  blogTags: string | null;
  blocks: BlockDto[];
}

@Injectable({ providedIn: 'root' })
export class PageService {
  private readonly http = inject(HttpClient);

  list() { return this.http.get<PageSummaryDto[]>('/api/intra/pages'); }

  blockTypes() { return this.http.get<BlockTypeDto[]>('/api/intra/pages/block-types'); }

  get(id: number) { return this.http.get<PageDto>(`/api/intra/pages/${id}`); }

  create(title: string, pageType = 'PAGE') {
    return this.http.post<PageDto>('/api/intra/pages', { title, pageType });
  }

  save(id: number, page: Partial<PageDto>) {
    return this.http.put<PageDto>(`/api/intra/pages/${id}`, page);
  }

  delete(id: number) { return this.http.delete<void>(`/api/intra/pages/${id}`); }

  togglePublish(id: number) {
    return this.http.patch<PageSummaryDto>(`/api/intra/pages/${id}/publish`, {});
  }

  setHome(id: number) {
    return this.http.patch<PageSummaryDto>(`/api/intra/pages/${id}/set-home`, {});
  }
}
