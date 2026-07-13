import { Routes } from '@angular/router';
import { ChartComponent } from './components/chart/chart.component';
import { InstrumentsComponent } from './components/instruments/instruments.component';

export const routes: Routes = [
  { path: '',            component: ChartComponent      },
  { path: 'instruments', component: InstrumentsComponent },
  { path: '**',          redirectTo: ''                  }
];
