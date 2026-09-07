/**
 * Accessible split-pane resize controller for the Amsha Visual Cockpit.
 *
 * Supports pointer dragging, keyboard adjustments (ArrowLeft, ArrowRight, Home, End),
 * ARIA separator role attributes, and session-storage persistence.
 */

export interface ResizeOptions {
  storageKey?: string;
  defaultWidth?: number;
  minWidth?: number;
  maxWidth?: number;
  step?: number;
  onResize?: (width: number) => void;
}

export class ResizeController {
  private _width: number;
  private _minWidth: number;
  private _maxWidth: number;
  private _step: number;
  private _storageKey: string;
  private _isDragging = false;
  private _startX = 0;
  private _startWidth = 0;
  private _onResize?: (width: number) => void;

  constructor(options: ResizeOptions = {}) {
    this._storageKey = options.storageKey ?? 'amsha-cockpit-dock-width';
    this._minWidth = options.minWidth ?? 280;
    this._maxWidth = options.maxWidth ?? 800;
    this._step = options.step ?? 16;
    this._onResize = options.onResize;

    const persisted = this._loadPersistedWidth();
    this._width = persisted ?? options.defaultWidth ?? 380;
    this._clampWidth();
  }

  get width(): number {
    return this._width;
  }

  get minWidth(): number {
    return this._minWidth;
  }

  get maxWidth(): number {
    return this._maxWidth;
  }

  get isDragging(): boolean {
    return this._isDragging;
  }

  public setWidth(newWidth: number): number {
    this._width = newWidth;
    this._clampWidth();
    this._persistWidth();
    if (this._onResize) {
      this._onResize(this._width);
    }
    return this._width;
  }

  public handlePointerDown(e: { clientX: number }): void {
    this._isDragging = true;
    this._startX = e.clientX;
    this._startWidth = this._width;
  }

  public handlePointerMove(e: { clientX: number }): void {
    if (!this._isDragging) return;
    // Moving pointer to the left increases right dock width; moving to right decreases it
    const delta = this._startX - e.clientX;
    this.setWidth(this._startWidth + delta);
  }

  public handlePointerUp(): void {
    this._isDragging = false;
  }

  public handleKeyDown(e: { key: string; preventDefault?: () => void }): boolean {
    let handled = false;
    if (e.key === 'ArrowLeft') {
      this.setWidth(this._width + this._step);
      handled = true;
    } else if (e.key === 'ArrowRight') {
      this.setWidth(this._width - this._step);
      handled = true;
    } else if (e.key === 'Home') {
      this.setWidth(this._minWidth);
      handled = true;
    } else if (e.key === 'End') {
      this.setWidth(this._maxWidth);
      handled = true;
    }

    if (handled && e.preventDefault) {
      e.preventDefault();
    }
    return handled;
  }

  public getAriaAttributes(): {
    role: string;
    tabindex: number;
    'aria-orientation': string;
    'aria-valuenow': number;
    'aria-valuemin': number;
    'aria-valuemax': number;
    'aria-label': string;
  } {
    return {
      role: 'separator',
      tabindex: 0,
      'aria-orientation': 'vertical',
      'aria-valuenow': Math.round(this._width),
      'aria-valuemin': this._minWidth,
      'aria-valuemax': this._maxWidth,
      'aria-label': 'Resize chat dock',
    };
  }

  private _clampWidth(): void {
    if (this._width < this._minWidth) {
      this._width = this._minWidth;
    } else if (this._width > this._maxWidth) {
      this._width = this._maxWidth;
    }
  }

  private _loadPersistedWidth(): number | null {
    try {
      if (typeof sessionStorage !== 'undefined') {
        const item = sessionStorage.getItem(this._storageKey);
        if (item) {
          const parsed = parseInt(item, 10);
          if (!isNaN(parsed)) return parsed;
        }
      }
    } catch {
      // Storage unavailable or disabled
    }
    return null;
  }

  private _persistWidth(): void {
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.setItem(this._storageKey, String(Math.round(this._width)));
      }
    } catch {
      // Storage unavailable or quota exceeded
    }
  }
}
