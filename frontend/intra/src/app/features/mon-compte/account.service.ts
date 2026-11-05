import { Injectable, inject, resource } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AccountProfile, CalendarAccount } from './account.model';

export interface PasswordResult { success: boolean; error?: string; }

@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);

  readonly profile = resource<AccountProfile, void>({
    loader: () => firstValueFrom(this.http.get<AccountProfile>('/api/intra/account')),
  });

  readonly calendars = resource<CalendarAccount[], void>({
    loader: () => firstValueFrom(this.http.get<CalendarAccount[]>('/api/intra/account/calendars')),
  });

  saveProfile(fullName: string) {
    return this.http.put<AccountProfile>('/api/intra/account/profile', { fullName });
  }

  changePassword(currentPassword: string, newPassword: string, confirmPassword: string) {
    return this.http.post<PasswordResult>('/api/intra/account/password', {
      currentPassword, newPassword, confirmPassword,
    });
  }
}
