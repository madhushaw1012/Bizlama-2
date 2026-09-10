import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DashboardApiService } from '../../core/api/dashboard-api.service';
import { KitchenEventsApiService } from '../../core/api/kitchen-events-api.service';
import { RecentEvent } from '../../core/models/dashboard';
import {
    ParsedKitchenEvent,
    ParseKitchenEventResponse
} from '../../core/models/kitchen-event';

@Component({
    selector: 'app-activity',
    imports: [CommonModule, FormsModule],
    templateUrl: './activity.component.html'
})
export class ActivityComponent implements OnInit {
    private readonly eventsApi = inject(KitchenEventsApiService);
    private readonly dashboardApi = inject(DashboardApiService);

    protected readonly statement = signal('');
    protected readonly parsedEvents = signal<ParsedKitchenEvent[]>([]);
    protected readonly proposal = signal<ParseKitchenEventResponse | null>(null);
    protected readonly recentEvents = signal<RecentEvent[]>([]);
    protected readonly error = signal<string | null>(null);
    protected readonly success = signal<string | null>(null);
    protected readonly isProcessing = signal(false);
    protected readonly isListening = signal(false);
    protected readonly voiceSupported =
        typeof window !== 'undefined' &&
        !!(
            (window as any).SpeechRecognition ||
            (window as any).webkitSpeechRecognition
        );

    ngOnInit(): void {
        this.loadRecentEvents();
    }

    protected parseEvent(): void {
        const statement = this.statement().trim();

        if (!statement) {
            return;
        }

        if (this.proposal()) {
            this.error.set('Discard the current review before parsing another update.');
            return;
        }

        this.error.set(null);
        this.success.set(null);
        this.parsedEvents.set([]);
        this.isProcessing.set(true);

        this.eventsApi.parse(statement).subscribe({
            next: (response) => {
                if (!response.requiresConfirmation || !response.proposalId) {
                    this.error.set(
                        response.clarification ??
                        'Clarify the activity and try again.'
                    );
                    this.isProcessing.set(false);
                    return;
                }
                this.proposal.set(response);
                this.parsedEvents.set(response.events);
                this.confirmationIdempotencyKey =
                    `kitchen-event:${response.proposalId}:${crypto.randomUUID()}`;
                this.isProcessing.set(false);
            },
            error: (response) => {
                this.error.set(
                    response.error?.detail ??
                    'BizLaMa could not understand that update.'
                );
                this.isProcessing.set(false);
            }
        });
    }

    protected confirmEvent(): void {
        const proposal = this.proposal();
        const events = this.parsedEvents();

        if (!proposal || !proposal.proposalId || !events.length) {
            return;
        }

        this.error.set(null);
        this.isProcessing.set(true);
        this.confirmationIdempotencyKey ??=
            `kitchen-event:${proposal.proposalId}:${crypto.randomUUID()}`;

        this.eventsApi.confirm({
            proposalId: proposal.proposalId,
            expectedVersion: proposal.version,
            idempotencyKey: this.confirmationIdempotencyKey
        }).subscribe({
            next: () => {
                this.success.set(
                    `${events.length} ${events.length === 1 ? 'update' : 'updates'
                    } confirmed and saved.`
                );
                this.clearProposal();
                this.statement.set('');
                this.isProcessing.set(false);
                this.loadRecentEvents();
            },
            error: (response) => {
                const terminal = response.status === 409 || response.status === 410;
                if (terminal) {
                    this.clearProposal();
                }
                this.error.set(
                    response.error?.detail ??
                    (terminal
                        ? 'That proposal is no longer current. Review the update again.'
                        : 'BizLaMa could not save that update. Retry will use the same confirmation key.')
                );
                this.isProcessing.set(false);
            }
        });
    }

    protected discardEvent(): void {
        const proposal = this.proposal();

        if (!proposal || !proposal.proposalId) {
            return;
        }

        this.error.set(null);
        this.isProcessing.set(true);
        this.eventsApi.supersede(
            proposal.proposalId,
            proposal.version
        ).subscribe({
            next: () => {
                this.clearProposal();
                this.isProcessing.set(false);
            },
            error: (response) => {
                if (response.status === 409 || response.status === 410) {
                    this.clearProposal();
                }
                this.error.set(
                    response.error?.detail ??
                    'BizLaMa could not discard that review. Please try again.'
                );
                this.isProcessing.set(false);
            }
        });
    }

    protected startVoiceEntry(): void {
        if (this.proposal()) {
            this.error.set('Discard the current review before dictating another update.');
            return;
        }

        const Recognition =
            (window as any).SpeechRecognition ||
            (window as any).webkitSpeechRecognition;

        if (!Recognition) {
            this.error.set(
                'Voice input is not supported in this browser. You can still type the update.'
            );
            return;
        }

        const recognition = new Recognition();

        recognition.lang = 'en-IN';
        recognition.interimResults = false;
        recognition.maxAlternatives = 1;

        recognition.onstart = () => {
            this.isListening.set(true);
        };

        recognition.onend = () => {
            this.isListening.set(false);
        };

        recognition.onerror = (event: any) => {
            this.isListening.set(false);
            this.error.set(
                event.error === 'not-allowed'
                    ? 'Microphone access is blocked. Allow microphone access in the browser, then try again.'
                    : 'I could not hear that clearly. Please try again or type the update.'
            );
        };

        recognition.onresult = (event: any) => {
            this.statement.set(event.results[0][0].transcript);
            this.error.set(null);
            this.success.set(
                'Voice captured. Check the words, then review the update.'
            );
        };

        recognition.start();
    }

    private confirmationIdempotencyKey: string | null = null;

    private clearProposal(): void {
        this.proposal.set(null);
        this.parsedEvents.set([]);
        this.confirmationIdempotencyKey = null;
    }

    private loadRecentEvents(): void {
        this.dashboardApi.getDashboard().subscribe({
            next: (dashboard) => {
                this.recentEvents.set(dashboard.recentEvents);
            },
            error: () => {
                this.error.set('BizLaMa could not load recent activity.');
            }
        });
    }
}