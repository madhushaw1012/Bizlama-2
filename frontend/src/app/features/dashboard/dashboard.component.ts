import { CommonModule } from '@angular/common';
import {
    Component,
    OnDestroy,
    OnInit,
    computed,
    inject,
    signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { DashboardApiService } from '../../core/api/dashboard-api.service';
import { RecommendationsApiService } from '../../core/api/recommendations-api.service';
import {
    DemandCalculation,
    GovernedRecommendation,
    IngredientDemand,
    RecommendationExplanation,
    RecommendationOutcomeType,
    RecommendationType
} from '../../core/models/recommendation';
import {
    WorkspaceApiService,
    WorkspaceContext
} from '../../core/api/workspace-api.service';
import { DashboardResponse, StockItem } from '../../core/models/dashboard';
import { AuthService } from '../../core/auth/auth.service';

interface OutcomeOption {
    value: RecommendationOutcomeType;
    label: string;
    description: string;
    requiresQuantity: boolean;
}

const OUTCOME_OPTIONS: Record<RecommendationOutcomeType, OutcomeOption> = {
    PREPARED: {
        value: 'PREPARED',
        label: 'Preparation completed',
        description: 'Measure the amount actually prepared.',
        requiresQuantity: true
    },
    SOLD: {
        value: 'SOLD',
        label: 'Stock sold',
        description: 'Measure the amount actually sold.',
        requiresQuantity: true
    },
    FULFILLED: {
        value: 'FULFILLED',
        label: 'Action fulfilled',
        description: 'Measure the amount actually fulfilled.',
        requiresQuantity: true
    },
    WASTED: {
        value: 'WASTED',
        label: 'Stock wasted',
        description: 'Measure the amount that became unusable.',
        requiresQuantity: true
    },
    EMERGENCY_PURCHASED: {
        value: 'EMERGENCY_PURCHASED',
        label: 'Emergency purchase made',
        description: 'Measure the amount actually bought at short notice.',
        requiresQuantity: true
    },
    STOCKOUT: {
        value: 'STOCKOUT',
        label: 'Stockout observed',
        description: 'Measure the unmet amount at the time of the stockout.',
        requiresQuantity: true
    },
    OVERRIDE: {
        value: 'OVERRIDE',
        label: 'Operator override',
        description: 'Document why a different action was taken.',
        requiresQuantity: false
    },
    CORRECTED_INVENTORY: {
        value: 'CORRECTED_INVENTORY',
        label: 'Inventory count corrected',
        description: 'Enter the verified quantity and link its correction movement.',
        requiresQuantity: true
    }
};

const COMPATIBLE_OUTCOMES: Record<
    RecommendationType,
    RecommendationOutcomeType[]
> = {
    PURCHASE: ['FULFILLED', 'EMERGENCY_PURCHASED', 'STOCKOUT', 'OVERRIDE'],
    PREPARE: ['PREPARED', 'SOLD', 'FULFILLED', 'WASTED', 'STOCKOUT', 'OVERRIDE'],
    UTILISE_EXPIRING_STOCK: ['SOLD', 'FULFILLED', 'WASTED', 'OVERRIDE'],
    REDUCE_OR_AVOID_PURCHASE: ['WASTED', 'OVERRIDE']
};

@Component({
    selector: 'app-dashboard',
    imports: [CommonModule, FormsModule, RouterLink],
    templateUrl: './dashboard.component.html',
    styleUrl: '../../app.scss'
})
export class DashboardComponent implements OnInit, OnDestroy {
    private readonly dashboardApi = inject(DashboardApiService);
    private readonly recommendationsApi = inject(RecommendationsApiService);
    private readonly workspaceApi = inject(WorkspaceApiService);
    private readonly auth = inject(AuthService);

    protected readonly dashboard = signal<DashboardResponse | null>(null);
    protected readonly workspace = signal<WorkspaceContext | null>(null);
    protected readonly recommendations = signal<GovernedRecommendation[]>([]);
    protected readonly selected = signal<GovernedRecommendation | null>(null);
    protected readonly calculation = signal<DemandCalculation | null>(null);
    protected readonly explanation = signal<RecommendationExplanation | null>(null);
    protected readonly error = signal<string | null>(null);
    protected readonly recommendationError = signal<string | null>(null);
    protected readonly explanationError = signal<string | null>(null);
    protected readonly syncing = signal(false);
    protected readonly generating = signal(false);
    protected readonly explanationLoading = signal(false);
    protected readonly showingCalculation = signal(false);
    protected readonly editQuantity = signal<number | null>(null);
    protected readonly showingOutcomeForm = signal(false);
    protected readonly outcomeType = signal<RecommendationOutcomeType | ''>('');
    protected readonly outcomeQuantity = signal<number | null>(null);
    protected readonly outcomeUnit = signal('');
    protected readonly outcomeOccurredAt = signal('');
    protected readonly outcomeSourceReferenceType = signal('');
    protected readonly outcomeSourceReferenceId = signal('');
    protected readonly outcomeNotes = signal('');
    protected readonly showingReverseForm = signal(false);
    protected readonly reverseReason = signal('');
    protected readonly online = signal(navigator.onLine);

    protected readonly outcomeNeedsQuantity = computed(() => {
        const type = this.outcomeType();
        return type ? OUTCOME_OPTIONS[type].requiresQuantity : false;
    });

    protected readonly currentRecommendation = computed(() => {
        const selected = this.selected();
        const live = this.attentionRecommendations();

        if (selected && live.some((item) => item.id === selected.id)) {
            return selected;
        }

        return live[0] ?? null;
    });

    protected readonly attentionRecommendations = computed(() =>
        this.recommendations()
            .filter((item) =>
                ['PENDING', 'APPROVED', 'INVENTORY_FLAGGED', 'APPLIED']
                    .includes(item.status)
            )
            .sort((left, right) =>
                this.priority(left) - this.priority(right) ||
                left.expiresAt.localeCompare(right.expiresAt)
            )
    );

    protected readonly canApprove = computed(() =>
        ['OWNER', 'ADMIN'].includes(this.auth.user()?.role ?? '')
    );

    protected readonly today = new Intl.DateTimeFormat('en-IN', {
        weekday: 'long',
        day: 'numeric',
        month: 'long'
    }).format(new Date());

    protected readonly greeting =
        new Date().getHours() < 12
            ? 'Good morning'
            : new Date().getHours() < 17
                ? 'Good afternoon'
                : 'Good evening';

    protected readonly displayName =
        this.auth.user()?.name?.split(' ')[0] || 'there';

    protected readonly quickActions = [
        {
            label: 'Take an order',
            detail: 'Create and queue a customer order',
            route: '/orders',
            icon: '≡'
        },
        {
            label: 'Add stock',
            detail: 'Record a purchase or delivery',
            route: '/inventory',
            icon: '□'
        },
        {
            label: 'Record activity',
            detail: 'Update the kitchen with a simple sentence',
            route: '/activity',
            icon: '+'
        },
        {
            label: 'Upload receipt',
            detail: 'Review a receipt before adding stock',
            route: '/receipts',
            icon: '▣'
        }
    ];

    private readonly onlineHandler = () => {
        this.online.set(true);
        this.loadRecommendations();
    };
    private readonly offlineHandler = () => this.online.set(false);

    ngOnInit(): void {
        window.addEventListener('online', this.onlineHandler);
        window.addEventListener('offline', this.offlineHandler);
        this.loadDashboard();
        this.workspaceApi.current().subscribe({
            next: (workspace) => {
                this.workspace.set(workspace);
                this.loadRecommendations();
            },
            error: (error) => {
                this.recommendationError.set(
                    this.errorMessage(
                        error,
                        'The demand workspace is temporarily unavailable.'
                    )
                );
            }
        });
    }

    ngOnDestroy(): void {
        window.removeEventListener('online', this.onlineHandler);
        window.removeEventListener('offline', this.offlineHandler);
    }

    protected attentionItems(items: StockItem[]): StockItem[] {
        return items.filter((item) => item.status !== 'good').slice(0, 4);
    }

    protected metricRoute(label: string): string {
        if (label === 'Stock items' || label === 'Expiring soon') {
            return '/inventory';
        }
        if (label === "Today's prep") {
            return '/recipes';
        }
        if (label === 'Restock signals') {
            return '/inventory';
        }
        return '/feedback';
    }

    protected generateRecommendations(): void {
        const workspace = this.workspace();
        if (!workspace || this.generating() || !this.online()) {
            return;
        }

        this.generating.set(true);
        this.recommendationError.set(null);
        this.recommendationsApi.generate(workspace).subscribe({
            next: (result) => {
                this.generating.set(false);
                this.recommendations.set(result.recommendations);
                this.calculation.set(result.calculation);
                this.showingCalculation.set(false);
                this.selectRecommendation(result.recommendations[0] ?? null);
            },
            error: (error) => {
                this.generating.set(false);
                this.recommendationError.set(
                    this.errorMessage(
                        error,
                        'Priorities could not be calculated. No action was taken.'
                    )
                );
            }
        });
    }

    protected selectRecommendation(
        recommendation: GovernedRecommendation | null
    ): void {
        this.selected.set(recommendation);
        this.editQuantity.set(recommendation?.proposedQuantity ?? null);
        this.showingCalculation.set(false);
        this.resetOutcomeForm();
        this.resetReverseForm();
        this.explanation.set(null);
        this.explanationError.set(null);
        this.explanationLoading.set(!!recommendation);

        if (!recommendation) {
            this.calculation.set(null);
            this.explanationLoading.set(false);
            return;
        }

        const selectedId = recommendation.id;
        const selectedVersion = recommendation.version;
        this.calculation.set(null);
        this.recommendationsApi.calculation(recommendation.id).subscribe({
            next: (calculation) => {
                if (
                    this.selected()?.id === selectedId &&
                    this.selected()?.version === selectedVersion
                ) {
                    this.calculation.set(calculation);
                }
            },
            error: (error) => {
                if (
                    this.selected()?.id === selectedId &&
                    this.selected()?.version === selectedVersion
                ) {
                    this.recommendationError.set(
                        this.errorMessage(error, 'Calculation evidence is unavailable.')
                    );
                }
            }
        });
        this.recommendationsApi.explanation(recommendation.id).subscribe({
            next: (explanation) => {
                if (
                    this.selected()?.id === selectedId &&
                    this.selected()?.version === selectedVersion
                ) {
                    this.explanation.set(explanation);
                    this.explanationLoading.set(false);
                }
            },
            error: () => {
                if (
                    this.selected()?.id === selectedId &&
                    this.selected()?.version === selectedVersion
                ) {
                    this.explanationLoading.set(false);
                    this.explanationError.set(
                        'The saved explanation is unavailable. Review the calculation evidence before acting.'
                    );
                }
            }
        });
    }

    protected toggleCalculation(): void {
        this.showingCalculation.update((value) => !value);
    }

    protected approve(item: GovernedRecommendation): void {
        this.mutate(
            this.recommendationsApi.approve(
                item,
                'Approved after reviewing the demand calculation.'
            )
        );
    }

    protected saveEditedQuantity(item: GovernedRecommendation): void {
        const quantity = Number(this.editQuantity());
        if (!Number.isFinite(quantity) || quantity <= 0) {
            this.recommendationError.set('Enter a positive quantity.');
            return;
        }

        this.mutate(
            this.recommendationsApi.edit(
                item,
                quantity,
                'Adjusted to the quantity the operator can action.'
            )
        );
    }

    protected dismiss(item: GovernedRecommendation): void {
        this.mutate(
            this.recommendationsApi.dismiss(
                item,
                'Dismissed after reviewing current kitchen conditions.'
            )
        );
    }

    protected flagInventory(item: GovernedRecommendation): void {
        this.mutate(
            this.recommendationsApi.flagInventory(
                item,
                'Physical stock does not match the calculation.'
            )
        );
    }

    protected apply(item: GovernedRecommendation): void {
        this.mutate(
            this.recommendationsApi.apply(
                item,
                'Operator completed the approved action and recorded its stock effect.'
            )
        );
    }

    protected compatibleOutcomeOptions(
        item: GovernedRecommendation
    ): OutcomeOption[] {
        const types: RecommendationOutcomeType[] =
            item.status === 'INVENTORY_FLAGGED'
                ? ['CORRECTED_INVENTORY']
                : COMPATIBLE_OUTCOMES[item.type];
        return types.map((type) => OUTCOME_OPTIONS[type]);
    }

    protected outcomeSourceTypes(item: GovernedRecommendation): string[] {
        const validatedTypes = ['ORDER', 'STOCK_MOVEMENT', 'RECEIPT'];
        if (item.status === 'INVENTORY_FLAGGED') {
            return ['STOCK_MOVEMENT'];
        }
        if (item.appliedActionType) {
            return [item.appliedActionType, ...validatedTypes]
                .filter((type, index, values) => values.indexOf(type) === index);
        }
        return validatedTypes;
    }

    protected selectOutcomeSourceType(type: string): void {
        if (type !== this.outcomeSourceReferenceType()) {
            this.outcomeSourceReferenceId.set('');
        }
        this.outcomeSourceReferenceType.set(type);
    }

    protected selectOutcomeType(type: RecommendationOutcomeType | ''): void {
        this.outcomeType.set(type);
        if (!type || !OUTCOME_OPTIONS[type].requiresQuantity) {
            this.outcomeQuantity.set(null);
            this.outcomeUnit.set('');
        }
    }

    protected beginOutcome(item: GovernedRecommendation): void {
        if (this.syncing() || !this.online()) {
            return;
        }
        this.resetOutcomeForm();
        this.resetReverseForm();
        if (item.status === 'INVENTORY_FLAGGED') {
            this.outcomeSourceReferenceType.set('STOCK_MOVEMENT');
        } else if (
            item.status === 'APPLIED' &&
            item.appliedActionType &&
            item.appliedActionId
        ) {
            this.outcomeSourceReferenceType.set(item.appliedActionType);
            this.outcomeSourceReferenceId.set(item.appliedActionId);
        }
        this.showingOutcomeForm.set(true);
        this.recommendationError.set(null);
    }

    protected cancelOutcome(): void {
        this.resetOutcomeForm();
        this.recommendationError.set(null);
    }

    protected submitOutcome(item: GovernedRecommendation): void {
        if (this.syncing() || !this.online()) {
            return;
        }

        const selectedType = this.outcomeType();
        const option = selectedType
            ? this.compatibleOutcomeOptions(item)
                .find((value) => value.value === selectedType)
            : undefined;
        if (!option) {
            this.recommendationError.set(
                'Select an outcome that matches this recommendation.'
            );
            return;
        }

        let quantity: number | undefined;
        let unit: string | undefined;
        if (option.requiresQuantity) {
            const measuredQuantity = Number(this.outcomeQuantity());
            const measuredUnit = this.outcomeUnit().trim();
            if (!Number.isFinite(measuredQuantity) || measuredQuantity <= 0) {
                this.recommendationError.set(
                    'Enter the positive quantity you actually measured.'
                );
                return;
            }
            if (!measuredUnit) {
                this.recommendationError.set(
                    'Enter the unit used for the measured quantity.'
                );
                return;
            }
            quantity = measuredQuantity;
            unit = measuredUnit;
        }

        const occurredAtInput = this.outcomeOccurredAt().trim();
        const occurredAtMillis = Date.parse(occurredAtInput);
        if (!occurredAtInput || !Number.isFinite(occurredAtMillis)) {
            this.recommendationError.set(
                'Enter when the outcome was actually observed.'
            );
            return;
        }
        if (occurredAtMillis > Date.now()) {
            this.recommendationError.set(
                'The measured outcome time cannot be in the future.'
            );
            return;
        }

        const actionAt = item.status === 'APPLIED'
            ? item.appliedAt
            : item.decidedAt;
        if (actionAt && occurredAtMillis < Date.parse(actionAt)) {
            this.recommendationError.set(
                'The measured outcome time cannot be before the governed action.'
            );
            return;
        }

        const sourceReferenceType =
            this.outcomeSourceReferenceType().trim();
        const sourceReferenceId = this.outcomeSourceReferenceId().trim();
        if (!sourceReferenceType || !sourceReferenceId) {
            this.recommendationError.set(
                'Select an evidence source and enter its reference ID.'
            );
            return;
        }

        const notes = this.outcomeNotes().trim();
        if (!notes) {
            this.recommendationError.set(
                'Add notes describing what was observed.'
            );
            return;
        }

        this.syncing.set(true);
        this.recommendationError.set(null);
        this.recommendationsApi.recordOutcome(item, {
            type: option.value,
            quantity,
            unit,
            sourceReferenceType,
            sourceReferenceId,
            notes,
            occurredAt: new Date(occurredAtMillis).toISOString()
        }).subscribe({
            next: () => {
                this.syncing.set(false);
                this.resetOutcomeForm();
                this.loadRecommendations(item.id);
            },
            error: (error) => {
                this.syncing.set(false);
                this.recommendationError.set(
                    this.errorMessage(
                        error,
                        error.status === 409
                            ? 'This recommendation changed. Refresh it before recording the outcome.'
                            : 'The measured outcome was not recorded. Review the evidence and try again.'
                    )
                );
            }
        });
    }

    protected beginReverse(): void {
        if (this.syncing() || !this.online()) {
            return;
        }
        this.resetOutcomeForm();
        this.resetReverseForm();
        this.showingReverseForm.set(true);
        this.recommendationError.set(null);
    }

    protected cancelReverse(): void {
        this.resetReverseForm();
        this.recommendationError.set(null);
    }

    protected submitReverse(item: GovernedRecommendation): void {
        const reason = this.reverseReason().trim();
        if (!reason) {
            this.recommendationError.set(
                'Explain why this governed recommendation must be reversed.'
            );
            return;
        }

        this.mutate(
            this.recommendationsApi.reverse(item, reason),
            () => this.resetReverseForm()
        );
    }

    protected actionTitle(item: GovernedRecommendation): string {
        const quantity = this.format(item.proposedQuantity);
        const target = this.targetName(item);
        switch (item.type) {
            case 'PURCHASE':
                return `Buy ${quantity} ${item.unit} of ${target}`;
            case 'PREPARE':
                return `Prepare ${quantity} ${item.unit} of ${target}`;
            case 'UTILISE_EXPIRING_STOCK':
                return `Use ${quantity} ${item.unit} of ${target} first`;
            case 'REDUCE_OR_AVOID_PURCHASE':
                return `Avoid buying ${quantity} ${item.unit} of ${target}`;
        }
    }

    protected plainReason(item: GovernedRecommendation): string {
        const evidence = this.ingredientEvidence(item);
        if (!evidence) {
            return item.type === 'PREPARE'
                ? 'Queued orders require this preparation using the pinned recipe version.'
                : 'This action follows the latest verified orders and usable stock.';
        }

        if (item.type === 'PURCHASE') {
            return `Demand plus buffer is ${this.format(
                evidence.grossDemand + evidence.safetyStock
            )} ${evidence.canonicalUnit}, with ${this.format(
                evidence.usableSupply
            )} usable before service.`;
        }

        return `${this.format(evidence.expiryRiskSurplus)} ${evidence.canonicalUnit} is expected to remain when its lots expire.`;
    }

    protected calculationLine(item: GovernedRecommendation): string {
        const evidence = this.ingredientEvidence(item);
        if (evidence) {
            return `Demand ${this.format(evidence.grossDemand)} + buffer ${this.format(
                evidence.safetyStock
            )} − usable supply ${this.format(evidence.usableSupply)} = shortage ${this.format(
                evidence.shortage
            )} ${evidence.canonicalUnit}`;
        }

        const preparation = this.calculation()?.preparations
            .find((value) => value.dishId === item.dishId);
        return preparation
            ? `${this.format(preparation.quantity)} ${preparation.unit} from ${preparation.contributingOrderIds.length} queued order(s), recipe ${preparation.recipeVersionId}.`
            : 'Calculation details are loading.';
    }

    protected evidenceSummary(item: GovernedRecommendation): string {
        const evidence = this.ingredientEvidence(item);
        if (evidence) {
            return `${evidence.contributingOrderIds.length} order(s), ${evidence.contributingRecipeVersionIds.length} recipe version(s), ${evidence.contributingLots.length} usable lot(s), and ${evidence.excludedLots.length} excluded lot(s).`;
        }

        const preparation = this.calculation()?.preparations
            .find((value) => value.dishId === item.dishId);
        return preparation
            ? `${preparation.contributingOrderIds.length} order(s) using pinned recipe ${preparation.recipeVersionId}.`
            : 'Evidence is loading.';
    }

    protected expiryLabel(item: GovernedRecommendation): string {
        return new Intl.DateTimeFormat('en-IN', {
            hour: 'numeric',
            minute: '2-digit'
        }).format(new Date(item.expiresAt));
    }

    protected format(value: number): string {
        return new Intl.NumberFormat('en-IN', {
            maximumFractionDigits: 3
        }).format(value);
    }

    private mutate(
        request: ReturnType<RecommendationsApiService['approve']>,
        onSuccess?: () => void
    ): void {
        if (this.syncing() || !this.online()) {
            return;
        }

        this.syncing.set(true);
        this.recommendationError.set(null);
        request.subscribe({
            next: (updated) => {
                this.syncing.set(false);
                onSuccess?.();
                this.loadRecommendations(updated.id);
            },
            error: (error) => {
                this.syncing.set(false);
                this.recommendationError.set(
                    this.errorMessage(
                        error,
                        error.status === 409
                            ? 'This recommendation changed. Refresh and review it again.'
                            : 'The decision was not saved. No action was taken.'
                    )
                );
            }
        });
    }

    private resetOutcomeForm(): void {
        this.showingOutcomeForm.set(false);
        this.outcomeType.set('');
        this.outcomeQuantity.set(null);
        this.outcomeUnit.set('');
        this.outcomeOccurredAt.set('');
        this.outcomeSourceReferenceType.set('');
        this.outcomeSourceReferenceId.set('');
        this.outcomeNotes.set('');
    }

    private resetReverseForm(): void {
        this.showingReverseForm.set(false);
        this.reverseReason.set('');
    }

    private loadRecommendations(preferredId?: string): void {
        const workspace = this.workspace();
        if (!workspace || !this.online()) {
            return;
        }

        this.recommendationsApi.list(workspace).subscribe({
            next: (recommendations) => {
                this.recommendations.set(recommendations);
                const preferred = recommendations.find(
                    (item) => item.id === preferredId
                );
                this.selectRecommendation(
                    preferred ?? this.attentionRecommendations()[0] ?? null
                );
            },
            error: (error) => this.recommendationError.set(
                this.errorMessage(
                    error,
                    'Saved recommendations could not be loaded.'
                )
            )
        });
    }

    protected ingredientEvidence(
        item: GovernedRecommendation
    ): IngredientDemand | undefined {
        return this.calculation()?.ingredients
            .find((value) => value.ingredientId === item.ingredientId);
    }

    private targetName(item: GovernedRecommendation): string {
        const ingredient = this.ingredientEvidence(item);
        if (ingredient) {
            return ingredient.ingredientName;
        }

        return this.calculation()?.preparations
            .find((value) => value.dishId === item.dishId)
            ?.dishName ?? item.ingredientId ?? item.dishId ?? 'this item';
    }

    private priority(item: GovernedRecommendation): number {
        const statusOrder: Record<string, number> = {
            APPROVED: 0,
            PENDING: 1,
            INVENTORY_FLAGGED: 2,
            APPLIED: 3
        };
        const riskOrder = { HIGH: 0, MEDIUM: 1, LOW: 2 };
        return (statusOrder[item.status] ?? 9) * 10 + riskOrder[item.riskTier];
    }

    private loadDashboard(): void {
        this.dashboardApi.getDashboard().subscribe({
            next: (dashboard) => this.dashboard.set(dashboard),
            error: () => this.error.set(
                'Your kitchen overview is temporarily unavailable.'
            )
        });
    }

    private errorMessage(error: any, fallback: string): string {
        if (error?.status === 0 || !navigator.onLine) {
            return 'You are offline. Nothing was changed; reconnect and try again.';
        }
        return error?.error?.detail ?? error?.error?.message ?? fallback;
    }
}
