import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-nav',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="nav">
      <div class="nav-brand">
        <span class="nav-mark">▲</span>
        <span class="nav-name">DhanCharts</span>
      </div>
      <div class="nav-links">
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{exact:true}" class="nav-link">
          📈 Charts
        </a>
        <a routerLink="/instruments" routerLinkActive="active" class="nav-link">
          🗂 Instruments
        </a>
      </div>
    </nav>
  `,
  styles: [`
    .nav {
      display: flex;
      align-items: center;
      gap: 24px;
      padding: 0 20px;
      height: 48px;
      background: #161b22;
      border-bottom: 1px solid #30363d;
      flex-shrink: 0;
    }
    .nav-brand { display:flex; align-items:center; gap:8px; }
    .nav-mark  { color:#58a6ff; font-size:15px; }
    .nav-name  { font-size:14px; font-weight:600; color:#e6edf3; letter-spacing:-0.3px; }
    .nav-links { display:flex; gap:4px; margin-left:8px; }
    .nav-link  {
      padding: 5px 12px;
      border-radius: 6px;
      color: #8b949e;
      text-decoration: none;
      font-size: 13px;
      font-weight: 500;
      transition: all 0.15s;
    }
    .nav-link:hover { background:#1f2937; color:#e6edf3; }
    .nav-link.active { background:rgba(88,166,255,0.12); color:#58a6ff; }
  `]
})
export class NavComponent {}
