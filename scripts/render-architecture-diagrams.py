#!/usr/bin/env python3
"""Render portable architecture SVGs with no external renderer or network dependency.

Keep labels/flows aligned with docs/architecture/system-overview.md when architecture changes.
Coordinates are explicit so exported diagrams retain a reviewable, stable layout.
"""
from html import escape
from pathlib import Path

OUTPUT = Path(__file__).resolve().parents[1] / 'docs' / 'images'


class Diagram:
    def __init__(self, title, description, width, height):
        self.parts = [f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" style="max-width:100%;height:auto" viewBox="0 0 {width} {height}" role="img" aria-labelledby="title description">
<title id="title">{escape(title)}</title><desc id="description">{escape(description)}</desc>
<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#475569"/></marker></defs>
<style>text{{font-family:Arial,Helvetica,sans-serif;fill:#0f172a}}.title{{font-size:28px;font-weight:bold}}.label{{font-size:17px}}.small{{font-size:15px;fill:#475569}}.heading{{font-size:19px;font-weight:bold}}.line{{stroke:#475569;stroke-width:2;fill:none}}</style>
<rect width="100%" height="100%" fill="#ffffff"/>''']
        self.text(32, 45, title, 'title')

    def text(self, x, y, text, kind='label', anchor='start'):
        self.parts.append(f'<text x="{x}" y="{y}" class="{kind}" text-anchor="{anchor}">{escape(text)}</text>')

    def box(self, x, y, width, height, title, lines=(), fill='#eff6ff'):
        self.parts.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="12" fill="{fill}" stroke="#94a3b8" stroke-width="1.5"/>')
        self.text(x + width / 2, y + 30, title, 'heading', 'middle')
        for index, line in enumerate(lines):
            self.text(x + width / 2, y + 56 + index * 24, line, 'label', 'middle')

    def cluster(self, x, y, width, height, title):
        self.parts.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="16" fill="#f8fafc" stroke="#64748b" stroke-width="1.5" stroke-dasharray="7 5"/>')
        self.text(x + 20, y + 29, title, 'heading')

    def arrow(self, points, label='', label_at=None, dashed=False, both=False):
        data = ' '.join(f'{x},{y}' for x, y in points)
        start = ' marker-start="url(#arrow)"' if both else ''
        dash = ' stroke-dasharray="6 5"' if dashed else ''
        self.parts.append(f'<polyline points="{data}" class="line" marker-end="url(#arrow)"{start}{dash}/>')
        if label and label_at:
            self.text(*label_at, label, 'small', 'middle')

    def save(self, name):
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / name).write_text('\n'.join(self.parts) + '\n</svg>\n')


def architecture():
    d = Diagram('Standalone system architecture', 'Consumers reach one modular control plane. PostgreSQL stores durable state; S3 stores evidence. Isolated Kubernetes Jobs generate the material load.', 1400, 880)
    d.box(250, 75, 350, 78, 'Browser consumer', ['Self-service console'])
    d.box(800, 75, 350, 78, 'CI or AI editor', ['Python CLI / REST / YAML'])
    d.box(400, 195, 600, 65, 'Company ingress and TLS')
    d.arrow([(425, 153), (425, 175), (550, 175), (550, 195)])
    d.arrow([(975, 153), (975, 175), (850, 175), (850, 195)])
    d.cluster(330, 295, 740, 190, 'Control-plane namespace')
    d.box(370, 340, 660, 100, 'One React + Spring Boot application',
          ['Authorization, validation, queue, reconciliation, analysis', 'Helm / ConfigMaps / existing operator Secrets'])
    d.arrow([(700, 260), (700, 340)])
    d.box(30, 565, 310, 110, 'Company PostgreSQL', ['Definitions, versions, runs, events', 'Leases, objectives and summaries'], '#f0fdf4')
    d.box(1060, 565, 310, 110, 'Private S3-compatible store', ['Plans, sources, datasets, results', 'Reports, traces, logs, progress'], '#f0fdf4')
    d.cluster(400, 540, 600, 170, 'Dedicated runner namespace')
    d.box(425, 585, 260, 80, 'JMeter Job', ['Bounded protocol load'])
    d.box(715, 585, 260, 80, 'Playwright Job', ['Real Chromium journeys'])
    d.text(700, 693, 'Ephemeral workspace · execute once · upload evidence', 'small', 'middle')
    d.arrow([(370, 390), (185, 390), (185, 565)], 'Durable state', (185, 525), both=True)
    d.arrow([(1030, 390), (1215, 390), (1215, 565)], 'Inputs and evidence', (1215, 525), both=True)
    d.arrow([(700, 440), (700, 540)], 'Kubernetes API: create / inspect / cancel', (700, 515))
    d.arrow([(1000, 625), (1060, 625)], both=True)
    d.box(475, 785, 450, 65, 'Approved application under test', fill='#fff7ed')
    d.arrow([(555, 710), (555, 785)])
    d.arrow([(845, 710), (845, 785)])
    d.text(700, 753, 'Actual traffic carries a run correlation ID', 'small', 'middle')
    d.save('system-architecture.svg')


def placement():
    d = Diagram('Technology placement', 'The workstation hosts the browser or CLI. The Java modular monolith hosts API, domain, orchestration and adapters. Separate JMeter and Playwright Pods execute workloads.', 1400, 920)
    d.cluster(30, 100, 300, 600, 'Consumer workstation')
    d.box(50, 160, 260, 140, 'Browser', ['React 19 + TypeScript', 'Vite-built static assets', 'Served by the control plane'])
    d.box(50, 360, 260, 140, 'AI editor / CI', ['Python 3 standard-library CLI', 'YAML / JSON specifications', 'Same authorized REST API'])
    d.cluster(390, 100, 580, 650, 'Control-plane Pod · platform-core composition root')
    d.box(420, 155, 520, 105, 'API and security', ['Spring Boot / Web / Security', 'Versioned DTOs · RFC 7807 · OpenAPI'])
    d.box(420, 295, 520, 105, 'Domain and engine SPI', ['Plain Java · engine-neutral specification', 'Immutable identifiers, versions and digests'])
    d.box(420, 435, 520, 105, 'Orchestration and results', ['Leases · state machine · reconciliation', 'Normalized measurements and objectives'])
    d.box(420, 575, 520, 105, 'Infrastructure adapters', ['Spring Data JPA / Flyway', 'S3 SDK / STS · fabric8 Kubernetes client'])
    for y in [260, 400, 540]:
        d.arrow([(680, y), (680, y + 35)])
    d.text(680, 725, 'Java 17 target · JDK 21 verified build', 'small', 'middle')
    d.arrow([(330, 230), (390, 230)])
    d.arrow([(330, 430), (360, 430), (360, 250), (390, 250)])
    d.cluster(1030, 100, 340, 600, 'Separate runner Pods')
    d.box(1050, 160, 300, 140, 'JMeter 5.6.3', ['JVM + Custom Thread Groups', 'Staged users / arrival rate', 'JTL protocol measurements'])
    d.box(1050, 360, 300, 140, 'Playwright 1.62.1', ['Node.js + Chromium', 'Browser / action / page metrics', 'Reports and failure evidence'])
    d.text(1200, 565, 'Dedicated service account', 'label', 'middle')
    d.text(1200, 595, 'Bounded CPU / memory / deadline', 'label', 'middle')
    d.text(1200, 625, 'No automatic material-load retry', 'label', 'middle')
    d.arrow([(970, 555), (1030, 555)])
    d.box(390, 800, 980, 90, 'Operator-owned durable services', ['PostgreSQL for state · private S3-compatible storage for artifacts'], '#f0fdf4')
    d.arrow([(680, 750), (680, 800)])
    d.arrow([(1200, 700), (1200, 800)], both=True)
    d.save('technology-placement.svg')


def sequence():
    d = Diagram('One run: definition to verified report', 'The API persists immutable versions and idempotent requests. The reconciler leases work, creates a single Job, reads its evidence and persists the result. Consumers read authorized reports.', 1600, 1070)
    positions = {'Consumer': 100, 'API': 330, 'PostgreSQL': 560, 'Reconciler': 790,
                 'Runner Job': 1020, 'Object store': 1250, 'Target': 1480}
    for name, x in positions.items():
        d.box(x - 85, 80, 170, 55, name)
        d.parts.append(f'<line x1="{x}" y1="135" x2="{x}" y2="1010" stroke="#cbd5e1" stroke-dasharray="6 5"/>')
    steps = [
        ('Consumer', 'API', 'Validate and save', False),
        ('API', 'PostgreSQL', 'Immutable version + digest', False),
        ('Consumer', 'API', 'Trigger + idempotency key', False),
        ('API', 'PostgreSQL', 'QUEUED + event', False),
        ('API', 'Consumer', '202 Accepted', True),
        ('Reconciler', 'PostgreSQL', 'Lease + admission', False),
        ('Reconciler', 'Object store', 'Compiled plan + digest', False),
        ('Reconciler', 'Runner Job', 'Create Job; persist UID', False),
        ('Runner Job', 'Object store', 'Read approved inputs', False),
        ('Runner Job', 'Target', 'Bounded workload + correlation ID', False),
        ('Runner Job', 'Object store', 'Progress + final evidence', False),
        ('Reconciler', 'Object store', 'Verify identities and read results', False),
        ('Reconciler', 'PostgreSQL', 'Objectives + terminal event', False),
        ('Consumer', 'API', 'Read report / download', False),
        ('API', 'Consumer', 'Authorized, verified results', True),
    ]
    for index, (source, target, label, dashed) in enumerate(steps):
        y = 185 + index * 55
        start, end = positions[source], positions[target]
        d.arrow([(start, y), (end, y)], label, ((start + end) / 2, y - 12), dashed=dashed)
    d.text(32, 1040, 'Runner Jobs receive no database credentials or control-plane API token. Durable state survives API restarts.', 'small')
    d.save('run-sequence.svg')


if __name__ == '__main__':
    architecture()
    placement()
    sequence()
