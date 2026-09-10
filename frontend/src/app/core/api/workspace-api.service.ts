import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface WorkspaceContext {
    kitchenId: string;
    kitchenName: string;
    locationId: string;
    locationName: string;
    timeZone: string;
    currency: string;
}

@Injectable({ providedIn: 'root' })
export class WorkspaceApiService {
    private readonly http = inject(HttpClient);

    current() {
        return this.http.get<WorkspaceContext>('/api/workspace');
    }
}
