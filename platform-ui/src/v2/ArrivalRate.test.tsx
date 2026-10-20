import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { RunReport as Report, V2Client, WorkloadProfile } from './api';
import RunReport from './RunReport';
import RunProgressBar from './RunProgressBar';
import { summarize } from './narrative';
import { ObjectivesStep, ReviewStep } from './studio/StepScreens';
import { newDraft } from './studio/draft';

const workload = {
  name: 'rate-load', type: 'PROTOCOL', modelType: 'ARRIVAL_RATE', peakUsers: 20,
  maxConcurrency: 20, peakArrivalRate: 100, totalDurationSeconds: 80,
  stages: [
    { durationSeconds: 10, targetUsers: 0, targetRate: 100 },
    { durationSeconds: 60, targetUsers: 0, targetRate: 100 },
    { durationSeconds: 10, targetUsers: 0, targetRate: 0 },
  ], scenarios: [],
} as WorkloadProfile;

function report(): Report {
  return {
    run: {} as Report['run'], synthetic: false, syntheticNotice: null,
    test: { name: 'rate-demo', application: 'demo', versionNumber: 1, digest: 'sha256:test', workloads: [workload] },
    execution: null, objectives: [], summary: { passed: 0, breached: 0, indeterminate: 0, demonstrated: false },
    measurements: null, timeline: [], artifacts: [],
  };
}

it('quotes rates in the stage profile without presenting a concurrency ceiling as fixed users', () => {
  render(<RunReport client={{} as V2Client} report={report()} onError={vi.fn()} />);
  expect(screen.getByText(/100 requests per second at peak/)).toHaveTextContent('20 requests in flight');
  expect(screen.getByRole('table', { name: /Requested rate at each stage/ })).toHaveTextContent('Requests per second');
  expect(screen.queryByText(/expected users at peak/)).not.toBeInTheDocument();
});

it('uses rate targets to identify a steady stage and label live plan progress', () => {
  const run = { status: 'RUNNING', startedAt: new Date(Date.now() - 30_000).toISOString() } as Report['run'];
  render(<RunProgressBar run={run} report={report()} />);
  expect(screen.getByText('Holding steady')).toBeInTheDocument();
  expect(screen.getByText(/100 requests\/second/)).toBeInTheDocument();
  expect(screen.queryByText(/0 users/)).not.toBeInTheDocument();
});

it('reviews the requested rate and ceiling, and labels throughput as a minimum', () => {
  const draft = newDraft('api-load', 'demo');
  draft.load = { ...draft.load, model: 'rate', peakRate: 100, maxConcurrency: 20 };
  draft.objectives = [{ metric: 'http.throughput', percentile: null, journey: null, value: 85 }];
  const { unmount } = render(<ReviewStep client={{} as V2Client} draft={draft} onChange={vi.fn()} onSave={vi.fn()} saving={false} />);
  expect(screen.getByText(/100 requests per second/)).toHaveTextContent('20 requests in flight');
  unmount();
  render(<ObjectivesStep draft={draft} onChange={vi.fn()} />);
  expect(screen.getByLabelText('At least (per second)')).toHaveValue(85);
});

it('describes the configured rate without claiming all twenty users were observed', () => {
  const data = report();
  data.measurements = { engine: 'jmeter', complete: true, shardsExpected: 1, shardsReported: 1,
    overall: { sampleCount: 7000, errorRatePercent: 0, p95Millis: 5 }, operations: [] } as unknown as Report['measurements'];
  expect(summarize(data).detail.join(' ')).toContain('targeting up to 100 requests per second');
  expect(summarize(data).detail.join(' ')).toContain('at most 20 requests in flight');
});
