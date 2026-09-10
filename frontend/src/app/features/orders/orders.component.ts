import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
    Dish,
    Order,
    OrderListItem,
    OrderStatus,
    OrderSummary,
    OrdersApiService,
    PageResponse
} from '../../core/api/orders-api.service';

@Component({
    selector: 'app-orders',
    imports: [CommonModule, FormsModule],
    templateUrl: './orders.component.html'
})
export class OrdersComponent implements OnInit {
    private readonly api = inject(OrdersApiService);
    private searchTimer?: ReturnType<typeof setTimeout>;

    protected readonly menu = signal<Dish[]>([]);
    protected readonly mode = signal<'queue' | 'new'>('queue');
    protected readonly orderPage = signal<PageResponse<OrderListItem> | null>(null);
    protected readonly summary = signal<OrderSummary | null>(null);
    protected readonly selectedOrder = signal<Order | null>(null);
    protected readonly cart = signal<Record<string, number>>({});
    protected readonly menuSearch = signal('');
    protected readonly selectedCategory = signal('all');
    protected readonly orderSearch = signal('');
    protected readonly status = signal('all');
    protected readonly page = signal(0);
    protected readonly message = signal('');

    protected readonly menuCategories = computed(() => {
        const counts = new Map<string, number>();

        this.menu().forEach((dish) => {
            const category = dish.categoryName || 'Other';
            counts.set(category, (counts.get(category) ?? 0) + 1);
        });

        return [...counts.entries()].map(([name, count]) => ({
            name,
            count
        }));
    });

    protected readonly filteredMenu = computed(() => {
        const query = this.menuSearch().trim().toLowerCase();
        const category = this.selectedCategory();

        return this.menu()
            .filter(
                (item) =>
                    (category === 'all' || item.categoryName === category) &&
                    (!query || item.name.toLowerCase().includes(query))
            )
            .slice(0, 100);
    });

    ngOnInit(): void {
        this.reload();
    }

    protected showQueue(): void {
        this.mode.set('queue');
        this.message.set('');
        this.loadOrders();
    }

    protected showNew(): void {
        this.mode.set('new');
        this.message.set('');
    }

    protected chooseCategory(category: string): void {
        this.selectedCategory.set(category);
    }

    protected add(id: string): void {
        this.cart.update((cart) => ({
            ...cart,
            [id]: (cart[id] ?? 0) + 1
        }));
    }

    protected remove(id: string): void {
        this.cart.update((cart) => {
            const next = { ...cart };
            const count = (next[id] ?? 0) - 1;

            if (count <= 0) {
                delete next[id];
            } else {
                next[id] = count;
            }

            return next;
        });
    }

    protected quantity(id: string): number {
        return this.cart()[id] ?? 0;
    }

    protected get totalItems(): number {
        return Object.values(this.cart()).reduce(
            (total, quantity) => total + quantity,
            0
        );
    }

    protected get total(): number {
        return this.menu().reduce(
            (sum, dish) => sum + dish.price * this.quantity(dish.id),
            0
        );
    }

    protected dishName(id: string): string {
        return this.menu().find((item) => item.id === id)?.name ?? id;
    }

    protected orderSearchChanged(value: string): void {
        this.orderSearch.set(value);
        this.page.set(0);
        clearTimeout(this.searchTimer);

        this.searchTimer = setTimeout(() => this.loadOrders(), 250);
    }

    protected statusChanged(value: string): void {
        this.status.set(value);
        this.page.set(0);
        this.selectedOrder.set(null);
        this.loadOrders();
    }

    protected previousPage(): void {
        if (this.page() > 0) {
            this.page.update((value) => value - 1);
            this.loadOrders();
        }
    }

    protected nextPage(): void {
        const data = this.orderPage();

        if (data && this.page() + 1 < data.totalPages) {
            this.page.update((value) => value + 1);
            this.loadOrders();
        }
    }

    protected openOrder(id: string): void {
        this.api.detail(id).subscribe((value) => {
            this.selectedOrder.set(value);
        });
    }

    protected closeOrder(): void {
        this.selectedOrder.set(null);
    }

    protected statusLabel(status: OrderStatus): string {
        return status.charAt(0) + status.slice(1).toLowerCase();
    }

    protected nextStatus(status: OrderStatus): OrderStatus | null {
        if (status === 'QUEUED') {
            return 'PREPARING';
        }
        if (status === 'PREPARING') {
            return 'DONE';
        }
        return null;
    }

    protected advanceOrder(order: Order): void {
        const next = this.nextStatus(order.status);

        if (!next) {
            return;
        }

        this.api.updateStatus(order.id, next).subscribe({
            next: (value) => {
                this.selectedOrder.set(value);
                this.message.set(
                    `${value.id} is now ${this.statusLabel(value.status)}.`
                );
                this.loadOrders();
            },
            error: () => {
                this.message.set('The order status could not be updated.');
            }
        });
    }

    protected placeOrder(): void {
        const items = Object.entries(this.cart()).map(
            ([dishId, quantity]) => ({
                dishId,
                quantity
            })
        );

        if (!items.length) {
            return;
        }

        this.api.create(items).subscribe({
            next: (order) => {
                this.message.set(`${order.id} was added to the kitchen queue.`);
                this.cart.set({});
                this.mode.set('queue');
                this.page.set(0);
                this.reload();
                this.openOrder(order.id);
            },
            error: (error) => {
                this.message.set(
                    error.error?.detail ?? 'Could not place order.'
                );
            }
        });
    }

    private reload(): void {
        this.api.dishes().subscribe((value) => this.menu.set(value));
        this.loadOrders();
        this.api.summary().subscribe((value) => this.summary.set(value));
    }

    private loadOrders(): void {
        this.api
            .search(
                this.orderSearch(),
                this.status(),
                this.page()
            )
            .subscribe((value) => this.orderPage.set(value));

        this.api.summary().subscribe((value) => this.summary.set(value));
    }
}