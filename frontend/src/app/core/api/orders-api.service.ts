import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';

export interface Dish {
    id: string;
    name: string;
    price: number;
    active: boolean;
    categoryId: string | null;
    categoryName: string;
}

export type OrderStatus = 'QUEUED' | 'PREPARING' | 'READY' | 'COMPLETED' | 'CANCELLED';

export interface Order {
    id: string;
    items: {
        dishId: string;
        quantity: number;
        unitPrice: number;
    }[];
    total: number;
    status: OrderStatus;
    createdAt: string;
}

export interface OrderListItem {
    id: string;
    total: number;
    status: OrderStatus;
    createdAt: string;
    itemCount: number;
}

export interface OrderSummary {
    openOrders: number;
    readyOrders: number;
    completedToday: number;
    revenueToday: number;
}

export interface PageResponse<T> {
    items: T[];
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
}

@Injectable({ providedIn: 'root' })
export class OrdersApiService {
    private readonly http = inject(HttpClient);

    dishes() {
        return this.http.get<Dish[]>('/api/dishes');
    }

    orders() {
        return this.http.get<Order[]>('/api/orders');
    }

    search(query: string, status: string, page: number, size = 25) {
        const params = new HttpParams()
            .set('query', query)
            .set('status', status)
            .set('page', page)
            .set('size', size);

        return this.http.get<PageResponse<OrderListItem>>('/api/orders/search', { params });
    }

    summary() {
        return this.http.get<OrderSummary>('/api/orders/summary');
    }

    detail(id: string) {
        return this.http.get<Order>(`/api/orders/${id}`);
    }

    updateStatus(id: string, status: OrderStatus) {
        return this.http.patch<Order>(`/api/orders/${id}/status`, { status });
    }

    create(items: { dishId: string; quantity: number }[]) {
        return this.http.post<Order>('/api/orders', { items });
    }
}