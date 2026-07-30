import React, { useState, useEffect } from 'react';
import axios from 'axios';
import PerformanceConsole from './v2/PerformanceConsole';
import {
  Activity,
  Play,
  RefreshCw,
  AlertCircle,
  CheckCircle2,
  XCircle,
  Clock,
  Download,
  Server
} from 'lucide-react';

// --- API Types ---
interface TeamConfigResponse {
  teamsLoaded: number;
  teams: string[];
}

interface TestRun {
  id: string;
  team: string;
  testType: string;
  status: string;
  slaResult: string;
  startedAt: string | null;
  completedAt: string | null;
  p95LatencyMs: number | null;
  errorRatePercent: number | null;
  peakHeapUsagePercent: number | null;
  reportPath: string | null;
  createdAt: string;
}

function App() {
  const [teams, setTeams] = useState<string[]>([]);
  const [selectedTeam, setSelectedTeam] = useState<string>('');
  const [apiKey, setApiKey] = useState<string>('');
  const [testType, setTestType] = useState<string>('load');

  /**
   * Which surface is showing.
   *
   * V2 is the default because it is the one that works end to end — durable runs, objectives,
   * cancellation. V1 stays reachable until its replacement is verified and its removal approved
   * (CLAUDE.md §5), which is why this is a tab and not a redirect.
   */
  const [surface, setSurface] = useState<'v2' | 'v1'>('v2');

  const [history, setHistory] = useState<TestRun[]>([]);
  const [loadingTeams, setLoadingTeams] = useState(true);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Initial load
  useEffect(() => {
    fetchTeams();
  }, []);

  // Fetch history when team changes
  useEffect(() => {
    if (selectedTeam) {
      fetchHistory(selectedTeam);
    } else {
      setHistory([]);
    }
  }, [selectedTeam]);

  // Polling history every 5 seconds if there are running tests
  useEffect(() => {
    let interval: number;
    const hasRunningTests = history.some(r => r.status === 'PENDING' || r.status === 'RUNNING');

    if (selectedTeam && hasRunningTests) {
      interval = window.setInterval(() => {
        fetchHistory(selectedTeam, false);
      }, 5000);
    }
    return () => clearInterval(interval);
  }, [selectedTeam, history]);

  const fetchTeams = async () => {
    try {
      setLoadingTeams(true);
      setError(null);
      const res = await axios.get<TeamConfigResponse>('/api/v1/config/teams');
      setTeams(res.data.teams);
      if (res.data.teams.length > 0 && !selectedTeam) {
        setSelectedTeam(res.data.teams[0]);
      }
    } catch (err: any) {
      console.error('Error fetching teams:', err);
      // For local dev, we might not have the API key required for config API if it was locked down,
      // but in our SecurityConfig /config/metrics might be public or we assume example-team exists.
      // Actually SecurityConfig says "Everything else requires API key"
      // So fetching teams WITHOUT an API key will fail with 403!
      // Let's handle this:
      setError('Cannot fetch teams. Ensure backend is running. If API Key is required for config, you must set it in the code or allow public access.');
      // Fallback for UI testing
      setTeams(['example-team', 'payment-service-team']);
      setSelectedTeam('payment-service-team');
    } finally {
      setLoadingTeams(false);
    }
  };

  const fetchHistory = async (team: string, showLoader = true) => {
    if (!team) return;
    try {
      if (showLoader) setLoadingHistory(true);
      // Use the provided API key if available, otherwise it might 403
      const headers = apiKey ? { 'X-API-Key': apiKey } : {};

      const res = await axios.get<TestRun[]>(`/api/v1/tests/history?team=${team}&limit=10`, { headers });
      setHistory(res.data);
      if (error && error.includes('API Key')) setError(null); // Clear auth errors if successful
    } catch (err: any) {
      if (err.response?.status === 403) {
        setError('403 Forbidden: Valid API Key required to view history for this team.');
        setHistory([]);
      } else {
        console.error('Error fetching history:', err);
      }
    } finally {
      if (showLoader) setLoadingHistory(false);
    }
  };

  const handleTriggerTest = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedTeam || !apiKey) {
      setError('Team and API Key are required to trigger a test.');
      return;
    }

    try {
      setTriggering(true);
      setError(null);
      const res = await axios.post<TestRun>(
        '/api/v1/tests/run',
        { team: selectedTeam, type: testType },
        { headers: { 'X-API-Key': apiKey } }
      );

      // Add the new pending run to the top of the history list
      setHistory(prev => [res.data, ...prev]);
    } catch (err: any) {
      if (err.response?.status === 403) {
        setError('403 Forbidden: Invalid API Key.');
      } else {
        setError(err.response?.data?.error || 'Failed to trigger test run.');
      }
      console.error('Trigger error:', err);
    } finally {
      setTriggering(false);
    }
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'PENDING':
        return <span className="badge badge-neutral"><Clock size={12} className="mr-1" /> Pending</span>;
      case 'RUNNING':
        return <span className="badge badge-primary bg-opacity-20 animate-pulse"><Activity size={12} className="mr-1" /> Running</span>;
      case 'COMPLETED':
        return <span className="badge badge-success"><CheckCircle2 size={12} className="mr-1" /> Completed</span>;
      case 'FAILED':
        return <span className="badge badge-danger"><XCircle size={12} className="mr-1" /> Failed</span>;
      default:
        return <span className="badge badge-neutral">{status}</span>;
    }
  };

  const getSlaBadge = (sla: string) => {
    if (sla === 'MET') return <span className="text-success font-semibold text-sm">MET</span>;
    if (sla === 'BREACH') return <span className="text-danger font-semibold text-sm">BREACH</span>;
    return <span className="text-secondary text-sm">{sla}</span>;
  };

  const formatDuration = (start: string | null, end: string | null) => {
    if (!start) return '-';
    const startTime = new Date(start).getTime();
    const endTime = end ? new Date(end).getTime() : Date.now();
    const diffSeconds = Math.floor((endTime - startTime) / 1000);

    if (diffSeconds < 60) return `${diffSeconds}s`;
    const mins = Math.floor(diffSeconds / 60);
    const secs = diffSeconds % 60;
    return `${mins}m ${secs}s`;
  };

  return (
    <div className="app-container">
      <header className="header">
        <div className="header-brand">
          <Activity className="logo-icon" size={28} />
          <span>Antigravity<span className="text-gradient">Core</span></span>
        </div>
        <nav className="surface-tabs" aria-label="Platform version">
          <button
            className={surface === 'v2' ? 'surface-tab active' : 'surface-tab'}
            onClick={() => setSurface('v2')}
            aria-current={surface === 'v2'}
          >
            Performance tests
          </button>
          <button
            className={surface === 'v1' ? 'surface-tab active' : 'surface-tab'}
            onClick={() => setSurface('v1')}
            aria-current={surface === 'v1'}
          >
            Legacy (v1)
          </button>
        </nav>
        <div className="text-sm text-secondary flex items-center gap-2">
          <Server size={16} />
          <span>v1.0.0</span>
        </div>
      </header>

      {surface === 'v2' && (
        <main className="main-content">
          <section className="glass-panel" style={{ marginBottom: '1.25rem' }}>
            <div className="card-body">
              <div className="form-group" style={{ marginBottom: 0 }}>
                <label className="form-label" htmlFor="v2-key">Team API key</label>
                <input
                  id="v2-key"
                  type="password"
                  className="form-control"
                  placeholder="X-API-Key for your team"
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  autoComplete="off"
                />
              </div>
            </div>
          </section>
          <PerformanceConsole apiKey={apiKey} />
        </main>
      )}

      {surface === 'v1' && (
      <main className="main-content">
        <div className="dashboard-grid">

          {/* Left Column: Form */}
          <section className="glass-panel" style={{ height: 'fit-content' }}>
            <div className="card-header">
              <h2 className="card-title">
                <Play className="text-brand-primary" size={20} />
                Trigger Test Run
              </h2>
            </div>

            <div className="card-body">
              {error && (
                <div className="alert alert-danger">
                  <AlertCircle className="alert-icon" size={18} />
                  <div>{error}</div>
                </div>
              )}

              <form onSubmit={handleTriggerTest}>
                <div className="form-group">
                  <label className="form-label">Target Team Context</label>
                  <select
                    className="form-control"
                    value={selectedTeam}
                    onChange={(e) => setSelectedTeam(e.target.value)}
                    disabled={loadingTeams || triggering}
                  >
                    <option value="" disabled>Select a team...</option>
                    {teams.map(t => (
                      <option key={t} value={t}>{t}</option>
                    ))}
                  </select>
                </div>

                <div className="form-group">
                  <label className="form-label">Test Type</label>
                  <select
                    className="form-control"
                    value={testType}
                    onChange={(e) => setTestType(e.target.value)}
                    disabled={triggering}
                  >
                    <option value="load">Load Test</option>
                    <option value="endurance">Endurance Test</option>
                    <option value="spike">Spike Test</option>
                  </select>
                </div>

                <div className="form-group">
                  <label className="form-label">API Key (Required for Execution)</label>
                  <input
                    type="password"
                    className="form-control"
                    placeholder="Enter team secret key..."
                    value={apiKey}
                    onChange={(e) => setApiKey(e.target.value)}
                    disabled={triggering}
                    required
                  />
                </div>

                <button
                  type="submit"
                  className="btn btn-primary w-full mt-4"
                  disabled={triggering || !selectedTeam || !apiKey}
                >
                  {triggering ? (
                    <><RefreshCw className="animate-spin" size={18} /> Initializing...</>
                  ) : (
                    <><Play size={18} /> Execute Standard {testType.toUpperCase()}</>
                  )}
                </button>
              </form>
            </div>
          </section>

          {/* Right Column: History */}
          <section className="glass-panel">
            <div className="card-header flex" style={{ justifyContent: 'space-between' }}>
              <h2 className="card-title">
                <Activity className="text-brand-secondary" size={20} />
                Execution Telemetry
              </h2>
              <button
                className="btn btn-secondary"
                style={{ padding: '0.4rem 0.8rem', fontSize: '0.8rem' }}
                onClick={() => fetchHistory(selectedTeam)}
                disabled={loadingHistory || !selectedTeam}
              >
                <RefreshCw size={14} className={loadingHistory ? "animate-spin" : ""} />
                Sync
              </button>
            </div>

            <div className="card-body" style={{ padding: 0 }}>
              {!selectedTeam ? (
                <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-tertiary)' }}>
                  Select a team to view test history.
                </div>
              ) : history.length === 0 ? (
                <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-tertiary)' }}>
                  {loadingHistory ? 'Fetching telemetry...' : 'No historical data available for this team.'}
                </div>
              ) : (
                <div className="table-container">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Status</th>
                        <th>Type</th>
                        <th>Duration</th>
                        <th>SLA Validation</th>
                        <th>Performance</th>
                        <th>Report</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.map((run) => (
                        <tr key={run.id}>
                          <td>{getStatusBadge(run.status)}</td>
                          <td>
                            <span className="text-secondary text-sm font-semibold tracking-wider">
                              {run.testType}
                            </span>
                          </td>
                          <td className="text-sm">{formatDuration(run.startedAt, run.completedAt)}</td>
                          <td>{getSlaBadge(run.slaResult)}</td>
                          <td>
                            {run.status === 'COMPLETED' ? (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
                                <span className="text-xs text-secondary">p95: <strong className="text-primary">{run.p95LatencyMs}ms</strong></span>
                                <span className="text-xs text-secondary">err: <strong className={run.errorRatePercent && run.errorRatePercent > 0 ? "text-warning" : "text-primary"}>{run.errorRatePercent}%</strong></span>
                              </div>
                            ) : (
                              <span className="text-xs text-tertiary">-</span>
                            )}
                          </td>
                          <td>
                            <a
                              href={run.status === 'COMPLETED' ? `/runs/${run.id}/perf-report.html` : '#'}
                              target={run.status === 'COMPLETED' ? '_blank' : '_self'}
                              className="btn btn-secondary"
                              style={{
                                padding: '0.25rem 0.5rem',
                                fontSize: '0.75rem',
                                opacity: run.status === 'COMPLETED' ? 1 : 0.4,
                                cursor: run.status === 'COMPLETED' ? 'pointer' : 'not-allowed',
                                textDecoration: 'none'
                              }}
                              onClick={(e) => {
                                if (run.status !== 'COMPLETED') e.preventDefault();
                              }}
                            >
                              <Download size={12} />
                              HTML
                            </a>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </section>

        </div>
      </main>
      )}
    </div>
  );
}

export default App;
