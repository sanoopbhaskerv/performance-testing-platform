import type { OperationMetrics } from './api';
import { objectiveLabel } from './labels';

/** Page measurements retain their own units and never join journey latency averages. */
export default function WebMetricsTable({ metrics }: { metrics: OperationMetrics[] }) {
  if (metrics.length === 0) return null;
  const display = (name: string, value: number) => name.startsWith('web.cls@')
    ? (value / 1000).toFixed(3) + ' score'
    : name.startsWith('web.lighthouse.score@') ? value.toFixed(0) + '%' : value.toFixed(0) + ' ms';
  return <>
    <h3>Page experience</h3>
    <p className="muted">Measurements from the final open page of each journey. Missing measurements stay unavailable; layout shift is a score, separate from timing.</p>
    <div className="table-scroll"><table className="metrics">
      <caption className="sr-only">Page measurements with their original units</caption>
      <thead><tr><th scope="col">Measure</th><th scope="col">Samples</th><th scope="col">Median</th><th scope="col">95th percentile</th></tr></thead>
      <tbody>{metrics.map(operation => {
        const [metric, scope] = operation.name.split('@');
        return <tr key={operation.name}>
          <th scope="row">{objectiveLabel(metric, scope ?? 'Whole test')}</th>
          <td className="numeric">{operation.sampleCount}</td>
          <td className="numeric">{display(operation.name, operation.p50Millis)}</td>
          <td className="numeric">{display(operation.name, operation.p95Millis)}</td>
        </tr>;
      })}</tbody>
    </table></div>
  </>;
}
