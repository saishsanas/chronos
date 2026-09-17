import React from 'react';
import { Layers, Zap, HardDrive } from 'lucide-react';
import type { AccountSummaryResponse } from '../types/chronos';
import { formatMoney } from './CurrentStateCard';

interface SummaryCardProps {
  summary: AccountSummaryResponse | null;
  loading: boolean;
}

export const SummaryCard: React.FC<SummaryCardProps> = ({ summary, loading }) => {
  if (loading) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-lg animate-pulse h-28 flex items-center justify-center">
        <div className="text-slate-500 font-mono text-xs">Querying CQRS Read Model...</div>
      </div>
    );
  }

  if (!summary) return null;

  return (
    <div className="bg-slate-900/90 border border-indigo-500/20 rounded-xl p-4 shadow-lg space-y-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Layers className="w-4 h-4 text-indigo-400" />
          <h3 className="text-xs font-bold text-slate-300 uppercase tracking-wider">
            CQRS Read Model Projection (GET /summary)
          </h3>
        </div>
        {summary.cached ? (
          <span className="px-2.5 py-0.5 text-[11px] font-mono font-semibold rounded-full bg-amber-500/10 text-amber-300 border border-amber-500/30 flex items-center gap-1">
            <Zap className="w-3 h-3 text-amber-400 fill-amber-400" /> REDIS CACHED (60s)
          </span>
        ) : (
          <span className="px-2.5 py-0.5 text-[11px] font-mono font-semibold rounded-full bg-indigo-500/10 text-indigo-300 border border-indigo-500/30 flex items-center gap-1">
            <HardDrive className="w-3 h-3 text-indigo-400" /> POSTGRES PROJECTION
          </span>
        )}
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
        <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
          <div className="text-slate-500">Read Balance</div>
          <div className="font-mono font-bold text-indigo-300 text-base">
            {formatMoney(summary.balanceMinor, summary.currency)}
          </div>
        </div>
        <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
          <div className="text-slate-500">Projection Sequence</div>
          <div className="font-mono font-bold text-slate-200 text-base">#{summary.lastSequenceNumber}</div>
        </div>
        <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
          <div className="text-slate-500">Status</div>
          <div className="font-semibold text-slate-200 text-sm mt-0.5">{summary.status}</div>
        </div>
        <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
          <div className="text-slate-500">Last Updated</div>
          <div className="font-mono text-[11px] text-slate-400 mt-0.5 truncate">
            {new Date(summary.lastUpdated).toLocaleTimeString()}
          </div>
        </div>
      </div>
    </div>
  );
};
