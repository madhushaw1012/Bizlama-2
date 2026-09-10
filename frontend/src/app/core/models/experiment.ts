export type ExperimentStatus = 'PROPOSED' | 'ACTIVE';

export interface Experiment {
    dish: string;
    theme: string;
    themeCount: number;
    feedbackCount: number;
    metricName: string;
    currentValue: number;
    proposedValue: number;
    unit: string;
    testDurationDays: number;
    approvedAt: string | null;
    status: ExperimentStatus;
}
