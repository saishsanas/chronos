import React, { useState } from 'react';
import { AlertTriangle, Check, Copy, X } from 'lucide-react';
import type { ApiErrorResponse } from '../types/chronos';

interface ErrorAlertProps {
  error: ApiErrorResponse | any | null;
  onClear: () => void;
}

export const ErrorAlert: React.FC<ErrorAlertProps> = ({ error, onClear }) => {
  const [copied, setCopied] = useState(false);

  if (!error) return null;

  const errorMessage = error.message || error.error || 'An unexpected error occurred.';
  const status = error.status || '500';
  const correlationId = error.correlationId || 'N/A';
  const timestamp = error.timestamp ? new Date(error.timestamp).toLocaleTimeString() : new Date().toLocaleTimeString();

  const handleCopyCorrelationId = () => {
    navigator.clipboard.writeText(correlationId);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="bg-rose-500/10 border border-rose-500/40 rounded-xl p-4 text-slate-200 shadow-xl space-y-2 animate-in fade-in duration-200">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2">
          <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0" />
          <h3 className="text-sm font-bold text-rose-300">
            Command Execution Failure (HTTP {status})
          </h3>
        </div>
        <button
          onClick={onClear}
          className="text-slate-400 hover:text-slate-200 p-1 rounded hover:bg-rose-500/20"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <p className="text-xs font-mono text-slate-200 pl-7">{errorMessage}</p>

      <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-rose-500/20 text-[11px] font-mono pl-7 text-slate-400">
        <span>Time: {timestamp}</span>
        <div className="flex items-center gap-2">
          <span className="text-rose-300">Correlation ID: {correlationId}</span>
          {correlationId !== 'N/A' && (
            <button
              onClick={handleCopyCorrelationId}
              className="flex items-center gap-1 text-cyan-400 hover:text-cyan-300 font-semibold"
            >
              {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />}
              {copied ? 'Copied' : 'Copy'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
