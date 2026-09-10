import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface FeedbackItem {
    id: string;
    recipeId: string;
    text: string;
    rating: number;
    occurredAt: string;
    source: string;
}

@Injectable({ providedIn: 'root' })
export class FeedbackApiService {
    private readonly http = inject(HttpClient);

    list(recipeId?: string) {
        return this.http.get<FeedbackItem[]>('/api/feedback', {
            params: recipeId ? { recipeId } : {}
        });
    }

    create(recipeId: string, text: string, rating: number) {
        return this.http.post<FeedbackItem>('/api/feedback', {
            recipeId,
            text,
            rating,
            source: 'owner-entry'
        });
    }

    remove(id: string) {
        return this.http.delete<void>(`/api/feedback/${id}`);
    }
}