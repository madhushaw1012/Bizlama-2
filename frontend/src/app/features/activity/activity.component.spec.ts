import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { DashboardApiService } from '../../core/api/dashboard-api.service';
import { KitchenEventsApiService } from '../../core/api/kitchen-events-api.service';
import { ParseKitchenEventResponse } from '../../core/models/kitchen-event';
import { ActivityComponent } from './activity.component';

describe('ActivityComponent confirmation attempts', () => {
    it('reuses the same idempotency key when confirmation is retried', () => {
        const proposal: ParseKitchenEventResponse = {
            proposalId: 'proposal-1',
            version: 2,
            riskTier: 'HIGH',
            expiresAt: '2026-09-09T17:00:00Z',
            requiresConfirmation: true,
            autoApplied: false,
            intent: 'INVENTORY_UPDATE',
            confidence: 0.95,
            clarification: null,
            events: [{
                type: 'PURCHASE',
                item: 'Paneer',
                itemId: 'ingredient-1',
                quantity: 2,
                unit: 'each',
                confidence: 0.95,
                summary: 'Bought paneer',
                decisionReason: 'Parsed purchase',
                intent: 'INVENTORY_UPDATE',
                expiresAt: null,
                note: null
            }]
        };
        const eventsApi = jasmine.createSpyObj<KitchenEventsApiService>(
            'KitchenEventsApiService',
            ['parse', 'confirm', 'supersede']
        );
        eventsApi.parse.and.returnValue(of(proposal));
        eventsApi.confirm.and.returnValues(
            throwError(() => new Error('temporary failure')),
            of(void 0)
        );
        const dashboardApi = jasmine.createSpyObj<DashboardApiService>(
            'DashboardApiService',
            ['getDashboard']
        );
        dashboardApi.getDashboard.and.returnValue(of({
            metrics: [],
            stockItems: [],
            prepRequirements: [],
            restockSuggestions: [],
            recentEvents: []
        }));
        TestBed.configureTestingModule({
            providers: [
                { provide: KitchenEventsApiService, useValue: eventsApi },
                { provide: DashboardApiService, useValue: dashboardApi }
            ]
        });
        const component = TestBed.runInInjectionContext(
            () => new ActivityComponent()
        ) as any;

        component.statement.set('Bought paneer');
        component.parseEvent();
        component.confirmEvent();
        component.confirmEvent();

        expect(eventsApi.confirm).toHaveBeenCalledTimes(2);
        expect(eventsApi.confirm.calls.argsFor(0)[0].idempotencyKey)
            .toBe(eventsApi.confirm.calls.argsFor(1)[0].idempotencyKey);
        expect(eventsApi.confirm.calls.argsFor(0)[0]).toEqual({
            proposalId: 'proposal-1',
            expectedVersion: 2,
            idempotencyKey: eventsApi.confirm.calls.argsFor(1)[0].idempotencyKey
        });
    });
});
