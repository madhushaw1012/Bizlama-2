import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { DashboardApiService } from '../../core/api/dashboard-api.service';
import { RecommendationsApiService } from '../../core/api/recommendations-api.service';
import { WorkspaceApiService } from '../../core/api/workspace-api.service';
import { AuthService } from '../../core/auth/auth.service';
import {
    DemandCalculation,
    GovernedRecommendation,
    RecommendationExplanation,
    RecommendationOutcome
} from '../../core/models/recommendation';
import { DashboardComponent } from './dashboard.component';

describe('DashboardComponent governed outcomes', () => {
    let component: any;
    let recommendationsApi: jasmine.SpyObj<RecommendationsApiService>;

    const recommendation: GovernedRecommendation = {
        id: 'recommendation-1',
        kitchenId: 'kitchen-1',
        locationId: 'location-1',
        type: 'PREPARE',
        dishId: 'dish-1',
        recipeVersionId: 'recipe-7',
        proposedQuantity: 99,
        unit: 'each',
        calculationId: 'calculation-1',
        confidence: 0.91,
        reasonCode: 'ORDER_PREPARATION_REQUIRED',
        riskTier: 'MEDIUM',
        status: 'APPLIED',
        version: 4,
        expiresAt: '2026-09-09T18:00:00Z',
        createdAt: '2026-09-09T15:00:00Z',
        decidedAt: '2026-09-09T15:10:00Z',
        appliedAt: '2026-09-09T15:30:00Z',
        appliedActionType: 'PRODUCTION_RUN',
        appliedActionId: 'run-204'
    };

    const calculation: DemandCalculation = {
        scope: {
            kitchenId: 'kitchen-1',
            locationId: 'location-1',
            horizonStart: '2026-09-09T15:00:00Z',
            horizonEnd: '2026-09-10T15:00:00Z',
            asOf: '2026-09-09T15:00:00Z'
        },
        asOfKitchenDate: '2026-09-09',
        horizonEndKitchenDate: '2026-09-10',
        calculatedAt: '2026-09-09T15:00:00Z',
        calculationMethod: 'DETERMINISTIC_DEMAND_V2_PROVENANCE_GATED',
        includedOrderStatuses: ['ACCEPTED', 'IN_PREPARATION'],
        ingredients: [],
        preparations: [],
        unresolvedOrderEvidence: []
    };

    const explanation: RecommendationExplanation = {
        recommendationId: recommendation.id,
        recommendationVersion: recommendation.version,
        calculationId: recommendation.calculationId,
        authoritativeQuantity: recommendation.proposedQuantity,
        authoritativeUnit: recommendation.unit,
        content: {
            summary: 'Queued orders require the pinned recipe preparation.',
            drivers: ['Two open order lines use recipe-7.'],
            caveats: ['Confirm the production run after measuring output.']
        },
        provider: 'deterministic',
        model: 'rules',
        promptVersion: 'v1',
        inputHash: 'hash-1',
        outcome: 'FALLBACK',
        cached: false,
        generatedAt: '2026-09-09T15:01:00Z'
    };

    beforeEach(() => {
        recommendationsApi = jasmine.createSpyObj<RecommendationsApiService>(
            'RecommendationsApiService',
            [
                'list',
                'generate',
                'calculation',
                'explanation',
                'approve',
                'dismiss',
                'flagInventory',
                'edit',
                'apply',
                'reverse',
                'recordOutcome'
            ]
        );
        recommendationsApi.calculation.and.returnValue(of(calculation));
        recommendationsApi.explanation.and.returnValue(of(explanation));

        const dashboardApi = jasmine.createSpyObj<DashboardApiService>(
            'DashboardApiService',
            ['getDashboard']
        );
        const workspaceApi = jasmine.createSpyObj<WorkspaceApiService>(
            'WorkspaceApiService',
            ['current']
        );
        TestBed.configureTestingModule({
            providers: [
                {
                    provide: RecommendationsApiService,
                    useValue: recommendationsApi
                },
                { provide: DashboardApiService, useValue: dashboardApi },
                { provide: WorkspaceApiService, useValue: workspaceApi },
                {
                    provide: AuthService,
                    useValue: {
                        user: () => ({
                            email: 'owner@example.com',
                            name: 'Owner',
                            role: 'OWNER'
                        })
                    }
                }
            ]
        });
        component = TestBed.runInInjectionContext(
            () => new DashboardComponent()
        );
        component.online.set(true);
    });

    it('opens a blank form without inferring or submitting an outcome', () => {
        component.beginOutcome(recommendation);

        expect(component.showingOutcomeForm()).toBeTrue();
        expect(component.outcomeType()).toBe('');
        expect(component.outcomeQuantity()).toBeNull();
        expect(component.outcomeUnit()).toBe('');
        expect(component.outcomeOccurredAt()).toBe('');
        expect(component.outcomeSourceReferenceType()).toBe('PRODUCTION_RUN');
        expect(component.outcomeSourceReferenceId()).toBe('run-204');
        expect(recommendationsApi.recordOutcome).not.toHaveBeenCalled();

        component.submitOutcome(recommendation);
        expect(recommendationsApi.recordOutcome).not.toHaveBeenCalled();
        expect(component.recommendationError()).toContain('Select an outcome');

        component.selectOutcomeType('PREPARED');
        component.submitOutcome(recommendation);
        expect(recommendationsApi.recordOutcome).not.toHaveBeenCalled();
        expect(component.recommendationError()).toContain('positive quantity');

        component.selectOutcomeSourceType('ORDER');
        expect(component.outcomeSourceReferenceId()).toBe('');
    });

    it('submits the operator measurement, source, notes and entered time', () => {
        const recorded: RecommendationOutcome = {
            id: 'outcome-1',
            recommendationId: recommendation.id,
            type: 'PREPARED',
            quantity: 12.5,
            unit: 'each',
            sourceReferenceType: 'PRODUCTION_RUN',
            sourceReferenceId: 'run-204',
            notes: 'Counted after the run cooled.',
            occurredAt: '2026-09-09T16:20:00.000Z',
            recordedAt: '2026-09-09T16:22:00Z'
        };
        recommendationsApi.recordOutcome.and.returnValue(of(recorded));

        component.beginOutcome(recommendation);
        component.selectOutcomeType('PREPARED');
        component.outcomeQuantity.set(12.5);
        component.outcomeUnit.set('each');
        component.outcomeOccurredAt.set('2026-09-09T16:20:00Z');
        component.outcomeSourceReferenceType.set('PRODUCTION_RUN');
        component.outcomeSourceReferenceId.set('run-204');
        component.outcomeNotes.set('Counted after the run cooled.');
        component.submitOutcome(recommendation);

        expect(recommendationsApi.recordOutcome).toHaveBeenCalledOnceWith(
            recommendation,
            {
                type: 'PREPARED',
                quantity: 12.5,
                unit: 'each',
                sourceReferenceType: 'PRODUCTION_RUN',
                sourceReferenceId: 'run-204',
                notes: 'Counted after the run cooled.',
                occurredAt: '2026-09-09T16:20:00.000Z'
            }
        );
        expect(component.showingOutcomeForm()).toBeFalse();
    });

    it('restricts corrected inventory to its compatible outcome type', () => {
        const flagged = {
            ...recommendation,
            status: 'INVENTORY_FLAGGED' as const
        };
        expect(
            component.compatibleOutcomeOptions(flagged)
                .map((value: { value: string }) => value.value)
        ).toEqual(['CORRECTED_INVENTORY']);
        expect(component.outcomeSourceTypes(flagged))
            .toEqual(['STOCK_MOVEMENT']);
        component.beginOutcome(flagged);
        expect(component.outcomeSourceReferenceType()).toBe('STOCK_MOVEMENT');
        expect(component.outcomeSourceReferenceId()).toBe('');
        expect(
            component.compatibleOutcomeOptions(recommendation)
                .map((value: { value: string }) => value.value)
        ).toEqual([
            'PREPARED',
            'SOLD',
            'FULFILLED',
            'WASTED',
            'STOCKOUT',
            'OVERRIDE'
        ]);
        expect(
            component.compatibleOutcomeOptions({
                ...recommendation,
                type: 'REDUCE_OR_AVOID_PURCHASE'
            }).map((value: { value: string }) => value.value)
        ).toEqual(['WASTED', 'OVERRIDE']);
    });

    it('loads and stores the backend fallback explanation for the selected version', () => {
        component.selectRecommendation(recommendation);

        expect(recommendationsApi.explanation)
            .toHaveBeenCalledOnceWith('recommendation-1');
        expect(component.explanation()).toEqual(explanation);
        expect(component.explanationLoading()).toBeFalse();
    });

    it('does not reverse until an owner enters a reason', () => {
        recommendationsApi.reverse.and.returnValue(of({
            ...recommendation,
            status: 'REVERSED',
            version: 5
        }));
        component.beginReverse();

        expect(recommendationsApi.reverse).not.toHaveBeenCalled();
        component.submitReverse(recommendation);
        expect(recommendationsApi.reverse).not.toHaveBeenCalled();

        component.reverseReason.set(
            'The linked order was cancelled after approval.'
        );
        component.submitReverse(recommendation);
        expect(recommendationsApi.reverse).toHaveBeenCalledOnceWith(
            recommendation,
            'The linked order was cancelled after approval.'
        );
        expect(component.showingReverseForm()).toBeFalse();
    });
});
