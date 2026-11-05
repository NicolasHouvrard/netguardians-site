export interface Appointment {
  id:             number;
  title:          string;
  startAt:        string;
  endAt:          string | null;
  type:           string;
  typeLabel:      string;
  status:         string;
  statusLabel:    string;
  customerId:     number | null;
  customerName:   string | null;
  assignedToId:   number | null;
  assignedToName: string | null;
  notes:          string | null;
  meetLink:       string | null;
  synced:         boolean;
}

export interface AppointmentPage {
  content:       Appointment[];
  page:          number;
  size:          number;
  totalElements: number;
  totalPages:    number;
}

export interface AppointmentMeta {
  statuses: { value: string; label: string }[];
  types:    { value: string; label: string }[];
}

export interface TeamMember {
  id:       number;
  fullName: string;
}

export interface TeamGroup {
  id:      number;
  name:    string;
  members: TeamMember[];
}
