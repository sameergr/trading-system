/**
 * AI Chart Pattern Analysis Models
 */

export interface PatternAnalysisRequest {
  securityId: number;
  interval: string;
  startDate: string;
  endDate: string;
  capitalLimit: number;
}

export interface DetectedPattern {
  patternType: string;        // e.g., "Head and Shoulders", "Double Top", "Triangle"
  patternId: string;
  confidence: number;          // 0-100
  startTime: number;           // Unix timestamp
  endTime: number;
  startIndex: number;
  endIndex: number;
  direction: 'bullish' | 'bearish' | 'neutral';
  keyPoints: PatternPoint[];   // Critical points in the pattern
  description: string;
}

export interface PatternPoint {
  time: number;                // Unix timestamp
  price: number;
  label: string;               // e.g., "Left Shoulder", "Head", "Neckline"
}

export interface BacktestMetrics {
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  totalReturn: number;         // Percentage
  totalReturnAmount: number;   // Absolute value
  maxDrawdown: number;         // Percentage
  maxDrawdownAmount: number;   // Absolute value
  averageReturn: number;       // Per trade
  winRate: number;             // Percentage
  profitFactor: number;        // Total profit / Total loss
  maxConsecutiveWins: number;
  maxConsecutiveLosses: number;
  averageWinStreak: number;
  averageLossStreak: number;
  sharpeRatio?: number;
}

export interface Trade {
  tradeId: string;
  patternType: string;
  entryTime: number;
  entryPrice: number;
  exitTime: number;
  exitPrice: number;
  quantity: number;
  direction: 'long' | 'short';
  returnPercentage: number;
  returnAmount: number;
  reason: string;              // Entry/exit reason
}

export interface AISuggestion {
  type: 'insight' | 'warning' | 'recommendation';
  title: string;
  message: string;
  priority: 'high' | 'medium' | 'low';
}

export interface PatternAnalysisResponse {
  analysisId: string;
  securityId: number;
  securityName: string;
  interval: string;
  analysisDate: string;
  patterns: DetectedPattern[];
  metrics: BacktestMetrics;
  trades: Trade[];
  suggestions: AISuggestion[];
  topPatterns: PatternSummary[];  // Most successful patterns
  strategyRecommendation: string;
}

export interface PatternSummary {
  patternType: string;
  occurrences: number;
  successRate: number;         // Percentage
  averageReturn: number;       // Percentage
  totalReturn: number;
  confidence: number;
}

export interface GeneratedStrategy {
  strategyName: string;
  strategyCode: string;        // Generated code
  language: 'python' | 'javascript';
  parameters: StrategyParameter[];
  description: string;
  expectedMetrics: BacktestMetrics;
}

export interface StrategyParameter {
  name: string;
  value: string | number;
  type: 'number' | 'string' | 'boolean';
  description: string;
}
