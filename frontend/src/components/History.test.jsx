import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import History from './History';
import { saveToHistory } from '../utils/history';

function koReport(overrides = {}) {
  return {
    analysis: {
      jobName: 'NIGHTLY_LOAD',
      overallStatus: 'FAILED',
      detectedFormat: 'batch-layer',
      formatConfidence: 1,
      totalOk: 5,
      totalKo: 2,
      totalError: 3,
      totalWarning: 0,
      ...overrides,
    },
    records: [],
    hasRecordLevelData: false,
  };
}

describe('History', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('shows guidance instead of an empty table when nothing has been saved', () => {
    render(<History onViewEntry={vi.fn()} />);

    expect(screen.getByText(/No saved analyses yet/i)).toBeInTheDocument();
    expect(screen.queryByText('View')).toBeNull();
  });

  it('lists a saved run with its status and job name', () => {
    saveToHistory(koReport(), { stdoutName: 'a_stdout.txt', stderrName: null });

    render(<History onViewEntry={vi.fn()} />);

    expect(screen.getByText('NIGHTLY_LOAD')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
    expect(screen.getByText('a_stdout.txt')).toBeInTheDocument();
  });

  it('passes the stored payload back on View, unchanged', async () => {
    const user = userEvent.setup();
    const onViewEntry = vi.fn();
    saveToHistory(koReport(), {});

    render(<History onViewEntry={onViewEntry} />);
    await user.click(screen.getByRole('button', { name: 'View' }));

    expect(onViewEntry).toHaveBeenCalledTimes(1);
    const passed = onViewEntry.mock.calls[0][0];
    expect(passed.koReport.analysis.jobName).toBe('NIGHTLY_LOAD');
  });

  it('deletes only the row that was deleted', async () => {
    const user = userEvent.setup();
    saveToHistory(koReport({ jobName: 'KEEP' }), {});
    saveToHistory(koReport({ jobName: 'REMOVE' }), {});

    render(<History onViewEntry={vi.fn()} />);
    const rows = screen.getAllByText(/KEEP|REMOVE/);
    const removeRow = rows.find((el) => el.textContent === 'REMOVE').closest('.history-row');
    await user.click(within(removeRow).getByRole('button', { name: 'Delete' }));

    expect(screen.queryByText('REMOVE')).toBeNull();
    expect(screen.getByText('KEEP')).toBeInTheDocument();
  });

  it('requires a second click before clearing everything', async () => {
    const user = userEvent.setup();
    saveToHistory(koReport(), {});

    render(<History onViewEntry={vi.fn()} />);
    const clearBtn = screen.getByRole('button', { name: /Clear All/ });

    await user.click(clearBtn);
    expect(screen.getByText('NIGHTLY_LOAD')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /confirm/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /confirm/i }));
    expect(screen.getByText(/No saved analyses yet/i)).toBeInTheDocument();
  });
});
