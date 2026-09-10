import { TestBed } from '@angular/core/testing';
import { OrdersApiService } from '../../core/api/orders-api.service';
import { OrdersComponent } from './orders.component';

describe('OrdersComponent transition controls', () => {
    let component: any;

    beforeEach(() => {
        const api = jasmine.createSpyObj<OrdersApiService>(
            'OrdersApiService',
            [
                'dishes',
                'search',
                'summary',
                'detail',
                'updateStatus',
                'create'
            ]
        );
        TestBed.configureTestingModule({
            providers: [{ provide: OrdersApiService, useValue: api }]
        });
        component = TestBed.runInInjectionContext(
            () => new OrdersComponent()
        );
    });

    it('does not offer a manual ready transition without production evidence', () => {
        expect(component.nextStatus('QUEUED')).toBe('PREPARING');
        expect(component.nextStatus('PREPARING')).toBeNull();
        expect(component.nextStatus('READY')).toBe('COMPLETED');
        expect(component.nextStatus('COMPLETED')).toBeNull();
        expect(component.nextStatus('CANCELLED')).toBeNull();
    });
});
