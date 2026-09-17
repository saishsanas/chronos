export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED' | 'UNINITIALIZED';

export type CorrectionType = 'CREDIT_ADJUSTMENT' | 'DEBIT_ADJUSTMENT' | 'FEE_REVERSAL' | 'INTEREST_CORRECTION';
export type CorrectionDirection = 'INCREASE_BALANCE' | 'DECREASE_BALANCE';

export interface AccountStateResponse {
  accountId: string;
  status: AccountStatus;
  currency: string;
  balanceMinor: number;
  overdraftLimitMinor: number;
  transactionLimitMinor: number;
  sequenceNumber: number;
}

export interface TemporalStateResponse {
  accountId: string;
  reconstructedState: AccountStateResponse;
  sequenceNumber: number;
  snapshotSequenceUsed: number | null;
  eventsReplayed: number;
  targetTimestamp: string;
}

export interface EventEnvelopeResponse {
  eventId: string;
  aggregateId: string;
  eventType: string;
  payload: Record<string, any>;
  sequenceNumber: number;
  recordedAt: string;
  version: number;
  metadata: {
    correlationId?: string;
    causationId?: string;
    actor?: string;
    idempotencyKey?: string;
  };
}

export interface AccountSummaryResponse {
  accountId: string;
  currency: string;
  balanceMinor: number;
  status: AccountStatus;
  overdraftLimitMinor: number;
  transactionLimitMinor: number;
  lastSequenceNumber: number;
  lastUpdated: string;
  cached: boolean;
}

export interface CommandExecutionResponse {
  aggregateId: string;
  producedSequenceNumber: number;
  eventId: string;
  correlationId: string;
}

export interface ApiErrorResponse {
  status: number;
  error: string;
  message: string;
  correlationId: string;
  timestamp: string;
}
