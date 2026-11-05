import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface MenuItemDto {
  id: number;
  label: string;
  url: string | null;
  linkedPageId: number | null;
  linkedPageSlug: string | null;
  linkedPageTitle: string | null;
  parentId: number | null;
  position: string;
  target: string;
  sortOrder: number;
  active: boolean;
  children: MenuItemDto[];
}

export interface PageLinkDto { id: number; title: string; slug: string; }

export interface SaveMenuItemRequest {
  label: string;
  url: string | null;
  linkedPageId: number | null;
  parentId: number | null;
  position: string;
  target: string;
  active: boolean;
}

@Injectable({ providedIn: 'root' })
export class MenuService {
  private readonly http = inject(HttpClient);

  list(position = 'MAIN') {
    return this.http.get<MenuItemDto[]>(`/api/intra/menu/all?position=${position}`);
  }

  pages() {
    return this.http.get<PageLinkDto[]>('/api/intra/menu/pages');
  }

  create(req: SaveMenuItemRequest) {
    return this.http.post<MenuItemDto>('/api/intra/menu/items', req);
  }

  update(id: number, req: SaveMenuItemRequest) {
    return this.http.put<MenuItemDto>(`/api/intra/menu/items/${id}`, req);
  }

  delete(id: number) {
    return this.http.delete<void>(`/api/intra/menu/items/${id}`);
  }

  saveOrder(order: { id: number; parentId: number | null; sortOrder: number }[]) {
    return this.http.put<void>('/api/intra/menu/order', order);
  }
}
