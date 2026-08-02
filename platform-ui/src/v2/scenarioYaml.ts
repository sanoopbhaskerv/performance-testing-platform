import type { ImportedScenario } from './api';

/**
 * Turns imported scenarios into the YAML a specification uses, and puts them into a document.
 *
 * A module of pure functions rather than logic inside a component, so the two things worth reasoning
 * about carefully — how a value is escaped, and where the replacement lands — are readable on their
 * own. Scoped to exactly this and nothing else; a shared module with no stated boundary is how a
 * `utils` dumping ground starts (CLAUDE.md §8).
 *
 * ## The splice never guesses
 *
 * Replacing part of a YAML document by manipulating text is only safe when the target is
 * unambiguous, so this refuses unless the document has exactly one `scenarios:` key. Anything else —
 * two workloads, no workload, a `scenarios:` written inline — returns a refusal with a reason, and
 * the caller falls back to showing the snippet for a person to place themselves.
 *
 * The caller then validates the spliced document against the server before accepting it. That is the
 * real safety net: the platform already owns a validator, and an edit that round-trips through it is
 * proven rather than assumed.
 */

/** One `scenarios:` key on its own line, capturing its indentation. */
const SCENARIOS_KEY = /^(\s*)scenarios:\s*$/;

export interface SpliceResult {
  /** The document with its journeys replaced, or null when nothing was changed. */
  document: string | null;
  /** Why it was not applied, in the consumer's language. Null on success. */
  reason: string | null;
}

/**
 * A YAML double-quoted scalar.
 *
 * Always quoted, never bare. An imported value is whatever a source contained — a path with a
 * colon, a header value starting with `*`, a JSON body full of braces — and deciding case by case
 * which of those need quoting is exactly the kind of judgement that is wrong once and then wrong
 * silently.
 */
function quote(value: string): string {
  const escaped = value
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n')
    .replace(/\t/g, '\\t');
  return `"${escaped}"`;
}

function mapLines(name: string, entries: Record<string, string>, pad: string): string[] {
  const keys = Object.keys(entries);
  if (keys.length === 0) return [];
  return [`${pad}${name}:`, ...keys.map((key) => `${pad}  ${quote(key)}: ${quote(entries[key])}`)];
}

/**
 * The scenarios as YAML list items, indented to sit under a `scenarios:` key.
 *
 * `operationId` is carried through whenever the source had one. The platform prefers an explicit
 * path when both are present, so it changes nothing about what runs — it is what lets a report be
 * matched back to the operation in the document it came from (CLAUDE.md §23).
 */
export function scenariosToYaml(scenarios: ImportedScenario[], indent: number): string {
  const pad = ' '.repeat(indent);
  const field = `${pad}    `;

  return scenarios
    .map((scenario) => {
      const lines = [
        `${pad}  - name: ${quote(scenario.name)}`,
        `${field}weight: ${scenario.weight}`,
      ];
      if (scenario.operationId) lines.push(`${field}operationId: ${quote(scenario.operationId)}`);
      lines.push(`${field}method: ${scenario.method}`);
      lines.push(`${field}path: ${quote(scenario.path)}`);
      lines.push(...mapLines('headers', scenario.headers, field));
      lines.push(...mapLines('query', scenario.query, field));
      if (scenario.body) {
        lines.push(`${field}body:`);
        lines.push(`${field}  contentType: ${quote(scenario.body.contentType)}`);
        lines.push(`${field}  content: ${quote(scenario.body.content)}`);
      }
      return lines.join('\n');
    })
    .join('\n');
}

/**
 * Replaces the journeys in a document with the imported ones.
 *
 * Replaces rather than appends: an import describes a whole set of journeys, and adding them to a
 * template's placeholder journeys would produce a test that runs both — half of it against endpoints
 * nobody imported.
 */
export function spliceScenarios(document: string, scenarios: ImportedScenario[]): SpliceResult {
  if (scenarios.length === 0) {
    return { document: null, reason: 'There are no journeys to insert.' };
  }

  const lines = document.split('\n');
  const matches: number[] = [];
  lines.forEach((line, index) => {
    if (SCENARIOS_KEY.test(line)) matches.push(index);
  });

  if (matches.length === 0) {
    return {
      document: null,
      reason:
        'This test has no journeys section to replace. Copy the journeys below and paste them ' +
        'under a workload’s "scenarios:".',
    };
  }
  if (matches.length > 1) {
    return {
      document: null,
      reason:
        `This test has ${matches.length} journey sections, so there is no single right place to ` +
        'put these. Copy the journeys below and paste them into the one you mean.',
    };
  }

  const start = matches[0];
  const keyIndent = (SCENARIOS_KEY.exec(lines[start]) as RegExpExecArray)[1].length;

  // The block runs to the first later line that is neither blank nor more indented than the key.
  let end = start + 1;
  while (end < lines.length) {
    const line = lines[end];
    if (line.trim() !== '' && indentOf(line) <= keyIndent) break;
    end++;
  }
  // Blank lines at the end belong to whatever follows, not to the block being replaced.
  while (end > start + 1 && lines[end - 1].trim() === '') end--;

  const replacement = [
    `${' '.repeat(keyIndent)}scenarios:`,
    ...scenariosToYaml(scenarios, keyIndent).split('\n'),
  ];

  return {
    document: [...lines.slice(0, start), ...replacement, ...lines.slice(end)].join('\n'),
    reason: null,
  };
}

function indentOf(line: string): number {
  return line.length - line.replace(/^\s*/, '').length;
}
