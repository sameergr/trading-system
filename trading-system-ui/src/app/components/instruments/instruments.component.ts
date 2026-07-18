import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NavComponent } from '../shared/nav/nav.component';
import { ChartApiService } from '../../services/chart-api.service';
import {
  InstrumentStatus, IntervalStatus, AddInstrumentRequest,
  EXCHANGE_SEGMENTS, INSTRUMENT_TYPES
} from '../../models/chart.models';
import { catchError, of } from 'rxjs';

type Tab = 'list' | 'add' | 'csv';
type Filter = 'ALL' | 'COMPLETE' | 'PARTIAL' | 'PENDING' | 'FAILED';

@Component({
  selector: 'app-instruments',
  standalone: true,
  imports: [CommonModule, FormsModule, NavComponent],
  templateUrl: './instruments.component.html',
  styleUrls: ['./instruments.component.scss']
})
export class InstrumentsComponent implements OnInit {
  private api = inject(ChartApiService);

  // ── Data ─────────────────────────────────────────────────────────────────
  instruments   = signal<InstrumentStatus[]>([]);
  loading       = signal(false);
  error         = signal<string | null>(null);
  toast         = signal<{ msg: string; type: 'success' | 'error' | 'info' } | null>(null);

  // ── UI state ──────────────────────────────────────────────────────────────
  activeTab     = signal<Tab>('list');
  filterStatus  = signal<Filter>('ALL');
  searchQuery   = signal('');
  expandedRow   = signal<number | null>(null);
  backfilling   = signal(false);

  // ── Add form ──────────────────────────────────────────────────────────────
  addForm: AddInstrumentRequest = {
    securityId: 0, symbol: '', exchangeSegment: 'NSE_EQ', instrumentType: 'EQUITY'
  };
  addLoading = signal(false);

  // ── CSV upload ────────────────────────────────────────────────────────────
  csvFile       = signal<File | null>(null);
  csvLoading    = signal(false);
  csvResult     = signal<{ added: number; symbols: string[] } | null>(null);

  readonly exchangeSegments = EXCHANGE_SEGMENTS;
  readonly instrumentTypes  = INSTRUMENT_TYPES;
  readonly filterOptions: Filter[] = ['ALL', 'COMPLETE', 'PARTIAL', 'PENDING', 'FAILED'];

  // ── Computed ──────────────────────────────────────────────────────────────
  filtered = computed(() => {
    const q   = this.searchQuery().toLowerCase();
    const st  = this.filterStatus();
    return this.instruments().filter(i => {
      const matchSearch = !q || i.symbol.toLowerCase().includes(q)
                              || i.exchangeSegment.toLowerCase().includes(q);
      const matchFilter = st === 'ALL' || i.overallStatus === st;
      return matchSearch && matchFilter;
    });
  });

  stats = computed(() => {
    const all = this.instruments();
    return {
      total:    all.length,
      complete: all.filter(i => i.overallStatus === 'COMPLETE').length,
      partial:  all.filter(i => i.overallStatus === 'PARTIAL').length,
      pending:  all.filter(i => i.overallStatus === 'PENDING').length,
      failed:   all.filter(i => i.overallStatus === 'FAILED').length,
      totalCandles: all.reduce((s, i) => s + i.totalCandles, 0),
    };
  });

  ngOnInit() { this.loadStatus(); }

  loadStatus() {
    this.loading.set(true);
    this.error.set(null);
    this.api.getInstrumentsStatus().pipe(
      catchError(err => {
        this.error.set('Failed to load instruments. Is chart-api running?');
        return of([]);
      })
    ).subscribe(data => {
      this.instruments.set(data);
      this.loading.set(false);
    });
  }

  // ── Add single ───────────────────────────────────────────────────────────
  submitAdd() {
    if (!this.addForm.securityId || !this.addForm.symbol) {
      this.showToast('Security ID and Symbol are required.', 'error');
      return;
    }
    this.addLoading.set(true);
    this.api.addInstrument(this.addForm).pipe(
      catchError(err => {
        const msg = err.error?.error || 'Failed to add instrument.';
        this.showToast(msg, 'error');
        this.addLoading.set(false);
        return of(null);
      })
    ).subscribe(result => {
      if (result) {
        this.showToast(`${this.addForm.symbol} added successfully.`, 'success');
        this.addForm = { securityId: 0, symbol: '', exchangeSegment: 'NSE_EQ', instrumentType: 'EQUITY' };
        this.addLoading.set(false);
        this.loadStatus();
        this.activeTab.set('list');
      }
    });
  }

  // ── CSV upload ────────────────────────────────────────────────────────────
  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    this.csvFile.set(input.files?.[0] ?? null);
    this.csvResult.set(null);
  }

  submitCSV() {
    const file = this.csvFile();
    if (!file) return;
    this.csvLoading.set(true);
    this.api.uploadCSV(file).pipe(
      catchError(err => {
        this.showToast(err.error?.error || 'CSV upload failed.', 'error');
        this.csvLoading.set(false);
        return of(null);
      })
    ).subscribe(result => {
      if (result) {
        this.csvResult.set(result);
        this.csvLoading.set(false);
        this.showToast(`${result.added} instruments added from CSV.`, 'success');
        this.loadStatus();
      }
    });
  }

  // ── Backfill ──────────────────────────────────────────────────────────────
  triggerBackfillAll() {
    this.backfilling.set(true);
    this.api.triggerBackfill([]).pipe(
      catchError(() => {
        this.showToast('Could not reach dhan-collector.', 'error');
        this.backfilling.set(false);
        return of(null);
      })
    ).subscribe(result => {
      if (result) {
        const msg = result.status === 'QUEUED'
          ? 'Backfill started for all instruments.'
          : result.message;
        this.showToast(msg, result.status === 'QUEUED' ? 'success' : 'error');
      }
      this.backfilling.set(false);
    });
  }

  triggerBackfillOne(securityId: number) {
    this.api.triggerBackfill([securityId]).pipe(
      catchError(() => { this.showToast('Could not reach dhan-collector.', 'error'); return of(null); })
    ).subscribe(result => {
      if (result) this.showToast(result.message, 'info');
    });
  }

  // ── Row expansion ─────────────────────────────────────────────────────────
  toggleExpand(id: number) {
    this.expandedRow.set(this.expandedRow() === id ? null : id);
  }

  isExpanded(id: number) { return this.expandedRow() === id; }

  // ── Deactivate ────────────────────────────────────────────────────────────
  deactivate(inst: InstrumentStatus) {
    if (!confirm(`Deactivate ${inst.symbol}? Candle data is retained.`)) return;
    this.api.deactivateInstrument(inst.securityId).subscribe(() => {
      this.showToast(`${inst.symbol} deactivated.`, 'info');
      this.loadStatus();
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  formatCandles(n: number): string {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000)     return (n / 1_000).toFixed(0) + 'K';
    return n.toString();
  }

  statusClass(s: string): string {
    return { COMPLETE: 'badge-green', PARTIAL: 'badge-yellow',
             PENDING: 'badge-gray', FAILED: 'badge-red' }[s] ?? 'badge-gray';
  }

  statusIcon(s: string): string {
    return { COMPLETE: '✓', PARTIAL: '◑', PENDING: '○', FAILED: '✕' }[s] ?? '○';
  }

  private toastTimer: any;
  showToast(msg: string, type: 'success' | 'error' | 'info') {
    this.toast.set({ msg, type });
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 4000);
  }

  trackById(_: number, i: InstrumentStatus) { return i.securityId; }
  trackByInterval(_: number, i: IntervalStatus) { return i.interval; }
}
