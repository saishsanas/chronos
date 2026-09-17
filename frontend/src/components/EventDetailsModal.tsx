import React from 'react';
import { Code, Copy, Check, X } from 'lucide-react';
import type { EventEnvelopeResponse } from '../types/chronos';

interface EventDetailsModalProps {
  event: EventEnvelopeResponse | null;
  onClose: () => void;
}

export const EventDetailsModal: React.FC<EventDetailsModalProps> = ({ event, onClose }) => {
  const [copied, setCopied] = React.useState(false);

  if (!event) return null;

  const handleCopyJson = () => {
    navigator.clipboard.writeText(JSON.stringify(event, null, 2));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 w-full max-w-2xl shadow-2xl space-y-4 max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <div className="flex items-center gap-2">
            <Code className="w-5 h-5 text-cyan-400" />
            <h3 className="text-lg font-bold text-slate-100">
              Domain Event #{event.sequenceNumber} - {event.eventType}
            </h3>
          </div>
          <button
            onClick={onClose}
            className="p-1 text-slate-400 hover:text-slate-200 rounded-lg hover:bg-slate-800 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content */}
        <div className="overflow-y-auto space-y-4 flex-1 pr-1">
          {/* Metadata Cards */}
          <div className="grid grid-cols-2 gap-3 text-xs font-mono">
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Event ID</div>
              <div className="text-cyan-300 font-semibold truncate">{event.eventId}</div>
            </div>
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Aggregate ID</div>
              <div className="text-slate-300 font-semibold truncate">{event.aggregateId}</div>
            </div>
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Recorded Timestamp</div>
              <div className="text-slate-300">{new Date(event.recordedAt).toISOString()}</div>
            </div>
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Correlation ID</div>
              <div className="text-indigo-300 font-semibold truncate">
                {event.metadata?.correlationId || 'N/A'}
              </div>
            </div>
          </div>

          {/* JSON Payload Viewer */}
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <span className="text-xs font-semibold text-slate-400">Full Event Envelope JSON</span>
              <button
                onClick={handleCopyJson}
                className="flex items-center gap-1 text-xs text-cyan-400 hover:text-cyan-300 font-mono"
              >
                {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                {copied ? 'Copied' : 'Copy JSON'}
              </button>
            </div>
            <pre className="bg-slate-950 border border-slate-800 rounded-xl p-4 text-xs font-mono text-cyan-300 overflow-x-auto leading-relaxed shadow-inner">
              {JSON.stringify(event, null, 2)}
            </pre>
          </div>
        </div>

        {/* Footer */}
        <div className="pt-2 flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-sm font-medium rounded-lg transition-colors"
          >
            Close Viewer
          </button>
        </div>
      </div>
    </div>
  );
};
