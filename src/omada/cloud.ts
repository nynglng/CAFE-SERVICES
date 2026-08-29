import { OmadaSession } from './session.ts';

/**
 * Talks to a real Omada Cloud site through the portal's own JSON API.
 *
 * Endpoint shapes below were derived by capturing the portal's traffic
 * (tools/capture-omada-api.ts) and confirmed against the live account
 * (tools/discover-voucher-codes.ts). If TP-Link changes their site, re-run
 * those two tools and adjust this file — it is the only place that knows what
 * Omada calls things.
 *
 * Vocabulary note: Omada creates vouchers in GROUPS. A group holds N codes and
 * is the unit of deletion. Creating a group returns only its id; the codes must
 * be read back with a second call.
 */

/** How a group decides when its unissued codes die. */
export const ValidityType = {
  /** Codes live forever. This is what makes vouchers pile up to the cap. */
  PERMANENT: 0,
  /**
   * "Fixed Dates" in the UI: the group carries an absolute effective and
   * expiration timestamp, and unused codes die at the expiration date.
   * Requires `effectiveTime` and `expirationTime`; note that
   * `voucherValidityEnable` stays FALSE for this mode — setting it true makes
   * the controller reject the request with -1001.
   */
  FIXED_DATES: 1,
} as const;

/** Omada's per-voucher status values. */
export const VoucherStatusCode = {
  UNUSED: 0,
  IN_USE: 1,
  EXPIRED: 2,
} as const;

/** Omada returns this for "no expiry" — Java's Long.MAX_VALUE. */
export const NEVER_EXPIRES = 9223372036854775807n;

export type CloudVoucher = {
  id: string;
  code: string;
  groupId: string;
  status: number;
  /** ms since epoch, or null when the code never expires. */
  endTime: number | null;
  timeLeftSec: number;
  timeUsedSec: number;
};

export type CloudGroup = {
  id: string;
  name: string;
  totalCount: number;
  unusedCount: number;
  usedCount: number;
  createdTime: number;
  duration: number;
  validityType: number;
};

export type CreateGroupInput = {
  name: string;
  amount: number;
  /** Minutes of access, counted from first login. */
  durationMinutes: number;
  codeLength: number;
  /** Devices allowed per code. */
  maxUsers: number;
  description?: string | null;
  /** Omit or PERMANENT to mirror the current manual behaviour. */
  validityType?: number;
  /**
   * With FIXED_DATES, how many days unused codes stay redeemable. Day 0 is
   * today, so 1 means "usable today and tomorrow" — matching how the portal's
   * date pickers behave.
   */
  expireAfterDays?: number;
  portalIds?: string[];
};

/** Local midnight at the start of the day `offsetDays` from now. */
function startOfDay(offsetDays: number): number {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** Local 23:59:59.999 on the day `offsetDays` from now. */
function endOfDay(offsetDays: number): number {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

function isNeverExpires(endTime: number | string | undefined): boolean {
  if (endTime === undefined) return true;
  // The value overflows a JS number, so compare as text as well.
  return String(endTime).startsWith('922337203685477');
}

export class OmadaCloudClient {
  private session: OmadaSession;
  private siteId: string;

  constructor(session: OmadaSession, siteId: string) {
    this.session = session;
    this.siteId = siteId;
  }

  private base(): string {
    return `/api/v2/hotspot/sites/${this.siteId}`;
  }

  /** Confirm the controller still considers us logged in. */
  async loginStatus(): Promise<unknown> {
    return this.session.get(`/api/v2/hotspot/loginStatus?needToken=true`);
  }

  async listGroups(pageSize = 100): Promise<CloudGroup[]> {
    const out: CloudGroup[] = [];
    for (let page = 1; ; page++) {
      const result = (await this.session.get(
        `${this.base()}/voucherGroups?currentPage=${page}` +
          `&currentPageSize=${pageSize}&searchField=name%2Ccode`,
      )) as { data?: CloudGroup[]; totalRows?: number };
      const rows = result.data ?? [];
      out.push(...rows);
      if (rows.length < pageSize) break;
      if (result.totalRows !== undefined && out.length >= result.totalRows) break;
    }
    return out;
  }

  /** Read the codes inside one group. */
  async listCodes(groupId: string, pageSize = 500): Promise<CloudVoucher[]> {
    const out: CloudVoucher[] = [];
    for (let page = 1; ; page++) {
      const result = (await this.session.get(
        `${this.base()}/voucherGroups/${groupId}` +
          `?currentPage=${page}&currentPageSize=${pageSize}`,
      )) as {
        data?: Array<{
          id: string;
          code: string;
          status?: number;
          endTime?: number;
          timeLeftSec?: number;
          timeUsedSec?: number;
        }>;
        totalRows?: number;
      };
      const rows = result.data ?? [];
      for (const row of rows) {
        out.push({
          id: row.id,
          code: row.code,
          groupId,
          status: row.status ?? 0,
          endTime: isNeverExpires(row.endTime) ? null : Number(row.endTime),
          timeLeftSec: row.timeLeftSec ?? 0,
          timeUsedSec: row.timeUsedSec ?? 0,
        });
      }
      if (rows.length < pageSize) break;
      if (result.totalRows !== undefined && out.length >= result.totalRows) break;
    }
    return out;
  }

  /** Total stored vouchers for the site — the number that hits the 5000 cap. */
  async countVouchers(): Promise<number> {
    const groups = await this.listGroups();
    return groups.reduce((sum, g) => sum + (g.totalCount ?? 0), 0);
  }

  /** Portal ids the site can attach vouchers to. */
  async portalIds(): Promise<string[]> {
    const result = (await this.session.get(`${this.base()}/setting/voucher/portals`)) as
      | Array<{ id?: string }>
      | { data?: Array<{ id?: string }> };
    const rows = Array.isArray(result) ? result : (result?.data ?? []);
    return rows.map((r) => r.id).filter((id): id is string => typeof id === 'string');
  }

  /**
   * Create a voucher group and return its id.
   *
   * The body mirrors exactly what the portal sends; unfamiliar fields are kept
   * at the portal's own defaults rather than guessed at.
   */
  async createGroup(input: CreateGroupInput): Promise<string> {
    const validityType = input.validityType ?? ValidityType.PERMANENT;
    const fixedDates = validityType === ValidityType.FIXED_DATES;

    const body: Record<string, unknown> = {
      codeLength: input.codeLength,
      codeForm: [0], // digits only — what guests can actually type
      amount: input.amount,
      name: input.name,
      type: 2, // unlimited usage count
      logout: true,
      downLimitUnit: 0,
      upLimitUnit: 0,
      trafficLimitEnable: false,
      trafficLimit: null,
      trafficLimitUnit: 0,
      // Stays false even for Fixed Dates — the portal never sets it true, and
      // doing so makes the controller reject the request with -1001.
      voucherValidityEnable: false,
      upTimeLimitEnable: false,
      durationType: 1, // voucher duration, counted from first login
      duration: input.durationMinutes,
      description: input.description ?? null,
      maxUsers: input.maxUsers,
      trafficLimitFrequency: 1,
      applyToAllPortals: true,
      validityType,
      startTime: '00:00',
      endTime: '23:59',
      scheduleTime: 0,
      weeklyEnableDays: { 1: true, 2: true, 3: true, 4: true, 5: true, 6: true, 7: true },
      pattern: {
        patternType: 2,
        position: 0,
        ssidNetworkEnable: false,
        durationEnable: false,
        limitEnable: false,
      },
    };

    if (input.portalIds && input.portalIds.length > 0) {
      body.portalIds = input.portalIds;
    }
    if (fixedDates) {
      // Absolute epoch-ms bounds, exactly as the portal's date pickers send
      // them: effective from the start of today, dead at end of the last day.
      body.effectiveTime = startOfDay(0);
      body.expirationTime = endOfDay(input.expireAfterDays ?? 1);
    }

    const result = (await this.session.post(`${this.base()}/voucherGroups`, body)) as {
      id?: string;
    };
    if (!result?.id) throw new Error('Omada did not return a voucher group id');
    return result.id;
  }

  /** Create a group and hand back the codes it contains. */
  async createAndRead(input: CreateGroupInput): Promise<CloudVoucher[]> {
    const groupId = await this.createGroup(input);
    return this.listCodes(groupId);
  }

  async deleteGroup(groupId: string): Promise<void> {
    await this.session.del(`${this.base()}/voucherGroups/${groupId}`);
  }
}
