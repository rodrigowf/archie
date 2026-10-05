/** Minimal typing of the `ws` client used by the mock-backend tests (no @types/ws installed). */
declare module 'ws' {
  export default class WebSocket {
    constructor(url: string);
    binaryType: string;
    readonly readyState: number;
    onopen: ((ev: unknown) => void) | null;
    onmessage: ((ev: { data: unknown }) => void) | null;
    onclose: ((ev: { code?: number; reason?: string; wasClean?: boolean }) => void) | null;
    onerror: ((ev: unknown) => void) | null;
    send(data: string): void;
    close(code?: number, reason?: string): void;
  }
}
