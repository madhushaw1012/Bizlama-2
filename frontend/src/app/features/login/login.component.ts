import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
    selector: 'app-login',
    imports: [CommonModule, FormsModule],
    templateUrl: './login.component.html'
})
export class LoginComponent {
    private readonly auth = inject(AuthService);
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);

    protected readonly email = signal('owner@bizlama.local');
    protected readonly password = signal('bizlama-demo');
    protected readonly busy = signal(false);
    protected readonly error = signal('');
    protected readonly showPassword = signal(false);

    constructor() {
        void this.auth.initialize().then(() => {
            if (this.auth.config()?.mode === 'identity-platform') {
                this.email.set('');
                this.password.set('');
            }

            if (this.auth.authenticated()) {
                void this.router.navigateByUrl('/dashboard');
            }
        });
    }

    protected signIn(): void {
        if (!this.email().trim() || !this.password()) {
            return;
        }

        this.busy.set(true);
        this.error.set('');

        this.auth.login(this.email().trim(), this.password()).subscribe({
            next: () => {
                void this.router.navigateByUrl(
                    this.route.snapshot.queryParamMap.get('returnUrl') || '/dashboard'
                );
            },
            error: (response) => {
                this.busy.set(false);
                this.error.set(
                    response.error?.detail ??
                    response.error?.error?.message ??
                    response.message ??
                    'Could not sign in.'
                );
            }
        });
    }

    protected togglePasswordVisibility(): void {
        this.showPassword.update((value) => !value);
    }
}