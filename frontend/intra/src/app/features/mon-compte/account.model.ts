export interface AccountProfile {
  id:       number;
  email:    string;
  fullName: string;
}

export interface CalendarAccount {
  id:        number;
  name:      string;
  authType:  'GOOGLE_OAUTH2' | 'MICROSOFT_OAUTH2';
  username:  string | null;
  connected: boolean;
}
