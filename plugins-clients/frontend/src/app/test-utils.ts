import { vi } from 'vitest';

export interface ReponseSimulee {
  status: number;
  corps?: unknown;
}

/** Remplace fetch par une simulation : "METHODE url" -> reponse ; renvoie la liste des appels pour les assertions. */
export function simulerFetch(routes: Record<string, ReponseSimulee>) {
  const appels: { methode: string; url: string; authorization: string | null; corps: string | null }[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const methode = init?.method ?? 'GET';
      const entetes = new Headers(init?.headers);
      appels.push({ methode, url, authorization: entetes.get('Authorization'), corps: (init?.body as string) ?? null });
      const r = routes[`${methode} ${url}`];
      if (!r) return new Response(null, { status: 404 });
      return new Response(r.corps === undefined ? null : JSON.stringify(r.corps), {
        status: r.status,
        headers: { 'content-type': 'application/json' },
      });
    }),
  );
  return appels;
}
