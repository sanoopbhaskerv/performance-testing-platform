import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import CorrelationProofs from './CorrelationProofs';
import type { CorrelationProof, CorrelationVerdict } from '../api';

function proof(overrides: Partial<CorrelationProof> = {}): CorrelationProof {
  return {
    variable: 'session',
    fromStep: 'login',
    usedBySteps: ['create-order'],
    verdict: 'proven',
    livePreview: 'fresh-token',
    message: 'A different value from the one recorded.',
    ...overrides,
  };
}

describe('CorrelationProofs', () => {
  it('says nothing when a journey has no correlations', () => {
    const { container } = render(<CorrelationProofs proofs={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  /**
   * The whole point of the panel. A rule that was accepted and applied to nothing passes every
   * step, so the only place it can be reported is here — and it has to read as a problem, not as
   * a neutral fact.
   */
  it('names a dead rule and says what the journey is really sending', () => {
    render(
      <CorrelationProofs
        proofs={[proof({ verdict: 'unused', usedBySteps: [] })]}
      />,
    );

    expect(screen.getByText(/Not used: session/)).toBeInTheDocument();
    expect(screen.getByText(/still carrying the value from the recording/)).toBeInTheDocument();
    expect(screen.getByText(/stale/)).toBeInTheDocument();
  });

  it('does not claim a pass when the live value matched the recording', () => {
    render(<CorrelationProofs proofs={[proof({ verdict: 'unproven' })]} />);

    expect(screen.getByText(/Unproven: session/)).toBeInTheDocument();
    expect(
      screen.getByText(/would look identical whether the rule worked or not/),
    ).toBeInTheDocument();
  });

  it('reports a proven rule with the steps that sent it', () => {
    render(
      <CorrelationProofs
        proofs={[proof({ usedBySteps: ['create-order', 'confirm'] })]}
      />,
    );

    expect(screen.getByText(/Proven: session/)).toBeInTheDocument();
    expect(screen.getByText(/Sent by create-order, confirm\./)).toBeInTheDocument();
  });

  it('stops at "used" when there was no recording to compare against', () => {
    render(<CorrelationProofs proofs={[proof({ verdict: 'used' })]} />);

    expect(screen.getByText(/Used: session/)).toBeInTheDocument();
    expect(screen.getByText(/no recording here to compare it against/)).toBeInTheDocument();
  });

  it('states a verdict for every rule, including the good ones', () => {
    const verdicts: CorrelationVerdict[] = [
      'proven',
      'unproven',
      'used',
      'unused',
      'not-extracted',
    ];
    render(
      <CorrelationProofs
        proofs={verdicts.map((verdict, index) =>
          proof({ verdict, variable: `v${index}` }),
        )}
      />,
    );

    expect(screen.getAllByRole('listitem')).toHaveLength(verdicts.length);
  });

  /** A verdict this build does not know about must still appear, not vanish. */
  it('falls back to the server message for an unrecognised verdict', () => {
    render(
      <CorrelationProofs
        proofs={[
          proof({
            verdict: 'something-new' as CorrelationVerdict,
            message: 'A verdict from a newer control plane.',
          }),
        ]}
      />,
    );

    expect(screen.getByText(/A verdict from a newer control plane/)).toBeInTheDocument();
  });
});
