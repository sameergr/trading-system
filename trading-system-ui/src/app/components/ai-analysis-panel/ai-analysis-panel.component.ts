import { Component, Input, Output, EventEmitter, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AIAnalysisService } from '../../services/ai-analysis.service';
import {
  PatternAnalysisRequest,
  PatternAnalysisResponse,
  DetectedPattern
} from '../../models/ai-analysis.models';

@Component({
  selector: 'ai-analysis-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './ai-analysis-panel.component.html',
  styleUrl: './ai-analysis-panel.component.scss'
})
export class AiAnalysisPanelComponent {
  @Input() securityId!: number;
  @Input() securityName!: string;
  @Output() patternsDetected = new EventEmitter<DetectedPattern[]>();
  @Output() analysisComplete = new EventEmitter<PatternAnalysisResponse>();

  private aiService = inject(AIAnalysisService);

  // Form inputs
  interval = '1d';
  durationDays = 90;
  capitalLimit = 100000;
  startDate = '';
  endDate = '';

  // State
  isAnalyzing = false;
  analysisResult: PatternAnalysisResponse | null = null;
  errorMessage = '';

  intervalOptions = [
    { value: '1m', label: '1 Minute' },
    { value: '5m', label: '5 Minutes' },
    { value: '15m', label: '15 Minutes' },
    { value: '1h', label: '1 Hour' },
    { value: '1d', label: '1 Day' },
    { value: '1W', label: '1 Week' },
    { value: '1M', label: '1 Month' }
  ];

  ngOnInit() {
    this.setDefaultDates();
  }

  setDefaultDates() {
    const end = new Date();
    const start = new Date();
    start.setDate(start.getDate() - this.durationDays);
    
    this.endDate = end.toISOString().split('T')[0];
    this.startDate = start.toISOString().split('T')[0];
  }

  onDurationChange() {
    this.setDefaultDates();
  }

  analyzePatterns() {
    if (!this.securityId) {
      this.errorMessage = 'No security selected';
      return;
    }

    this.isAnalyzing = true;
    this.errorMessage = '';
    this.analysisResult = null;

    const request: PatternAnalysisRequest = {
      securityId: this.securityId,
      interval: this.interval,
      startDate: this.startDate,
      endDate: this.endDate,
      capitalLimit: this.capitalLimit
    };

    this.aiService.analyzePatterns(request).subscribe({
      next: (response) => {
        this.analysisResult = response;
        this.patternsDetected.emit(response.patterns);
        this.analysisComplete.emit(response);
        this.isAnalyzing = false;
      },
      error: (err) => {
        this.errorMessage = err.error?.message || 'Analysis failed. Please try again.';
        this.isAnalyzing = false;
      }
    });
  }

  generateStrategy() {
    if (!this.analysisResult) return;

    this.aiService.generateStrategy(this.analysisResult.analysisId).subscribe({
      next: (strategy) => {
        this.downloadStrategy(strategy.strategyCode, strategy.strategyName);
      },
      error: (err) => {
        this.errorMessage = 'Strategy generation failed: ' + (err.error?.message || 'Unknown error');
      }
    });
  }

  downloadStrategy(code: string, name: string) {
    const blob = new Blob([code], { type: 'text/plain' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${name}.py`;
    a.click();
    window.URL.revokeObjectURL(url);
  }

  getSuggestionIcon(type: string): string {
    switch (type) {
      case 'insight': return '💡';
      case 'warning': return '⚠️';
      case 'recommendation': return '✨';
      default: return 'ℹ️';
    }
  }

  formatPercent(value: number): string {
    return value >= 0 ? `+${value.toFixed(2)}%` : `${value.toFixed(2)}%`;
  }

  formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 0
    }).format(value);
  }
}
