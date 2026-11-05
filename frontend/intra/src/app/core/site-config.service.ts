import { Injectable, resource, inject, effect } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { AuthService } from './auth/auth.service';

export interface SiteConfig {
  siteName: string;
  siteUrl: string;
  tagline: string;
  faviconUrl: string;
  logoUrl: string;
  contactEmail: string;
  contactPhone: string;
  address: string;
  metaDescription: string;
  metaKeywords: string;
  socialFacebook: string;
  socialLinkedin: string;
  socialInstagram: string;
  socialTwitter: string;
  socialYoutube: string;
  footerText: string;
  googleAnalyticsId: string;
}

@Injectable({ providedIn: 'root' })
export class SiteConfigService {
  private readonly auth     = inject(AuthService);
  private readonly http     = inject(HttpClient);
  private readonly document = inject(DOCUMENT);

  readonly config = resource<SiteConfig, string | null>({
    params: () => this.auth.token(),
    loader: ({ params: token }) => {
      if (!token) return Promise.resolve(this.defaults());
      return fetch('/api/intra/site-config', {
        headers: { Authorization: `Bearer ${token}` },
      }).then(r => r.ok ? r.json() : this.defaults());
    },
  });

  constructor() {
    effect(() => {
      const cfg = this.config.value();
      if (cfg?.faviconUrl) this.setFavicon(cfg.faviconUrl);
    });
  }

  save(cfg: Partial<SiteConfig>) {
    return this.http.put<SiteConfig>('/api/intra/site-config', cfg);
  }

  private setFavicon(url: string) {
    let link = this.document.querySelector<HTMLLinkElement>("link[rel='icon']");
    if (!link) {
      link = this.document.createElement('link');
      link.rel = 'icon';
      this.document.head.appendChild(link);
    }
    link.href = url;
  }

  private defaults(): SiteConfig {
    return {
      siteName: 'Mon Site', siteUrl: '/', tagline: '', faviconUrl: '',
      logoUrl: '', contactEmail: '', contactPhone: '', address: '',
      metaDescription: '', metaKeywords: '', socialFacebook: '',
      socialLinkedin: '', socialInstagram: '', socialTwitter: '',
      socialYoutube: '', footerText: '', googleAnalyticsId: '',
    };
  }
}
