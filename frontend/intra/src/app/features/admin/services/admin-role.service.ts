import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RoleOption } from '../admin.model';

@Injectable({ providedIn: 'root' })
export class AdminRoleService {
  private readonly http = inject(HttpClient);

  getRoles() {
    return this.http.get<RoleOption[]>('/api/intra/admin/roles');
  }

  createRole(req: { name: string; label: string; type: string }) {
    return this.http.post<RoleOption>('/api/intra/admin/roles', req);
  }

  updateRole(id: number, req: { label: string; type: string }) {
    return this.http.put<RoleOption>(`/api/intra/admin/roles/${id}`, req);
  }

  deleteRole(id: number) {
    return this.http.delete<void>(`/api/intra/admin/roles/${id}`);
  }
}
