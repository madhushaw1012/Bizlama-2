import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ReceiptsApiService } from '../../core/api/receipts-api.service';
import { StockApiService } from '../../core/api/stock-api.service';
import { ReceiptView } from '../../core/models/receipt';
import { ReceiptsComponent } from './receipts.component';

describe('ReceiptsComponent confirmation attempts', () => {
    it('reuses the same idempotency key when confirmation is retried', () => {
        const receipt: ReceiptView = {
            id: 'receipt-1',
            originalFilename: 'receipt.pdf',
            objectUri: 'file:///receipt.pdf',
            status: 'REVIEW_REQUIRED',
            merchant: 'Market',
            purchaseDate: '2026-09-09',
            total: 12,
            createdAt: '2026-09-09T12:00:00Z',
            version: 5,
            failureCode: null,
            failureMessage: null,
            confirmedAt: null,
            items: [{
                id: 'line-1',
                rawName: 'Paneer',
                ingredientId: 'ingredient-1',
                canonicalName: 'Paneer',
                quantity: 2,
                unit: 'each',
                sourceQuantity: 2,
                sourceUnit: 'each',
                unitPrice: 6,
                confidence: 0.95,
                selected: true,
                expiresAt: '2026-09-12',
                expiryProvenance: 'PRINTED_DATE',
                reviewStatus: 'APPROVED'
            }]
        };
        const receiptsApi = jasmine.createSpyObj<ReceiptsApiService>(
            'ReceiptsApiService',
            ['list', 'get', 'upload', 'retry', 'addManualLine', 'review', 'confirm']
        );
        receiptsApi.confirm.and.returnValues(
            throwError(() => new HttpErrorResponse({
                status: 503,
                error: { detail: 'temporary failure' }
            })),
            of({
                ...receipt,
                status: 'CONFIRMED',
                version: 6,
                confirmedAt: '2026-09-09T12:30:00Z'
            })
        );
        const stockApi = jasmine.createSpyObj<StockApiService>(
            'StockApiService',
            ['ingredients']
        );
        TestBed.configureTestingModule({
            providers: [
                { provide: ReceiptsApiService, useValue: receiptsApi },
                { provide: StockApiService, useValue: stockApi }
            ]
        });
        const component = TestBed.runInInjectionContext(
            () => new ReceiptsComponent()
        ) as any;
        component.selected.set(receipt);

        component.confirm();
        component.confirm();

        expect(receiptsApi.confirm).toHaveBeenCalledTimes(2);
        expect(receiptsApi.confirm.calls.argsFor(0)[0]).toBe('receipt-1');
        expect(receiptsApi.confirm.calls.argsFor(0)[1].idempotencyKey)
            .toBe(receiptsApi.confirm.calls.argsFor(1)[1].idempotencyKey);
        expect(receiptsApi.confirm.calls.argsFor(0)[1]).toEqual({
            expectedVersion: 5,
            idempotencyKey: receiptsApi.confirm.calls.argsFor(1)[1].idempotencyKey
        });
    });

    it('retries only the current failed persisted version', () => {
        const failed: ReceiptView = {
            id: 'receipt-2',
            originalFilename: 'receipt.pdf',
            objectUri: 'private://receipt-2',
            status: 'REVIEW_REQUIRED',
            merchant: null,
            purchaseDate: null,
            total: null,
            createdAt: '2026-09-09T12:00:00Z',
            version: 3,
            failureCode: 'EXTRACTION_FAILED',
            failureMessage: 'Private evidence retained.',
            confirmedAt: null,
            items: []
        };
        const succeeded: ReceiptView = {
            ...failed,
            version: 5,
            failureCode: null,
            failureMessage: null,
            items: [{
                id: 'line-1',
                rawName: 'Rice',
                ingredientId: null,
                canonicalName: null,
                quantity: 1,
                unit: 'g',
                sourceQuantity: 1,
                sourceUnit: 'g',
                unitPrice: null,
                confidence: 0,
                selected: true,
                expiresAt: null,
                expiryProvenance: 'UNRESOLVED',
                reviewStatus: 'PENDING'
            }]
        };
        const receiptsApi = jasmine.createSpyObj<ReceiptsApiService>(
            'ReceiptsApiService',
            ['retry']
        );
        receiptsApi.retry.and.returnValue(of(succeeded));
        TestBed.configureTestingModule({
            providers: [
                { provide: ReceiptsApiService, useValue: receiptsApi },
                { provide: StockApiService, useValue: jasmine.createSpyObj(
                    'StockApiService',
                    ['ingredients']
                ) }
            ]
        });
        const component = TestBed.runInInjectionContext(
            () => new ReceiptsComponent()
        ) as any;
        component.selected.set(failed);

        component.retryExtraction();

        expect(receiptsApi.retry).toHaveBeenCalledOnceWith(
            'receipt-2',
            { expectedVersion: 3 }
        );
        expect(component.selected()).toEqual(succeeded);
    });
});
