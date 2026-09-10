import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = async (_, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);

    await auth.initialize();

    return auth.authenticated()
        ? true
        : router.createUrlTree(['/login'], {
            queryParams: {
                returnUrl: state.url
            }
        });
};