import { useState } from 'react';
import FileUpload from './components/FileUpload';
import ReportView from './components/ReportView';
import KoReport from './components/KoReport';
import History from './components/History';
import ThemeToggle from './components/ThemeToggle';
import { analyzeLogs } from './api/logAnalyzerApi';
import { saveToHistory } from './utils/history';
import './App.css';

const NAV_TABS = [
  { id: 'log-analyzer', label: 'Log Analyzer' },
  { id: 'ko-report', label: 'KO Report' },
  { id: 'history', label: 'History' },
];

function App() {
  const [activeNav, setActiveNav] = useState('log-analyzer');
  // koReport holds the full KoReportDto: { analysis, records, hasRecordLevelData }
  const [koReport, setKoReport] = useState(null);
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState(null);
  const [lastFiles, setLastFiles] = useState(null);

  const handleAnalyze = async (stdoutFile, stderrFile) => {
    setLastFiles({ stdoutFile, stderrFile });
    setLoading(true);
    setError(null);
    setKoReport(null);
    try {
      const data = await analyzeLogs(stdoutFile, stderrFile);
      setKoReport(data);
      saveToHistory(data, {
        stdoutName: stdoutFile?.name ?? null,
        stderrName: stderrFile?.name ?? null,
      });
    } catch (err) {
      setError(err.message || 'Failed to analyze logs.');
    } finally {
      setLoading(false);
    }
  };

  const handleRetry = () => {
    if (lastFiles) handleAnalyze(lastFiles.stdoutFile, lastFiles.stderrFile);
  };

  const handleClear = () => {
    setKoReport(null);
    setError(null);
    setLoading(false);
    setLastFiles(null);
  };

  // Reopens a saved history entry without re-uploading anything: it already carries the exact
  // response the backend returned when it was first analysed.
  const handleViewHistoryEntry = (entry) => {
    setKoReport(entry.koReport);
    setError(null);
    setLastFiles(null);
    setActiveNav('log-analyzer');
  };

  // ReportView receives only the AnalysisReport portion; KoReport receives the full
  // KoReportDto. Fall back to the payload itself so an older backend contract (a bare
  // AnalysisReport) still renders instead of showing an empty page.
  const analysisReport = koReport ? (koReport.analysis ?? koReport) : null;

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-logo" aria-hidden="true">📊</div>
        <div className="app-header-text">
          <h1>Log Analyzer</h1>
          <p>Upload STDOUT / STDERR batch logs to inspect ETL layer execution details.</p>
        </div>
        <nav className="app-nav" role="navigation" aria-label="Main navigation">
          {NAV_TABS.map(t => (
            <button
              key={t.id}
              className={`app-nav-btn${activeNav === t.id ? ' app-nav-btn--active' : ''}`}
              onClick={() => setActiveNav(t.id)}
              aria-current={activeNav === t.id ? 'page' : undefined}
            >
              {t.label}
              {t.id === 'ko-report' && koReport && (
                <span className="nav-ready-dot" aria-label="Report ready" />
              )}
            </button>
          ))}
        </nav>
        <ThemeToggle />
      </header>

      <main>
        {activeNav === 'log-analyzer' && (
          <>
            {!analysisReport ? (
              <FileUpload onAnalyze={handleAnalyze} onClear={handleClear} loading={loading} />
            ) : (
              <div className="analyzed-bar">
                <span className="analyzed-bar-status">✓ Analysis complete</span>
                <button type="button" className="analyzed-bar-clear" onClick={handleClear}>
                  Clear &amp; Analyze New Files
                </button>
              </div>
            )}

            {error && (
              <div className="error-banner" role="alert">
                ⚠️ {error}
                {lastFiles && (
                  <button type="button" className="retry-btn" onClick={handleRetry} disabled={loading}>
                    Retry
                  </button>
                )}
              </div>
            )}
            {loading && (
              <div className="loading-banner" role="status">
                Analyzing logs…
              </div>
            )}

            <ReportView report={analysisReport} />
          </>
        )}

        {activeNav === 'ko-report' && (
          <KoReport report={koReport} onClear={handleClear} />
        )}

        {activeNav === 'history' && (
          <History onViewEntry={handleViewHistoryEntry} />
        )}
      </main>
    </div>
  );
}

export default App;
