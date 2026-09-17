import React, { useState } from 'react';
import { PlusCircle, RefreshCw, Search, Sparkles } from 'lucide-react';
import { chronosApi } from '../api/client';
import type { CommandExecutionResponse } from '../types/chronos';

interface AccountSelectorProps {
  currentAccountId: string;
  onSelectAccount: (accountId: string) => void;
  onCommandSuccess: (res: CommandExecutionResponse) => void;
  onError: (err: any) => void;
}

export const AccountSelector: React.FC<AccountSelectorProps> = ({
  currentAccountId,
  onSelectAccount,
  onCommandSuccess,
  onError
}) => {
  const [inputUuid, setInputUuid] = useState(currentAccountId);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [currency, setCurrency] = useState('USD');
  const [initialOverdraft, setInitialOverdraft] = useState(0);
  const [initialTxLimit, setInitialTxLimit] = useState(100000);
  const [loading, setLoading] = useState(false);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (inputUuid.trim()) {
      onSelectAccount(inputUuid.trim());
    }
  };

  const handleGenerateRandom = () => {
    const randomUuid = crypto.randomUUID();
    setInputUuid(randomUuid);
  };

  const handleCreateAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      const res = await chronosApi.createAccount(
        currency,
        Number(initialOverdraft),
        Number(initialTxLimit)
      );
      onCommandSuccess(res);
      onSelectAccount(res.aggregateId);
      setInputUuid(res.aggregateId);
      setShowCreateModal(false);
    } catch (err) {
      onError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-lg flex flex-wrap items-center justify-between gap-4">
      {/* Search Account */}
      <form onSubmit={handleSearch} className="flex items-center gap-2 flex-1 min-w-[320px]">
        <div className="relative flex-1">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            value={inputUuid}
            onChange={(e) => setInputUuid(e.target.value)}
            placeholder="Enter Bank Account UUID..."
            className="w-full pl-9 pr-3 py-2 text-sm bg-slate-950 border border-slate-800 rounded-lg text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500 font-mono"
          />
        </div>
        <button
          type="submit"
          className="px-4 py-2 bg-cyan-600 hover:bg-cyan-500 text-white font-medium text-sm rounded-lg transition-colors flex items-center gap-1.5 shadow-sm shadow-cyan-900/50"
        >
          Load Stream
        </button>
        <button
          type="button"
          onClick={handleGenerateRandom}
          title="Generate Random UUID"
          className="p-2 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-lg border border-slate-700 transition-colors"
        >
          <RefreshCw className="w-4 h-4" />
        </button>
      </form>

      {/* Create Account Trigger */}
      <button
        onClick={() => setShowCreateModal(true)}
        className="px-4 py-2 bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-medium text-sm rounded-lg transition-all flex items-center gap-2 shadow-md shadow-emerald-950/50"
      >
        <PlusCircle className="w-4 h-4" />
        Create New Account
      </button>

      {/* Create Account Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 w-full max-w-md shadow-2xl space-y-5">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div className="flex items-center gap-2">
                <Sparkles className="w-5 h-5 text-emerald-400" />
                <h3 className="text-lg font-bold text-slate-100">Initialize Bank Account</h3>
              </div>
              <button
                onClick={() => setShowCreateModal(false)}
                className="text-slate-400 hover:text-slate-200 text-sm font-bold"
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleCreateAccount} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-400 mb-1">Currency Code</label>
                <select
                  value={currency}
                  onChange={(e) => setCurrency(e.target.value)}
                  className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-sm focus:border-emerald-500"
                >
                  <option value="USD">USD - US Dollar ($)</option>
                  <option value="EUR">EUR - Euro (€)</option>
                  <option value="GBP">GBP - British Pound (£)</option>
                </select>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-400 mb-1">
                  Initial Overdraft Limit (Minor Units e.g. 50000 = $500.00)
                </label>
                <input
                  type="number"
                  value={initialOverdraft}
                  onChange={(e) => setInitialOverdraft(Number(e.target.value))}
                  min="0"
                  className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-sm font-mono focus:border-emerald-500"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-400 mb-1">
                  Initial Single Transaction Limit (Minor Units e.g. 1000000 = $10,000.00)
                </label>
                <input
                  type="number"
                  value={initialTxLimit}
                  onChange={(e) => setInitialTxLimit(Number(e.target.value))}
                  min="1"
                  className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-slate-100 text-sm font-mono focus:border-emerald-500"
                />
              </div>

              <div className="pt-2 flex justify-end gap-3">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 text-sm text-slate-400 hover:text-slate-200"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={loading}
                  className="px-5 py-2 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-medium text-sm rounded-lg transition-colors shadow-lg shadow-emerald-950"
                >
                  {loading ? 'Creating...' : 'Initialize Account'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
