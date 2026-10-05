/**
 * Typed REST errors (spec 13 §3.4). The backend `detail` is kept **verbatim** so the UI shows
 * "Directory does not exist: /x" instead of "400 Bad Request" (inv02 §6.2, CFG-2, W-6.2).
 */

/** Any non-2xx answer from the backend. */
export class ApiError extends Error {
  readonly status: number;
  /** Human message: the backend `detail` string, joined 422 messages, or a short body excerpt. */
  readonly detail: string;
  /** Parsed JSON body, or the raw text. */
  readonly raw: unknown;

  constructor(status: number, detail: string, raw: unknown) {
    super(detail);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
    this.raw = raw;
  }
}

/** nginx rejected the body (HTML 413, G-1: default `client_max_body_size` 1 MiB until BF-3). */
export class PayloadTooLargeError extends ApiError {
  constructor(raw: unknown) {
    super(413, PAYLOAD_TOO_LARGE_MESSAGE, raw);
    this.name = 'PayloadTooLargeError';
  }
}

export const PAYLOAD_TOO_LARGE_MESSAGE = 'File too large for the server (nginx limit 1 MB)';

/** The request never got an HTTP answer (offline, DNS, TLS, CORS, connection reset). */
export class NetworkError extends Error {
  constructor(message = "Can't reach the server") {
    super(message);
    this.name = 'NetworkError';
  }
}

export class TimeoutError extends Error {
  constructor(message = 'The server took too long to answer') {
    super(message);
    this.name = 'TimeoutError';
  }
}

export class AbortedError extends Error {
  constructor() {
    super('Cancelled');
    this.name = 'AbortedError';
  }
}

/** One line for a snackbar or banner: the verbatim detail for API errors. */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.detail;
  if (err instanceof Error && err.message) return err.message;
  if (typeof err === 'string' && err) return err;
  return 'Something went wrong';
}

export function isApiError(err: unknown, status?: number): err is ApiError {
  return err instanceof ApiError && (status === undefined || err.status === status);
}

function textOfDetail(detail: unknown): string | null {
  if (typeof detail === 'string') return detail;
  if (Array.isArray(detail)) {
    // FastAPI 422: [{loc, msg, type}]
    const msgs = detail
      .map((d) => {
        if (!d || typeof d !== 'object') return typeof d === 'string' ? d : '';
        const o = d as { msg?: unknown; loc?: unknown };
        const loc = Array.isArray(o.loc) ? o.loc.filter((x) => x !== 'body').join('.') : '';
        const msg = typeof o.msg === 'string' ? o.msg : '';
        return loc && msg ? `${loc}: ${msg}` : msg;
      })
      .filter(Boolean);
    return msgs.length ? msgs.join('; ') : null;
  }
  return null;
}

const isHtml = (contentType: string, body: string): boolean =>
  /text\/html/i.test(contentType) || /^\s*<(!doctype|html)/i.test(body);

/** Build the error for a non-2xx response from its status, content type and body text. */
export function errorFromResponse(status: number, contentType: string, body: string): ApiError {
  if (status === 413 && isHtml(contentType, body)) return new PayloadTooLargeError(body);
  let raw: unknown = body;
  let detail: string | null = null;
  if (body) {
    try {
      raw = JSON.parse(body);
      if (raw && typeof raw === 'object') detail = textOfDetail((raw as { detail?: unknown }).detail);
    } catch {
      raw = body;
    }
  }
  if (detail === null) {
    if (body && !isHtml(contentType, body)) detail = body.trim().slice(0, 300);
    else detail = `${status} ${STATUS_TEXT[status] ?? 'Error'}`;
  }
  return new ApiError(status, detail, raw);
}

const STATUS_TEXT: Record<number, string> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  405: 'Method Not Allowed',
  409: 'Conflict',
  413: 'Payload Too Large',
  422: 'Unprocessable Entity',
  500: 'Internal Server Error',
  502: 'Bad Gateway',
  503: 'Service Unavailable',
  504: 'Gateway Timeout',
};
