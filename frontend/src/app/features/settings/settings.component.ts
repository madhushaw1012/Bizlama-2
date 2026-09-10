import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import {
    SystemApiService,
    WorkspaceStatus
} from '../../core/api/system-api.service';
import {
    WorkspaceApiService,
    WorkspaceContext
} from '../../core/api/workspace-api.service';

@Component({
    selector: 'app-settings',
    imports: [CommonModule],
    templateUrl: './settings.component.html'
})
export class SettingsComponent implements OnInit {
    private readonly api = inject(SystemApiService);
    private readonly workspaceApi = inject(WorkspaceApiService);

    protected readonly status = signal<WorkspaceStatus | null>(null);
    protected readonly workspace = signal<WorkspaceContext | null>(null);
    protected readonly error = signal('');

    ngOnInit(): void {
        this.workspaceApi.current().subscribe({
            next: (value) => this.workspace.set(value),
            error: () => this.error.set('Workspace details are unavailable.')
        });
        this.api.status().subscribe({
            next: (value) => this.status.set(value),
            error: () =>
                this.error.set('Workspace status is unavailable.')
        });
    }
}
