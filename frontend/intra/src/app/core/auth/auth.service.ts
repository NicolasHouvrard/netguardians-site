import { Injectable, signal, computed, inject, resource } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { of, tap, catchError } from 'rxjs';
import { AuthUser, OAuthProvider } from './auth.model';

const STORAGE_KEY = 'intra_auth';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _user = signal<AuthUser | null>(this.loadFromStorage());

  readonly user            = this._user.asReadonly();
  readonly isLoggedIn      = computed(() => this._user() !== null);
  readonly isAdmin         = computed(() => this._user()?.roles.includes('ROLE_ADMIN') ?? false);
  readonly isEsnUser       = computed(() => {
    const roles = this._user()?.roles ?? [];
    // Rôles ESN standards + ROLE_ESN_USER (synthétique pour les rôles custom par tenant)
    return roles.some(r => [
      'ROLE_ADMIN', 'ROLE_USER', 'ROLE_COMMERCIAL',
      'ROLE_TECHNICIEN', 'ROLE_MARKETING', 'ROLE_COMPTABLE',
      'ROLE_ESN_USER',
    ].includes(r));
  });
  readonly isPortalUser    = computed(() => {
    const roles = this._user()?.roles ?? [];
    return roles.includes('ROLE_CLIENT') || roles.includes('ROLE_GESTIONNAIRE');
  });
  readonly isGestionnaire  = computed(() => this._user()?.roles.includes('ROLE_GESTIONNAIRE') ?? false);
  readonly tenantId        = computed(() => this._user()?.tenantId ?? null);
  readonly token           = computed(() => this._user()?.token ?? null);

  /** Providers OAuth2 actifs sur le serveur (Google, Microsoft…) */
  readonly providers = resource<OAuthProvider[], void>({
    loader: () => fetch('/api/auth/providers').then(r => r.json()),
  });

  // ── Authentification email/password ───────────────────────────────────────

  login(email: string, password: string) {
    return this.http.post<AuthUser>('/api/auth/login', { email, password }).pipe(
      tap((user: AuthUser) => this.store(user))
    );
  }

  // ── Authentification OAuth2 (Google, Microsoft…) ──────────────────────────

  /**
   * Pose un cookie court (5 min) pour indiquer à Spring Boot que la
   * redirection post-OAuth2 doit revenir vers Angular avec un JWT.
   */
  loginWithProvider(providerId: string) {
    document.cookie = 'oauth_origin=angular;path=/;SameSite=Lax;max-age=300';
    window.location.href = `/oauth2/authorization/${providerId}`;
  }

  /**
   * Appelé depuis LoginComponent quand Spring Boot redirige vers
   * /login?token=xxx après un OAuth2 réussi.
   * Charge /api/auth/me avec le token pour récupérer les infos complètes.
   */
  loginWithOAuthToken(token: string) {
    return this.http.get<Omit<AuthUser, 'token'>>('/api/auth/me', {
      headers: { Authorization: `Bearer ${token}` },
    }).pipe(
      tap(info => this.store({ ...info, token }))
    );
  }

  // ── SSO depuis session Thymeleaf ──────────────────────────────────────────

  /**
   * Lit le cookie _intra_jwt posé par Spring Boot après un login Thymeleaf,
   * puis échange ce token contre les infos utilisateur complètes.
   * Appelé au démarrage de l'app (APP_INITIALIZER).
   */
  tryJwtCookieLogin() {
    const match = document.cookie.match(/(?:^|;\s*)_intra_jwt=([^;]+)/);
    if (!match) return of(null as AuthUser | null);
    const token = decodeURIComponent(match[1]);
    document.cookie = '_intra_jwt=;path=/;max-age=0;SameSite=Lax';
    return this.loginWithOAuthToken(token).pipe(
      catchError(() => of(null as AuthUser | null))
    );
  }

  /**
   * Fallback SSO : si une session Thymeleaf (JSESSIONID) est active,
   * Spring Boot émet un JWT via /auth/session-token.
   * Couvre le cas où le cookie _intra_jwt est absent ou expiré.
   */
  trySessionLogin() {
    return this.http.get<AuthUser>('/auth/session-token').pipe(
      tap((user: AuthUser) => this.store(user)),
      catchError(() => of(null as AuthUser | null))
    );
  }

  // ── Déconnexion ───────────────────────────────────────────────────────────

  /**
   * Vérifie si l'utilisateur a au moins un des rôles donnés.
   * ROLE_ADMIN passe toujours. Lisible dans les templates Angular (réactif via signal).
   */
  hasRole(...roles: string[]): boolean {
    if (this.isAdmin()) return true;
    const userRoles = this._user()?.roles ?? [];
    return userRoles.some(r => roles.includes(r));
  }

  /** Met à jour le nom affiché localement après une sauvegarde de profil. */
  updateProfile(fullName: string): void {
    const u = this._user();
    if (u) this.store({ ...u, fullName });
  }

  logout() {
    this._user.set(null);
    localStorage.removeItem(STORAGE_KEY);
    window.location.href = '/';
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private store(user: AuthUser) {
    this._user.set(user);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
  }

  private loadFromStorage(): AuthUser | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? (JSON.parse(raw) as AuthUser) : null;
    } catch {
      return null;
    }
  }
}
