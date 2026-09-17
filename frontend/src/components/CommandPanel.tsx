import React, { useState } from 'react';
import {
  ArrowDownCircle,
  ArrowUpCircle,
  CheckCircle2,
  Edit3,
  Lock,
  Play,
  Sliders,
  Unlock,
  XCircle,
  Zap
} from 'lucide-react';
import { chronosApi } from '../api/client';
import type {
  CommandExecutionResponse,
  CorrectionDirection,
  CorrectionType
} from '../types/chronos';

interface CommandPanelProps {
  accountId: string;
  onCommandSuccess: (res: CommandExecutionResponse) => void;
  onError: (err: any) => void;
}

export const CommandPanel: React.FC<CommandPanelProps> = ({
  accountId,
  onCommandSuccess,
  onError
}) => {
  const [activeTab, setActiveTab] = useState<'deposit' | 'withdraw' | 'freeze' | 'unfreeze' | 'overdraft' | 'txlimit' | 'correction' | 'close'>('deposit');
  const [amountMinor, setAmountMinor] = useState<number>(10000);
  const [reason, setReason] = useState<string>('Standard business operation');
  const [newOverdraft, setNewOverdraft] = useState<number>(50000);
  const [newTxLimit, setNewTxLimit] = useState<number>(500000);
  const [targetEventId, setTargetEventId] = useState<string>('');
  const [correctionType, setCorrectionType] = useState<CorrectionType>('CREDIT_ADJUSTMENT');
  const [direction, setDirection] = useState<CorrectionDirection>('INCREASE_BALANCE');
  const [useIdempotency, setUseIdempotency] = useState<boolean>(false);
  const [idempotencyKey, setIdempotencyKey] = useState<string>(`idemp-${Date.now()}`);
  const [loading, setLoading] = useState<boolean>(false);
  const [lastSuccess, setLastSuccess] = useState<CommandExecutionResponse | null>(null);

  const handleExecute = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!accountId) return;
    setLoading(true);
    setLastSuccess(null);

    const key = useIdempotency ? idempotencyKey : undefined;

    try {
      let res: CommandExecutionResponse;
      switch (activeTab) {
        case 'deposit':
          res = await chronosApi.deposit(accountId, Number(amountMinor), key);
          break;
        case 'withdraw':
          res = await chronosApi.withdraw(accountId, Number(amountMinor), key);
          break;
        case 'freeze':
          res = await chronosApi.freeze(accountId, reason);
          break;
        case 'unfreeze':
          res = await chronosApi.unfreeze(accountId, reason);
          break;
        case 'overdraft':
          res = await chronosApi.setOverdraftLimit(accountId, Number(newOverdraft));
          break;
        case 'txlimit':
          res = await chronosApi.setTransactionLimit(accountId, Number(newTxLimit));
          break;
        case 'correction':
          res = await chronosApi.issueCorrection(
            accountId,
            targetEventId || crypto.randomUUID(),
            correctionType,
            direction,
            Number(amountMinor),
            reason
          );
          break;
        case 'close':
          res = await chronosApi.closeAccount(accountId, reason);
          break;
        default:
          throw new Error('Unknown command tab');
      }

      setLastSuccess(res);
      onCommandSuccess(res);
      if (useIdempotency) {
        setIdempotencyKey(`idemp-${Date.now()}`);
      }
    } catch (err) {
      onError(err);
    } finally {
      setLoading(false);
    }
  };

  const tabs = [
    { id: 'deposit', label: 'Deposit', icon: <ArrowDownCircle className="w-3.5 h-3.5" /> },
    { id: 'withdraw', label: 'Withdraw', icon: <ArrowUpCircle className="w-3.5 h-3.5" /> },
    { id: 'freeze', label: 'Freeze', icon: <Lock className="w-3.5 h-3.5" /> },
    { id: 'unfreeze', label: 'Unfreeze', icon: <Unlock className="w-3.5 h-3.5" /> },
    { id: 'overdraft', label: 'Overdraft', icon: <Sliders className="w-3.5 h-3.5" /> },
    { id: 'txlimit', label: 'Tx Limit', icon: <Sliders className="w-3.5 h-3.5" /> },
    { id: 'correction', label: 'Correction', icon: <Edit3 className="w-3.5 h-3.5" /> },
    { id: 'close', label: 'Close Account', icon: <XCircle className="w-3.5 h-3.5 text-rose-400" /> }
  ] as const;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-4">
      <div className="flex items-center justify-between border-b border-slate-800 pb-3">
        <div className="flex items-center gap-2">
          <Zap className="w-5 h-5 text-amber-400" />
          <h2 className="text-sm font-bold text-slate-300 uppercase tracking-wider">
            Execute Domain Commands
          </h2>
        </div>

        {/* Idempotency Key Toggle */}
        <label className="flex items-center gap-2 text-xs font-mono text-slate-400 cursor-pointer">
          <input
            type="checkbox"
            checked={useIdempotency}
            onChange={(e) => setUseIdempotency(e.target.checked)}
            className="rounded border-slate-800 bg-slate-950 text-amber-500 focus:ring-0"
          />
          Idempotency Key
        </label>
      </div>

      {useIdempotency && (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-2.5 flex items-center gap-2 text-xs font-mono">
          <span className="text-amber-400 font-semibold">Header Idempotency-Key:</span>
          <input
            type="text"
            value={idempotencyKey}
            onChange={(e) => setIdempotencyKey(e.target.value)}
            className="flex-1 bg-slate-950 border border-slate-800 px-2 py-1 rounded text-amber-300 text-xs font-mono"
          />
        </div>
      )}

      {/* Tabs */}
      <div className="flex flex-wrap gap-1.5 border-b border-slate-800 pb-2">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setActiveTab(t.id as any)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium flex items-center gap-1.5 transition-all ${
              activeTab === t.id
                ? 'bg-cyan-600 text-white font-semibold shadow-md shadow-cyan-950'
                : 'bg-slate-950 text-slate-400 hover:text-slate-200 hover:bg-slate-800'
            }`}
          >
            {t.icon}
            {t.label}
          </button>
        ))}
      </div>

      {/* Active Form */}
      <form onSubmit={handleExecute} className="space-y-4 pt-1">
        {(activeTab === 'deposit' || activeTab === 'withdraw') && (
          <div>
            <label className="block text-xs font-semibold text-slate-400 mb-1">
              Amount (Minor Units e.g. 10000 = $100.00)
            </label>
            <input
              type="number"
              value={amountMinor}
              onChange={(e) => setAmountMinor(Number(e.target.value))}
              min="1"
              required
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 font-mono text-sm focus:border-cyan-500"
            />
          </div>
        )}

        {(activeTab === 'freeze' || activeTab === 'unfreeze' || activeTab === 'close') && (
          <div>
            <label className="block text-xs font-semibold text-slate-400 mb-1">Audit Reason</label>
            <input
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              required
              placeholder="Specify compliance or administrative reason..."
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-sm focus:border-cyan-500"
            />
          </div>
        )}

        {activeTab === 'overdraft' && (
          <div>
            <label className="block text-xs font-semibold text-slate-400 mb-1">
              New Overdraft Limit (Minor Units e.g. 50000 = $500.00)
            </label>
            <input
              type="number"
              value={newOverdraft}
              onChange={(e) => setNewOverdraft(Number(e.target.value))}
              min="0"
              required
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 font-mono text-sm focus:border-cyan-500"
            />
          </div>
        )}

        {activeTab === 'txlimit' && (
          <div>
            <label className="block text-xs font-semibold text-slate-400 mb-1">
              New Single Transaction Limit (Minor Units)
            </label>
            <input
              type="number"
              value={newTxLimit}
              onChange={(e) => setNewTxLimit(Number(e.target.value))}
              min="1"
              required
              className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 font-mono text-sm focus:border-cyan-500"
            />
          </div>
        )}

        {activeTab === 'correction' && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-semibold text-slate-400 mb-1">Target Event UUID</label>
              <input
                type="text"
                value={targetEventId}
                onChange={(e) => setTargetEventId(e.target.value)}
                placeholder="Target event UUID (or auto-generate)"
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-xs font-mono"
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-400 mb-1">Correction Type</label>
              <select
                value={correctionType}
                onChange={(e) => setCorrectionType(e.target.value as CorrectionType)}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-xs"
              >
                <option value="CREDIT_ADJUSTMENT">CREDIT_ADJUSTMENT</option>
                <option value="DEBIT_ADJUSTMENT">DEBIT_ADJUSTMENT</option>
                <option value="FEE_REVERSAL">FEE_REVERSAL</option>
                <option value="INTEREST_CORRECTION">INTEREST_CORRECTION</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-400 mb-1">Direction</label>
              <select
                value={direction}
                onChange={(e) => setDirection(e.target.value as CorrectionDirection)}
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-xs"
              >
                <option value="INCREASE_BALANCE">INCREASE_BALANCE</option>
                <option value="DECREASE_BALANCE">DECREASE_BALANCE</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-400 mb-1">Adjustment Amount Minor</label>
              <input
                type="number"
                value={amountMinor}
                onChange={(e) => setAmountMinor(Number(e.target.value))}
                min="1"
                className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-xs font-mono"
              />
            </div>
          </div>
        )}

        <button
          type="submit"
          disabled={loading || !accountId}
          className="w-full py-2.5 bg-gradient-to-r from-cyan-600 to-sky-600 hover:from-cyan-500 hover:to-sky-500 disabled:opacity-50 text-white font-semibold text-sm rounded-lg transition-all flex items-center justify-center gap-2 shadow-lg shadow-cyan-950"
        >
          <Play className="w-4 h-4 fill-current" />
          {loading ? 'Executing Command...' : `Dispatch ${activeTab.toUpperCase()} Command`}
        </button>
      </form>

      {/* Success Feedback Banner */}
      {lastSuccess && (
        <div className="bg-emerald-500/10 border border-emerald-500/30 rounded-xl p-3.5 space-y-1 text-xs font-mono">
          <div className="flex items-center gap-1.5 text-emerald-400 font-bold">
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
            Command Appended Successfully (Seq #{lastSuccess.producedSequenceNumber})
          </div>
          <div className="text-slate-400 text-[11px]">Event ID: {lastSuccess.eventId}</div>
          <div className="text-indigo-300 text-[11px]">Correlation ID: {lastSuccess.correlationId}</div>
        </div>
      )}
    </div>
  );
};
