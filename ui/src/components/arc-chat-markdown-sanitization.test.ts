// @vitest-environment jsdom

import { describe, expect, it } from 'vitest';
import { ArcChatInterface } from './arc-chat-interface';

describe('ArcChatInterface Markdown sanitization', () => {
  it('removes active HTML and unsafe links before rendering', () => {
    const component = new ArcChatInterface() as any;
    const rendered = component._renderMarkdown(
      'untrusted-markdown',
      [
        '<script>alert(1)</script>',
        '<img src=x onerror=alert(1)>',
        '[click me](javascript:alert(1))',
      ].join('\n\n')
    ) as { values: string[] };

    const sanitizedHtml = rendered.values[0];

    expect(sanitizedHtml).not.toMatch(/<script\b/i);
    expect(sanitizedHtml).not.toMatch(/<img\b/i);
    expect(sanitizedHtml).not.toMatch(/\bonerror\s*=/i);
    expect(sanitizedHtml).not.toMatch(/javascript:/i);
  });
});
