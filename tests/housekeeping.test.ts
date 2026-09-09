import { test } from 'node:test';
import assert from 'node:assert/strict';

const { stillWorthKeeping, STATUS_UNUSED, STATUS_IN_USE, STATUS_EXPIRED } =
  await import('../src/omada/housekeeping.ts');

const NOW = 1_800_000_000_000;
const HOUR = 60 * 60 * 1000;

/**
 * The rule that decides whether a voucher group may be deleted.
 *
 * This shipped wrong and cut paying customers off the cafe wifi mid-session:
 * only unused codes counted as worth keeping, so once every code in a group
 * had been redeemed the group looked spent and the next sale deleted it. The
 * cases below are the ones that were actually observed, and the in-use ones
 * are the reason this file exists.
 */

test('a code someone is on right now is kept', () => {
  assert.equal(
    stillWorthKeeping({ status: STATUS_IN_USE, endTime: NOW + HOUR }, NOW),
    true,
  );
});

test('an in-use code is kept even past the expiry stamp', () => {
  // The stamp bounds when a code may be redeemed, not how long the access it
  // bought lasts. Redeem at ten to midnight and the two hours run past it -
  // cutting the guest off at midnight is the same bug in a smaller window.
  assert.equal(
    stillWorthKeeping({ status: STATUS_IN_USE, endTime: NOW - HOUR }, NOW),
    true,
  );
});

test('an unused code within its window is kept', () => {
  assert.equal(
    stillWorthKeeping({ status: STATUS_UNUSED, endTime: NOW + HOUR }, NOW),
    true,
  );
});

test('an unused code that never expires is kept', () => {
  assert.equal(stillWorthKeeping({ status: STATUS_UNUSED, endTime: null }, NOW), true);
});

test('an unused code past its window is spent', () => {
  assert.equal(
    stillWorthKeeping({ status: STATUS_UNUSED, endTime: NOW - 1 }, NOW),
    false,
  );
});

test('an expired code is spent', () => {
  assert.equal(
    stillWorthKeeping({ status: STATUS_EXPIRED, endTime: NOW - HOUR }, NOW),
    false,
  );
});

test('a group is spent only when no code is unused or in use', () => {
  const codes = [
    { status: STATUS_EXPIRED, endTime: NOW - HOUR },
    { status: STATUS_EXPIRED, endTime: NOW - HOUR },
  ];
  assert.equal(codes.some((c) => stillWorthKeeping(c, NOW)), false);

  // The case from the cafe: every code redeemed, guests still online.
  const allRedeemed = [
    { status: STATUS_EXPIRED, endTime: NOW - HOUR },
    { status: STATUS_IN_USE, endTime: NOW + HOUR },
  ];
  assert.equal(allRedeemed.some((c) => stillWorthKeeping(c, NOW)), true);
});
