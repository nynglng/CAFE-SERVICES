import type { OmadaAdapter } from './adapter.ts';
import { MockOmadaAdapter } from './mock.ts';
import { LiveOmadaAdapter } from './live.ts';
import { config } from '../config.ts';

/** The one backend the whole service talks to. Chosen by OMADA_MODE. */
export const adapter: OmadaAdapter =
  config.omadaMode === 'live' ? new LiveOmadaAdapter() : new MockOmadaAdapter();

/** Non-null only in simulation, where the /sim routes can drive the controller. */
export const mock: MockOmadaAdapter | null =
  adapter instanceof MockOmadaAdapter ? adapter : null;
