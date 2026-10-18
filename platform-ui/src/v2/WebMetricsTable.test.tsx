import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import type { OperationMetrics } from './api';
import WebMetricsTable from './WebMetricsTable';

it('renders layout shift as a score and navigation time in milliseconds', () => {
  const metric = (name: string, value: number) => ({ name, sampleCount: 4, p50Millis: value, p95Millis: value } as OperationMetrics);
  render(<WebMetricsTable metrics={[metric('web.cls@checkout', 95), metric('web.ttfb@checkout', 128)]} />);
  expect(screen.getAllByText('0.095 score')).toHaveLength(2);
  expect(screen.getAllByText('128 ms')).toHaveLength(2);
  expect(screen.queryByText('95 ms')).not.toBeInTheDocument();
});
