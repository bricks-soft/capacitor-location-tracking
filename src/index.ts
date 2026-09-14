import { registerPlugin } from '@capacitor/core';
import type { LocationTrackingPlugin } from './definitions';

export const LocationTracking = registerPlugin<LocationTrackingPlugin>('LocationTracking', {
  web: () => import('./web').then(m => new m.LocationTrackingWeb()),
});
export * from './definitions';
