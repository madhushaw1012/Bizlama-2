import { provideHttpClient } from '@angular/common/http';
import {
    HttpTestingController,
    provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RecipesApiService } from './recipes-api.service';

describe('RecipesApiService provenance review', () => {
    let service: RecipesApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                RecipesApiService,
                provideHttpClient(),
                provideHttpClientTesting()
            ]
        });
        service = TestBed.inject(RecipesApiService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('loads the visible legacy provenance review queue', () => {
        service.provenanceReviews().subscribe((queue) => {
            expect(queue.recipeYields.length).toBe(1);
            expect(queue.orderRecipePins.length).toBe(1);
        });

        const request = http.expectOne('/api/recipes/provenance/reviews');
        expect(request.request.method).toBe('GET');
        request.flush({
            recipeYields: [{
                recipeVersionId: 'recipe-1',
                dishId: 'dish-1',
                dishName: 'Paneer',
                yieldQuantity: 1,
                yieldUnit: 'each',
                provenance: 'LEGACY_PER_ITEM_SCHEMA'
            }],
            orderRecipePins: [{
                orderId: 'order-1',
                lineNumber: 1,
                dishId: 'dish-1',
                dishName: 'Paneer',
                provisionalRecipeVersionId: 'recipe-1',
                provenance: 'LEGACY_RECONSTRUCTED',
                orderedQuantity: 2,
                preparedQuantity: 0,
                orderedAt: '2026-09-09T10:00:00Z',
                requiredAt: '2026-09-09T12:00:00Z'
            }]
        });
    });

    it('attests the exact retained yield and encodes the recipe id', () => {
        service.confirmLegacyYield(
            'recipe/17',
            1,
            'each',
            'Checked against signed recipe ledger'
        ).subscribe();

        const request = http.expectOne(
            '/api/recipes/provenance/recipe-yields/recipe%2F17/confirm'
        );
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            yieldQuantity: 1,
            yieldUnit: 'each',
            reason: 'Checked against signed recipe ledger'
        });
        request.flush({
            reviewType: 'RECIPE_YIELD',
            recipeVersionId: 'recipe/17',
            orderId: null,
            lineNumber: null,
            provenance: 'OWNER_CONFIRMED',
            changed: true,
            reviewId: 'review-1'
        });
    });

    it('attests the provisional recipe pin without sending replacement history', () => {
        service.confirmOrderRecipePin(
            'order/9',
            3,
            'recipe-2',
            'Checked against printed kitchen ticket'
        ).subscribe();

        const request = http.expectOne(
            '/api/recipes/provenance/orders/order%2F9/lines/3/confirm'
        );
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({
            recipeVersionId: 'recipe-2',
            reason: 'Checked against printed kitchen ticket'
        });
        expect(Object.keys(request.request.body)).toEqual([
            'recipeVersionId',
            'reason'
        ]);
        request.flush({
            reviewType: 'ORDER_RECIPE_PIN',
            recipeVersionId: 'recipe-2',
            orderId: 'order/9',
            lineNumber: 3,
            provenance: 'OWNER_CONFIRMED',
            changed: true,
            reviewId: 'review-2'
        });
    });
});
