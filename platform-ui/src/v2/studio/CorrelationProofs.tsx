import { AlertCircle, AlertTriangle, CheckCircle2, HelpCircle } from 'lucide-react';
import type { CorrelationProof, CorrelationVerdict } from '../api';

/**
 * What the Validator found out about each correlation rule.
 *
 * ## Why this is its own panel rather than a line under the step
 *
 * The step evidence already says "extraction `session` resolved to abc123", and that sentence is
 * exactly what makes the failure invisible. A rule that was accepted but applied to nothing also
 * resolves, and the journey passes, because the literal the rule was meant to replace is usually
 * still valid minutes after it was recorded. The gap only shows up when you ask a different
 * question — *did anything send this value, and was it a fresh one* — and that question is about
 * the journey, not about the step that extracted it.
 *
 * A verdict is therefore stated for every rule, including the good ones. Silence is what the studio
 * used to offer, and silence read as approval.
 */

interface Verdict {
  label: string;
  tone: 'good' | 'warn' | 'bad' | 'muted';
  /** What a person should take from it, in the studio's own voice rather than the API's. */
  meaning: string;
}

const VERDICTS: Record<CorrelationVerdict, Verdict> = {
  proven: {
    label: 'Proven',
    tone: 'good',
    meaning: 'carried a different value from the recording, so the rule demonstrably works',
  },
  used: {
    label: 'Used',
    tone: 'good',
    meaning: 'extracted and sent onwards. There is no recording here to compare it against',
  },
  unproven: {
    label: 'Unproven',
    tone: 'warn',
    meaning:
      'the same value the recording captured. Expected if it never changes — but if it should be ' +
      'different each time, this run would look identical whether the rule worked or not',
  },
  unused: {
    label: 'Not used',
    tone: 'bad',
    meaning:
      'extracted, then sent by nothing. The later steps are still carrying the value from the ' +
      'recording, which will be stale by the time this runs at load',
  },
  'not-extracted': {
    label: 'Not found',
    tone: 'bad',
    meaning: 'nothing matched, so there is no value to carry forward',
  },
};

function VerdictIcon({ tone }: { tone: Verdict['tone'] }) {
  if (tone === 'good') return <CheckCircle2 size={15} className="tone-good" aria-hidden />;
  if (tone === 'warn') return <AlertTriangle size={15} className="tone-warn" aria-hidden />;
  if (tone === 'bad') return <AlertCircle size={15} className="tone-bad" aria-hidden />;
  return <HelpCircle size={15} className="muted" aria-hidden />;
}

export default function CorrelationProofs({ proofs }: { proofs: CorrelationProof[] }) {
  if (proofs.length === 0) {
    return null;
  }

  return (
    <section className="correlation-proofs">
      <h5>Correlations</h5>
      <ul className="rule-evidence">
        {proofs.map((proof) => {
          const verdict = VERDICTS[proof.verdict] ?? {
            label: proof.verdict,
            tone: 'muted' as const,
            meaning: proof.message,
          };
          return (
            <li key={proof.variable}>
              <VerdictIcon tone={verdict.tone} />{' '}
              <strong>
                {verdict.label}: {proof.variable}
              </strong>{' '}
              — taken from <code>{proof.fromStep}</code>, {verdict.meaning}.
              {proof.usedBySteps.length > 0 && (
                <span className="muted"> Sent by {proof.usedBySteps.join(', ')}.</span>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
