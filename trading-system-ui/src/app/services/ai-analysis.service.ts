import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import {
  PatternAnalysisRequest,
  PatternAnalysisResponse,
  GeneratedStrategy
} from '../models/ai-analysis.models';

@Injectable({
  providedIn: 'root'
})
export class AIAnalysisService {
  private http = inject(HttpClient);
  private apiUrl = 'http://localhost:8080/api';

  /**
   * Analyze chart patterns using AI
   */
  analyzePatterns(request: PatternAnalysisRequest): Observable<PatternAnalysisResponse> {
    return this.http.post<PatternAnalysisResponse>(
      `${this.apiUrl}/ai/analyze-patterns`,
      request
    );
  }

  /**
   * Generate trading strategy based on detected patterns
   */
  generateStrategy(
    analysisId: string,
    language: 'python' | 'javascript' = 'python'
  ): Observable<GeneratedStrategy> {
    const params = new HttpParams().set('language', language);
    return this.http.get<GeneratedStrategy>(
      `${this.apiUrl}/ai/generate-strategy/${analysisId}`,
      { params }
    );
  }

  /**
   * Get analysis history for a security
   */
  getAnalysisHistory(securityId: number): Observable<PatternAnalysisResponse[]> {
    return this.http.get<PatternAnalysisResponse[]>(
      `${this.apiUrl}/ai/history/${securityId}`
    );
  }

  /**
   * Delete an analysis
   */
  deleteAnalysis(analysisId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/ai/analysis/${analysisId}`);
  }
}
