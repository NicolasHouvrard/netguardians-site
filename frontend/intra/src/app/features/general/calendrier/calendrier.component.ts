import { Component, inject, signal, viewChild } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';

import { MatTableModule }          from '@angular/material/table';
import { MatChipsModule }          from '@angular/material/chips';
import { MatFormFieldModule }      from '@angular/material/form-field';
import { MatSelectModule }         from '@angular/material/select';
import { MatButtonModule }         from '@angular/material/button';
import { MatIconModule }           from '@angular/material/icon';
import { MatProgressBarModule }    from '@angular/material/progress-bar';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTooltipModule }        from '@angular/material/tooltip';
import { MatButtonToggleModule }   from '@angular/material/button-toggle';
import { MatCardModule }           from '@angular/material/card';
import { MatDividerModule }        from '@angular/material/divider';

import { FullCalendarModule }      from '@fullcalendar/angular';
import { FullCalendarComponent }   from '@fullcalendar/angular';
import type { CalendarOptions, EventClickArg, EventSourceFuncArg } from '@fullcalendar/core';
import dayGridPlugin               from '@fullcalendar/daygrid';
import timeGridPlugin              from '@fullcalendar/timegrid';
import interactionPlugin           from '@fullcalendar/interaction';
import listPlugin                  from '@fullcalendar/list';
import frLocale                    from '@fullcalendar/core/locales/fr';

import { AppointmentService } from './appointment.service';
import { TeamGroup }          from './appointment.model';

const STATUS_COLOR: Record<string, 'primary' | 'accent' | 'warn' | ''> = {
  SCHEDULED: 'primary',
  CONFIRMED: 'accent',
  COMPLETED: '',
  CANCELLED: 'warn',
};

const TYPE_ICON: Record<string, string> = {
  ONSITE:  'location_on',
  REMOTE:  'videocam',
  PHONE:   'phone',
  PRIVATE: 'lock',
};

@Component({
  selector: 'app-calendrier',
  standalone: true,
  imports: [
    ReactiveFormsModule, DatePipe,
    MatTableModule, MatChipsModule, MatFormFieldModule,
    MatSelectModule, MatButtonModule, MatIconModule,
    MatProgressBarModule, MatPaginatorModule, MatTooltipModule,
    MatButtonToggleModule, MatCardModule, MatDividerModule,
    FullCalendarModule,
  ],
  template: `
    <!-- ── EN-TÊTE ──────────────────────────────────────────────────────────── -->
    <div class="page-header">
      <h2 class="page-title">
        <mat-icon>calendar_month</mat-icon>
        Calendrier
      </h2>

      <mat-button-toggle-group class="view-toggle"
        [value]="viewMode()" (change)="viewMode.set($event.value)">
        <mat-button-toggle value="calendar">
          <mat-icon>calendar_month</mat-icon> Calendrier
        </mat-button-toggle>
        <mat-button-toggle value="list">
          <mat-icon>list</mat-icon> Liste
        </mat-button-toggle>
      </mat-button-toggle-group>

      @if (viewMode() === 'list') {
        <div class="filters">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Statut</mat-label>
            <mat-select [formControl]="statusCtrl">
              <mat-option value="">Tous</mat-option>
              @for (s of svc.meta.value()?.statuses ?? []; track s.value) {
                <mat-option [value]="s.value">{{ s.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>
      }
    </div>

    <!-- ── VUE CALENDRIER ───────────────────────────────────────────────────── -->
    @if (viewMode() === 'calendar') {

      <!-- Barre de filtre équipes / membres -->
      <div class="filter-bar">
        <button class="filter-chip all-chip"
          [class.active]="selectedUserIds().size === 0"
          (click)="clearFilter()">
          <mat-icon>group</mat-icon>
          Tous
        </button>

        @for (team of svc.teams.value() ?? []; track team.id) {
          <div class="team-group">
            <button class="filter-chip team-chip"
              [class.active]="isTeamSelected(team)"
              [class.partial]="isTeamPartial(team)"
              (click)="toggleTeam(team)"
              [matTooltip]="'Équipe : ' + team.name">
              <mat-icon>people</mat-icon>
              {{ team.name }}
            </button>
            @for (member of team.members; track member.id) {
              <button class="filter-chip member-chip"
                [class.active]="isMemberSelected(member.id)"
                (click)="toggleMember(member.id)">
                {{ member.fullName }}
              </button>
            }
          </div>
        }
      </div>

      <!-- FullCalendar -->
      <div class="calendar-wrapper">
        <full-calendar #fc [options]="calendarOptions" />
      </div>

      <!-- Panneau de détail d'un RDV -->
      @if (selectedEvent()) {
        <mat-card class="event-detail-card" appearance="outlined">
          <mat-card-header>
            <mat-card-title>{{ selectedEvent()!['title'] }}</mat-card-title>
            <button mat-icon-button class="close-btn" (click)="selectedEvent.set(null)">
              <mat-icon>close</mat-icon>
            </button>
          </mat-card-header>
          <mat-divider />
          <mat-card-content>
            <dl class="detail-grid">
              <dt>Type</dt>       <dd>{{ selectedEvent()!['typeLabel'] }}</dd>
              <dt>Statut</dt>     <dd>{{ selectedEvent()!['statusLabel'] }}</dd>
              @if (selectedEvent()!['customerName']) {
                <dt>Client</dt>   <dd>{{ selectedEvent()!['customerName'] }}</dd>
              }
              @if (selectedEvent()!['assignedToName']) {
                <dt>Assigné</dt>  <dd>{{ selectedEvent()!['assignedToName'] }}</dd>
              }
              @if (selectedEvent()!['location']) {
                <dt>Lieu</dt>     <dd>{{ selectedEvent()!['location'] }}</dd>
              }
              @if (selectedEvent()!['notes']) {
                <dt>Notes</dt>    <dd class="notes">{{ selectedEvent()!['notes'] }}</dd>
              }
              @if (selectedEvent()!['meetLink']) {
                <dt>Meet</dt>
                <dd>
                  <a [href]="selectedEvent()!['meetLink']" target="_blank" mat-stroked-button>
                    <mat-icon>video_call</mat-icon> Rejoindre
                  </a>
                </dd>
              }
              @if (selectedEvent()!['canEdit'] && selectedEvent()!['editUrl']) {
                <dt>Modifier</dt>
                <dd>
                  <a [href]="selectedEvent()!['editUrl']" target="_blank" mat-stroked-button>
                    <mat-icon>edit_calendar</mat-icon> Ouvrir dans l'agenda
                  </a>
                </dd>
              }
            </dl>
          </mat-card-content>
        </mat-card>
      }
    }

    <!-- ── VUE LISTE ─────────────────────────────────────────────────────────── -->
    @if (viewMode() === 'list') {

      @if (svc.appointments.isLoading()) {
        <mat-progress-bar mode="indeterminate" />
      }

      <mat-table [dataSource]="svc.appointments.value()?.content ?? []" class="table">

        <ng-container matColumnDef="date">
          <mat-header-cell *matHeaderCellDef>Date</mat-header-cell>
          <mat-cell *matCellDef="let a">
            <div class="date-cell">
              <span class="date-day">{{ a.startAt | date:'dd MMM' }}</span>
              <span class="date-time">{{ a.startAt | date:'HH:mm' }}</span>
            </div>
          </mat-cell>
        </ng-container>

        <ng-container matColumnDef="title">
          <mat-header-cell *matHeaderCellDef>Titre</mat-header-cell>
          <mat-cell *matCellDef="let a">
            <div class="title-cell">
              <mat-icon class="type-icon" [matTooltip]="a.typeLabel">{{ typeIcon(a.type) }}</mat-icon>
              <span>{{ a.title }}</span>
              @if (a.meetLink) {
                <a [href]="a.meetLink" target="_blank" mat-icon-button matTooltip="Rejoindre le lien">
                  <mat-icon class="meet-icon">video_call</mat-icon>
                </a>
              }
            </div>
          </mat-cell>
        </ng-container>

        <ng-container matColumnDef="status">
          <mat-header-cell *matHeaderCellDef>Statut</mat-header-cell>
          <mat-cell *matCellDef="let a">
            <mat-chip [color]="statusColor(a.status)" highlighted>{{ a.statusLabel }}</mat-chip>
          </mat-cell>
        </ng-container>

        <ng-container matColumnDef="customer">
          <mat-header-cell *matHeaderCellDef>Client</mat-header-cell>
          <mat-cell *matCellDef="let a">{{ a.customerName ?? '—' }}</mat-cell>
        </ng-container>

        <ng-container matColumnDef="assignedTo">
          <mat-header-cell *matHeaderCellDef>Technicien</mat-header-cell>
          <mat-cell *matCellDef="let a">{{ a.assignedToName ?? '—' }}</mat-cell>
        </ng-container>

        <mat-header-row *matHeaderRowDef="columns" />
        <mat-row *matRowDef="let row; columns: columns;" />

        <tr class="mat-row" *matNoDataRow>
          <td class="mat-cell empty-row" [attr.colspan]="columns.length">
            Aucun rendez-vous trouvé.
          </td>
        </tr>
      </mat-table>

      <mat-paginator
        [length]="svc.appointments.value()?.totalElements ?? 0"
        [pageSize]="svc.filter().size"
        [pageIndex]="svc.filter().page"
        [pageSizeOptions]="[10, 20, 50]"
        (page)="onPage($event)" />
    }
  `,
  styles: [`
    /* ── En-tête ─────────────────────────────────────────────── */
    .page-header {
      display: flex; align-items: center; gap: 16px;
      margin-bottom: 16px; flex-wrap: wrap;
    }
    .page-title {
      display: flex; align-items: center; gap: 8px;
      margin: 0; font-size: 20px; font-weight: 500;
      mat-icon { color: #3f51b5; }
    }
    .view-toggle { height: 36px; }
    .filters { display: flex; gap: 12px; flex-wrap: wrap; margin-left: auto; }
    mat-form-field { min-width: 150px; }

    /* ── Barre de filtre ──────────────────────────────────────── */
    .filter-bar {
      display: flex; align-items: flex-start; gap: 6px;
      flex-wrap: wrap; margin-bottom: 12px;
      padding: 10px 12px; background: #f8f9fa;
      border-radius: 8px; border: 1px solid #e9ecef;
    }
    .team-group {
      display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
      padding: 2px 8px 2px 6px;
      border-left: 2px solid #dee2e6;
    }
    .filter-chip {
      display: inline-flex; align-items: center; gap: 4px;
      padding: 3px 10px; border-radius: 14px;
      border: 1px solid #ced4da; background: white;
      cursor: pointer; font-size: 12px; font-family: inherit;
      transition: background 0.12s, border-color 0.12s, color 0.12s;
      line-height: 1.5;
      mat-icon { font-size: 14px; width: 14px; height: 14px; }

      &:hover:not(.active) { border-color: #3f51b5; color: #3f51b5; }

      &.active {
        background: #3f51b5; color: white; border-color: #3f51b5;
        mat-icon { color: white; }
      }
      &.partial {
        background: #e8eaf6; color: #3f51b5; border-color: #9fa8da;
      }
    }
    .team-chip { font-weight: 600; }
    .all-chip mat-icon { color: #666; }

    /* ── Calendrier ───────────────────────────────────────────── */
    .calendar-wrapper {
      border-radius: 8px; overflow: hidden;
      border: 1px solid #e0e0e0;
    }

    /* ── Panneau détail ───────────────────────────────────────── */
    .event-detail-card {
      margin-top: 16px; position: relative;
      mat-card-header { padding-bottom: 0; }
    }
    .close-btn { position: absolute; top: 8px; right: 8px; }
    .detail-grid {
      display: grid; grid-template-columns: 90px 1fr;
      gap: 8px 16px; margin: 12px 0 0; font-size: 14px;
      dt { font-weight: 500; color: #666; }
      dd { margin: 0; }
      .notes { white-space: pre-wrap; color: #444; }
    }

    /* ── Vue liste ────────────────────────────────────────────── */
    .table { width: 100%; }
    mat-header-cell, mat-cell { padding: 8px 12px; font-size: 13px; }

    .date-cell { display: flex; flex-direction: column; line-height: 1.3; }
    .date-day  { font-weight: 500; font-size: 13px; }
    .date-time { font-size: 11px; color: #888; }

    .title-cell {
      display: flex; align-items: center; gap: 6px;
      .type-icon { font-size: 16px; width: 16px; height: 16px; color: #666; }
      .meet-icon { font-size: 16px; width: 16px; height: 16px; color: #1a73e8; }
    }

    .empty-row { text-align: center; padding: 32px; color: #888; }
  `],
})
export class CalendrierComponent {
  readonly svc    = inject(AppointmentService);
  private readonly http = inject(HttpClient);
  private readonly fcRef = viewChild<FullCalendarComponent>('fc');

  // ── State ─────────────────────────────────────────────────────────────────

  readonly viewMode        = signal<'calendar' | 'list'>('calendar');
  readonly selectedUserIds = signal<Set<number>>(new Set());
  readonly selectedEvent   = signal<Record<string, unknown> | null>(null);

  readonly columns   = ['date', 'title', 'status', 'customer', 'assignedTo'];
  readonly statusCtrl = new FormControl('');

  constructor() {
    this.statusCtrl.valueChanges.subscribe(status => {
      this.svc.filter.update(f => ({ ...f, status: status || undefined, page: 0 }));
    });
  }

  // ── FullCalendar ──────────────────────────────────────────────────────────

  readonly calendarOptions: CalendarOptions = {
    plugins: [dayGridPlugin, timeGridPlugin, interactionPlugin, listPlugin],
    initialView: 'timeGridWeek',
    locale: frLocale,
    headerToolbar: {
      left:   'prev,next today',
      center: 'title',
      right:  'dayGridMonth,timeGridWeek,timeGridDay,listWeek',
    },
    height: 'auto',
    slotMinTime: '07:00:00',
    slotMaxTime: '20:00:00',
    nowIndicator: true,
    businessHours: { daysOfWeek: [1, 2, 3, 4, 5], startTime: '08:00', endTime: '18:00' },
    eventTimeFormat: { hour: '2-digit', minute: '2-digit', hour12: false },
    events: (info, success) => this.loadEvents(info, success),
    eventClick: (info) => this.onEventClick(info),
  };

  private loadEvents(
    info: EventSourceFuncArg,
    success: (events: object[]) => void
  ): void {
    let params = new HttpParams()
      .set('start', info.startStr.substring(0, 19))
      .set('end',   info.endStr.substring(0, 19));

    [...this.selectedUserIds()].forEach(id => {
      params = params.append('userIds', String(id));
    });

    this.http.get<object[]>('/api/intra/appointments/events', { params })
      .subscribe({ next: success, error: () => success([]) });
  }

  private onEventClick(info: EventClickArg): void {
    const ep   = info.event.extendedProps;
    const kind = ep['eventKind'] as string;

    if (kind === 'intervention') return;

    if (kind === 'external') {
      this.selectedEvent.set({
        title:          info.event.title,
        start:          info.event.start,
        end:            info.event.end,
        typeLabel:      ep['calendarName'] ?? 'Agenda externe',
        statusLabel:    null,
        customerName:   null,
        assignedToName: ep['assignedToName'],
        notes:          ep['description'] || null,
        meetLink:       null,
        location:       ep['location'] || null,
        editUrl:        ep['editUrl'] || null,
        canEdit:        ep['canEdit'] === true,
      });
      return;
    }

    // RDV local
    this.selectedEvent.set({
      title:          info.event.title,
      start:          info.event.start,
      end:            info.event.end,
      typeLabel:      ep['typeLabel'],
      statusLabel:    ep['statusLabel'],
      customerName:   ep['customerName'],
      assignedToName: ep['assignedToName'],
      notes:          ep['notes'],
      meetLink:       ep['meetLink'],
    });
  }

  // ── Filtres équipes / membres ─────────────────────────────────────────────

  clearFilter(): void {
    this.selectedUserIds.set(new Set());
    this.refetch();
  }

  isTeamSelected(team: TeamGroup): boolean {
    const ids = this.selectedUserIds();
    return team.members.length > 0 && team.members.every(m => ids.has(m.id));
  }

  isTeamPartial(team: TeamGroup): boolean {
    const ids = this.selectedUserIds();
    return !this.isTeamSelected(team) && team.members.some(m => ids.has(m.id));
  }

  isMemberSelected(id: number): boolean {
    return this.selectedUserIds().has(id);
  }

  toggleTeam(team: TeamGroup): void {
    this.selectedUserIds.update(ids => {
      const next = new Set(ids);
      if (this.isTeamSelected(team)) {
        team.members.forEach(m => next.delete(m.id));
      } else {
        team.members.forEach(m => next.add(m.id));
      }
      return next;
    });
    this.refetch();
  }

  toggleMember(id: number): void {
    this.selectedUserIds.update(ids => {
      const next = new Set(ids);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
    this.refetch();
  }

  private refetch(): void {
    this.fcRef()?.getApi().refetchEvents();
  }

  // ── Vue liste ─────────────────────────────────────────────────────────────

  onPage(e: PageEvent): void {
    this.svc.filter.update(f => ({ ...f, page: e.pageIndex, size: e.pageSize }));
  }

  statusColor(status: string): 'primary' | 'accent' | 'warn' | '' {
    return STATUS_COLOR[status] ?? '';
  }

  typeIcon(type: string): string {
    return TYPE_ICON[type] ?? 'event';
  }
}
