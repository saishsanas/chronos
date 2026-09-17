import type {
  AccountStateResponse,
  AccountSummaryResponse,
  ApiErrorResponse,
  CommandExecutionResponse,
  CorrectionDirection,
  CorrectionType,
  EventEnvelopeResponse,
  TemporalStateResponse
} from '../types/chronos';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let errorData: ApiErrorResponse;
    try {
      errorData = await response.json();
    } catch {
      errorData = {
        status: response.status,
        error: response.statusText,
        message: 'An unexpected error occurred.',
        correlationId: response.headers.get('X-Correlation-Id') || 'unknown',
        timestamp: new Date().toISOString()
      };
    }
    throw errorData;
  }
  return response.json();
}

export const chronosApi = {
  async getHealth(): Promise<{ status: string }> {
    const res = await fetch(`${BASE_URL}/actuator/health`);
    if (!res.ok) return { status: 'DOWN' };
    return res.json();
  },

  async createAccount(
    currency: string = 'USD',
    initialOverdraftLimitMinor: number = 0,
    initialTransactionLimitMinor: number = 1000000,
    idempotencyKey?: string
  ): Promise<CommandExecutionResponse> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    const res = await fetch(`${BASE_URL}/api/v1/accounts`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ currency, initialOverdraftLimitMinor, initialTransactionLimitMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async getCurrentState(accountId: string): Promise<AccountStateResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}`);
    return handleResponse<AccountStateResponse>(res);
  },

  async getAccountSummary(accountId: string): Promise<AccountSummaryResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/summary`);
    return handleResponse<AccountSummaryResponse>(res);
  },

  async getEventHistory(accountId: string): Promise<EventEnvelopeResponse[]> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/events`);
    return handleResponse<EventEnvelopeResponse[]>(res);
  },

  async getStateAt(accountId: string, atIsoString: string): Promise<TemporalStateResponse> {
    const encodedAt = encodeURIComponent(atIsoString);
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/state-at?at=${encodedAt}`);
    return handleResponse<TemporalStateResponse>(res);
  },

  async deposit(accountId: string, amountMinor: number, idempotencyKey?: string): Promise<CommandExecutionResponse> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/deposits`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ amountMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async withdraw(accountId: string, amountMinor: number, idempotencyKey?: string): Promise<CommandExecutionResponse> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/withdrawals`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ amountMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async freeze(accountId: string, reason: string): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/freeze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async unfreeze(accountId: string, reason: string): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/unfreeze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async setOverdraftLimit(accountId: string, newOverdraftLimitMinor: number): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/limits/overdraft`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newOverdraftLimitMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async setTransactionLimit(accountId: string, newTransactionLimitMinor: number): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/limits/transaction`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newTransactionLimitMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async issueCorrection(
    accountId: string,
    targetEventId: string,
    correctionType: CorrectionType,
    direction: CorrectionDirection,
    adjustmentAmountMinor: number,
    reason: string
  ): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/corrections`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        targetEventId,
        correctionType,
        direction,
        adjustmentAmountMinor,
        reason
      })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async closeAccount(accountId: string, reason: string): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/close`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    });
    return handleResponse<CommandExecutionResponse>(res);
  }
};
