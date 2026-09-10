import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface RecipeIngredient {
    ingredientId: string;
    quantity: number;
    unit: string;
}

export interface RecipeVersion {
    id: string;
    dishId: string;
    versionNumber: number;
    ingredients: RecipeIngredient[];
    instructions: string[];
    yieldQuantity: number;
    yieldUnit: string;
    yieldProvenance: 'LEGACY_PER_ITEM_SCHEMA' | 'OPERATOR_ENTERED' | 'OWNER_CONFIRMED';
    changeReason: string;
    createdAt: string;
    createdBy: string;
    active: boolean;
    approvedBy?: string;
    approvedAt?: string;
    effectiveAt?: string;
    supersededAt?: string;
    supersededBy?: string;
}

export interface RecipeYieldReview {
    recipeVersionId: string;
    dishId: string;
    dishName: string;
    yieldQuantity: number;
    yieldUnit: string;
    provenance: 'LEGACY_PER_ITEM_SCHEMA';
}

export interface OrderRecipePinReview {
    orderId: string;
    lineNumber: number;
    dishId: string;
    dishName: string;
    provisionalRecipeVersionId: string;
    provenance: 'LEGACY_RECONSTRUCTED';
    orderedQuantity: number;
    preparedQuantity: number;
    orderedAt: string;
    requiredAt: string;
}

export interface LegacyProvenanceReviewQueue {
    recipeYields: RecipeYieldReview[];
    orderRecipePins: OrderRecipePinReview[];
}

export interface ProvenanceConfirmation {
    reviewType: 'RECIPE_YIELD' | 'ORDER_RECIPE_PIN';
    recipeVersionId: string;
    orderId: string | null;
    lineNumber: number | null;
    provenance: 'OWNER_CONFIRMED';
    changed: boolean;
    reviewId: string | null;
}

export interface MenuCategory {
    id: string;
    name: string;
}

export interface CreateDishRequest {
    name: string;
    price: number;
    categoryId: string;
    preparationMinutes: number;
    yieldQuantity: number;
    yieldUnit: string;
    ingredients: RecipeIngredient[];
    instructions: string[];
}

@Injectable({ providedIn: 'root' })
export class RecipesApiService {
    private readonly http = inject(HttpClient);

    list() {
        return this.http.get<RecipeVersion[]>('/api/recipes');
    }

    categories() {
        return this.http.get<MenuCategory[]>('/api/dish-categories');
    }

    provenanceReviews() {
        return this.http.get<LegacyProvenanceReviewQueue>(
            '/api/recipes/provenance/reviews'
        );
    }

    confirmLegacyYield(
        recipeVersionId: string,
        yieldQuantity: number,
        yieldUnit: string,
        reason: string
    ) {
        return this.http.post<ProvenanceConfirmation>(
            '/api/recipes/provenance/recipe-yields/'
                + encodeURIComponent(recipeVersionId)
                + '/confirm',
            { yieldQuantity, yieldUnit, reason }
        );
    }

    confirmOrderRecipePin(
        orderId: string,
        lineNumber: number,
        recipeVersionId: string,
        reason: string
    ) {
        return this.http.post<ProvenanceConfirmation>(
            '/api/recipes/provenance/orders/'
                + encodeURIComponent(orderId)
                + '/lines/'
                + lineNumber
                + '/confirm',
            { recipeVersionId, reason }
        );
    }

    createDish(request: CreateDishRequest) {
        return this.http.post<RecipeVersion>('/api/recipes/dishes', request);
    }

    propose(dishId: string, recipe: RecipeVersion, changeReason: string) {
        return this.http.post<RecipeVersion>(
            `/api/recipes/dishes/${dishId}/proposals`,
            {
                ingredients: recipe.ingredients,
                instructions: recipe.instructions,
                yieldQuantity: recipe.yieldQuantity,
                yieldUnit: recipe.yieldUnit,
                changeReason
            }
        );
    }

    activate(recipeId: string) {
        return this.http.post<RecipeVersion>(
            `/api/recipes/${recipeId}/activate`,
            {}
        );
    }
}