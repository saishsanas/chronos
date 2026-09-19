import type {
  AccountStateResponse,
  AccountSummaryResponse,
  ApiErrorResponse,
  CommandExecutionResponse,
  CorrectionDirection,
  CorrectionType,
  EventEnvelopeResponse,
  LoginResponse,
  TemporalStateResponse
} from '../types/chronos';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

let authToken: string | null = null;

export function setAuthToken(token: string | null) {
  authToken = token;
}

export function getAuthToken(): string | null {
  return authToken;
}

function getHeaders(customHeaders: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { ...customHeaders };
  if (authToken) {
    headers['Authorization'] = `Bearer ${authToken}`;
  }
  return headers;
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let errorData: ApiErrorResponse;
    try {
      errorData = await response.json();
    } catch {
      let defaultMsg = 'An unexpected error occurred.';
      if (response.status === 401) {
        defaultMsg = 'Please sign in.';
      } else if (response.status === 403) {
        defaultMsg = 'You do not have permission for this operation.';
      }
      errorData = {
        status: response.status,
        errorCode: response.status === 401 ? 'UNAUTHORIZED' : response.status === 403 ? 'FORBIDDEN' : response.statusText,
        error: response.statusText,
        message: defaultMsg,
        correlationId: response.headers.get('X-Correlation-Id') || 'unknown',
        timestamp: new Date().toISOString()
      };
    }
    if (response.status === 401 && !errorData.message) {
      errorData.message = 'Please sign in.';
    } else if (response.status === 403 && !errorData.message) {
      errorData.message = 'You do not have permission for this operation.';
    }
    throw errorData;
  }
  return response.json();
}

export const chronosApi = {
  async login(username: string, password: string): Promise<LoginResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    const data = await handleResponse<LoginResponse>(res);
    setAuthToken(data.accessToken);
    return data;
  },

  logout() {
    setAuthToken(null);
  },

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
    const headers = getHeaders({ 'Content-Type': 'application/json' });
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    const res = await fetch(`${BASE_URL}/api/v1/accounts`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ currency, initialOverdraftLimitMinor, initialTransactionLimitMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async getCurrentState(accountId: string): Promise<AccountStateResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}`, {
      headers: getHeaders()
    });
    return handleResponse<AccountStateResponse>(res);
  },

  async getAccountSummary(accountId: string): Promise<AccountSummaryResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/summary`, {
      headers: getHeaders()
    });
    return handleResponse<AccountSummaryResponse>(res);
  },

  async getEventHistory(accountId: string): Promise<EventEnvelopeResponse[]> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/events`, {
      headers: getHeaders()
    });
    return handleResponse<EventEnvelopeResponse[]>(res);
  },

  async getStateAt(accountId: string, atIsoString: string): Promise<TemporalStateResponse> {
    const encodedAt = encodeURIComponent(atIsoString);
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/state-at?at=${encodedAt}`, {
      headers: getHeaders()
    });
    return handleResponse<TemporalStateResponse>(res);
  },

  async deposit(accountId: string, amountMinor: number, idempotencyKey?: string): Promise<CommandExecutionResponse> {
    const headers = getHeaders({ 'Content-Type': 'application/json' });
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/deposits`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ amountMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async withdraw(accountId: string, amountMinor: number, idempotencyKey?: string): Promise<CommandExecutionResponse> {
    const headers = getHeaders({ 'Content-Type': 'application/json' });
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
      headers: getHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ reason })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async unfreeze(accountId: string, reason: string): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/unfreeze`, {
      method: 'POST',
      headers: getHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ reason })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async setOverdraftLimit(accountId: string, newOverdraftLimitMinor: number): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/limits/overdraft`, {
      method: 'PUT',
      headers: getHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ newOverdraftLimitMinor })
    });
    return handleResponse<CommandExecutionResponse>(res);
  },

  async setTransactionLimit(accountId: string, newTransactionLimitMinor: number): Promise<CommandExecutionResponse> {
    const res = await fetch(`${BASE_URL}/api/v1/accounts/${accountId}/limits/transaction`, {
      method: 'PUT',
      headers: getHeaders({ 'Content-Type': 'application/json' }),
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
      headers: getHeaders({ 'Content-Type': 'application/json' }),
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
      headers: getHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ reason })
    });
    return handleResponse<CommandExecutionResponse>(res);
  }
};
