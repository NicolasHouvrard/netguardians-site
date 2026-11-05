import { Injectable, inject, signal, resource } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Customer, CustomerPage, SaveCustomerRequest, SaveSiteRequest, SaveContactRequest, Site, Contact, ContactAccount, Subscription } from './customer.model';

export interface CustomerFilter {
  q?:      string;
  type?:   string;   // customer | prospect | supplier | esn
  active?: boolean;
  page:    number;
  size:    number;
}

@Injectable({ providedIn: 'root' })
export class CustomerService {
  private readonly http = inject(HttpClient);
  

  readonly filter = signal<CustomerFilter>({ page: 0, size: 20 });

  readonly customers = resource<CustomerPage, CustomerFilter>({
    params: () => this.filter(),
    loader: ({ params }) => {
      let p = new HttpParams()
        .set('page', String(params.page))
        .set('size', String(params.size));
      if (params.q)                p = p.set('q',      params.q);
      if (params.type)             p = p.set('type',   params.type);
      if (params.active != null)   p = p.set('active', String(params.active));
      return firstValueFrom(this.http.get<CustomerPage>('/api/intra/customers', { params: p }));
    }
  });

  getById(id: number) {
    return this.http.get<Customer>(`/api/intra/customers/${id}`);
  }

  create(req: SaveCustomerRequest) {
    return this.http.post<Customer>('/api/intra/customers', req);
  }

  update(id: number, req: SaveCustomerRequest) {
    return this.http.put<Customer>(`/api/intra/customers/${id}`, req);
  }

  getSites(id: number) {
    return this.http.get<Site[]>(`/api/intra/customers/${id}/sites`);
  }

  createSite(customerId: number, req: SaveSiteRequest) {
    return this.http.post<Site>(`/api/intra/customers/${customerId}/sites`, req);
  }

  updateSite(customerId: number, siteId: number, req: SaveSiteRequest) {
    return this.http.put<Site>(`/api/intra/customers/${customerId}/sites/${siteId}`, req);
  }

  deleteSite(customerId: number, siteId: number) {
    return this.http.delete<void>(`/api/intra/customers/${customerId}/sites/${siteId}`);
  }

  getContacts(id: number) {
    return this.http.get<Contact[]>(`/api/intra/customers/${id}/contacts`);
  }

  createContact(customerId: number, req: SaveContactRequest) {
    return this.http.post<Contact>(`/api/intra/customers/${customerId}/contacts`, req);
  }

  updateContact(customerId: number, contactId: number, req: SaveContactRequest) {
    return this.http.put<Contact>(`/api/intra/customers/${customerId}/contacts/${contactId}`, req);
  }

  deleteContact(customerId: number, contactId: number) {
    return this.http.delete<void>(`/api/intra/customers/${customerId}/contacts/${contactId}`);
  }

  // ── Gestion des comptes utilisateur ──────────────────────────────────────

  private accountBase(cid: number, ctid: number) {
    return `/api/intra/customers/${cid}/contacts/${ctid}/account`;
  }

  getAccount(cid: number, ctid: number) {
    return this.http.get<ContactAccount>(this.accountBase(cid, ctid));
  }

  createAccount(cid: number, ctid: number) {
    return this.http.post<ContactAccount>(this.accountBase(cid, ctid), {});
  }

  revokeAccount(cid: number, ctid: number) {
    return this.http.delete<ContactAccount>(this.accountBase(cid, ctid));
  }

  activateAccount(cid: number, ctid: number) {
    return this.http.patch<ContactAccount>(`${this.accountBase(cid, ctid)}/activate`, {});
  }

  promoteAccount(cid: number, ctid: number) {
    return this.http.patch<ContactAccount>(`${this.accountBase(cid, ctid)}/promote`, {});
  }

  demoteAccount(cid: number, ctid: number) {
    return this.http.patch<ContactAccount>(`${this.accountBase(cid, ctid)}/demote`, {});
  }

  setEsnRoles(cid: number, ctid: number, roleNames: string[]) {
    return this.http.put<ContactAccount>(`${this.accountBase(cid, ctid)}/esn-roles`, { roleNames });
  }

  sendVerification(cid: number, ctid: number) {
    return this.http.post<void>(`${this.accountBase(cid, ctid)}/send-verification`, {});
  }

  sendPasswordReset(cid: number, ctid: number) {
    return this.http.post<void>(`${this.accountBase(cid, ctid)}/send-reset`, {});
  }

  getSubscriptions(id: number) {
    return this.http.get<Subscription[]>(`/api/intra/customers/${id}/subscriptions`);
  }
}
