/**
 * A clock we can fast-forward, so the simulation can age vouchers and prove the
 * janitor works without waiting a week. In production `offsetMs` stays 0.
 */
let offsetMs = 0;

export function now(): number {
  return Date.now() + offsetMs;
}

export function advance(minutes: number): number {
  offsetMs += minutes * 60_000;
  return now();
}

export function reset(): void {
  offsetMs = 0;
}

export function offset(): number {
  return offsetMs;
}
