import { Injectable, inject, signal, resource } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Appointment, AppointmentPage, AppointmentMeta, TeamGroup } from './appointment.model';

export interface AppointmentFilter {
  status?: string;
  page:    number;
  size:    number;
}

@Injectable({ providedIn: 'root' })
export class AppointmentService {
  private readonly http = inject(HttpClient);

  readonly filter = signal<AppointmentFilter>({ page: 0, size: 20 });

  readonly appointments = resource<AppointmentPage, AppointmentFilter>({
    params: () => this.filter(),
    loader: ({ params }) => {
      let p = new HttpParams()
        .set('page', String(params.page))
        .set('size', String(params.size));
      if (params.status) p = p.set('status', params.status);
      return firstValueFrom(
        this.http.get<AppointmentPage>('/api/intra/appointments', { params: p })
      );
    }
  });

  readonly meta = resource<AppointmentMeta, void>({
    loader: () => firstValueFrom(this.http.get<AppointmentMeta>('/api/intra/appointments/meta'))
  });

  readonly teams = resource<TeamGroup[], void>({
    loader: () => firstValueFrom(this.http.get<TeamGroup[]>('/api/intra/appointments/teams'))
  });
}
