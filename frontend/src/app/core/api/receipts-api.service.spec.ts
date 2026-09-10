import { provideHttpClient } from '@angular/common/http';
import {
    HttpTestingController,
    provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ReceiptView } from '../models/receipt';
import { ReceiptsApiService } from './receipts-api.service';

describe('ReceiptsApiService', () => {
    let service: ReceiptsApiService;
    let http: HttpTestingController;

    const receipt: ReceiptView = {
        id: 'receipt/17',
        originalFilename: 'receipt.pdf',
        objectUri: 'file:///receipt.pdf',
        status: 'REVIEW_REQUIRED',
        merchant: 'Market',
        purchaseDate: '2026-09-09',
        total: 42,
        createdAt: '2026-09-09T12:00:00Z',
        version: 3,
        failureCode: null,
        failureMessage: null,
        confirmedAt: null,
        items: []
    };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                ReceiptsApiService,
                provideHttpClient(),
                provideHttpClientTesting()
            ]
        });
        service = TestBed.inject(ReceiptsApiService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('retries extraction against an exact persisted version', () => {
        service.retry(receipt.id, {
            expectedVersion: 3
        }).subscribe();

        const request = http.expectOne('/api/receipts/receipt%2F17/retry');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            expectedVersion: 3
        });
        request.flush(receipt);
    });

    it('persists a manual line against the expected receipt version', () => {
        const body = {
            expectedVersion: 3,
            rawName: 'Paneer',
            quantity: 2,
            unit: 'each',
            unitPrice: 4.5
        };
        service.addManualLine(receipt.id, body).subscribe();

        const request = http.expectOne('/api/receipts/receipt%2F17/lines');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual(body);
        request.flush(receipt);
    });

    it('writes the complete persisted review with explicit expiry provenance', () => {
        const body = {
            expectedVersion: 3,
            purchaseDate: '2026-09-09',
            lines: [{
                id: 'line-1',
                selected: true,
                ingredientId: 'ingredient-1',
                quantity: 2,
                unit: 'each',
                expiresAt: null,
                expiryProvenance: 'REVIEWED_SHELF_LIFE_RULE' as const
            }]
        };
        service.review(receipt.id, body).subscribe();

        const request = http.expectOne('/api/receipts/receipt%2F17/review');
        expect(request.request.method).toBe('PUT');
        expect(request.request.body).toEqual(body);
        request.flush(receipt);
    });

    it('confirms with receipt identity, version and idempotency key only', () => {
        service.confirm(receipt.id, {
            expectedVersion: 3,
            idempotencyKey: 'receipt-attempt-1'
        }).subscribe();

        const request = http.expectOne('/api/receipts/receipt%2F17/confirm');
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            expectedVersion: 3,
            idempotencyKey: 'receipt-attempt-1'
        });
        expect(Object.keys(request.request.body)).toEqual([
            'expectedVersion',
            'idempotencyKey'
        ]);
        request.flush(receipt);
    });
});
