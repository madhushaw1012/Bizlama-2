export type KitchenEventType =
    'PURCHASE' |
    'PRODUCTION' |
    'WASTE';

export interface ParsedKitchenEvent {
    type: KitchenEventType;
    item: string;
    itemId: string;
    quantity: number;
    unit: string;
    confidence: number;
    summary: string;
    decisionReason: string;
}

export interface ParseKitchenEventResponse {
    proposalId: string;
    version: number;
    riskTier: 'LOW' | 'MEDIUM' | 'HIGH';
    expiresAt: string;
    events: ParsedKitchenEvent[];
    requiresConfirmation: boolean;
    autoApplied: false;
}

export interface ConfirmKitchenEventRequest {
    proposalId: string;
    expectedVersion: number;
    idempotencyKey: string;
}
