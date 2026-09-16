import '@testing-library/jest-dom/vitest';

// Node's own experimental Web Storage global (stable-ish since Node 22+, opt-out via
// --no-experimental-webstorage) shadows jsdom's window.localStorage/sessionStorage without a
// --localstorage-file, leaving them defined but always returning undefined. That breaks any
// test touching localStorage regardless of jsdom version. A tiny in-memory polyfill sidesteps
// the conflict without depending on Node CLI flags (which differ across machines/CI).
class MemoryStorage {
  #store = new Map();
  get length() { return this.#store.size; }
  key(index) { return Array.from(this.#store.keys())[index] ?? null; }
  getItem(key) { return this.#store.has(key) ? this.#store.get(key) : null; }
  setItem(key, value) { this.#store.set(String(key), String(value)); }
  removeItem(key) { this.#store.delete(key); }
  clear() { this.#store.clear(); }
}

for (const prop of ['localStorage', 'sessionStorage']) {
  try {
    if (!window[prop] || typeof window[prop].setItem !== 'function') {
      Object.defineProperty(window, prop, { value: new MemoryStorage(), configurable: true, writable: true });
    }
  } catch {
    // Si el entorno sí provee un localStorage funcional, no interferimos.
  }
}
