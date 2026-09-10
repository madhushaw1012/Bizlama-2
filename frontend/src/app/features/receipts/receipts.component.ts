import { HttpErrorResponse } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Ingredient, StockApiService } from '../../core/api/stock-api.service';
import { ReceiptsApiService } from '../../core/api/receipts-api.service';
import {
    ExpiryProvenance,
    ReceiptLine,
    ReceiptView,
    ReviewReceiptRequest
} from '../../core/models/receipt';

type ReceiptOperation =
    | 'idle'
    | 'uploading'
    | 'retrying'
    | 'saving-line'
    | 'saving-review'
    | 'confirming'
    | 'reloading';

interface ManualLineDraft {
    rawName: string;
    quantity: number;
    unit: string;
    unitPrice: number | null;
}

@Component({
    selector: 'app-receipts',
    imports: [CommonModule, FormsModule],
    templateUrl: './receipts.component.html'
})
export class ReceiptsComponent implements OnInit {
    private readonly api = inject(ReceiptsApiService);
    private readonly stockApi = inject(StockApiService);
    private readonly confirmationAttempts = new Map<
        string,
        { version: number; key: string }
    >();

    protected readonly receipts = signal<ReceiptView[]>([]);
    protected readonly ingredients = signal<Ingredient[]>([]);
    protected readonly selected = signal<ReceiptView | null>(null);
    protected readonly operation = signal<ReceiptOperation>('idle');
    protected readonly loading = computed(() => this.operation() !== 'idle');
    protected readonly uploading = computed(() => this.operation() === 'uploading');
    protected readonly dirty = signal(false);
    protected readonly manualLine = signal<ManualLineDraft | null>(null);
    protected readonly notice = signal<string | null>(null);
    protected readonly error = signal<string | null>(null);
    protected readonly conflict = signal(false);
    protected readonly units = ['mg', 'g', 'kg', 'ml', 'l', 'each'];

    ngOnInit(): void {
        this.reload();
        this.stockApi.ingredients().subscribe({
            next: (values) => this.ingredients.set(values),
            error: () => this.error.set(
                'Ingredient choices could not be loaded. Refresh before reviewing.'
            )
        });
    }

    protected chooseFile(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';

        if (!file || this.loading()) {
            return;
        }

        this.begin('uploading');
        this.notice.set('Uploading securely and preparing the persisted review...');
        this.api.upload(file).subscribe({
            next: (receipt) => {
                this.accept(receipt);
                this.operation.set('idle');
                this.notice.set(
                    receipt.failureCode
                        ? 'Automatic extraction needs attention. Retry or continue with manual review.'
                        : receipt.items.length
                        ? 'Your receipt is ready to review.'
                        : 'Receipt saved. Add a persisted manual line before review.'
                );
            },
            error: (response: HttpErrorResponse) => {
                this.operation.set('idle');
                this.error.set(this.detail(
                    response,
                    'Could not upload this receipt.'
                ));
            }
        });
    }

    protected open(receipt: ReceiptView): void {
        if (this.loading()) {
            return;
        }
        this.selected.set(structuredClone(receipt));
        this.dirty.set(false);
        this.manualLine.set(null);
        this.notice.set(null);
        this.error.set(null);
        this.conflict.set(false);
    }

    protected refreshSelected(): void {
        const receipt = this.selected();
        if (!receipt || this.loading()) {
            return;
        }
        this.fetchLatest(receipt.id, false);
    }

    protected retryExtraction(): void {
        const receipt = this.selected();
        if (!receipt || receipt.status !== 'REVIEW_REQUIRED'
            || !receipt.failureCode || receipt.items.length || this.loading()) {
            return;
        }

        this.begin('retrying');
        this.api.retry(receipt.id, {
            expectedVersion: receipt.version
        }).subscribe({
            next: (value) => {
                this.accept(value);
                this.operation.set('idle');
                this.notice.set(value.failureCode
                    ? 'Automatic extraction still needs attention. You can retry or enter lines manually.'
                    : 'Automatic extraction succeeded. Review the persisted lines.');
            },
            error: (response: HttpErrorResponse) => this.mutationFailed(
                response,
                receipt.id,
                'Could not retry receipt extraction.'
            )
        });
    }

    protected updatePurchaseDate(value: string): void {
        const receipt = this.selected();
        if (!receipt || !this.isReviewable(receipt)) {
            return;
        }
        this.selected.set({ ...receipt, purchaseDate: value || null });
        this.markDirty();
    }

    protected startManualLine(): void {
        const receipt = this.selected();
        if (!receipt || !this.isReviewable(receipt) || this.loading()) {
            return;
        }
        if (this.dirty()) {
            this.error.set('Save or reload the current review before adding a line.');
            return;
        }
        this.error.set(null);
        this.manualLine.set({
            rawName: '',
            quantity: 1,
            unit: 'g',
            unitPrice: null
        });
    }

    protected updateManualLine(
        field: keyof ManualLineDraft,
        value: string | number | null
    ): void {
        this.manualLine.update((line) => line
            ? { ...line, [field]: value } as ManualLineDraft
            : null);
    }

    protected cancelManualLine(): void {
        if (!this.loading()) {
            this.manualLine.set(null);
        }
    }

    protected saveManualLine(): void {
        const receipt = this.selected();
        const line = this.manualLine();
        if (!receipt || !line || !this.isReviewable(receipt) || this.loading()) {
            return;
        }
        const quantity = Number(line.quantity);
        const unitPrice = line.unitPrice === null
            ? null
            : Number(line.unitPrice);
        if (!line.rawName.trim() || !Number.isFinite(quantity) || quantity <= 0
            || !line.unit.trim() || (unitPrice !== null
                && (!Number.isFinite(unitPrice) || unitPrice < 0))) {
            this.error.set('Enter a printed name, positive quantity, supported unit, and valid price.');
            return;
        }

        this.begin('saving-line');
        this.api.addManualLine(receipt.id, {
            expectedVersion: receipt.version,
            rawName: line.rawName.trim(),
            quantity,
            unit: line.unit,
            unitPrice
        }).subscribe({
            next: (value) => {
                this.confirmationAttempts.delete(value.id);
                this.accept(value);
                this.operation.set('idle');
                this.notice.set('Manual line persisted. Review its ingredient and expiry.');
            },
            error: (response: HttpErrorResponse) => this.mutationFailed(
                response,
                receipt.id,
                'Could not persist that receipt line.'
            )
        });
    }

    protected updateLine(
        index: number,
        field: 'selected' | 'quantity' | 'unit' | 'expiresAt',
        value: boolean | number | string | null
    ): void {
        this.editLine(index, { [field]: value } as Partial<ReceiptLine>);
    }

    protected chooseIngredient(index: number, ingredientId: string): void {
        const ingredient = this.ingredients().find(
            (value) => value.id === ingredientId
        );
        this.editLine(index, {
            ingredientId: ingredient?.id ?? null,
            canonicalName: ingredient?.name ?? null,
            ...(ingredient ? { unit: ingredient.baseUnit } : {})
        });
    }

    protected updateExpiryProvenance(
        index: number,
        provenance: ExpiryProvenance
    ): void {
        this.editLine(index, {
            expiryProvenance: provenance,
            ...(provenance === 'REVIEWED_SHELF_LIFE_RULE'
                ? { expiresAt: null }
                : {})
        });
    }

    protected saveReview(): void {
        const receipt = this.selected();
        if (!receipt || !this.isReviewable(receipt) || this.loading()) {
            return;
        }
        const validation = this.reviewValidation(receipt);
        if (validation) {
            this.error.set(validation);
            return;
        }

        const request: ReviewReceiptRequest = {
            expectedVersion: receipt.version,
            purchaseDate: receipt.purchaseDate!,
            lines: receipt.items.map((line) => ({
                id: line.id,
                selected: line.selected,
                ingredientId: line.ingredientId,
                quantity: Number(line.quantity),
                unit: line.unit,
                expiresAt: line.expiryProvenance === 'REVIEWED_SHELF_LIFE_RULE'
                    ? null
                    : line.expiresAt,
                expiryProvenance: line.expiryProvenance
            }))
        };

        this.begin('saving-review');
        this.api.review(receipt.id, request).subscribe({
            next: (value) => {
                this.confirmationAttempts.delete(value.id);
                this.accept(value);
                this.operation.set('idle');
                this.notice.set('Review saved. Confirm to add approved lines to stock.');
            },
            error: (response: HttpErrorResponse) => this.mutationFailed(
                response,
                receipt.id,
                'Could not save this receipt review.'
            )
        });
    }

    protected confirm(): void {
        const receipt = this.selected();
        if (!receipt || !this.canConfirm(receipt) || this.loading()) {
            return;
        }

        const idempotencyKey = this.confirmationKey(receipt);
        this.begin('confirming');
        this.api.confirm(receipt.id, {
            expectedVersion: receipt.version,
            idempotencyKey
        }).subscribe({
            next: (value) => {
                this.accept(value);
                this.operation.set('idle');
                this.notice.set('Receipt confirmed. Approved items were added to inventory.');
            },
            error: (response: HttpErrorResponse) => this.mutationFailed(
                response,
                receipt.id,
                'Could not confirm this receipt. Retry will reuse the same confirmation key.'
            )
        });
    }

    protected isReviewable(receipt: ReceiptView): boolean {
        return receipt.status === 'REVIEW_REQUIRED';
    }

    protected canConfirm(receipt: ReceiptView): boolean {
        return this.isReviewable(receipt)
            && !this.dirty()
            && !!receipt.purchaseDate
            && receipt.items.some((line) =>
                line.selected && line.reviewStatus === 'APPROVED')
            && receipt.items.every((line) => line.reviewStatus !== 'PENDING');
    }

    protected needsReview(receipt: ReceiptView): boolean {
        return this.dirty()
            || receipt.items.some((line) => line.reviewStatus === 'PENDING');
    }

    protected confidence(item: ReceiptLine): string {
        return `${Math.round(item.confidence * 100)}%`;
    }

    protected matchLabel(item: ReceiptLine): string {
        return item.confidence >= 0.9 ? 'Strong match' : 'Check match';
    }

    protected statusLabel(receipt: ReceiptView): string {
        switch (receipt.status) {
            case 'UPLOADED': return 'Uploaded';
            case 'EXTRACTING': return 'Processing';
            case 'REVIEW_REQUIRED': return receipt.failureCode
                ? 'Manual review'
                : 'Review';
            case 'FAILED': return 'Failed';
            case 'CONFIRMED': return 'Added to stock';
        }
    }

    protected provenanceLabel(value: ExpiryProvenance): string {
        switch (value) {
            case 'PRINTED_DATE': return 'Printed date';
            case 'REVIEWED_SHELF_LIFE_RULE': return 'Reviewed shelf-life rule';
            case 'OWNER_CONFIRMED': return 'Owner confirmed';
            case 'LEGACY_RECORDED': return 'Legacy recorded';
            case 'UNRESOLVED': return 'Choose expiry source';
        }
    }

    private editLine(index: number, change: Partial<ReceiptLine>): void {
        const receipt = this.selected();
        if (!receipt || !this.isReviewable(receipt) || this.loading()) {
            return;
        }
        const items = receipt.items.map((item, itemIndex) => itemIndex === index
            ? { ...item, ...change, reviewStatus: 'PENDING' as const }
            : item);
        this.selected.set({ ...receipt, items });
        this.markDirty();
    }

    private markDirty(): void {
        this.dirty.set(true);
        this.notice.set(null);
        this.error.set(null);
        this.conflict.set(false);
    }

    private reviewValidation(receipt: ReceiptView): string | null {
        if (!receipt.purchaseDate) {
            return 'Review and choose the purchase date.';
        }
        if (!receipt.items.length || !receipt.items.some((line) => line.selected)) {
            return 'Select at least one persisted receipt line.';
        }
        for (const line of receipt.items.filter((item) => item.selected)) {
            if (!line.ingredientId) {
                return `Choose an ingredient for “${line.rawName}”.`;
            }
            if (!Number.isFinite(Number(line.quantity)) || Number(line.quantity) <= 0
                || !line.unit.trim()) {
                return `Enter a positive quantity and unit for “${line.rawName}”.`;
            }
            if (line.expiryProvenance !== 'PRINTED_DATE'
                && line.expiryProvenance !== 'OWNER_CONFIRMED'
                && line.expiryProvenance !== 'REVIEWED_SHELF_LIFE_RULE') {
                return `Choose explicit expiry evidence for “${line.rawName}”.`;
            }
            if (line.expiryProvenance !== 'REVIEWED_SHELF_LIFE_RULE'
                && !line.expiresAt) {
                return `Enter the expiry date for “${line.rawName}”.`;
            }
        }
        return null;
    }

    private confirmationKey(receipt: ReceiptView): string {
        const existing = this.confirmationAttempts.get(receipt.id);
        if (existing?.version === receipt.version) {
            return existing.key;
        }
        const attempt = {
            version: receipt.version,
            key: `receipt:${receipt.id}:${crypto.randomUUID()}`
        };
        this.confirmationAttempts.set(receipt.id, attempt);
        return attempt.key;
    }

    private begin(operation: ReceiptOperation): void {
        this.operation.set(operation);
        this.error.set(null);
        this.conflict.set(false);
    }

    private mutationFailed(
        response: HttpErrorResponse,
        receiptId: string,
        fallback: string
    ): void {
        this.operation.set('idle');
        this.error.set(this.detail(response, fallback));
        if (response.status === 409) {
            this.conflict.set(true);
            this.fetchLatest(receiptId, true);
        }
    }

    private fetchLatest(receiptId: string, preserveMessage: boolean): void {
        this.operation.set('reloading');
        this.api.get(receiptId).subscribe({
            next: (value) => {
                this.accept(value);
                this.operation.set('idle');
                if (preserveMessage) {
                    this.error.set('Receipt changed elsewhere. The latest persisted version is shown.');
                    this.conflict.set(true);
                } else {
                    this.notice.set('Latest persisted receipt loaded.');
                }
            },
            error: (response: HttpErrorResponse) => {
                this.operation.set('idle');
                this.error.set(this.detail(response, 'Could not reload this receipt.'));
            }
        });
    }

    private accept(receipt: ReceiptView): void {
        const copy = structuredClone(receipt);
        this.selected.set(copy);
        this.receipts.update((values) => {
            const index = values.findIndex((value) => value.id === receipt.id);
            if (index < 0) {
                return [structuredClone(receipt), ...values];
            }
            return values.map((value, valueIndex) => valueIndex === index
                ? structuredClone(receipt)
                : value);
        });
        this.dirty.set(false);
        this.manualLine.set(null);
    }

    private detail(response: HttpErrorResponse, fallback: string): string {
        return response.error?.detail ?? fallback;
    }

    private reload(): void {
        this.api.list().subscribe({
            next: (values) => {
                this.receipts.set(values);
                if (!this.selected() && values.length) {
                    this.selected.set(structuredClone(values[0]));
                }
            },
            error: (response: HttpErrorResponse) => this.error.set(
                this.detail(response, 'Could not load receipt history.')
            )
        });
    }
}
