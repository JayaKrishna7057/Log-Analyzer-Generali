import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import KoReport from './KoReport';

/**
 * KoReport renders whatever the single /api/analyze call returned. These tests cover the states
 * that actually broke in practice: no analysis yet, a payload whose shape does not match, and a
 * format that carries no per-record data.
 */

function analysis(overrides = {}) {
  return {
    mode: 'layers',
    detectedFormat: 'elab-batch',
    formatConfidence: 1.0,
    jobName: 'NIGHTLY_LOAD',
    overallStatus: 'FINISHED_OK_WARNINGS',
    overallStartTime: '2026-08-01T15:10:46',
    overallEndTime: '2026-08-01T15:12:52',
    overallDuration: '0:02:06',
    totalRawData: 231,
    totalOk: 125,
    totalKo: 107,
    totalError: 0,
    totalWarning: 0,
    completedEtlLayers: [],
    inProgressEtlLayers: [],
    issues: [],
    ...overrides,
  };
}

const records = [
  { dialogueId: '111', internalKey: 'K1', uniqueCode: 'U1', status: 'OK', koReason: null },
  { dialogueId: '222', internalKey: '-', uniqueCode: 'U2', status: 'KO', koReason: 'Config missing' },
];

describe('KoReport', () => {

  it('points the user at the Log Analyzer tab before any analysis has run', () => {
    render(<KoReport report={null} />);

    expect(screen.getByText(/Log Analyzer/)).toBeInTheDocument();
    expect(screen.getByText(/generated automatically/i)).toBeInTheDocument();
    // No second upload control - the logs are provided once, in the other tab.
    expect(document.querySelector('input[type=file]')).toBeNull();
  });

  it('shows record tabs and per-record data when the format supports it', () => {
    render(<KoReport report={{ analysis: analysis(), records, hasRecordLevelData: true }} />);

    expect(screen.getByRole('tab', { name: /All Records \(2\)/ })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /KO Details \(1\)/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'NIGHTLY_LOAD' })).toBeInTheDocument();
  });

  it('says per-record data is unavailable rather than showing an empty table', () => {
    render(<KoReport report={{ analysis: analysis(), records: [], hasRecordLevelData: false }} />);

    expect(screen.queryByRole('tab', { name: /All Records/ })).toBeNull();
    expect(screen.queryByRole('tab', { name: /KO Details/ })).toBeNull();
    expect(screen.getByText(/not available for this log format/i)).toBeInTheDocument();
  });

  it('hides the KO Details tab when every record succeeded', () => {
    const allOk = [{ dialogueId: '1', internalKey: 'K', uniqueCode: 'U', status: 'OK', koReason: null }];
    render(<KoReport report={{ analysis: analysis(), records: allOk, hasRecordLevelData: true }} />);

    expect(screen.getByRole('tab', { name: /All Records \(1\)/ })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /KO Details/ })).toBeNull();
  });

  it('only offers an Issues tab when there are issues', () => {
    const withIssues = analysis({
      issues: [{
        severity: 'ERROR', category: 'DATA_QUALITY', message: 'null value in column',
        occurrences: 3, source: 'stderr', firstLine: 4, lastLine: 6,
      }],
    });

    render(<KoReport report={{ analysis: withIssues, records: [], hasRecordLevelData: false }} />);
    expect(screen.getByRole('tab', { name: /Issues \(1\)/ })).toBeInTheDocument();

    render(<KoReport report={{ analysis: analysis(), records: [], hasRecordLevelData: false }} />);
    expect(screen.queryAllByRole('tab', { name: /Issues/ })).toHaveLength(1);
  });

  it('renders a bare AnalysisReport instead of blanking the page', () => {
    // A backend serving an older contract returns the report without the wrapper. This threw and
    // unmounted the view, which is what made the KO Report appear blank.
    render(<KoReport report={analysis()} />);

    expect(screen.getByRole('heading', { name: 'NIGHTLY_LOAD' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Summary/ })).toBeInTheDocument();
  });

  it('does not trust hasRecordLevelData when no records actually arrived', () => {
    render(<KoReport report={{ analysis: analysis(), records: [], hasRecordLevelData: true }} />);

    expect(screen.queryByRole('tab', { name: /All Records/ })).toBeNull();
    expect(screen.getByText(/not available for this log format/i)).toBeInTheDocument();
  });

  it('counts a record with no status in the total and labels it, rather than hiding it', async () => {
    const user = userEvent.setup();
    const withUnreported = [
      ...records,
      { dialogueId: '333', internalKey: 'K3', uniqueCode: 'U3', status: null, koReason: null },
    ];

    render(<KoReport report={{ analysis: analysis(), records: withUnreported, hasRecordLevelData: true }} />);

    // The record is part of the declared total, not quietly dropped from it.
    expect(screen.getByRole('tab', { name: /All Records \(3\)/ })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /All Records/ }));
    expect(screen.getAllByText('NOT REPORTED').length).toBeGreaterThan(0);

    // It is neither a pass nor a failure, so it stays out of the KO list.
    expect(screen.getByRole('tab', { name: /KO Details \(1\)/ })).toBeInTheDocument();
  });
});
