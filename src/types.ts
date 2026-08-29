/** Shared shapes for the voucher service. */

export type Plan = {
  id: string;
  name: string;
  /** Minutes of access the guest gets, counted from first login. */
  durationMinutes: number;
  downKbps: number;
  upKbps: number;
  deviceLimit: number;
  /** Price in the currency's minor unit (centavos), for the printed slip only. */
  priceMinor: number;
  currency: string;
  codeLength: number;
  /** A voucher that is never redeemed self-destructs after this many days. */
  unusedValidDays: number;
  /** How many spare codes to keep pre-made for offline fallback. */
  poolTarget: number;
  /** Loyverse item SKUs that map to this plan. */
  loyverseSkus: string[];
};

export type Site = {
  id: string;
  name: string;
  omadaSiteId: string;
  /** Loyverse store id, so a receipt webhook can be routed to this site. */
  loyverseStoreId?: string;
  ssid: string;
  /** Hard ceiling on stored vouchers for the account (5000 on Omada cloud). */
  voucherCap: number;
  portalUrl: string;
  plans: Plan[];
};

export type VoucherStatus = 'unused' | 'in_use' | 'expired';

export type Voucher = {
  id: string;
  code: string;
  siteId: string;
  planId: string;
  status: VoucherStatus;
  createdAt: number;
  /** Deadline for first login; past this an unused voucher is dead weight. */
  validUntil: number;
  durationMinutes: number;
  firstUsedAt: number | null;
  /** When the guest's session runs out, set on first login. */
  usedUntil: number | null;
  note: string;
};

export type IssueSource = 'live' | 'pool';

/** One sale = one ledger row. This is our record, independent of the controller. */
export type Issuance = {
  issuanceId: string;
  siteId: string;
  planId: string;
  voucherId: string;
  code: string;
  source: IssueSource;
  reference: string | null;
  idempotencyKey: string | null;
  issuedAt: number;
  reprints: number;
};

export type CreateVoucherInput = {
  planId: string;
  count: number;
  note: string;
};
