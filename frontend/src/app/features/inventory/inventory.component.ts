import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
    Ingredient,
    InventoryLot,
    InventorySummary,
    PageResponse,
    StockApiService
} from '../../core/api/stock-api.service';

type CanonicalUnit = 'g' | 'ml' | 'each';

@Component({
    selector: 'app-inventory',
    imports: [CommonModule, FormsModule],
    templateUrl: './inventory.component.html'
})
export class InventoryComponent implements OnInit {
    private readonly api = inject(StockApiService);
    private searchTimer?: ReturnType<typeof setTimeout>;

    protected readonly ingredients = signal<Ingredient[]>([]);
    protected readonly inventory = signal<PageResponse<InventoryLot> | null>(null);
    protected readonly summary = signal<InventorySummary | null>(null);
    protected readonly mode = signal<'browse' | 'add'>('browse');
    protected readonly searchText = signal('');
    protected readonly status = signal('all');
    protected readonly page = signal(0);
    protected readonly loading = signal(false);
    protected readonly ingredient = signal('');
    protected readonly quantity = signal(1);
    protected readonly unit = signal<CanonicalUnit>('g');
    protected readonly expiry = signal('');
    protected readonly message = signal('');

    ngOnInit(): void {
        this.api.ingredients().subscribe((value) => this.ingredients.set(value));
        this.reload();
    }

    protected showBrowse(): void {
        this.mode.set('browse');
        this.message.set('');
    }

    protected showAdd(): void {
        this.mode.set('add');
        this.message.set('');
    }

    protected ingredientChanged(value: string): void {
        this.ingredient.set(value);
        const existing = this.ingredients().find(
            (item) => item.name.toLowerCase() === value.trim().toLowerCase()
        );
        if (existing && ['g', 'ml', 'each'].includes(existing.baseUnit)) {
            this.unit.set(existing.baseUnit as CanonicalUnit);
        }
    }

    protected searchChanged(value: string): void {
        this.searchText.set(value);
        this.page.set(0);
        clearTimeout(this.searchTimer);

        this.searchTimer = setTimeout(() => this.loadPage(), 250);
    }

    protected changeStatus(value: string): void {
        this.status.set(value);
        this.page.set(0);
        this.loadPage();
    }

    protected previousPage(): void {
        if (this.page() > 0) {
            this.page.update((value) => value - 1);
            this.loadPage();
        }
    }

    protected nextPage(): void {
        const data = this.inventory();

        if (data && this.page() + 1 < data.totalPages) {
            this.page.update((value) => value + 1);
            this.loadPage();
        }
    }

    protected save(): void {
        const name = this.ingredient().trim();

        if (!name || !this.quantity()) {
            return;
        }

        const existing = this.ingredients().find(
            (value) => value.name.toLowerCase() === name.toLowerCase()
        );

        if (existing) {
            this.savePurchase(existing.id);
            return;
        }

        this.api.createIngredient(name, this.unit()).subscribe({
            next: (value) => {
                this.ingredients.update((items) => [...items, value]);
                this.savePurchase(value.id);
            },
            error: (error) => this.showError(error)
        });
    }

    protected statusLabel(value: InventoryLot['status']): string {
        switch (value) {
            case 'expired':
                return 'Expired';
            case 'expiring':
                return 'Expiring soon';
            case 'quarantined':
                return 'Expiry review';
            default:
                return 'Available';
        }
    }

    private savePurchase(ingredientId: string): void {
        this.api.purchase({
            ingredientId,
            quantity: this.quantity(),
            unit: this.unit(),
            purchasedAt: new Date().toISOString().slice(0, 10),
            expiresAt: this.expiry() || undefined,
            source: 'stockroom'
        }).subscribe({
            next: () => {
                this.message.set('Purchase added to inventory.');
                this.ingredient.set('');
                this.quantity.set(1);
                this.unit.set('g');
                this.expiry.set('');
                this.mode.set('browse');
                this.page.set(0);
                this.reload();
            },
            error: (error) => this.showError(error)
        });
    }

    private showError(error: any): void {
        this.message.set(
            error.error?.detail ?? 'Could not save stock.'
        );
    }

    private reload(): void {
        this.loadPage();
        this.api.summary().subscribe((value) => this.summary.set(value));
    }

    private loadPage(): void {
        this.loading.set(true);

        this.api.search(
            this.searchText(),
            this.status(),
            this.page()
        ).subscribe({
            next: (value) => {
                this.inventory.set(value);
                this.loading.set(false);
            },
            error: () => {
                this.message.set('Inventory could not be loaded.');
                this.loading.set(false);
            }
        });
    }
}
