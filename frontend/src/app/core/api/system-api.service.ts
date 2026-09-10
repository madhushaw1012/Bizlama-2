import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

export interface WorkspaceStatus {
    environment: 'local' | 'cloud';
    connections: {
        name: string;
        provider: string;
        connected: boolean;
        detail: string;
    }[];
}

@Injectable({ providedIn: 'root' })
export class SystemApiService {
    private readonly http = inject(HttpClient);

    status() {
        return this.http.get<WorkspaceStatus>('/api/system/status');
    }
}