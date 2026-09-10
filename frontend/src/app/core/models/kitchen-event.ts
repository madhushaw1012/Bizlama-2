export type KitchenEventType =
    'PURCHASE' |
    'PRODUCTION' |
    'WASTE' |
    'ORDER' |
    'FEEDBACK';

export type KitchenEventIntent =
    'INVENTORY_UPDATE' |
    'ORDER_CAPTURE' |
    'FEEDBACK_CAPTURE' |
    'UNKNOWN';


export interface ParsedKitchenEvent {
    type: KitchenEventType;
    item: string;
    itemId: string;
    quantity: number;
    unit: string;
    confidence: number;
    summary: string;
    decisionReason: string;
    intent: KitchenEventIntent;
    expiresAt: string | null;
    note: string | null;
}

export interface ParseKitchenEventResponse {
    proposalId: string | null;
    version: number;
    riskTier: 'LOW' | 'MEDIUM' | 'HIGH';
    expiresAt: string | null;
    events: ParsedKitchenEvent[];
    requiresConfirmation: boolean;
    autoApplied: false;
    intent: KitchenEventIntent;
    confidence: number;
    clarification: string | null;
}

export interface ConfirmKitchenEventRequest {
    proposalId: string;
    expectedVersion: number;
    idempotencyKey: string;
}
