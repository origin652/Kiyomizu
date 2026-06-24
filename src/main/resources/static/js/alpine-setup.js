// Alpine wiring. This file must load BEFORE alpine.min.js so the `alpine:init`
// listener is registered before Alpine auto-starts. The dream store backs the
// declarative dream-run result panel; companion.js render functions push data
// into it instead of mutating the DOM imperatively.
document.addEventListener('alpine:init', () => {
  Alpine.store('dream', {
    visible: false,
    summary: null,
    kind: 'info',
    items: [],
    empty: null,
  });
});