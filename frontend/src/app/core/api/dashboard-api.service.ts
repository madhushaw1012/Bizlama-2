import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DashboardResponse } from '../models/dashboard';

@Injectable({ providedIn: 'root' })
export class DashboardApiService {
    private readonly http = inject(HttpClient);

    getDashboard() {
        return this.http.get<DashboardResponse>('/api/dashboard');
    }
}