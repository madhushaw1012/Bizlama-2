import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DashboardApiService } from '../../core/api/dashboard-api.service';
import { Dish, OrdersApiService } from '../../core/api/orders-api.service';
import {
    LegacyProvenanceReviewQueue,
    MenuCategory,
    OrderRecipePinReview,
    RecipeIngredient,
    RecipeYieldReview,
    RecipeVersion,
    RecipesApiService
} from '../../core/api/recipes-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { PrepRequirement } from '../../core/models/dashboard';
import { Ingredient, StockApiService } from '../../core/api/stock-api.service';

@Component({
    selector: 'app-recipes',
    imports: [CommonModule, FormsModule],
    templateUrl: './recipes.component.html'
})
export class RecipesComponent implements OnInit {
    private readonly dashboardApi = inject(DashboardApiService);
    private readonly recipesApi = inject(RecipesApiService);
    private readonly ordersApi = inject(OrdersApiService);
    private readonly stockApi = inject(StockApiService);
    private readonly auth = inject(AuthService);

    protected readonly prep = signal<PrepRequirement[]>([]);
    protected readonly dishes = signal<Dish[]>([]);
    protected readonly recipes = signal<RecipeVersion[]>([]);
    protected readonly ingredients = signal<Ingredient[]>([]);
    protected readonly categories = signal<MenuCategory[]>([]);
    protected readonly selected = signal<RecipeVersion | null>(null);
    protected readonly search = signal('');
    protected readonly message = signal('');
    protected readonly creating = signal(false);
    protected readonly reason = signal('Respond to recurring customer feedback');

    protected readonly provenanceReviews = signal<LegacyProvenanceReviewQueue>({
        recipeYields: [],
        orderRecipePins: []
    });
    protected readonly provenanceReason = signal('Verified against retained source records');
    protected readonly reviewingProvenance = signal<string | null>(null);

    protected readonly newDishName = signal('');
    protected readonly newDishPrice = signal<number | null>(null);
    protected readonly newDishCategory = signal('');
    protected readonly newDishPrepMinutes = signal<number | null>(null);
    protected readonly newDishYieldQuantity = signal<number | null>(1);
    protected readonly newDishYieldUnit = signal('each');
    protected readonly newDishIngredients = signal<RecipeIngredient[]>([]);
    protected readonly newDishSteps = signal<string[]>([]);
    protected readonly savingDish = signal(false);

    protected readonly filteredRecipes = computed(() => {
        const query = this.search().trim().toLowerCase();

        return query
            ? this.recipes().filter((recipe) =>
                `${this.dishName(recipe.dishId)} ${recipe.changeReason}`
                    .toLowerCase()
                    .includes(query)
            )
            : this.recipes();
    });

    protected readonly provenanceReviewCount = computed(() =>
        this.provenanceReviews().recipeYields.length
            + this.provenanceReviews().orderRecipePins.length
    );

    protected readonly canReviewProvenance = computed(() =>
        ['OWNER', 'ADMIN'].includes(this.auth.user()?.role ?? '')
    );

    protected readonly canCreateDish = computed(() => {
        const price = this.newDishPrice();
        const prepMinutes = this.newDishPrepMinutes();
        const yieldQuantity = this.newDishYieldQuantity();
        const ingredients = this.newDishIngredients();
        const steps = this.newDishSteps();

        return Boolean(
            this.newDishName().trim() &&
            price &&
            price > 0 &&
            this.newDishCategory() &&
            prepMinutes &&
            prepMinutes > 0 &&
            yieldQuantity &&
            yieldQuantity > 0 &&
            this.newDishYieldUnit().trim() &&
            ingredients.length &&
            ingredients.every(
                (item) =>
                    item.ingredientId &&
                    item.quantity > 0 &&
                    item.unit.trim()
            ) &&
            steps.length &&
            steps.every((step) => step.trim())
        );
    });

    ngOnInit(): void {
        this.reload();

        this.stockApi.ingredients().subscribe((values) => {
            this.ingredients.set(values);
        });

        this.recipesApi.categories().subscribe((values) => {
            this.categories.set(values);

            if (!this.newDishCategory() && values.length) {
                this.newDishCategory.set(values[0].id);
            }
        });

        this.dashboardApi.getDashboard().subscribe((data) => {
            this.prep.set(data.prepRequirements);
        });
    }

    protected startCreate(): void {
        const ingredient = this.ingredients()[0];

        this.creating.set(true);
        this.message.set('');
        this.newDishName.set('');
        this.newDishPrice.set(null);
        this.newDishCategory.set(this.categories()[0]?.id ?? '');
        this.newDishPrepMinutes.set(null);
        this.newDishYieldQuantity.set(1);
        this.newDishYieldUnit.set('each');

        this.newDishIngredients.set(
            ingredient
                ? [
                    {
                        ingredientId: ingredient.id,
                        quantity: 1,
                        unit: ingredient.baseUnit
                    }
                ]
                : []
        );

        this.newDishSteps.set(['']);
    }

    protected cancelCreate(): void {
        this.creating.set(false);
        this.message.set('');
    }

    protected addNewDishIngredient(): void {
        const available = this.ingredients().find(
            (item) =>
                !this.newDishIngredients().some(
                    (value) => value.ingredientId === item.id
                )
        );

        if (available) {
            this.newDishIngredients.update((values) => [
                ...values,
                {
                    ingredientId: available.id,
                    quantity: 1,
                    unit: available.baseUnit
                }
            ]);
        }
    }

    protected updateNewDishIngredient(
        index: number,
        field: 'ingredientId' | 'quantity' | 'unit',
        value: string | number
    ): void {
        this.newDishIngredients.update((values) =>
            values.map((item, itemIndex) => {
                if (itemIndex !== index) {
                    return item;
                }

                const next = {
                    ...item,
                    [field]: field === 'quantity' ? Number(value) : value
                } as RecipeIngredient;

                if (field === 'ingredientId') {
                    next.unit =
                        this.ingredients().find(
                            (option) => option.id === value
                        )?.baseUnit ?? next.unit;
                }

                return next;
            })
        );
    }

    protected ingredientAlreadySelected(
        ingredientId: string,
        currentIndex: number
    ): boolean {
        return this.newDishIngredients().some(
            (item, index) =>
                index !== currentIndex &&
                item.ingredientId === ingredientId
        );
    }

    protected removeNewDishIngredient(index: number): void {
        if (this.newDishIngredients().length > 1) {
            this.newDishIngredients.update((values) =>
                values.filter((_, itemIndex) => itemIndex !== index)
            );
        }
    }

    protected addNewDishStep(): void {
        this.newDishSteps.update((values) => [...values, '']);
    }

    protected updateNewDishStep(index: number, value: string): void {
        this.newDishSteps.update((values) =>
            values.map((item, itemIndex) =>
                itemIndex === index ? value : item
            )
        );
    }

    protected removeNewDishStep(index: number): void {
        if (this.newDishSteps().length > 1) {
            this.newDishSteps.update((values) =>
                values.filter((_, itemIndex) => itemIndex !== index)
            );
        }
    }

    protected createDish(): void {
        if (!this.canCreateDish() || this.savingDish()) {
            return;
        }

        this.savingDish.set(true);

        this.recipesApi
            .createDish({
                name: this.newDishName().trim(),
                price: this.newDishPrice()!,
                categoryId: this.newDishCategory(),
                preparationMinutes: this.newDishPrepMinutes()!,
                yieldQuantity: this.newDishYieldQuantity()!,
                yieldUnit: this.newDishYieldUnit().trim(),
                ingredients: this.newDishIngredients(),
                instructions: this.newDishSteps().map((step) => step.trim())
            })
            .subscribe({
                next: (recipe) => {
                    this.savingDish.set(false);
                    this.creating.set(false);
                    this.message.set(
                        `${this.newDishName().trim()} is now on the live menu with recipe version 1.`
                    );
                    this.reload(recipe.id);
                },
                error: (error) => {
                    this.savingDish.set(false);
                    this.message.set(
                        error.error?.detail ?? 'Could not create this menu item.'
                    );
                }
            });
    }

    protected choose(recipe: RecipeVersion): void {
        this.selected.set(structuredClone(recipe));
        this.message.set('');
    }

    protected dishName(id: string): string {
        return this.dishes().find((value) => value.id === id)?.name ?? id;
    }

    protected ingredientName(id: string): string {
        return this.ingredients().find((value) => value.id === id)?.name ?? id;
    }

    protected confirmLegacyYield(review: RecipeYieldReview): void {
        const reason = this.provenanceReason().trim();
        const reviewKey = 'yield:' + review.recipeVersionId;

        if (!this.canReviewProvenance() || !reason || this.reviewingProvenance()) {
            return;
        }

        this.reviewingProvenance.set(reviewKey);
        this.recipesApi.confirmLegacyYield(
            review.recipeVersionId,
            review.yieldQuantity,
            review.yieldUnit,
            reason
        ).subscribe({
            next: (result) => {
                this.reviewingProvenance.set(null);
                this.message.set(result.changed
                    ? 'Legacy recipe yield confirmed. Its retained value is now trusted evidence.'
                    : 'That recipe yield was already confirmed.');
                this.reload(review.recipeVersionId);
            },
            error: (error) => {
                this.reviewingProvenance.set(null);
                this.message.set(
                    error.error?.detail ?? 'Could not confirm this legacy recipe yield.'
                );
            }
        });
    }

    protected confirmOrderRecipePin(review: OrderRecipePinReview): void {
        const reason = this.provenanceReason().trim();
        const reviewKey = 'order:' + review.orderId + ':' + review.lineNumber;

        if (!this.canReviewProvenance() || !reason || this.reviewingProvenance()) {
            return;
        }

        this.reviewingProvenance.set(reviewKey);
        this.recipesApi.confirmOrderRecipePin(
            review.orderId,
            review.lineNumber,
            review.provisionalRecipeVersionId,
            reason
        ).subscribe({
            next: (result) => {
                this.reviewingProvenance.set(null);
                this.message.set(result.changed
                    ? 'Legacy order recipe pin confirmed. Exact demand can now use this line.'
                    : 'That order recipe pin was already confirmed.');
                this.reload();
            },
            error: (error) => {
                this.reviewingProvenance.set(null);
                this.message.set(
                    error.error?.detail ?? 'Could not confirm this legacy order recipe pin.'
                );
            }
        });
    }

    protected propose(): void {
        const recipe = this.selected();

        if (!recipe || !this.reason().trim()) {
            return;
        }

        this.recipesApi
            .propose(recipe.dishId, recipe, this.reason())
            .subscribe({
                next: (value) => {
                    this.message.set(
                        `Version ${value.versionNumber} saved as a proposal. It has not changed the live recipe.`
                    );
                    this.reload(value.id);
                },
                error: (error) => {
                    this.message.set(
                        error.error?.detail ?? 'Could not save the proposal.'
                    );
                }
            });
    }

    protected activate(): void {
        const recipe = this.selected();

        if (!recipe || recipe.active) {
            return;
        }

        this.recipesApi.activate(recipe.id).subscribe({
            next: (value) => {
                this.message.set(
                    `Owner approved version ${value.versionNumber}. It is now active.`
                );
                this.reload(value.id);
            },
            error: (error) => {
                this.message.set(
                    error.error?.detail ?? 'Could not activate this version.'
                );
            }
        });
    }

    protected updateInstruction(index: number, value: string): void {
        const recipe = this.selected();

        if (recipe) {
            this.selected.set({
                ...recipe,
                instructions: recipe.instructions.map((item, itemIndex) =>
                    itemIndex === index ? value : item
                )
            });
        }
    }

    protected addInstruction(): void {
        const recipe = this.selected();

        if (recipe?.active) {
            this.selected.set({
                ...recipe,
                instructions: [...recipe.instructions, '']
            });
        }
    }

    protected removeInstruction(index: number): void {
        const recipe = this.selected();

        if (recipe?.active && recipe.instructions.length > 1) {
            this.selected.set({
                ...recipe,
                instructions: recipe.instructions.filter(
                    (_, itemIndex) => itemIndex !== index
                )
            });
        }
    }

    protected addIngredient(): void {
        const recipe = this.selected();

        const available = this.ingredients().find(
            (item) =>
                !recipe?.ingredients.some(
                    (value) => value.ingredientId === item.id
                )
        );

        if (recipe?.active && available) {
            this.selected.set({
                ...recipe,
                ingredients: [
                    ...recipe.ingredients,
                    {
                        ingredientId: available.id,
                        quantity: 1,
                        unit: available.baseUnit
                    }
                ]
            });
        }
    }

    protected updateIngredient(
        index: number,
        field: 'ingredientId' | 'quantity' | 'unit',
        value: string | number
    ): void {
        const recipe = this.selected();

        if (!recipe?.active) {
            return;
        }

        const ingredients = recipe.ingredients.map((item, itemIndex) => {
            if (itemIndex !== index) {
                return item;
            }

            const next = {
                ...item,
                [field]: field === 'quantity' ? Number(value) : value
            } as RecipeIngredient;

            if (field === 'ingredientId') {
                const baseUnit = this.ingredients().find(
                    (item) => item.id === value
                )?.baseUnit;

                if (baseUnit) {
                    next.unit = baseUnit;
                }
            }

            return next;
        });

        this.selected.set({
            ...recipe,
            ingredients
        });
    }

    protected removeIngredient(index: number): void {
        const recipe = this.selected();

        if (recipe?.active && recipe.ingredients.length > 1) {
            this.selected.set({
                ...recipe,
                ingredients: recipe.ingredients.filter(
                    (_, itemIndex) => itemIndex !== index
                )
            });
        }
    }

    protected updateYield(
        field: 'quantity' | 'unit',
        value: string | number
    ): void {
        const recipe = this.selected();
        if (!recipe?.active) {
            return;
        }

        this.selected.set({
            ...recipe,
            yieldQuantity: field === 'quantity'
                ? Number(value)
                : recipe.yieldQuantity,
            yieldUnit: field === 'unit'
                ? String(value)
                : recipe.yieldUnit
        });
    }

    private reload(selectId?: string): void {
        this.reloadProvenanceReviews();

        this.ordersApi.dishes().subscribe((values) => {
            this.dishes.set(values);
        });

        this.recipesApi.list().subscribe((values) => {
            this.recipes.set(values);

            const selectedRecipe =
                values.find((value) => value.id === selectId) ??
                values.find((value) => value.active) ??
                values[0];

            this.selected.set(
                selectedRecipe ? structuredClone(selectedRecipe) : null
            );
        });
    }

    private reloadProvenanceReviews(): void {
        this.recipesApi.provenanceReviews().subscribe({
            next: (reviews) => {
                this.provenanceReviews.set(reviews);
            },
            error: (error) => {
                this.message.set(
                    error.error?.detail
                        ?? 'Could not load the legacy provenance review queue.'
                );
            }
        });
    }
}