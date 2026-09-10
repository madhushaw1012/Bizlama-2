import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import {
    ConfirmReceiptRequest,
    ManualReceiptLineRequest,
    ReceiptView,
    RetryReceiptRequest,
    ReviewReceiptRequest
} from '../models/receipt';

@Injectable({ providedIn: 'root' })
export class ReceiptsApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = '/api/receipts';

    list() {
        return this.http.get<ReceiptView[]>(this.baseUrl);
    }

    get(receiptId: string) {
        return this.http.get<ReceiptView>(
            `${this.baseUrl}/${encodeURIComponent(receiptId)}`
        );
    }

    upload(file: File) {
        const data = new FormData();
        data.append('file', file);
        return this.http.post<ReceiptView>(this.baseUrl, data);
    }

    retry(receiptId: string, request: RetryReceiptRequest) {
        return this.http.post<ReceiptView>(
            `${this.baseUrl}/${encodeURIComponent(receiptId)}/retry`,
            request
        );
    }

    addManualLine(
        receiptId: string,
        request: ManualReceiptLineRequest
    ) {
        return this.http.post<ReceiptView>(
            `${this.baseUrl}/${encodeURIComponent(receiptId)}/lines`,
            request
        );
    }

    review(receiptId: string, request: ReviewReceiptRequest) {
        return this.http.put<ReceiptView>(
            `${this.baseUrl}/${encodeURIComponent(receiptId)}/review`,
            request
        );
    }

    confirm(receiptId: string, request: ConfirmReceiptRequest) {
        return this.http.post<ReceiptView>(
            `${this.baseUrl}/${encodeURIComponent(receiptId)}/confirm`,
            request
        );
    }
}