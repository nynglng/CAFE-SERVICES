import type { Voucher, CreateVoucherInput } from '../types.ts';

/**
 * Everything the service needs from a hotspot backend. The mock and the live
 * Omada client both implement this, so switching between them is one env var.
 */
export type OmadaAdapter = {
  readonly name: string;
  createVouchers(siteId: string, input: CreateVoucherInput): Promise<Voucher[]>;
  listVouchers(siteId: string): Promise<Voucher[]>;
  deleteVouchers(siteId: string, voucherIds: string[]): Promise<number>;
  countVouchers(siteId: string): Promise<number>;
};

/** Thrown when the site is at its stored-voucher ceiling (the 5000 wall). */
export class QuotaExceededError extends Error {
  siteId: string;
  cap: number;
  constructor(siteId: string, cap: number) {
    super(`Site ${siteId} is at its voucher cap of ${cap}`);
    this.name = 'QuotaExceededError';
    this.siteId = siteId;
    this.cap = cap;
  }
}

/** Thrown when the controller is unreachable — the trigger for pool fallback. */
export class BackendUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BackendUnavailableError';
  }
}
