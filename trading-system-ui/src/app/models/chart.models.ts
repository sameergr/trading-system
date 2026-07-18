export interface Instrument {
  securityId: number;
  symbol: string;
  exchangeSegment: string;
}

export interface Candle {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface DateRange {
  from: string;
  to: string;
}

export type Interval = '1m' | '5m' | '15m' | '60m' | '1D' | '1W' | '1M';

export const INTERVALS: { value: Interval; label: string }[] = [
  { value: '1m',  label: '1 Min'   },
  { value: '5m',  label: '5 Min'   },
  { value: '15m', label: '15 Min'  },
  { value: '60m', label: '1 Hour'  },
  { value: '1D',  label: '1 Day'   },
  { value: '1W',  label: '1 Week'  },
  { value: '1M',  label: '1 Month' },
];

// ── Instruments management models ─────────────────────────────────────────

export interface IntervalStatus {
  interval: string;
  candleCount: number;
  firstCandleDate: string | null;
  lastCandleDate: string | null;
  doneChunks: number;
  failedChunks: number;
  pendingChunks: number;
  status: 'COMPLETE' | 'PARTIAL' | 'PENDING' | 'FAILED' | 'IN_PROGRESS';
}

export interface InstrumentStatus {
  securityId: number;
  symbol: string;
  exchangeSegment: string;
  instrumentType: string;
  active: boolean;
  intervals: IntervalStatus[];
  totalCandles: number;
  overallStatus: 'COMPLETE' | 'PARTIAL' | 'PENDING' | 'FAILED';
}

export interface AddInstrumentRequest {
  securityId: number;
  symbol: string;
  exchangeSegment: string;
  instrumentType: string;
}

export interface BackfillTriggerResult {
  status: 'QUEUED' | 'ERROR' | 'SKIPPED';
  message: string;
  symbols: string[];
}

export const EXCHANGE_SEGMENTS = [
  { value: 'NSE_EQ',  label: 'NSE Equity'       },
  { value: 'BSE_EQ',  label: 'BSE Equity'        },
  { value: 'NSE_FNO', label: 'NSE F&O'           },
  { value: 'NSE_CURRENCY', label: 'NSE Currency' },
  { value: 'MCX_COMM', label: 'MCX Commodity'    },
];

export const INSTRUMENT_TYPES = [
  { value: 'EQUITY',  label: 'Equity'       },
  { value: 'FUTIDX',  label: 'Futures Index' },
  { value: 'OPTIDX',  label: 'Options Index' },
  { value: 'FUTSTK',  label: 'Futures Stock' },
  { value: 'OPTSTK',  label: 'Options Stock' },
];
