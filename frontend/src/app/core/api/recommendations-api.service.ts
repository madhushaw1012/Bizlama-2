import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import {
    DemandCalculation,
    GovernedRecommendation,
    RecommendationExplanation,
    RecommendationGeneration,
    RecommendationOutcome,
    RecommendationOutcomeInput,
    RecommendationStatus
} from '../models/recommendation';
import { WorkspaceContext } from './workspace-api.service';

@Injectable({ providedIn: 'root' })
export class RecommendationsApiService {
    private readonly http = inject(HttpClient);

    list(scope: WorkspaceContext, status?: RecommendationStatus) {
        let params = new HttpParams()
            .set('kitchenId', scope.kitchenId)
            .set('locationId', scope.locationId);

        if (status) {
            params = params.set('status', status);
        }

        return this.http.get<GovernedRecommendation[]>(
            '/api/recommendations',
            { params }
        );
    }

    generate(scope: WorkspaceContext) {
        const now = new Date();
        const horizonEnd = new Date(now.getTime() + 24 * 60 * 60 * 1000);
        const expiresAt = new Date(now.getTime() + 30 * 60 * 1000);

        return this.http.post<RecommendationGeneration>(
            '/api/recommendations/generate',
            {
                kitchenId: scope.kitchenId,
                locationId: scope.locationId,
                horizonStart: now.toISOString(),
                horizonEnd: horizonEnd.toISOString(),
                expiresAt: expiresAt.toISOString()
            }
        );
    }

    calculation(id: string) {
        return this.http.get<DemandCalculation>(
            `/api/recommendations/${encodeURIComponent(id)}/calculation`
        );
    }

    explanation(id: string) {
        return this.http.get<RecommendationExplanation>(
            `/api/recommendations/${encodeURIComponent(id)}/explanation`
        );
    }

    approve(item: GovernedRecommendation, reason: string) {
        return this.decision(item, 'approve', reason);
    }

    dismiss(item: GovernedRecommendation, reason: string) {
        return this.decision(item, 'dismiss', reason);
    }

    flagInventory(item: GovernedRecommendation, reason: string) {
        return this.decision(item, 'flag-inventory', reason);
    }

    edit(
        item: GovernedRecommendation,
        proposedQuantity: number,
        reason: string
    ) {
        return this.http.post<GovernedRecommendation>(
            `/api/recommendations/${encodeURIComponent(item.id)}/edit`,
            {
                expectedVersion: item.version,
                proposedQuantity,
                reason
            }
        );
    }

    apply(item: GovernedRecommendation, reason: string) {
        return this.http.post<GovernedRecommendation>(
            `/api/recommendations/${encodeURIComponent(item.id)}/apply`,
            {
                expectedVersion: item.version,
                reason
            }
        );
    }

    reverse(item: GovernedRecommendation, reason: string) {
        return this.http.post<GovernedRecommendation>(
            `/api/recommendations/${encodeURIComponent(item.id)}/reverse`,
            {
                expectedVersion: item.version,
                reason
            }
        );
    }

    recordOutcome(
        item: GovernedRecommendation,
        outcome: RecommendationOutcomeInput
    ) {
        return this.http.post<RecommendationOutcome>(
            `/api/recommendations/${encodeURIComponent(item.id)}/outcomes`,
            {
                expectedVersion: item.version,
                ...outcome
            }
        );
    }

    private decision(
        item: GovernedRecommendation,
        action: 'approve' | 'dismiss' | 'flag-inventory',
        reason: string
    ) {
        return this.http.post<GovernedRecommendation>(
            `/api/recommendations/${encodeURIComponent(item.id)}/${action}`,
            {
                expectedVersion: item.version,
                reason
            }
        );
    }
}
