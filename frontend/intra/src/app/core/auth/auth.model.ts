export interface AuthUser {
  token: string;
  email: string;
  fullName: string;
  roles: string[];
  userId: number;
  tenantId: number | null;
  tenantName: string;
}

export interface OAuthProvider {
  id: string;    // 'google', 'microsoft', 'azure', …
  name: string;  // 'Google', 'Microsoft', …
}
