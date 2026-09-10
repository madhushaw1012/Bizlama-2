import { provideHttpClient } from '@angular/common/http';
import {
    HttpTestingController,
    provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService Identity Platform roles', () => {
    let service: AuthService;
    let http: HttpTestingController;

    beforeEach(() => {
        sessionStorage.clear();
        TestBed.configureTestingModule({
            providers: [
                AuthService,
                provideHttpClient(),
                provideHttpClientTesting()
            ]
        });
        service = TestBed.inject(AuthService);
        http = TestBed.inject(HttpTestingController);
        service.config.set({
            mode: 'identity-platform',
            projectId: 'bizlama',
            identityApiKey: 'test-key'
        });
    });

    afterEach(() => {
        http.verify();
        sessionStorage.clear();
    });

    it('uses the database role for a token without a role claim', () => {
        loginWithEffectiveRole({}, 'KITCHEN_OPERATOR');

        expect(service.user()?.role).toBe('KITCHEN_OPERATOR');
    });

    it('uses a downgraded database role over an elevated token claim', () => {
        loginWithEffectiveRole({ role: 'OWNER' }, 'VIEWER');

        expect(service.user()?.role).toBe('VIEWER');
    });

    it('uses a neutral role when the server returns an unknown role', () => {
        loginWithEffectiveRole({}, 'SUPERUSER');

        expect(service.user()?.role).toBe('UNASSIGNED');
    });

    it('does not trust a previously stored OWNER display value', () => {
        sessionStorage.setItem('bizlama.session', JSON.stringify({
            accessToken: jwt({}),
            expiresAt: new Date(Date.now() + 60_000).toISOString(),
            user: {
                email: 'cook@example.test',
                name: 'Cook',
                role: 'OWNER'
            }
        }));

        const restored = (service as any).restoreSession();

        expect(restored.user.role).toBe('UNASSIGNED');
    });

    function loginWithEffectiveRole(
        tokenClaims: Record<string, unknown>,
        effectiveRole: string
    ): void {
        const idToken = jwt(tokenClaims);

        service.login('cook@example.test', 'secret').subscribe();

        const identityRequest = http.expectOne(
            'https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=test-key'
        );
        expect(identityRequest.request.method).toBe('POST');
        identityRequest.flush({
            idToken,
            email: 'cook@example.test',
            displayName: 'Cook',
            expiresIn: '3600'
        });

        const profileRequest = http.expectOne('/api/auth/me');
        expect(profileRequest.request.method).toBe('GET');
        expect(profileRequest.request.headers.get('Authorization'))
            .toBe(`Bearer ${idToken}`);
        profileRequest.flush({
            email: 'database-user@example.test',
            name: 'Database user',
            role: effectiveRole
        });
    }

    function jwt(claims: Record<string, unknown>): string {
        const payload = btoa(JSON.stringify(claims))
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=+$/, '');
        return `header.${payload}.signature`;
    }
});
