import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { catchError, forkJoin, of } from 'rxjs';
import { ExperimentsApiService } from '../../core/api/experiments-api.service';
import { FeedbackApiService, FeedbackItem } from '../../core/api/feedback-api.service';
import { Dish, OrdersApiService } from '../../core/api/orders-api.service';
import { RecipeVersion, RecipesApiService } from '../../core/api/recipes-api.service';
import { Experiment } from '../../core/models/experiment';

@Component({
    selector: 'app-feedback',
    imports: [CommonModule, FormsModule],
    templateUrl: './feedback.component.html'
})
export class FeedbackComponent implements OnInit {
    private readonly experimentsApi = inject(ExperimentsApiService);
    private readonly feedbackApi = inject(FeedbackApiService);
    private readonly ordersApi = inject(OrdersApiService);
    private readonly recipesApi = inject(RecipesApiService);

    protected readonly dishes = signal<Dish[]>([]);
    protected readonly recipes = signal<RecipeVersion[]>([]);
    protected readonly selectedDishId = signal('');
    protected readonly experiment = signal<Experiment | null>(null);
    protected readonly comments = signal<FeedbackItem[]>([]);
    protected readonly comment = signal('');
    protected readonly rating = signal(4);
    protected readonly message = signal('');
    protected readonly loadError = signal('');
    protected readonly experimentError = signal('');
    protected readonly commentsError = signal('');
    protected readonly loading = signal(true);
    protected readonly experimentLoading = signal(false);
    protected readonly commentsLoading = signal(false);
    protected readonly saving = signal(false);
    protected readonly approving = signal(false);

    protected readonly availableDishes = computed(() =>
        this.dishes().filter((dish) =>
            this.recipes().some(
                (recipe) => recipe.dishId === dish.id && recipe.active
            )
        )
    );

    protected readonly selectedDish = computed(() =>
        this.availableDishes().find(
            (dish) => dish.id === this.selectedDishId()
        ) ?? null
    );

    protected readonly selectedRecipe = computed(() =>
        this.recipes().find(
            (recipe) =>
                recipe.dishId === this.selectedDishId() && recipe.active
        ) ?? null
    );

    ngOnInit(): void {
        this.reload();
    }

    protected selectDish(dishId: string): void {
        this.selectedDishId.set(dishId);
        this.comment.set('');
        this.message.set('');
        this.reloadSelectedDish();
    }

    protected add(): void {
        const value = this.comment().trim();
        const recipe = this.selectedRecipe();

        if (!value || !recipe || this.saving()) {
            return;
        }

        this.saving.set(true);
        this.message.set('');

        this.feedbackApi
            .create(recipe.id, value, this.rating())
            .subscribe({
                next: () => {
                    this.comment.set('');
                    this.message.set('Feedback saved.');
                    this.saving.set(false);
                    this.reloadSelectedDish();
                },
                error: (error) => {
                    this.saving.set(false);
                    this.message.set(
                        error.error?.detail ?? 'Could not save feedback.'
                    );
                }
            });
    }

    protected remove(id: string): void {
        this.message.set('');
        this.feedbackApi.remove(id).subscribe({
            next: () => {
                this.message.set('Feedback removed.');
                this.reloadSelectedDish();
            },
            error: (error) => {
                this.message.set(
                    error.error?.detail ?? 'Could not remove feedback.'
                );
            }
        });
    }

    protected approve(): void {
        const dishId = this.selectedDishId();

        if (!dishId || this.approving()) {
            return;
        }

        this.approving.set(true);
        this.message.set('');

        this.experimentsApi.approveExperiment(dishId).subscribe({
            next: (value) => {
                this.approving.set(false);
                this.experiment.set(value);
                this.message.set(
                    'Experiment approved. The permanent recipe still requires a separate owner decision after results are reviewed.'
                );
            },
            error: (error) => {
                this.approving.set(false);
                this.message.set(
                    error.error?.detail ?? 'Could not approve this experiment.'
                );
            }
        });
    }

    protected reload(): void {
        this.loading.set(true);
        this.loadError.set('');
        this.message.set('');

        forkJoin({
            dishes: this.ordersApi.dishes(),
            recipes: this.recipesApi.list(),
            feedback: this.feedbackApi.list().pipe(catchError(() => of([])))
        }).subscribe({
            next: ({ dishes, recipes, feedback }) => {
                this.dishes.set(dishes);
                this.recipes.set(recipes);

                const availableDishIds = new Set(dishes.map((dish) => dish.id));
                const feedbackRecipeIds = new Set(
                    feedback.map((item) => item.recipeId)
                );
                const initialRecipe =
                    recipes.find(
                        (recipe) =>
                            recipe.active &&
                            availableDishIds.has(recipe.dishId) &&
                            feedbackRecipeIds.has(recipe.id)
                    ) ??
                    recipes.find(
                        (recipe) =>
                            recipe.active && availableDishIds.has(recipe.dishId)
                    );

                this.loading.set(false);

                if (!initialRecipe) {
                    this.selectedDishId.set('');
                    this.experiment.set(null);
                    this.comments.set([]);
                    return;
                }

                this.selectedDishId.set(initialRecipe.dishId);
                this.reloadSelectedDish();
            },
            error: () => {
                this.loading.set(false);
                this.loadError.set(
                    'Dishes and recipe versions could not be loaded.'
                );
            }
        });
    }

    protected retryExperiment(): void {
        this.loadExperiment();
    }

    private reloadSelectedDish(): void {
        this.loadExperiment();
        this.loadComments();
    }

    private loadExperiment(): void {
        const dishId = this.selectedDishId();

        this.experiment.set(null);
        this.experimentError.set('');

        if (!dishId) {
            this.experimentLoading.set(false);
            return;
        }

        this.experimentLoading.set(true);
        this.experimentsApi.getExperiment(dishId).subscribe({
            next: (value) => {
                this.experiment.set(value);
                this.experimentLoading.set(false);
            },
            error: (error) => {
                this.experimentLoading.set(false);
                const detail =
                    error.error?.detail ?? error.error?.message ?? '';

                if (
                    error.status !== 404 &&
                    !detail.toLowerCase().includes('experiment not found')
                ) {
                    this.experimentError.set(
                        detail || 'Experiment details could not be loaded.'
                    );
                }
            }
        });
    }

    private loadComments(): void {
        const recipe = this.selectedRecipe();

        this.comments.set([]);
        this.commentsError.set('');

        if (!recipe) {
            this.commentsLoading.set(false);
            return;
        }

        this.commentsLoading.set(true);
        this.feedbackApi.list(recipe.id).subscribe({
            next: (values) => {
                this.comments.set(values);
                this.commentsLoading.set(false);
            },
            error: () => {
                this.commentsLoading.set(false);
                this.commentsError.set('Recent comments could not be loaded.');
            }
        });
    }
}
