import React, { useState } from 'react';
import { AlertTriangle, Check, Copy, Lock, ShieldAlert, X } from 'lucide-react';
import type { ApiErrorResponse } from '../types/chronos';

interface ErrorAlertProps {
  error: ApiErrorResponse | any | null;
  onClear: () => void;
}

export const ErrorAlert: React.FC<ErrorAlertProps> = ({ error, onClear }) => {
  const [copied, setCopied] = useState(false);

  if (!error) return null;

  const status = error.status || 500;
  let title = `Operation Failed (HTTP ${status})`;
  let errorMessage = error.message || error.error || 'An unexpected error occurred.';

  if (status === 401) {
    title = 'Authentication Required (HTTP 401)';
    errorMessage = 'Please sign in.';
  } else if (status === 403) {
    title = 'Access Forbidden (HTTP 403)';
    errorMessage = 'You do not have permission for this operation.';
  }

  const correlationId = error.correlationId || 'N/A';
  const timestamp = error.timestamp ? new Date(error.timestamp).toLocaleTimeString() : new Date().toLocaleTimeString();

  const handleCopyCorrelationId = () => {
    navigator.clipboard.writeText(correlationId);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const getBorderColor = () => {
    if (status === 401) return 'border-amber-500/40 bg-amber-500/10';
    if (status === 403) return 'border-rose-500/40 bg-rose-500/10';
    return 'border-rose-500/40 bg-rose-500/10';
  };

  const getIcon = () => {
    if (status === 401) return <Lock className="w-5 h-5 text-amber-400 shrink-0" />;
    if (status === 403) return <ShieldAlert className="w-5 h-5 text-rose-400 shrink-0" />;
    return <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0" />;
  };

  return (
    <div className={`border rounded-xl p-4 text-slate-200 shadow-xl space-y-2 animate-in fade-in duration-200 ${getBorderColor()}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2">
          {getIcon()}
          <h3 className="text-sm font-bold text-slate-100">
            {title}
          </h3>
        </div>
        <button
          onClick={onClear}
          className="text-slate-400 hover:text-slate-200 p-1 rounded hover:bg-slate-800/40"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <p className="text-xs font-mono text-slate-200 pl-7">{errorMessage}</p>

      <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-slate-700/40 text-[11px] font-mono pl-7 text-slate-400">
        <span>Time: {timestamp}</span>
        <div className="flex items-center gap-2">
          <span className="text-slate-300">Correlation ID: {correlationId}</span>
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
