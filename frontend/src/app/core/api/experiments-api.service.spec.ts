import { provideHttpClient } from '@angular/common/http';
import {
    HttpTestingController,
    provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Experiment } from '../models/experiment';
import { ExperimentsApiService } from './experiments-api.service';

describe('ExperimentsApiService', () => {
    let service: ExperimentsApiService;
    let http: HttpTestingController;

    const experiment: Experiment = {
        dish: 'Seasonal Tart',
        theme: 'Too sweet',
        themeCount: 3,
        feedbackCount: 5,
        metricName: 'Sugar',
        currentValue: 20,
        proposedValue: 16,
        unit: 'g',
        testDurationDays: 7,
        approvedAt: null,
        status: 'PROPOSED'
    };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                ExperimentsApiService,
                provideHttpClient(),
                provideHttpClientTesting()
            ]
        });

        service = TestBed.inject(ExperimentsApiService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
        http.verify();
    });

    it('loads an experiment using the encoded dish reference', () => {
        service.getExperiment('seasonal/tart').subscribe((value) => {
            expect(value).toEqual(experiment);
        });

        const request = http.expectOne(
            '/api/experiments/seasonal%2Ftart'
        );
        expect(request.request.method).toBe('GET');
        request.flush(experiment);
    });

    it('approves the experiment selected by dish reference', () => {
        service.approveExperiment('seasonal tart').subscribe((value) => {
            expect(value).toEqual(experiment);
        });

        const request = http.expectOne(
            '/api/experiments/seasonal%20tart/approve'
        );
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({});
        request.flush(experiment);
    });
});
