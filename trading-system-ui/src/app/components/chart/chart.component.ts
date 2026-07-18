import {
  Component, OnInit, OnDestroy, AfterViewInit,
  ElementRef, ViewChild, inject, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  createChart, IChartApi, ISeriesApi,
  ColorType, CrosshairMode,
  CandlestickData, HistogramData, Time, SeriesMarker
} from 'lightweight-charts';
import { ChartApiService } from '../../services/chart-api.service';
import { Candle, Instrument, Interval, INTERVALS } from '../../models/chart.models';
import { DetectedPattern, PatternAnalysisResponse } from '../../models/ai-analysis.models';
import { NavComponent } from '../shared/nav/nav.component';
import { AiAnalysisPanelComponent } from '../ai-analysis-panel/ai-analysis-panel.component';
import { Subject, takeUntil, of, catchError } from 'rxjs';

@Component({
  selector: 'app-chart',
  standalone: true,
  imports: [CommonModule, FormsModule, NavComponent, AiAnalysisPanelComponent],
  templateUrl: './chart.component.html',
  styleUrls: ['./chart.component.scss']
})
export class ChartComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('chartContainer') chartContainer!: ElementRef<HTMLDivElement>;

  private api = inject(ChartApiService);
  private destroy$ = new Subject<void>();

  // ── State ─────────────────────────────────────────────────────────────────
  instruments    = signal<Instrument[]>([]);
  selectedInstrument = signal<Instrument | null>(null);
  selectedInterval   = signal<Interval>('1D');
  fromDate = signal<string>(this.defaultFrom('1D'));
  toDate   = signal<string>(this.today());
  loading  = signal(false);
  error    = signal<string | null>(null);
  candleCount = signal(0);

  hoveredCandle = signal<Candle | null>(null);
  lastCandle    = signal<Candle | null>(null);

  // AI Panel
  showAIPanel = signal(false);
  currentPatterns: DetectedPattern[] = [];

  readonly intervals = INTERVALS;

  // ── Chart internals ───────────────────────────────────────────────────────
  private chart: IChartApi | null = null;
  private candleSeries: ISeriesApi<'Candlestick'> | null = null;
  private volumeSeries: ISeriesApi<'Histogram'>   | null = null;
  private allCandles: Candle[] = [];   // kept for jump navigation

  // Navigation
  zoomLevel = signal(100);
  private readonly ZOOM_IN_FACTOR  = 0.6;  // shrink visible range to 60%
  private readonly ZOOM_OUT_FACTOR = 1.6;  // expand visible range to 160%
  private readonly PAN_STEP        = 0.2;  // pan 20% of visible range per click

  // ── Theme ─────────────────────────────────────────────────────────────────
  private readonly theme = {
    bg:        '#0d1117',
    bgPanel:   '#161b22',
    border:    '#30363d',
    text:      '#e6edf3',
    textMuted: '#8b949e',
    green:     '#3fb950',
    red:       '#f85149',
    blue:      '#58a6ff',
    greenFill: 'rgba(63,185,80,0.15)',
    redFill:   'rgba(248,81,73,0.15)',
    crosshair: '#58a6ff',
  };

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit() {
    this.api.getInstruments()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: instruments => {
          this.instruments.set(instruments);
          if (instruments.length > 0) {
            this.selectedInstrument.set(instruments[0]);
            this.loadCandles();
          }
        },
        error: () => this.error.set('Failed to load instruments. Is the chart-api running?')
      });
  }

  ngAfterViewInit() {
    this.initChart();
    window.addEventListener('resize', this.onResize);
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
    window.removeEventListener('resize', this.onResize);
    this.chart?.remove();
  }

  // ── Chart init ────────────────────────────────────────────────────────────
  private initChart() {
    const el = this.chartContainer.nativeElement;
    const t  = this.theme;

    this.chart = createChart(el, {
      width:  el.clientWidth,
      height: el.clientHeight,
      layout: {
        background: { type: ColorType.Solid, color: t.bg },
        textColor:  t.text,
        fontFamily: "'Inter', 'SF Pro Display', system-ui, sans-serif",
        fontSize:   12,
      },
      grid: {
        vertLines: { color: t.border },
        horzLines: { color: t.border },
      },
      crosshair: {
        mode:     CrosshairMode.Normal,
        vertLine: { color: t.crosshair, labelBackgroundColor: t.crosshair },
        horzLine: { color: t.crosshair, labelBackgroundColor: t.crosshair },
      },
      rightPriceScale: {
        borderColor:  t.border,
        scaleMargins: { top: 0.1, bottom: 0.25 },
      },
      timeScale: {
        borderColor:    t.border,
        timeVisible:    true,
        secondsVisible: false,
        rightOffset:    8,
        barSpacing:     8,
      },
    });

    this.candleSeries = this.chart.addCandlestickSeries({
      upColor:         t.green,
      downColor:       t.red,
      borderUpColor:   t.green,
      borderDownColor: t.red,
      wickUpColor:     t.green,
      wickDownColor:   t.red,
    });

    this.volumeSeries = this.chart.addHistogramSeries({
      priceFormat:  { type: 'volume' },
      priceScaleId: 'volume',
    });
    this.chart.priceScale('volume').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });

    // Crosshair tooltip
    this.chart.subscribeCrosshairMove(param => {
      if (!param.time || !param.seriesData.size) {
        this.hoveredCandle.set(null);
        return;
      }
      const bar = param.seriesData.get(this.candleSeries!) as CandlestickData | undefined;
      const vol = param.seriesData.get(this.volumeSeries!) as HistogramData   | undefined;
      if (bar) {
        this.hoveredCandle.set({
          time:   typeof bar.time === 'number' ? bar.time : 0,
          open:   bar.open, high: bar.high, low: bar.low, close: bar.close,
          volume: vol ? Number(vol.value) : 0,
        });
      }
    });
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  loadCandles() {
    const instrument = this.selectedInstrument();
    if (!instrument) return;

    this.loading.set(true);
    this.error.set(null);

    this.api.getCandles(
      instrument.securityId,
      this.selectedInterval(),
      this.fromDate(),
      this.toDate()
    ).pipe(
      takeUntil(this.destroy$),
      catchError(() => {
        this.error.set('Failed to load candle data.');
        this.loading.set(false);
        return of([]);
      })
    ).subscribe(candles => {
      this.loading.set(false);
      this.candleCount.set(candles.length);

      if (candles.length === 0) {
        this.error.set('No data found for this selection.');
        return;
      }

      this.allCandles = candles;
      this.lastCandle.set(candles[candles.length - 1]);
      this.zoomLevel.set(100);

      const candleData: CandlestickData[] = candles.map(c => ({
        time: c.time as Time,
        open: c.open, high: c.high, low: c.low, close: c.close,
      }));

      const volumeData: HistogramData[] = candles.map(c => ({
        time:  c.time as Time,
        value: c.volume,
        color: c.close >= c.open ? this.theme.greenFill : this.theme.redFill,
      }));

      this.candleSeries?.setData(candleData);
      this.volumeSeries?.setData(volumeData);
      this.chart?.timeScale().fitContent();
    });
  }

  // ── Navigation ────────────────────────────────────────────────────────────

  /** Zoom in — shrink the visible time range around the centre */
  zoomIn() {
    const ts = this.chart?.timeScale();
    if (!ts) return;
    const range = ts.getVisibleLogicalRange();
    if (!range) return;
    const centre = (range.from + range.to) / 2;
    const half   = (range.to - range.from) / 2 * this.ZOOM_IN_FACTOR;
    ts.setVisibleLogicalRange({ from: centre - half, to: centre + half });
    this.zoomLevel.update(z => Math.min(z + 20, 500));
  }

  /** Zoom out — expand the visible time range around the centre */
  zoomOut() {
    const ts = this.chart?.timeScale();
    if (!ts) return;
    const range = ts.getVisibleLogicalRange();
    if (!range) return;
    const centre = (range.from + range.to) / 2;
    const half   = (range.to - range.from) / 2 * this.ZOOM_OUT_FACTOR;
    ts.setVisibleLogicalRange({ from: centre - half, to: centre + half });
    this.zoomLevel.update(z => Math.max(z - 20, 10));
  }

  /** Reset — show all loaded candles */
  resetZoom() {
    this.chart?.timeScale().fitContent();
    this.zoomLevel.set(100);
  }

  /** Scroll to the most recent (last) candle */
  goToLast() {
    const ts = this.chart?.timeScale();
    if (!ts || this.allCandles.length === 0) return;
    const range = ts.getVisibleLogicalRange();
    const visibleBars = range ? range.to - range.from : 50;
    const last = this.allCandles.length - 1;
    ts.setVisibleLogicalRange({ from: last - visibleBars, to: last + 5 });
  }

  /** Scroll to the first (oldest) candle */
  goToFirst() {
    const ts = this.chart?.timeScale();
    if (!ts || this.allCandles.length === 0) return;
    const range = ts.getVisibleLogicalRange();
    const visibleBars = range ? range.to - range.from : 50;
    ts.setVisibleLogicalRange({ from: -5, to: visibleBars });
  }

  /** Pan left — shift visible range backward by PAN_STEP */
  panLeft() {
    const ts = this.chart?.timeScale();
    if (!ts) return;
    const range = ts.getVisibleLogicalRange();
    if (!range) return;
    const step = (range.to - range.from) * this.PAN_STEP;
    ts.setVisibleLogicalRange({ from: range.from - step, to: range.to - step });
  }

  /** Pan right — shift visible range forward by PAN_STEP */
  panRight() {
    const ts = this.chart?.timeScale();
    if (!ts) return;
    const range = ts.getVisibleLogicalRange();
    if (!range) return;
    const step = (range.to - range.from) * this.PAN_STEP;
    ts.setVisibleLogicalRange({ from: range.from + step, to: range.to + step });
  }

  /** Jump back N candles from current view */
  jumpBack(candles: number) {
    const ts = this.chart?.timeScale();
    if (!ts) return;
    const range = ts.getVisibleLogicalRange();
    if (!range) return;
    ts.setVisibleLogicalRange({ from: range.from - candles, to: range.to - candles });
  }

  /** Jump forward N candles from current view */
  jumpForward(candles: number) {
    const ts = this.chart?.timeScale();
    if (!ts) return;
    const range = ts.getVisibleLogicalRange();
    if (!range) return;
    ts.setVisibleLogicalRange({ from: range.from + candles, to: range.to + candles });
  }

  // ── Toolbar controls ──────────────────────────────────────────────────────
  onInstrumentChange(event: Event) {
    const id    = Number((event.target as HTMLSelectElement).value);
    const found = this.instruments().find(i => i.securityId === id);
    if (found) { this.selectedInstrument.set(found); this.loadCandles(); }
  }

  onIntervalChange(interval: Interval) {
    this.selectedInterval.set(interval);
    this.fromDate.set(this.defaultFrom(interval));
    this.loadCandles();
  }

  onDateChange() { this.loadCandles(); }

  private onResize = () => {
    const el = this.chartContainer?.nativeElement;
    if (el && this.chart) {
      this.chart.applyOptions({ width: el.clientWidth, height: el.clientHeight });
    }
  };

  // ── Helpers ───────────────────────────────────────────────────────────────
  today(): string { return new Date().toISOString().split('T')[0]; }

  defaultFrom(interval: Interval): string {
    const d = new Date();
    switch (interval) {
      case '1m':  d.setDate(d.getDate() - 5);         break;
      case '5m':  d.setDate(d.getDate() - 30);        break;
      case '15m': d.setDate(d.getDate() - 90);        break;
      case '60m': d.setFullYear(d.getFullYear() - 1); break;
      default:    d.setFullYear(d.getFullYear() - 3); break;
    }
    return d.toISOString().split('T')[0];
  }

  formatPrice(v: number): string { return v.toFixed(2); }

  formatVolume(v: number): string {
    if (v >= 1_000_000) return (v / 1_000_000).toFixed(1) + 'M';
    if (v >= 1_000)     return (v / 1_000).toFixed(1) + 'K';
    return v.toString();
  }

  get displayCandle(): Candle | null { return this.hoveredCandle() ?? this.lastCandle(); }

  get changePercent(): string {
    const c = this.displayCandle;
    if (!c) return '';
    const pct = ((c.close - c.open) / c.open) * 100;
    return (pct >= 0 ? '+' : '') + pct.toFixed(2) + '%';
  }

  get isPositive(): boolean {
    const c = this.displayCandle;
    return c ? c.close >= c.open : true;
  }

  // ── AI Panel Integration ──────────────────────────────────────────────────

  toggleAIPanel() {
    this.showAIPanel.update(show => !show);
    if (!this.showAIPanel()) {
      this.clearPatterns();
    }
  }

  onPatternsDetected(patterns: DetectedPattern[]) {
    this.currentPatterns = patterns;
    this.drawPatterns(patterns);
  }

  onAnalysisComplete(response: PatternAnalysisResponse) {
    console.log('Analysis complete:', response);
    // Additional handling if needed (e.g., show notification)
  }

  // ── Pattern Drawing ───────────────────────────────────────────────────────
  
  /**
   * Draw detected patterns on the chart as markers and shapes
   */
  drawPatterns(patterns: DetectedPattern[]) {
    if (!this.candleSeries || patterns.length === 0) return;

    const markers: SeriesMarker<Time>[] = [];

    patterns.forEach(pattern => {
      // Add start marker
      markers.push({
        time: pattern.startTime as Time,
        position: pattern.direction === 'bullish' ? 'belowBar' : 'aboveBar',
        color: pattern.direction === 'bullish' ? this.theme.green : this.theme.red,
        shape: pattern.direction === 'bullish' ? 'arrowUp' : 'arrowDown',
        text: `${pattern.patternType} (${pattern.confidence}%)`,
        size: 1.5
      });

      // Add end marker if pattern completed
      if (pattern.endTime > pattern.startTime) {
        markers.push({
          time: pattern.endTime as Time,
          position: pattern.direction === 'bullish' ? 'belowBar' : 'aboveBar',
          color: pattern.direction === 'bullish' ? this.theme.green : this.theme.red,
          shape: 'circle',
          size: 0.8
        });
      }

      // Add key point markers
      pattern.keyPoints.forEach(point => {
        markers.push({
          time: point.time as Time,
          position: 'inBar',
          color: this.theme.blue,
          shape: 'circle',
          text: point.label,
          size: 0.5
        });
      });
    });

    // Sort markers by time
    markers.sort((a, b) => (a.time as number) - (b.time as number));

    this.candleSeries.setMarkers(markers);
  }

  /**
   * Clear all pattern markers from the chart
   */
  clearPatterns() {
    if (!this.candleSeries) return;
    this.candleSeries.setMarkers([]);
  }

  /**
   * Highlight a specific pattern on the chart
   */
  highlightPattern(pattern: DetectedPattern) {
    if (!this.chart || !this.candleSeries) return;

    // Zoom to pattern time range with some padding
    const ts = this.chart.timeScale();
    const startIndex = pattern.startIndex;
    const endIndex = pattern.endIndex;
    const padding = Math.max(10, (endIndex - startIndex) * 0.2);
    
    ts.setVisibleLogicalRange({
      from: startIndex - padding,
      to: endIndex + padding
    });

    // Draw marker for this pattern
    const marker: SeriesMarker<Time> = {
      time: pattern.startTime as Time,
      position: pattern.direction === 'bullish' ? 'belowBar' : 'aboveBar',
      color: this.theme.blue,
      shape: pattern.direction === 'bullish' ? 'arrowUp' : 'arrowDown',
      text: `${pattern.patternType} - ${pattern.confidence}%`,
      size: 2
    };

    this.candleSeries.setMarkers([marker]);
  }
}
