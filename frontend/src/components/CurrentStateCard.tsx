import React from 'react';
import { Database, Lock, ShieldAlert } from 'lucide-react';
import type { AccountStateResponse } from '../types/chronos';

interface CurrentStateCardProps {
  state: AccountStateResponse | null;
  loading: boolean;
}

export const formatMoney = (minorUnits: number, currency: string = 'USD'): string => {
  const major = minorUnits / 100;
  const symbolMap: Record<string, string> = { USD: '$', EUR: '€', GBP: '£' };
  const symbol = symbolMap[currency] || '$';
  return `${symbol}${major.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
};

export const CurrentStateCard: React.FC<CurrentStateCardProps> = ({ state, loading }) => {
  if (loading) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg animate-pulse h-48 flex items-center justify-center">
        <div className="text-slate-500 font-mono text-sm">Reconstructing Event Store State...</div>
      </div>
    );
  }

  if (!state || state.status === 'UNINITIALIZED') {
    return (
      <div className="bg-slate-900 border border-dashed border-slate-800 rounded-xl p-6 shadow-lg text-center space-y-3">
        <ShieldAlert className="w-8 h-8 text-amber-500 mx-auto opacity-80" />
        <div className="text-slate-300 font-medium">No Active Aggregate Stream</div>
        <p className="text-xs text-slate-500 max-w-sm mx-auto">
          Enter an existing Account UUID above or click "Create New Account" to append an AccountCreated domain event to the PostgreSQL event store.
        </p>
      </div>
    );
  }

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'ACTIVE':
        return <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">ACTIVE</span>;
      case 'FROZEN':
        return <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-cyan-500/10 text-cyan-400 border border-cyan-500/30 flex items-center gap-1"><Lock className="w-3 h-3" /> FROZEN</span>;
      case 'CLOSED':
        return <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-rose-500/10 text-rose-400 border border-rose-500/30">CLOSED</span>;
      default:
        return <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-slate-800 text-slate-400">{status}</span>;
    }
  };

  return (
    <div className="bg-gradient-to-br from-slate-900 via-slate-900 to-slate-950 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-4">
      <div className="flex items-center justify-between border-b border-slate-800 pb-3">
        <div className="flex items-center gap-2">
          <Database className="w-5 h-5 text-cyan-400" />
          <h2 className="text-sm font-bold text-slate-300 uppercase tracking-wider">
            Event Store Reconstructed State
          </h2>
        </div>
        {getStatusBadge(state.status)}
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Balance Card */}
        <div className="bg-slate-950/70 border border-slate-800/80 rounded-xl p-4 space-y-1">
          <div className="text-xs font-medium text-slate-400">Current Balance</div>
          <div className="text-2xl font-bold font-mono text-cyan-300">
            {formatMoney(state.balanceMinor, state.currency)}
          </div>
          <div className="text-[11px] text-slate-500 font-mono">({state.balanceMinor.toLocaleString()} minor units)</div>
        </div>

        {/* Limits Card */}
        <div className="bg-slate-950/70 border border-slate-800/80 rounded-xl p-4 space-y-1">
          <div className="text-xs font-medium text-slate-400">Limits Configuration</div>
          <div className="flex justify-between text-xs pt-1">
            <span className="text-slate-400">Overdraft:</span>
            <span className="font-mono font-medium text-slate-200">{formatMoney(state.overdraftLimitMinor, state.currency)}</span>
          </div>
          <div className="flex justify-between text-xs">
            <span className="text-slate-400">Tx Limit:</span>
            <span className="font-mono font-medium text-slate-200">{formatMoney(state.transactionLimitMinor, state.currency)}</span>
          </div>
        </div>

        {/* Metadata Card */}
        <div className="bg-slate-950/70 border border-slate-800/80 rounded-xl p-4 space-y-1">
          <div className="text-xs font-medium text-slate-400">Stream Metadata</div>
          <div className="flex justify-between text-xs pt-1">
            <span className="text-slate-400">Sequence No:</span>
            <span className="font-mono font-bold text-cyan-400">#{state.sequenceNumber}</span>
          </div>
          <div className="flex justify-between text-xs">
            <span className="text-slate-400">Currency:</span>
            <span className="font-mono font-medium text-slate-200">{state.currency}</span>
          </div>
        </div>
      </div>
    </div>
  );
};
