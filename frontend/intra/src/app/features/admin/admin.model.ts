export interface AdminUser {
  id: number;
  email: string;
  fullName: string;
  active: boolean;
  mustChangePassword: boolean;
  type: 'INTERNE' | 'PORTAIL';
  roles: string[];
  tenantName: string | null;
  clientName: string | null;
  createdAt: string | null;
  lastLoginIp: string | null;
}

export interface AdminStats {
  total: number;
  active: number;
  interne: number;
  portail: number;
  roles: number;
}

export interface AuditLog {
  id: number;
  type: 'LOGIN_SUCCESS' | 'LOGIN_FAILURE' | 'PAGE_VISIT';
  userId: number | null;
  email: string | null;
  ip: string | null;
  country: string | null;
  countryCode: string | null;
  city: string | null;
  url: string | null;
  userAgent: string | null;
  timestamp: string;
}

export interface RoleOption {
  id: number;
  name: string;
  label: string;
  type: string | null;
}
