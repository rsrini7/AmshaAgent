import { describe, expect, it } from 'vitest';
import { ArcHarnessConveyor } from './arc-harness-conveyor';

describe('Conveyor Accessibility & Reduced Motion', () => {
  it('declares prefers-reduced-motion rule disabling animations and transitions', () => {
    const cssText = ArcHarnessConveyor.styles.toString();

    expect(cssText).toContain('prefers-reduced-motion: reduce');
    expect(cssText).toContain('transition: none !important');
    expect(cssText).toContain('animation: none !important');
  });

  it('declares calm-pulse animation with gentle opacity/shadow for calm-by-default', () => {
    const cssText = ArcHarnessConveyor.styles.toString();

    expect(cssText).toContain('@keyframes calm-pulse');
  });
});
