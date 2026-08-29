import type { Site, Plan, Voucher, Issuance } from '../types.ts';

/**
 * The thing the customer actually walks away with. Loyverse cannot print a
 * generated code on its own sale receipt, so we render a second slip here and
 * send it to a printer this service controls (ESC/POS over the shop LAN).
 *
 * `text` is pre-wrapped to 32 columns, which is a 58mm thermal roll. Use
 * `WIDTH_80MM` for 80mm printers.
 */

export const WIDTH_58MM = 32;
export const WIDTH_80MM = 48;

export type Slip = {
  /** Plain text, ready to push to a thermal printer or show on screen. */
  text: string;
  /** The same content as fields, for a POS that renders its own layout. */
  fields: {
    header: string;
    ssid: string;
    portalUrl: string;
    plan: string;
    code: string;
    duration: string;
    speed: string;
    devices: number;
    price: string;
    validUntil: string;
    reference: string | null;
    issuanceId: string;
  };
};

function centre(line: string, width: number): string {
  const pad = Math.max(0, Math.floor((width - line.length) / 2));
  return ' '.repeat(pad) + line;
}

function rule(width: number, ch = '-'): string {
  return ch.repeat(width);
}

function formatDuration(minutes: number): string {
  if (minutes % 1440 === 0) {
    const days = minutes / 1440;
    return days === 1 ? '24 hours' : `${days} days`;
  }
  if (minutes % 60 === 0) {
    const hours = minutes / 60;
    return hours === 1 ? '1 hour' : `${hours} hours`;
  }
  return `${minutes} minutes`;
}

function formatMoney(minor: number, currency: string): string {
  return `${currency} ${(minor / 100).toFixed(2)}`;
}

function formatDate(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Space the code in pairs so a customer can read it off the paper. */
function spaceCode(code: string): string {
  return code.replace(/(.{2})/g, '$1 ').trim();
}

export function buildSlip(
  site: Site,
  plan: Plan,
  voucher: Voucher,
  issuance: Issuance,
  width: number = WIDTH_58MM,
): Slip {
  const speed = `${Math.round(plan.downKbps / 1000)}/${Math.round(plan.upKbps / 1000)} Mbps`;

  const lines = [
    centre(site.name, width),
    centre('WIFI ACCESS CODE', width),
    rule(width, '='),
    '',
    centre(spaceCode(voucher.code), width),
    '',
    rule(width),
    `Plan     : ${plan.name}`,
    `Good for : ${formatDuration(plan.durationMinutes)}`,
    `Speed    : up to ${speed}`,
    `Devices  : ${plan.deviceLimit}`,
    `Price    : ${formatMoney(plan.priceMinor, plan.currency)}`,
    rule(width),
    `Network  : ${site.ssid}`,
    'How to connect:',
    ` 1. Connect to ${site.ssid}`,
    ' 2. The login page opens by itself',
    ' 3. Type the code above',
    '',
    `Timer starts on your first login.`,
    `Use before ${formatDate(voucher.validUntil)}.`,
    rule(width),
    `Ref: ${issuance.reference ?? '-'}`,
    `ID : ${issuance.issuanceId.slice(0, 8)}`,
    `${formatDate(issuance.issuedAt)}`,
    '',
  ];

  return {
    text: lines.join('\n'),
    fields: {
      header: site.name,
      ssid: site.ssid,
      portalUrl: site.portalUrl,
      plan: plan.name,
      code: voucher.code,
      duration: formatDuration(plan.durationMinutes),
      speed,
      devices: plan.deviceLimit,
      price: formatMoney(plan.priceMinor, plan.currency),
      validUntil: formatDate(voucher.validUntil),
      reference: issuance.reference,
      issuanceId: issuance.issuanceId,
    },
  };
}

/**
 * ESC/POS byte stream for a network thermal printer. Sending this is a plain
 * TCP write to port 9100 — see docs/PRINTING.md before wiring a real printer.
 */
export function toEscPos(slip: Slip): Buffer {
  const ESC = 0x1b;
  const GS = 0x1d;
  const parts: number[] = [
    ESC, 0x40,            // initialise
    ESC, 0x61, 0x01,      // centre
  ];
  const body = Buffer.from(`${slip.text}\n\n\n`, 'ascii');
  const tail = [GS, 0x56, 0x00]; // full cut
  return Buffer.concat([Buffer.from(parts), body, Buffer.from(tail)]);
}
