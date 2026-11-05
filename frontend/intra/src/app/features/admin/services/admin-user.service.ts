import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { AdminUser, AuditLog } from '../admin.model';

@Injectable({ providedIn: 'root' })
export class AdminUserService {
  private readonly http = inject(HttpClient);

  getUsers(q?: string, type?: string, active?: boolean) {
    let p = new HttpParams();
    if (q)              p = p.set('q',      q);
    if (type)           p = p.set('type',   type);
    if (active != null) p = p.set('active', String(active));
    return this.http.get<AdminUser[]>('/api/intra/admin/users', { params: p });
  }

  toggleActive(userId: number) {
    return this.http.patch<AdminUser>(`/api/intra/admin/users/${userId}/toggle-active`, {});
  }

  setRoles(userId: number, roleNames: string[]) {
    return this.http.put<AdminUser>(`/api/intra/admin/users/${userId}/roles`, { roleNames });
  }

  getAuditLogs(type?: string, email?: string, ip?: string, limit = 200) {
    let p = new HttpParams().set('limit', String(limit));
    if (type)  p = p.set('type',  type);
    if (email) p = p.set('email', email);
    if (ip)    p = p.set('ip',    ip);
    return this.http.get<AuditLog[]>('/api/intra/admin/audit-logs', { params: p });
  }
}
