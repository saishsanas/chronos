import React from 'react';
import { TrendingDown, TrendingUp, Minus } from 'lucide-react';
import type { AccountStateResponse } from '../types/chronos';
import { formatMoney } from './CurrentStateCard';

interface StateComparisonProps {
  currentState: AccountStateResponse;
  historicalState: AccountStateResponse;
  historicalSequence: number;
}

export const StateComparison: React.FC<StateComparisonProps> = ({
  currentState,
  historicalState,
  historicalSequence
}) => {
  const balanceDelta = currentState.balanceMinor - historicalState.balanceMinor;
  const sequenceDelta = currentState.sequenceNumber - historicalSequence;

  return (
    <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 space-y-3">
      <div className="text-xs font-bold text-slate-400 uppercase tracking-wider">
        Head State vs Historical Replay Delta
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 items-center">
        {/* Historical State */}
        <div className="bg-slate-900 p-3 rounded-lg border border-slate-800 space-y-1">
          <div className="text-[11px] text-purple-400 font-semibold font-mono">
            State at T (Seq #{historicalSequence})
          </div>
          <div className="text-lg font-bold font-mono text-purple-200">
            {formatMoney(historicalState.balanceMinor, historicalState.currency)}
          </div>
          <div className="text-xs text-slate-400">Status: {historicalState.status}</div>
        </div>

        {/* Delta Card */}
        <div className="bg-slate-900/90 p-3 rounded-lg border border-purple-500/30 text-center space-y-1">
          <div className="text-[11px] text-slate-400 font-semibold uppercase">Replay Delta</div>
          <div className="flex items-center justify-center gap-1.5 text-base font-bold font-mono">
            {balanceDelta > 0 && (
              <span className="text-emerald-400 flex items-center gap-1">
                <TrendingUp className="w-4 h-4" /> +{formatMoney(balanceDelta, currentState.currency)}
              </span>
            )}
            {balanceDelta < 0 && (
              <span className="text-amber-400 flex items-center gap-1">
                <TrendingDown className="w-4 h-4" /> -{formatMoney(Math.abs(balanceDelta), currentState.currency)}
              </span>
            )}
            {balanceDelta === 0 && (
              <span className="text-slate-400 flex items-center gap-1">
                <Minus className="w-4 h-4" /> No Change
              </span>
            )}
          </div>
          <div className="text-[11px] text-slate-500 font-mono">
            {sequenceDelta} new event(s) processed since T
          </div>
        </div>

        {/* Current Head State */}
        <div className="bg-slate-900 p-3 rounded-lg border border-slate-800 space-y-1">
          <div className="text-[11px] text-cyan-400 font-semibold font-mono">
            Current Head (Seq #{currentState.sequenceNumber})
          </div>
          <div className="text-lg font-bold font-mono text-cyan-200">
            {formatMoney(currentState.balanceMinor, currentState.currency)}
          </div>
          <div className="text-xs text-slate-400">Status: {currentState.status}</div>
        </div>
      </div>
    </div>
  );
};
