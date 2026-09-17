import React, { useEffect, useState } from 'react';
import { Navbar } from './components/Navbar';
import { AccountSelector } from './components/AccountSelector';
import { CurrentStateCard } from './components/CurrentStateCard';
import { SummaryCard } from './components/SummaryCard';
import { CommandPanel } from './components/CommandPanel';
import { EventTimeline } from './components/EventTimeline';
import { TemporalInspector } from './components/TemporalInspector';
import { ErrorAlert } from './components/ErrorAlert';
import { EventDetailsModal } from './components/EventDetailsModal';
import { chronosApi } from './api/client';
import type {
  AccountStateResponse,
  AccountSummaryResponse,
  CommandExecutionResponse,
  EventEnvelopeResponse
} from './types/chronos';

export const App: React.FC = () => {
  const [accountId, setAccountId] = useState<string>('');
  const [currentState, setCurrentState] = useState<AccountStateResponse | null>(null);
  const [summary, setSummary] = useState<AccountSummaryResponse | null>(null);
  const [events, setEvents] = useState<EventEnvelopeResponse[]>([]);
  const [selectedEvent, setSelectedEvent] = useState<EventEnvelopeResponse | null>(null);
  const [error, setError] = useState<any | null>(null);
  const [loading, setLoading] = useState<boolean>(false);

  const fetchAccountData = async (id: string) => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const [stateRes, summaryRes, eventsRes] = await Promise.allSettled([
        chronosApi.getCurrentState(id),
        chronosApi.getAccountSummary(id),
        chronosApi.getEventHistory(id)
      ]);

      if (stateRes.status === 'fulfilled') {
        setCurrentState(stateRes.value);
      } else {
        setCurrentState(null);
      }

      if (summaryRes.status === 'fulfilled') {
        setSummary(summaryRes.value);
      } else {
        setSummary(null);
      }

      if (eventsRes.status === 'fulfilled') {
        setEvents(eventsRes.value);
      } else {
        setEvents([]);
      }
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (accountId) {
      fetchAccountData(accountId);
    }
  }, [accountId]);

  const handleCommandSuccess = (_res: CommandExecutionResponse) => {
    setError(null);
    if (accountId) {
      fetchAccountData(accountId);
    }
  };

  return (
    <div className="min-h-screen bg-[#0b0f19] text-slate-100 font-sans flex flex-col selection:bg-cyan-500/30 selection:text-cyan-200">
      <Navbar />

      <main className="flex-1 max-w-7xl w-full mx-auto p-4 md:p-6 space-y-6">
        {/* Error Notification Banner */}
        <ErrorAlert error={error} onClear={() => setError(null)} />

        {/* Account Selector */}
        <AccountSelector
          currentAccountId={accountId}
          onSelectAccount={(id) => setAccountId(id)}
          onCommandSuccess={handleCommandSuccess}
          onError={(err) => setError(err)}
        />

        {/* State Overview & Summary Grid */}
        <div className="space-y-6">
          <CurrentStateCard state={currentState} loading={loading} />
          {accountId && <SummaryCard summary={summary} loading={loading} />}
        </div>

        {/* Action Panel & Temporal Inspector Side-by-Side */}
        {accountId && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <CommandPanel
              accountId={accountId}
              onCommandSuccess={handleCommandSuccess}
              onError={(err) => setError(err)}
            />
            <TemporalInspector
              accountId={accountId}
              currentState={currentState}
              events={events}
              onError={(err) => setError(err)}
            />
          </div>
        )}

        {/* Event Timeline */}
        {accountId && (
          <EventTimeline
            events={events}
            loading={loading}
            onSelectEvent={(evt) => setSelectedEvent(evt)}
          />
        )}
      </main>

      {/* Event JSON Modal */}
      <EventDetailsModal event={selectedEvent} onClose={() => setSelectedEvent(null)} />

      {/* Footer */}
      <footer className="border-t border-slate-900 bg-slate-950 py-4 px-6 text-center text-xs font-mono text-slate-500">
        Chronos Engine v1.0 • Distributed Event Sourcing & CQRS Framework • Built for High Throughput & Deterministic Replay
      </footer>
    </div>
  );
};

export default App;
