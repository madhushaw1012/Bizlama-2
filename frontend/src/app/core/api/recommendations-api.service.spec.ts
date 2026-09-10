import { provideHttpClient } from '@angular/common/http';
import {
    HttpTestingController,
    provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
    GovernedRecommendation,
    RecommendationExplanation,
    RecommendationOutcome
} from '../models/recommendation';
import { RecommendationsApiService } from './recommendations-api.service';

describe('RecommendationsApiService operator evidence', () => {
    let service: RecommendationsApiService;
    let http: HttpTestingController;

    const recommendation: GovernedRecommendation = {
        id: 'recommendation/17',
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
        appliedAt: '2026-09-09T15:30:00Z'
    };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                RecommendationsApiService,
                provideHttpClient(),
                provideHttpClientTesting()
            ]
        });
        service = TestBed.inject(RecommendationsApiService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('loads the versioned explanation using an encoded recommendation ID', () => {
        const explanation: RecommendationExplanation = {
            recommendationId: recommendation.id,
            recommendationVersion: 4,
            calculationId: 'calculation-1',
            authoritativeQuantity: 99,
            authoritativeUnit: 'each',
            content: {
                summary: 'Queued orders require preparation.',
                drivers: ['Pinned recipe version recipe-7.'],
                caveats: ['Confirm the production run record.']
            },
            provider: 'deterministic',
            model: 'rules',
            promptVersion: 'v1',
            inputHash: 'hash-1',
            outcome: 'FALLBACK',
            cached: false,
            generatedAt: '2026-09-09T15:31:00Z'
        };

        service.explanation(recommendation.id).subscribe((value) => {
            expect(value).toEqual(explanation);
        });

        const request = http.expectOne(
            '/api/recommendations/recommendation%2F17/explanation'
        );
        expect(request.request.method).toBe('GET');
        request.flush(explanation);
    });

    it('forwards only the measured outcome supplied by the operator', () => {
        const input = {
            type: 'PREPARED' as const,
            quantity: 12.5,
            unit: 'each',
            sourceReferenceType: 'PRODUCTION_RUN',
            sourceReferenceId: 'run-204',
            notes: 'Counted after the run cooled.',
            occurredAt: '2026-09-09T16:20:00.000Z'
        };
        service.recordOutcome(recommendation, input).subscribe();

        const request = http.expectOne(
            '/api/recommendations/recommendation%2F17/outcomes'
        );
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            expectedVersion: 4,
            ...input
        });
        expect(request.request.body.quantity)
            .not.toBe(recommendation.proposedQuantity);
        request.flush({
            id: 'outcome-1',
            recommendationId: recommendation.id,
            ...input,
            recordedAt: '2026-09-09T16:22:00Z'
        } satisfies RecommendationOutcome);
    });

    it('reverses the displayed version with the operator reason', () => {
        service.reverse(
            recommendation,
            'The linked order was cancelled after approval.'
        ).subscribe();

        const request = http.expectOne(
            '/api/recommendations/recommendation%2F17/reverse'
        );
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            expectedVersion: 4,
            reason: 'The linked order was cancelled after approval.'
        });
        request.flush({
            ...recommendation,
            status: 'REVERSED',
            version: 5
        });
    });
});
