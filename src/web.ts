import { WebPlugin } from '@capacitor/core';
import type { LocationTrackingPlugin } from './definitions';

export class LocationTrackingWeb extends WebPlugin implements LocationTrackingPlugin {
  ready(..._args: Parameters<LocationTrackingPlugin['ready']>): ReturnType<LocationTrackingPlugin['ready']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  configure(..._args: Parameters<LocationTrackingPlugin['configure']>): ReturnType<LocationTrackingPlugin['configure']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  start(..._args: Parameters<LocationTrackingPlugin['start']>): ReturnType<LocationTrackingPlugin['start']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  stop(..._args: Parameters<LocationTrackingPlugin['stop']>): ReturnType<LocationTrackingPlugin['stop']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  getState(..._args: Parameters<LocationTrackingPlugin['getState']>): ReturnType<LocationTrackingPlugin['getState']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  getCurrentPosition(..._args: Parameters<LocationTrackingPlugin['getCurrentPosition']>): ReturnType<LocationTrackingPlugin['getCurrentPosition']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  sync(..._args: Parameters<LocationTrackingPlugin['sync']>): ReturnType<LocationTrackingPlugin['sync']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  getCount(..._args: Parameters<LocationTrackingPlugin['getCount']>): ReturnType<LocationTrackingPlugin['getCount']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  destroyLocations(..._args: Parameters<LocationTrackingPlugin['destroyLocations']>): ReturnType<LocationTrackingPlugin['destroyLocations']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  requestPermissions(..._args: Parameters<LocationTrackingPlugin['requestPermissions']>): ReturnType<LocationTrackingPlugin['requestPermissions']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  getProviderState(..._args: Parameters<LocationTrackingPlugin['getProviderState']>): ReturnType<LocationTrackingPlugin['getProviderState']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  setAuthorization(..._args: Parameters<LocationTrackingPlugin['setAuthorization']>): ReturnType<LocationTrackingPlugin['setAuthorization']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  clearAuthorization(..._args: Parameters<LocationTrackingPlugin['clearAuthorization']>): ReturnType<LocationTrackingPlugin['clearAuthorization']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  setConfig(..._args: Parameters<LocationTrackingPlugin['setConfig']>): ReturnType<LocationTrackingPlugin['setConfig']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  log(..._args: Parameters<LocationTrackingPlugin['log']>): ReturnType<LocationTrackingPlugin['log']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  getDiagnostics(..._args: Parameters<LocationTrackingPlugin['getDiagnostics']>): ReturnType<LocationTrackingPlugin['getDiagnostics']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  clearDiagnostics(..._args: Parameters<LocationTrackingPlugin['clearDiagnostics']>): ReturnType<LocationTrackingPlugin['clearDiagnostics']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  override addListener(): ReturnType<LocationTrackingPlugin['addListener']> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
  override removeAllListeners(): Promise<void> {
    return Promise.reject(this.unimplemented('LocationTracking requires Android'));
  }
}
