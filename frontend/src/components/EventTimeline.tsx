import React from 'react';
import {
  ArrowDownCircle,
  ArrowUpCircle,
  Clock,
  Code,
  Edit3,
  FileCheck,
  Lock,
  Sliders,
  Unlock,
  XCircle
} from 'lucide-react';
import type { EventEnvelopeResponse } from '../types/chronos';
import { formatMoney } from './CurrentStateCard';

interface EventTimelineProps {
  events: EventEnvelopeResponse[];
  loading: boolean;
  onSelectEvent: (event: EventEnvelopeResponse) => void;
}

export const EventTimeline: React.FC<EventTimelineProps> = ({ events, loading, onSelectEvent }) => {
  if (loading) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 text-center animate-pulse">
        <div className="text-slate-500 font-mono text-sm">Loading Chronological Event History...</div>
      </div>
    );
  }

  if (events.length === 0) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 text-center text-slate-500 text-sm">
        No domain events recorded yet in this stream.
      </div>
    );
  }

  const getEventBadge = (eventType: string) => {
    switch (eventType) {
      case 'AccountCreated':
        return {
          icon: <FileCheck className="w-4 h-4 text-indigo-400" />,
          style: 'bg-indigo-500/10 text-indigo-300 border-indigo-500/30'
        };
      case 'MoneyDeposited':
        return {
          icon: <ArrowDownCircle className="w-4 h-4 text-emerald-400" />,
          style: 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'
        };
      case 'MoneyWithdrawn':
        return {
          icon: <ArrowUpCircle className="w-4 h-4 text-amber-400" />,
          style: 'bg-amber-500/10 text-amber-300 border-amber-500/30'
        };
      case 'AccountFrozen':
        return {
          icon: <Lock className="w-4 h-4 text-cyan-400" />,
          style: 'bg-cyan-500/10 text-cyan-300 border-cyan-500/30'
        };
      case 'AccountUnfrozen':
        return {
          icon: <Unlock className="w-4 h-4 text-teal-400" />,
          style: 'bg-teal-500/10 text-teal-300 border-teal-500/30'
        };
      case 'OverdraftLimitChanged':
      case 'TransactionLimitChanged':
        return {
          icon: <Sliders className="w-4 h-4 text-purple-400" />,
          style: 'bg-purple-500/10 text-purple-300 border-purple-500/30'
        };
      case 'CorrectionIssued':
        return {
          icon: <Edit3 className="w-4 h-4 text-orange-400" />,
          style: 'bg-orange-500/10 text-orange-300 border-orange-500/30'
        };
      case 'AccountClosed':
        return {
          icon: <XCircle className="w-4 h-4 text-rose-400" />,
          style: 'bg-rose-500/10 text-rose-300 border-rose-500/30'
        };
      default:
        return {
          icon: <Clock className="w-4 h-4 text-slate-400" />,
          style: 'bg-slate-800 text-slate-300 border-slate-700'
        };
    }
  };

  const getPayloadSummary = (event: EventEnvelopeResponse): string => {
    const p = event.payload;
    switch (event.eventType) {
      case 'AccountCreated':
        return `Currency: ${p.currency} | Limits: Overdraft ${formatMoney(p.initialOverdraftLimitMinor || 0, p.currency)}, Tx ${formatMoney(p.initialTransactionLimitMinor || 0, p.currency)}`;
      case 'MoneyDeposited':
        return `Amount: +${formatMoney(p.amountMinor || 0)}`;
      case 'MoneyWithdrawn':
        return `Amount: -${formatMoney(p.amountMinor || 0)}`;
      case 'AccountFrozen':
      case 'AccountUnfrozen':
      case 'AccountClosed':
        return `Reason: ${p.reason || 'N/A'}`;
      case 'OverdraftLimitChanged':
        return `New Overdraft: ${formatMoney(p.newOverdraftLimitMinor || 0)}`;
      case 'TransactionLimitChanged':
        return `New Tx Limit: ${formatMoney(p.newTransactionLimitMinor || 0)}`;
      case 'CorrectionIssued':
        return `Type: ${p.correctionType} | Dir: ${p.direction} | Adj: ${formatMoney(p.adjustmentAmountMinor || 0)}`;
      default:
        return JSON.stringify(p);
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-4">
      <div className="flex items-center justify-between border-b border-slate-800 pb-3">
        <div className="flex items-center gap-2">
          <Clock className="w-5 h-5 text-cyan-400" />
          <h2 className="text-sm font-bold text-slate-300 uppercase tracking-wider">
            Chronological Domain Event Stream ({events.length} Events)
          </h2>
        </div>
        <span className="text-xs text-slate-500 font-mono">Ordered by sequence ASC</span>
      </div>

      <div className="relative pl-6 space-y-4 before:absolute before:left-3 before:top-3 before:bottom-3 before:w-0.5 before:bg-slate-800">
        {events.map((evt) => {
          const badge = getEventBadge(evt.eventType);
          return (
            <div
              key={evt.eventId}
              onClick={() => onSelectEvent(evt)}
              className="relative group bg-slate-950/80 hover:bg-slate-950 border border-slate-800/80 hover:border-cyan-500/40 rounded-xl p-3.5 transition-all cursor-pointer shadow-md"
            >
              {/* Timeline Bullet */}
              <div className="absolute -left-6 top-4 w-3.5 h-3.5 rounded-full bg-slate-900 border-2 border-cyan-400 group-hover:scale-110 transition-transform" />

              <div className="flex flex-wrap items-center justify-between gap-2 mb-1.5">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-mono font-bold text-cyan-400 bg-cyan-950/60 px-2 py-0.5 rounded border border-cyan-800/50">
                    #{evt.sequenceNumber}
                  </span>
                  <span className={`px-2.5 py-0.5 rounded-md text-xs font-semibold border flex items-center gap-1.5 ${badge.style}`}>
                    {badge.icon}
                    {evt.eventType}
                  </span>
                </div>
                <span className="text-[11px] font-mono text-slate-500">
                  {new Date(evt.recordedAt).toLocaleString()}
                </span>
              </div>

              <div className="text-xs font-mono text-slate-300 pl-1">
                {getPayloadSummary(evt)}
              </div>

              <div className="flex items-center justify-between text-[10px] font-mono text-slate-500 pt-2 border-t border-slate-900 mt-2">
                <span>Event ID: {evt.eventId.slice(0, 8)}...</span>
                <span className="flex items-center gap-1 text-cyan-400/80 opacity-0 group-hover:opacity-100 transition-opacity">
                  <Code className="w-3 h-3" /> View Payload JSON
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
