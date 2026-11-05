import { Injectable, inject, signal, resource } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  Ticket, TicketPage, TicketMeta, TicketFormMeta, CreateTicketRequest
} from './ticket.model';

export interface TicketFilter {
  status?:      string;
  priority?:    string;
  customerId?:  number;
  assignedToId?: number;
  teamId?:      number;
  q?:           string;
  page:         number;
  size:         number;
}

export interface AssignRequest {
  customerId?:   number | null;
  contactId?:    number | null;
  siteId?:       number | null;
  assignedToId?: number | null;
  teamId?:       number | null;
}

@Injectable({ providedIn: 'root' })
export class TicketService {
  private readonly http = inject(HttpClient);

  readonly filter = signal<TicketFilter>({ page: 0, size: 20 });

  readonly tickets = resource<TicketPage, TicketFilter>({
    params: () => this.filter(),
    loader: ({ params }) => {
      let p = new HttpParams()
        .set('page', String(params.page))
        .set('size', String(params.size));
      if (params.status)       p = p.set('status',       params.status);
      if (params.priority)     p = p.set('priority',     params.priority);
      if (params.customerId)   p = p.set('customerId',   String(params.customerId));
      if (params.assignedToId) p = p.set('assignedToId', String(params.assignedToId));
      if (params.teamId)       p = p.set('teamId',       String(params.teamId));
      if (params.q)            p = p.set('q',            params.q);
      return firstValueFrom(this.http.get<TicketPage>('/api/intra/tickets', { params: p }));
    }
  });

  readonly meta = resource<TicketMeta, void>({
    loader: () => firstValueFrom(this.http.get<TicketMeta>('/api/intra/tickets/meta'))
  });

  readonly formMeta = resource<TicketFormMeta, void>({
    loader: () => firstValueFrom(this.http.get<TicketFormMeta>('/api/intra/tickets/form-meta'))
  });

  getById(id: number) {
    return this.http.get<Ticket>(`/api/intra/tickets/${id}`);
  }

  create(req: CreateTicketRequest) {
    return this.http.post<Ticket>('/api/intra/tickets', req);
  }

  updateStatus(id: number, status: string) {
    return this.http.patch<Ticket>(`/api/intra/tickets/${id}/status`, { status });
  }

  assign(id: number, req: AssignRequest) {
    return this.http.patch<Ticket>(`/api/intra/tickets/${id}/assign`, req);
  }

  addComment(id: number, content: string, isPublic: boolean) {
    return this.http.post<Ticket>(`/api/intra/tickets/${id}/comment`, { content, isPublic });
  }

  getSites(customerId: number) {
    return this.http.get<{ id: number; name: string }[]>(
      `/api/intra/tickets/customers/${customerId}/sites`);
  }

  getContacts(customerId: number, siteId?: number) {
    let p = new HttpParams();
    if (siteId) p = p.set('siteId', String(siteId));
    return this.http.get<{ id: number; fullName: string; email: string }[]>(
      `/api/intra/tickets/customers/${customerId}/contacts`, { params: p });
  }

  uploadAttachment(id: number, file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<Ticket>(`/api/intra/tickets/${id}/attachments`, form);
  }

  deleteAttachment(id: number, attachId: number) {
    return this.http.delete<Ticket>(`/api/intra/tickets/${id}/attachments/${attachId}`);
  }
}
