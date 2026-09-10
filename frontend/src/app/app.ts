import { Component, computed, inject, signal } from '@angular/core';
import {
    Router,
    RouterLink,
    RouterLinkActive,
    RouterOutlet
} from '@angular/router';
import { AuthService } from './core/auth/auth.service';

@Component({
    selector: 'app-root',
    imports: [RouterLink, RouterLinkActive, RouterOutlet],
    templateUrl: './app.html'
})
export class App {
    protected readonly auth = inject(AuthService);
    private readonly router = inject(Router);
    protected readonly sidebarCollapsed = signal(false);
    protected readonly mobileNavigationOpen = signal(false);
    protected readonly workspaceLabel = computed(() => {
        const name = this.auth.user()?.name?.trim();
        return name ? `${name}'s kitchen` : 'Kitchen workspace';
    });

    protected toggleSidebar(): void {
        this.sidebarCollapsed.update((collapsed) => !collapsed);
    }

    protected toggleMobileNavigation(): void {
        this.mobileNavigationOpen.update((open) => !open);
    }

    protected closeMobileNavigation(): void {
        this.mobileNavigationOpen.set(false);
    }

    protected logout(): void {
        this.auth.logout();
        void this.router.navigate(['/login']);
    }
}
