import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  Candle, DateRange, Instrument,
  InstrumentStatus, AddInstrumentRequest, BackfillTriggerResult
} from '../models/chart.models';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ChartApiService {
  private http = inject(HttpClient);
  private base = environment.apiUrl;

  // ── Chart endpoints ────────────────────────────────────────────────────────
  getInstruments(): Observable<Instrument[]> {
    return this.http.get<Instrument[]>(`${this.base}/instruments`);
  }

  getCandles(instrumentId: number, interval: string, from: string, to: string): Observable<Candle[]> {
    const params = new HttpParams()
      .set('instrumentId', instrumentId)
      .set('interval', interval)
      .set('from', from)
      .set('to', to);
    return this.http.get<Candle[]>(`${this.base}/candles`, { params });
  }

  getDateRange(instrumentId: number, interval: string): Observable<DateRange> {
    const params = new HttpParams()
      .set('instrumentId', instrumentId)
      .set('interval', interval);
    return this.http.get<DateRange>(`${this.base}/candles/range`, { params });
  }

  // ── Instrument management endpoints ────────────────────────────────────────
  getInstrumentsStatus(): Observable<InstrumentStatus[]> {
    return this.http.get<InstrumentStatus[]>(`${this.base}/instruments/status`);
  }

  addInstrument(req: AddInstrumentRequest): Observable<InstrumentStatus> {
    return this.http.post<InstrumentStatus>(`${this.base}/instruments`, req);
  }

  uploadCSV(file: File): Observable<{ added: number; symbols: string[] }> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ added: number; symbols: string[] }>(
      `${this.base}/instruments/csv`, form
    );
  }

  triggerBackfill(securityIds: number[]): Observable<BackfillTriggerResult> {
    return this.http.post<BackfillTriggerResult>(
      `${this.base}/instruments/backfill`,
      securityIds.length ? { securityIds } : {}
    );
  }

  deactivateInstrument(securityId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/instruments/${securityId}`);
  }
}
