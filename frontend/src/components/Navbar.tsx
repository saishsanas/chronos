import React, { useEffect, useState } from 'react';
import { Activity, BookOpen, Clock } from 'lucide-react';
import { chronosApi } from '../api/client';

export const Navbar: React.FC = () => {
  const [health, setHealth] = useState<'UP' | 'DOWN' | 'CHECKING'>('CHECKING');

  const checkHealth = async () => {
    try {
      const res = await chronosApi.getHealth();
      setHealth(res.status === 'UP' ? 'UP' : 'DOWN');
    } catch {
      setHealth('DOWN');
    }
  };

  useEffect(() => {
    checkHealth();
    const interval = setInterval(checkHealth, 10000);
    return () => clearInterval(interval);
  }, []);

  return (
    <header className="border-b border-slate-800 bg-slate-900/90 backdrop-blur sticky top-0 z-50 px-6 py-3.5 flex flex-wrap items-center justify-between gap-4 shadow-xl">
      <div className="flex items-center gap-3">
        <div className="p-2 bg-cyan-500/10 border border-cyan-500/30 rounded-xl text-cyan-400 flex items-center justify-center">
          <Clock className="w-6 h-6 animate-pulse" />
        </div>
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-bold tracking-tight bg-gradient-to-r from-cyan-400 via-sky-300 to-indigo-400 bg-clip-text text-transparent">
              CHRONOS ENGINE
            </h1>
            <span className="text-xs px-2 py-0.5 rounded-full font-mono bg-cyan-500/10 text-cyan-300 border border-cyan-500/30 font-medium">
              v1.0.0
            </span>
          </div>
          <p className="text-xs text-slate-400 font-medium">
            Event-Sourced Bank Core • OCC • Outbox Kafka • CQRS Read Model • Temporal Replay
          </p>
        </div>
      </div>

      <div className="flex items-center gap-4">
        {/* Actuator Status Pill */}
        <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-950/80 border border-slate-800 text-xs font-mono">
          <Activity className="w-3.5 h-3.5 text-slate-400" />
          <span className="text-slate-400">Backend API:</span>
          {health === 'CHECKING' && (
            <span className="flex items-center gap-1.5 text-amber-400">
              <span className="w-2 h-2 rounded-full bg-amber-400 animate-ping" /> Checking...
            </span>
          )}
          {health === 'UP' && (
            <span className="flex items-center gap-1.5 text-emerald-400 font-semibold">
              <span className="w-2 h-2 rounded-full bg-emerald-400 shadow-sm shadow-emerald-400/50" /> ONLINE
            </span>
          )}
          {health === 'DOWN' && (
            <span className="flex items-center gap-1.5 text-rose-400 font-semibold">
              <span className="w-2 h-2 rounded-full bg-rose-500" /> OFFLINE
            </span>
          )}
        </div>

        {/* Swagger link */}
        <a
          href="http://localhost:8080/swagger-ui.html"
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-300 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg transition-colors"
        >
          <BookOpen className="w-3.5 h-3.5 text-sky-400" />
          OpenAPI / Swagger
        </a>
      </div>
    </header>
  );
};
