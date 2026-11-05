import { Injectable, inject } from '@angular/core';
import { AdminStatsService } from './services/admin-stats.service';
import { AdminUserService }  from './services/admin-user.service';
import { AdminRoleService }  from './services/admin-role.service';

/**
 * Façade de compatibilité — délègue aux services spécialisés.
 * Les nouveaux composants devraient injecter directement les services individuels.
 */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly statsService = inject(AdminStatsService);
  private readonly userService  = inject(AdminUserService);
  private readonly roleService  = inject(AdminRoleService);

  // ── Stats ─────────────────────────────────────────────────────────────────
  getStats()  { return this.statsService.getStats(); }

  // ── Users ─────────────────────────────────────────────────────────────────
  getUsers(q?: string, type?: string, active?: boolean) {
    return this.userService.getUsers(q, type, active);
  }
  toggleActive(userId: number) { return this.userService.toggleActive(userId); }
  setRoles(userId: number, roleNames: string[]) {
    return this.userService.setRoles(userId, roleNames);
  }

  // ── Roles ─────────────────────────────────────────────────────────────────
  getRoles()                                           { return this.roleService.getRoles(); }
  createRole(req: { name: string; label: string; type: string }) {
    return this.roleService.createRole(req);
  }
  updateRole(id: number, req: { label: string; type: string }) {
    return this.roleService.updateRole(id, req);
  }
  deleteRole(id: number) { return this.roleService.deleteRole(id); }
}

// Ré-exports pour les imports directs
export { AdminStatsService } from './services/admin-stats.service';
export { AdminUserService }  from './services/admin-user.service';
export { AdminRoleService }  from './services/admin-role.service';
