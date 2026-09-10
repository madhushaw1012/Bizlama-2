import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';

export interface StockLot {
    id: string;
    ingredientId: string;
    quantityRemaining: number;
    unit: string;
    purchasedAt: string;
    expiresAt: string | null;
    source: string;
}

export interface Ingredient {
    id: string;
    name: string;
    baseUnit: string;
    active: boolean;
}

export interface InventoryLot extends StockLot {
    ingredientName: string;
    status: 'available' | 'expiring' | 'expired' | 'quarantined';
}

export interface InventorySummary {
    ingredients: number;
    activeLots: number;
    expiringLots: number;
    expiredLots: number;
}

export interface PageResponse<T> {
    items: T[];
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
}

@Injectable({ providedIn: 'root' })
export class StockApiService {
    private readonly http = inject(HttpClient);

    ingredients() {
        return this.http.get<Ingredient[]>('/api/ingredients');
    }

    createIngredient(name: string, baseUnit: 'g' | 'ml' | 'each') {
        return this.http.post<Ingredient>('/api/ingredients', {
            name,
            baseUnit
        });
    }

    lots() {
        return this.http.get<StockLot[]>('/api/stock');
    }

    search(query: string, status: string, page: number, size = 25) {
        const params = new HttpParams()
            .set('query', query)
            .set('status', status)
            .set('page', page)
            .set('size', size);

        return this.http.get<PageResponse<InventoryLot>>('/api/stock/search', { params });
    }

    summary() {
        return this.http.get<InventorySummary>('/api/stock/summary');
    }

    purchase(request: {
        ingredientId: string;
        quantity: number;
        unit: string;
        purchasedAt: string;
        expiresAt?: string;
        source: string;
    }) {
        return this.http.post<StockLot>('/api/stock/purchases', request);
    }
}
