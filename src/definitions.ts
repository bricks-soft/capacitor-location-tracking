import type { PluginListenerHandle } from '@capacitor/core';

export type Json = null | boolean | number | string | Json[] |
  { [key: string]: Json };
export type Provider = 'auto' | 'gms' | 'hms' | 'platform';
export type LogLevel = 'error' | 'warn' | 'info' | 'debug';
export interface Scope { scopeKey: string; sessionId: string }
export interface Authorization {
  scopeKey: string;
  revision: number; // monotonic per scope; reject stale writes
  headers: Record<string, string>; // e.g. Authorization supplied by JS
  expiresAtEpochMs?: number;
}
export interface TrackingConfig extends Scope {
  schemaVersion: 1;
  provider: Provider;
  allowPlatformFallback: boolean;
  intervalMs: number;
  minUpdateIntervalMs: number;
  heartbeatIntervalSeconds: number; // 0 disables diagnostic heartbeat
  accuracy: 'high' | 'balanced' | 'low';
  stopAtEpochMs: number | null; // absolute; never recompute on boot
  startOnBoot: boolean;
  stopOnTerminate: boolean;
  http: {
    url: string;
    method: 'POST' | 'PUT' | 'PATCH';
    rootProperty: string;
    headers: Record<string, string>; // non-auth; reject reserved auth names
    params: { [key: string]: Json }; // merged at request root
    template: { [key: string]: Json }; // typed placeholder map, see below
    authRequired: boolean;
    autoSync: boolean;
    batchSync: boolean;
    autoSyncThreshold: number;
    maxBatchSize: number;
    maxBatchAgeSeconds: number; // flush small tails while active
    timeoutSeconds: number;
  };
  retention: { maxDaysToPersist: number; maxRecordsToPersist: number };
  notification: {
    id: number; channelId: string; channelName: string;
    title: string; text: string; smallIcon: string;
  };
  diagnostics: { level: LogLevel; maxBytes: number; maxDays: number };
}
export interface Position {
  uuid: string;
  timestamp: string; // ISO UTC, acquisition time
  provider: Exclude<Provider, 'auto'>;
  coords: {
    latitude: number; longitude: number; accuracy: number | null;
    altitude: number | null; altitudeAccuracy: number | null;
    speed: number | null; heading: number | null;
  };
  mock: boolean;
  battery: { level: number | null; isCharging: boolean };
}
export interface ProviderState {
  selected: Exclude<Provider, 'auto'> | null;
  gmsStatus: number | null; hmsStatus: number | null;
  locationEnabled: boolean; locationAvailable: boolean | null;
  permission: 'denied' | 'coarse' | 'fine';
  backgroundPermission: boolean;
  powerSave: boolean; exactAlarmAllowed: boolean;
  degradedReasons: string[];
}
export interface TrackingState {
  rejectedFixes?: number;
  lastFixGapMs?: number | null;
  effectiveMinUpdateIntervalMs?: number | null;
  requestedEnabled: boolean;
  enabled: boolean; // service/subscription active, not a promise of a fix
  session: Scope | null;
  generation: number;
  configRevision: number;
  uploadState: 'idle' | 'uploading' | 'offline' | 'authPaused' | 'blocked';
  lastFixAt: string | null;
  lastStopReason: string | null;
  queueCount: number;
  provider: ProviderState;
}
export interface SyncResult {
  uploaded: number; remaining: number;
  outcome: 'drained' | 'offline' | 'authPaused' | 'blocked' | 'deferred';
}
export interface TrackingEvents {
  location: { position: Position; persisted: boolean; generation: number };
  http: { status: number | null; uuids: string[]; outcome: string;
    remaining: number; authRevision: number };
  enabledchange: { enabled: boolean; requestedEnabled: boolean; reason: string };
  providerchange: ProviderState;
  powersavechange: { enabled: boolean };
  heartbeat: { at: string; lastFixAt: string | null; queueCount: number };
}
export interface LocationTrackingPlugin {
  ready(options: { config: TrackingConfig; authorization?: Authorization }):
    Promise<TrackingState>;
  configure(options: { config: TrackingConfig; authorization?: Authorization }):
    Promise<TrackingState>; // exact alias of ready
  start(): Promise<TrackingState>;
  stop(options?: { pauseUploads?: boolean }): Promise<TrackingState>;
  getState(): Promise<TrackingState>;
  getCurrentPosition(options?: { timeoutSeconds?: number; maximumAgeMs?: number;
    persist?: boolean }): Promise<Position>;
  sync(options?: { scopeKey?: string }): Promise<SyncResult>;
  getCount(options?: { scopeKey?: string }): Promise<{ count: number }>;
  destroyLocations(options: { scopeKey: string; uuids?: string[] }):
    Promise<{ deleted: number }>;
  requestPermissions(options: { background?: boolean }): Promise<ProviderState>;
  getProviderState(): Promise<ProviderState>;
  setAuthorization(options: Authorization): Promise<TrackingState>;
  clearAuthorization(options: { scopeKey: string }): Promise<void>;
  setConfig(options: { patch: Partial<TrackingConfig> }): Promise<TrackingState>;
  log(options: { level: LogLevel; message: string }): Promise<void>;
  getDiagnostics(options?: { afterId?: number; limit?: number }):
    Promise<{ entries: Json[]; nextId: number | null; state: TrackingState }>;
  clearDiagnostics(): Promise<void>;
  addListener<E extends keyof TrackingEvents>(eventName: E,
    listener: (event: TrackingEvents[E]) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
