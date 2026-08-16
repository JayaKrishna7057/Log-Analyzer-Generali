import { useEffect, useRef, useState } from 'react';
import { getSupportedFormats } from '../api/logAnalyzerApi';

const STREAMS = ['stdout', 'stderr'];

/**
 * Which stream a file name points at, or null when it gives no hint.
 * The exact "stdout"/"stderr" words are checked first so that a name like
 * "stdout_errors.log" is not read as a stderr file by the looser "err"/"out" test.
 */
function hintedStream(fileName) {
  const name = fileName.toLowerCase();

  const exact = STREAMS.find((stream) => name.includes(stream));
  if (exact) return exact;

  if (name.includes('err')) return 'stderr';
  if (name.includes('out')) return 'stdout';
  return null;
}

/** Assigns the picked files to the stdout/stderr slots: by name first, then by selection order. */
function classifyFiles(files) {
  const picked = { stdout: null, stderr: null };
  const unhinted = [];

  files.forEach((file) => {
    const stream = hintedStream(file.name);
    if (!stream) {
      unhinted.push(file);
    } else if (!picked[stream]) {
      picked[stream] = file;
    }
    // A second file hinting at an already-filled slot is ignored rather than
    // pushed into the other slot, where it would be analysed as the wrong stream.
  });

  unhinted.forEach((file) => {
    const freeStream = STREAMS.find((stream) => !picked[stream]);
    if (freeStream) picked[freeStream] = file;
  });

  return picked;
}

/** Supported-format chips, read from the backend so this can never list a format it does not have. */
function SupportedFormats() {
  const [formats, setFormats] = useState([]);

  useEffect(() => {
    let cancelled = false;
    getSupportedFormats().then((result) => {
      if (!cancelled) setFormats(result);
    });
    return () => { cancelled = true; };
  }, []);

  if (formats.length === 0) return null;

  return (
    <div className="supported-formats">
      <span>Auto-detected formats:</span>
      {formats.map((f) => (
        <span key={f.id} className="supported-formats-chip">{f.displayName}</span>
      ))}
    </div>
  );
}

function FileUpload({ onAnalyze, onClear, loading }) {
  const [stdoutFile, setStdoutFile] = useState(null);
  const [stderrFile, setStderrFile] = useState(null);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef(null);

  const applyFiles = (fileList) => {
    const { stdout, stderr } = classifyFiles(Array.from(fileList || []));
    setStdoutFile(stdout);
    setStderrFile(stderr);
  };

  const handleFilesChange = (e) => applyFiles(e.target.files);

  const handleDragOver = (e) => {
    e.preventDefault();
    setDragActive(true);
  };

  const handleDragLeave = (e) => {
    e.preventDefault();
    setDragActive(false);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragActive(false);
    if (loading) return;
    applyFiles(e.dataTransfer.files);
  };

  const canSubmit = Boolean(stdoutFile || stderrFile) && !loading;

  const handleSubmit = (e) => {
    e.preventDefault();
    // Guards the submit-on-Enter path, which the disabled button does not cover.
    if (!canSubmit) return;
    onAnalyze(stdoutFile, stderrFile);
  };

  const handleReset = () => {
    setStdoutFile(null);
    setStderrFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
    onClear?.();
  };

  return (
    <form className="upload-form" onSubmit={handleSubmit}>
      <div className="upload-fields">
        <label
          className={`upload-field upload-field-combined${dragActive ? ' upload-field--drag-active' : ''}`}
          onDragOver={handleDragOver}
          onDragEnter={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
        >
          <span className="upload-label">STDOUT / STDERR files</span>
          <input
            ref={fileInputRef}
            type="file"
            accept=".txt,.log,.out,.err"
            multiple
            onChange={handleFilesChange}
          />
          <div className="file-name-list">
            <span className="file-name">STDOUT: {stdoutFile ? stdoutFile.name : 'not selected'}</span>
            <span className="file-name">STDERR: {stderrFile ? stderrFile.name : 'not selected'}</span>
          </div>
        </label>
      </div>

      <div className="upload-actions">
        <button type="submit" disabled={!canSubmit}>
          {loading ? 'Analyzing…' : 'Analyze Logs'}
        </button>
        <button type="button" onClick={handleReset} disabled={loading}>
          Clear
        </button>
      </div>
      <p className="upload-hint">
        Choose one or both files in a single dialog, or drag and drop them onto the box above
        (name them with "stdout"/"stderr" for automatic detection).
      </p>
      <SupportedFormats />
    </form>
  );
}

export default FileUpload;
