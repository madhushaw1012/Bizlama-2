export type ReceiptStatus =
    | 'UPLOADED'
    | 'EXTRACTING'
    | 'REVIEW_REQUIRED'
    | 'FAILED'
    | 'CONFIRMED';

export type ReceiptReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type ExpiryProvenance =
    | 'UNRESOLVED'
    | 'PRINTED_DATE'
    | 'REVIEWED_SHELF_LIFE_RULE'
    | 'OWNER_CONFIRMED'
    | 'LEGACY_RECORDED';

export interface ReceiptLine {
    id: string;
    rawName: string;
    ingredientId: string | null;
    canonicalName: string | null;
    quantity: number;
    unit: string;
    sourceQuantity: number;
    sourceUnit: string;
    unitPrice: number | null;
    confidence: number;
    selected: boolean;
    expiresAt: string | null;
    expiryProvenance: ExpiryProvenance;
    reviewStatus: ReceiptReviewStatus;
}

export interface ReceiptView {
    id: string;
    originalFilename: string;
    objectUri: string;
    status: ReceiptStatus;
    merchant: string | null;
    purchaseDate: string | null;
    total: number | null;
    createdAt: string;
    version: number;
    failureCode: string | null;
    failureMessage: string | null;
    confirmedAt: string | null;
    items: ReceiptLine[];
}

export interface RetryReceiptRequest {
    expectedVersion: number;
}

export interface ManualReceiptLineRequest {
    expectedVersion: number;
    rawName: string;
    quantity: number;
    unit: string;
    unitPrice: number | null;
}

export interface ReviewReceiptLineRequest {
    id: string;
    selected: boolean;
    ingredientId: string | null;
    quantity: number;
    unit: string;
    expiresAt: string | null;
    expiryProvenance: ExpiryProvenance;
}

export interface ReviewReceiptRequest {
    expectedVersion: number;
    purchaseDate: string;
    lines: ReviewReceiptLineRequest[];
}

export interface ConfirmReceiptRequest {
    expectedVersion: number;
    idempotencyKey: string;
}
