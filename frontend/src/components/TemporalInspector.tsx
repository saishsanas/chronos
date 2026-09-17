import React, { useState } from 'react';
import { History, Play } from 'lucide-react';
import { chronosApi } from '../api/client';
import type { TemporalStateResponse } from '../types/chronos';
import { StateComparison } from './StateComparison';

interface TemporalInspectorProps {
  accountId: string;
  currentState: any;
  events: any[];
  onError: (err: any) => void;
}

export const TemporalInspector: React.FC<TemporalInspectorProps> = ({
  accountId,
  currentState,
  events,
  onError
}) => {
  const [targetIso, setTargetIso] = useState<string>(new Date().toISOString().slice(0, 16));
  const [temporalState, setTemporalState] = useState<TemporalStateResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(false);

  const handleQueryStateAt = async (isoString: string) => {
    if (!accountId) return;
    setLoading(true);
    try {
      const fullIso = new Date(isoString).toISOString();
      const res = await chronosApi.getStateAt(accountId, fullIso);
      setTemporalState(res);
    } catch (err) {
      onError(err);
      setTemporalState(null);
    } finally {
      setLoading(false);
    }
  };

  const handleQuickJump = (minutesAgo: number) => {
    const target = new Date(Date.now() - minutesAgo * 60 * 1000);
    const isoLocal = target.toISOString().slice(0, 16);
    setTargetIso(isoLocal);
    handleQueryStateAt(target.toISOString());
  };

  const handleSelectEventTimestamp = (recordedAt: string) => {
    const target = new Date(recordedAt);
    setTargetIso(target.toISOString().slice(0, 16));
    handleQueryStateAt(target.toISOString());
  };

  return (
    <div className="bg-slate-900 border border-purple-500/20 rounded-2xl p-5 shadow-xl space-y-5">
      <div className="flex items-center justify-between border-b border-slate-800 pb-3">
        <div className="flex items-center gap-2">
          <History className="w-5 h-5 text-purple-400" />
          <h2 className="text-sm font-bold text-slate-300 uppercase tracking-wider">
            Temporal Replay Inspector (stateAt T)
          </h2>
        </div>
        <span className="text-xs px-2.5 py-0.5 rounded-full font-mono bg-purple-500/10 text-purple-300 border border-purple-500/30">
          recordedAt &lt;= T
        </span>
      </div>

      {/* Target Timestamp Picker & Quick Jumps */}
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[240px]">
            <input
              type="datetime-local"
              value={targetIso}
              onChange={(e) => setTargetIso(e.target.value)}
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-sm font-mono focus:border-purple-500 focus:outline-none"
            />
          </div>
          <button
            onClick={() => handleQueryStateAt(targetIso)}
            disabled={loading}
            className="px-4 py-2 bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white font-medium text-sm rounded-lg transition-colors flex items-center gap-1.5 shadow-md shadow-purple-950"
          >
            <Play className="w-4 h-4 fill-current" />
            {loading ? 'Replaying...' : 'Inspect State at T'}
          </button>
        </div>

        {/* Quick Jumps */}
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="text-slate-500 font-medium">Quick Time Travel:</span>
          <button
            onClick={() => handleQuickJump(0)}
            className="px-2.5 py-1 bg-slate-950 hover:bg-slate-800 border border-slate-800 text-slate-300 rounded-md font-mono"
          >
            Now (t=0)
          </button>
          <button
            onClick={() => handleQuickJump(5)}
            className="px-2.5 py-1 bg-slate-950 hover:bg-slate-800 border border-slate-800 text-slate-300 rounded-md font-mono"
          >
            -5 mins
          </button>
          <button
            onClick={() => handleQuickJump(60)}
            className="px-2.5 py-1 bg-slate-950 hover:bg-slate-800 border border-slate-800 text-slate-300 rounded-md font-mono"
          >
            -1 hour
          </button>
          <button
            onClick={() => handleQuickJump(1440)}
            className="px-2.5 py-1 bg-slate-950 hover:bg-slate-800 border border-slate-800 text-slate-300 rounded-md font-mono"
          >
            -24 hours
          </button>
        </div>

        {/* Quick Event Jump Buttons */}
        {events.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5 text-[11px] pt-1">
            <span className="text-slate-500 font-medium">Jump to Event:</span>
            {events.slice(0, 6).map((e) => (
              <button
                key={e.eventId}
                onClick={() => handleSelectEventTimestamp(e.recordedAt)}
                className="px-2 py-0.5 bg-slate-950 hover:bg-purple-950/60 border border-slate-800 hover:border-purple-500/50 text-slate-400 hover:text-purple-300 rounded font-mono transition-colors"
              >
                #{e.sequenceNumber} ({e.eventType})
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Replay Execution Result Header */}
      {temporalState && (
        <div className="space-y-4 pt-2 border-t border-slate-800">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Target Time T</div>
              <div className="font-mono text-purple-300 font-medium truncate">
                {new Date(temporalState.targetTimestamp).toLocaleTimeString()}
              </div>
            </div>
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Replay Sequence</div>
              <div className="font-mono font-bold text-slate-200">#{temporalState.sequenceNumber}</div>
            </div>
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Snapshot Used</div>
              <div className="font-mono text-slate-300 font-semibold">
                {temporalState.snapshotSequenceUsed ? `#${temporalState.snapshotSequenceUsed}` : 'None (Genesis)'}
              </div>
            </div>
            <div className="bg-slate-950 p-2.5 rounded-lg border border-slate-800">
              <div className="text-slate-500">Events Replayed</div>
              <div className="font-mono text-emerald-400 font-bold">{temporalState.eventsReplayed}</div>
            </div>
          </div>

          {/* Side-by-side comparison */}
          {currentState && (
            <StateComparison
              currentState={currentState}
              historicalState={temporalState.reconstructedState}
              historicalSequence={temporalState.sequenceNumber}
            />
          )}
        </div>
      )}
    </div>
  );
};
