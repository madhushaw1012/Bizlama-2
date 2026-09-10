import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import {
    ConfirmKitchenEventRequest,
    ParseKitchenEventResponse
} from '../models/kitchen-event';

@Injectable({ providedIn: 'root' })
export class KitchenEventsApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = '/api/events';

    parse(statement: string) {
        return this.http.post<ParseKitchenEventResponse>(
            `${this.baseUrl}/parse`,
            { statement }
        );
    }

    confirm(request: ConfirmKitchenEventRequest) {
        return this.http.post<void>(
            `${this.baseUrl}/confirm`,
            request
        );
    }

    supersede(proposalId: string, expectedVersion: number) {
        return this.http.post<void>(
            `${this.baseUrl}/${proposalId}/supersede`,
            { expectedVersion }
        );
    }
}