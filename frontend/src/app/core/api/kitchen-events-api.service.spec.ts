import { provideHttpClient } from '@angular/common/http';
import {
    HttpTestingController,
    provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { KitchenEventsApiService } from './kitchen-events-api.service';

describe('KitchenEventsApiService', () => {
    let service: KitchenEventsApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                KitchenEventsApiService,
                provideHttpClient(),
                provideHttpClientTesting()
            ]
        });
        service = TestBed.inject(KitchenEventsApiService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('confirms with proposal identity, version and idempotency key only', () => {
        service.confirm({
            proposalId: 'proposal-17',
            expectedVersion: 4,
            idempotencyKey: 'event-attempt-1'
        }).subscribe();

        const request = http.expectOne('/api/events/confirm');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            proposalId: 'proposal-17',
            expectedVersion: 4,
            idempotencyKey: 'event-attempt-1'
        });
        expect(Object.keys(request.request.body)).toEqual([
            'proposalId',
            'expectedVersion',
            'idempotencyKey'
        ]);
        request.flush(null);
    });

    it('supersedes the persisted proposal version when discarded', () => {
        service.supersede('proposal-17', 4).subscribe();

        const request = http.expectOne('/api/events/proposal-17/supersede');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({ expectedVersion: 4 });
        request.flush(null);
    });
});
