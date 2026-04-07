Here is a thorough end-to-end review of the ArcClaw UI codebase, organized by category. Every finding is flagged as a concern rather than a breaking change, so you can selectively apply fixes.

---

## 1. Layout & Wide-Screen Alignment

**Duplicate Chat Header (most visible on wide screens)**
`arc-shell` renders a topbar with `"Chat Session"` as its title, and `arc-chat-interface` renders its *own* `.chat-header` with the exact same "Chat Session" title and a WS status badge. On the Chat tab, these two headers stack vertically — you get a double header bar. The inner chat header should be removed (the shell topbar already owns that real estate) or the shell topbar should hide its title when `#/chat` is active.

**Fixed `max-width: 80%` for message bubbles**
On a 2560px widescreen, an 80% chat bubble is ~2048px wide, which looks bloated. Cap the bubble at a comfortable reading width instead:
```css
.msg-group { max-width: min(680px, 80%); }
```

**Floating theme toggle is layout-coupled via hardcoded padding**
The toggle is `position: fixed; top: 14px; right: 16px` and the topbar compensates with `padding-right: 5.5rem`. This breaks if you ever add anything else to the top-right (the topbar already has `.topbar-right`). Move the toggle button into `.topbar-right` so it flows naturally in the layout instead of being bolted on top of it.

**`arc-settings` is fine (has `max-width: 800px; margin: 0 auto`) but other panels are not**
`arc-agent-status`, `arc-hitl-panel`, and `arc-skills-panel` use `padding: 32px` with no max-width constraint. On ultrawide monitors they go full-bleed. Add a `max-width` wrapper (e.g. `1200px`) with `margin: 0 auto` inside each panel host.

**`arc-nav` has no responsiveness**
`width: 220px` is hardcoded with no breakpoints. Below ~640px the nav consumes ~35% of the viewport. At minimum, add a media query to collapse or hide it on small screens.

---

## 2. Functional / Logic Issues

**`arc-chat-interface` — approval buttons persist on historical messages**
When chat history is reloaded, messages with `isApprovalPrompt: true` still show "Approve Task" / "Deny" buttons. Clicking them sends `/approve` or `/deny` to the live session for a request that's already resolved. Fix: render the action buttons only when `!m.historic`.

**`arc-onboarding` — `.wide` class is declared but never defined in CSS**
`class="wizard-card ${this._currentStep === 5 ? 'wide' : ''}"` — there's no `.wide` rule in the component styles, so step 5 gets no extra width. Define it or remove it.

**`arc-onboarding` — `_validationOk` never flips to `true`**
It's initialized to `false`, reset to `false` on provider change, but the success path never sets it to `true`. The status message (`this._validationOk ? 'success' : 'error'`) will always render the error style even on a successful key validation where the server says `valid: true`.

**`arc-onboarding` — Step 6 renders outside the stepper**
The stepper only loops over `[1, 2, 3, 4, 5]`. When `_currentStep` advances to 6 (the "Setup Complete!" view), there are no stepper dots visible — it's a blank card with just the completion content. Either add step 6 to the stepper or transition to a full-screen completion view.

**`arc-onboarding` — Double `_finish()` call is possible**
`_finish()` calls `onboardingComplete()`. The "Skip" button on step 5 calls `_finish()` directly, AND step 6's "Go to Dashboard" also calls `_finish()`. If the user clicks Skip and somehow ends up on step 6, two POST requests fire. One path should call `_finish()`, the other should just dispatch the event.

**`arc-chat-interface` — WS reconnect logic doesn't clean up stale socket**
In `_connect()`, `onStatusChange` never nulls `_ws`. If the status changes to `closed` or `error`, `_ws` remains pointing at a dead socket. On the next `updated()` cycle, the guard `!this._ws` prevents reconnection. The `_intentionalClose` flag partially mitigates this, but unintentional drops (server restart, network loss) leave the component in a permanently-closed state until the user manually clicks "Reconnect."

**`arc-shell` — `_provideContext()` is never wired up**
It returns an object but that object is never actually consumed. `arc-context.ts` defines a proper `@lit/context` `arcContext`, but no component uses `@consume`. The manual `@arc-app-context` event on the shell div is also never dispatched to child components. This is dead infrastructure — either wire it up properly or remove it.

**`vite.config.ts` — Stale proxy rules for `/onboarding` and `/settings`**
`ArcClient` calls `${baseUrl}/onboarding/status` where `baseUrl = '/api/v1'`, so the actual URL path is `/api/v1/onboarding/status`. The `/onboarding` and `/settings` proxy entries would only match bare `/onboarding/…` paths, which `ArcClient` never generates. These rules are harmless but dead configuration.

---

## 3. Security

**`unsafeHTML(marked.parse(...))` — XSS risk on assistant messages**
Assistant markdown is parsed and injected as raw HTML. If the model returns a message with `<script>` tags or `<img onerror=...>` payloads (injection through tool results, for example), it executes in the browser. Add DOMPurify before passing to `unsafeHTML`:
```ts
import DOMPurify from 'dompurify';
return unsafeHTML(DOMPurify.sanitize(marked.parse(text, { async: false }) as string));
```

**API keys in `localStorage`**
Noted as a known trade-off for a local tool, but `sessionStorage` would be meaningfully safer against persistent XSS since it clears on tab close. The theme preference already uses `sessionStorage` — align the key storage with that pattern.

**API key in SSE query string**
`/api/v1/chat/stream?apiKey=...`, `/api/v1/hitl/events?apiKey=...`, `/api/v1/agents/events?apiKey=...` — all pass the key in the URL. Query parameters appear in server logs, proxy logs, and browser history. Prefer a cookie (already used in onboarding via `credentials: 'include'`) or an `Authorization` header for the SSE fetch approach used in `SseManager`.

---

## 4. Accessibility

**Nav links missing `aria-current="page"`**
```html
<a href="#/chat" class="${this.route === '#/chat' ? 'active' : ''}"
   aria-current="${this.route === '#/chat' ? 'page' : nothing}">
```

**Provider cards missing `role="radiogroup"` wrapper**
Both in `arc-onboarding` and `arc-settings`, provider cards have `role="radio"` and `aria-checked`, which is correct — but they must be wrapped in a `role="radiogroup"` element or the screen reader won't announce them as a group.

**Chat textarea and send button lack accessible labels**
```html
<textarea aria-label="Message ArcClaw"></textarea>
<button aria-label="Send message" ...>
```

**`icon` CSS selector in `arc-nav` is dead**
The nav CSS defines `icon { width: 16px; ... }` but the template uses `<span>` elements, not `<icon>` elements. The CSS rule never matches.

---

## 5. Code Style & Consistency

**Inconsistent spacing units across components**
`arc-onboarding` and `arc-settings` use design tokens (`var(--space-4)`, `var(--radius-md)`), but `arc-agent-status`, `arc-hitl-panel`, and `arc-skills-panel` use raw `px` values (`padding: 32px`, `gap: 20px`, `border-radius: 16px`). Standardize on the token system across all components.

**Inline reconnect handler in `arc-chat-interface`**
```ts
@click="${() => { this._ws = null; this._intentionalClose = false; this._connecting = true; this._loadHistory().then(...) }}"
```
This is a multi-line handler inlined in the template. Extract it as a `private _reconnect()` method for readability and testability.

**`arc-skills-panel` and `arc-tool-panel` both use a `⚒` avatar for everything**
Skill cards and tool cards use the exact same avatar icon. Since they appear in the same panel, they're visually indistinguishable before reading the text. Use different icons (e.g., `⚡` for skills, `🔧` for tools).

**`arc-connection-badge` applies accent styling unconditionally**
The `:host` rule always applies `background: var(--accent-glow); border: 1px solid var(--accent); color: var(--accent)` regardless of connection status. In `disconnected` or `error` states, the badge looks "live" because of the accent color framing. The status-dependent styles should override the host or conditionally set the colors.

**`tokens.css` — full duplication of light-theme block**
The `@media (prefers-color-scheme: light)` block is an exact copy of `[data-theme="light"]`. This is unavoidable in vanilla CSS (no mixins), but consider a PostCSS setup or a short comment noting the intentional duplication to avoid future drift between the two.

**`marked` `async: false` option**
`marked.parse(text, { async: false })` — the `async: false` option is deprecated in Marked v9+ and will be removed. Use the synchronous `marked.parseInline` for inline content or the sync signature without the option object:
```ts
marked.parse(text) as string
```

---

## 6. Performance

**All route panels mount simultaneously**
`_renderViews()` renders all five panels with `display: none/flex` toggling. This means all components call `connectedCallback()` and fire API requests at startup — `arc-settings` fetches settings and `arc-skills-panel` loads skills even when the user is on the Chat tab. Consider lazy-mounting inactive panels using a conditional `${this._route === '#/settings' ? html`<arc-settings ...>` : nothing}` pattern and accepting state loss on tab switch, or at minimum gate the API calls behind a `_hasBeenVisible` flag.

**`_scrollToBottom` uses a 50ms `setTimeout`**
A hardcoded delay is fragile. Prefer `this.updateComplete.then(() => requestAnimationFrame(...))` to scroll after Lit has flushed the DOM.

**No virtual scrolling in chat**
For long sessions, all messages render in the DOM. Consider capping rendered messages at the last N (e.g. 100) with a "Load earlier" trigger if this is expected to be used in extended sessions.

---

## Summary Priority Table

| Priority | Issue |
|---|---|
| 🔴 High | Duplicate Chat header (double topbar) |
| 🔴 High | XSS via `unsafeHTML` on assistant messages |
| 🔴 High | Approval buttons active on historic messages |
| 🟠 Medium | WS reconnect doesn't clear dead socket |
| 🟠 Medium | `_validationOk` never sets to `true` (broken success state in onboarding) |
| 🟠 Medium | Theme toggle outside topbar flow (fragile layout coupling) |
| 🟠 Medium | Wide-screen chat bubble max-width not capped |
| 🟡 Low | `.wide` CSS class missing definition |
| 🟡 Low | Dead `arcContext` / `_provideContext` infrastructure |
| 🟡 Low | `aria-current`, `role="radiogroup"`, textarea labels missing |
| 🟡 Low | API key in SSE query strings |
| 🟡 Low | Inconsistent px vs token spacing |

---

Please go ahead and share the screenshots — I'll map these findings to what I see visually on your widescreen and identify any additional layout issues that aren't obvious from the code alone.