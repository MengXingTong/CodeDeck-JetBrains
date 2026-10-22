const icons: Record<string, string> = {
    plus: '<path d="M12 5v14M5 12h14"/>',
    history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.6v-.2h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    edit: '<path d="m4 16-1 5 5-1L19 9l-4-4L4 16Z"/><path d="m13.5 6.5 4 4"/>',
    back: '<path d="m15 18-6-6 6-6"/>',
    paperclip: '<path d="m21 12-8.5 8.5a6 6 0 0 1-8.5-8.5l9-9a4 4 0 0 1 5.7 5.7l-9 9a2 2 0 0 1-2.9-2.8l8.4-8.4"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="m21 15-5-5L5 20"/>',
    send: '<path d="m5 12 14-7-4 14-3-6-7-1Z"/><path d="m12 13 7-8"/>',
    stop: '<rect x="7" y="7" width="10" height="10" rx="1"/>',
    branch: '<circle cx="6" cy="5" r="2"/><circle cx="18" cy="6" r="2"/><circle cx="6" cy="19" r="2"/><path d="M6 7v10M8 6h4a6 6 0 0 1 6 6v4"/>',
    code: '<path d="m8 9-3 3 3 3M16 9l3 3-3 3M14 5l-4 14"/>',
    tasks: '<path d="M9 6h11M9 12h11M9 18h11"/><path d="m3.5 6 1.5 1.5L7.5 5M3.5 12l1.5 1.5 2.5-2.5M3.5 18l1.5 1.5 2.5-2.5"/>',
    copy: '<rect x="8" y="8" width="11" height="11" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
    scissors: '<circle cx="6" cy="6" r="2.5"/><circle cx="6" cy="18" r="2.5"/><path d="m8 7 12 5M8 17l12-5"/>',
    spark: '<path d="m12 3 1.3 4.7L18 9l-4.7 1.3L12 15l-1.3-4.7L6 9l4.7-1.3L12 3Z"/>',
    sliders: '<path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/>',
    bolt: '<path d="m13 2-8 12h7l-1 8 8-12h-7l1-8Z"/>',
    bulb: '<path d="M9 18h6M10 22h4M8.2 14.7A7 7 0 1 1 15.8 14.7c-.8.7-1.3 1.5-1.3 2.3h-5c0-.8-.5-1.6-1.3-2.3Z"/>',
    robot: '<rect x="5" y="7" width="14" height="11" rx="3"/><path d="M12 3v4M8 12h.01M16 12h.01M9 15h6"/>',
    server: '<rect x="4" y="4" width="16" height="6" rx="2"/><rect x="4" y="14" width="16" height="6" rx="2"/><path d="M8 7h.01M8 17h.01"/>',
    note: '<path d="M6 3h12v18H6zM9 8h6M9 12h6M9 16h4"/>',
    chevron: '<path d="m9 18 6-6-6-6"/>',
    down: '<path d="m7 10 5 5 5-5"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    trash: '<path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6"/>',
    codex: '<path d="M12 3.2a4.4 4.4 0 0 1 4.2 3.1 4.4 4.4 0 0 1 1.3 8.5 4.4 4.4 0 0 1-7.4 4.6 4.4 4.4 0 0 1-6.3-6.1 4.4 4.4 0 0 1 3.2-7.4A4.4 4.4 0 0 1 12 3.2Z"/><path d="m8.2 8.1 7.6 4.4v4.2M15.8 15.9l-7.6-4.4V7.3M12 18.1V9.3"/>',
    claude: '<path d="M12 3 4.5 7.3v9.4L12 21l7.5-4.3V7.3L12 3Z"/><path d="m8.4 9 3.6 2 3.6-2M12 11v5"/>',
    provider: '<rect x="3" y="5" width="18" height="13" rx="2"/><path d="M8 21h8M12 18v3M7 10h4M9 8v4"/>',
    chat: '<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z"/>'
    ,refresh: '<path d="M20 6v5h-5M4 18v-5h5"/><path d="M6.1 9a7 7 0 0 1 11.6-2.6L20 11M4 13l2.3 4.6A7 7 0 0 0 17.9 15"/>'
    ,external: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 13v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h6"/>'
    ,question: '<circle cx="12" cy="12" r="9"/><path d="M9.8 9a2.4 2.4 0 1 1 3.3 2.2c-.8.4-1.1.9-1.1 1.8M12 17h.01"/>'
    ,alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 17h.01"/>'
    ,close: '<path d="m6 6 12 12M18 6 6 18"/>'
    ,closeAll: '<path d="M4 7h16M7 7l1 14h8l1-14M10 11v6M14 11v6"/><path d="M9 4h6"/>'
    ,chevronDown: '<path d="m6 9 6 6 6-6"/>'
    ,chevronUp: '<path d="m6 15 6-6 6 6"/>'
    ,list: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3 6h.01M3 12h.01M3 18h.01"/>'
    ,download: '<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>'
    ,arrowLeft: '<path d="M19 12H5M11 6l-6 6 6 6"/>'
    ,arrowRight: '<path d="M5 12h14M13 6l6 6-6 6"/>'
    ,palette: '<path d="M12 3a9 9 0 0 0 0 18h1.4a1.7 1.7 0 0 0 1.2-2.9 1.7 1.7 0 0 1 1.2-2.9H18A3 3 0 0 0 21 12a9 9 0 0 0-9-9Z"/><path d="M7.5 10h.01M10 6.5h.01M15 7.5h.01M17 11.5h.01"/>'
    ,monitor: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>'
    ,textSize: '<path d="M4 6V4h10v2M9 4v16M6 20h6M15 9V7h6v2M18 7v13M16 20h4"/>'
    ,comment: '<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z"/>'
    ,eye: '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="2.5"/>'
    ,keyboard: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10h.01M11 10h.01M15 10h.01M18 10h.01M7 14h.01M10 14h7"/>'
    ,clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'
    ,file: '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v5h5"/>'
    ,diff: '<circle cx="7" cy="5" r="2"/><circle cx="17" cy="19" r="2"/><path d="M7 7v12M17 5v12M13 9l4-4 4 4"/>'
    ,bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>'
    ,volume: '<path d="M5 10v4h4l5 4V6l-5 4H5Z"/><path d="M17 9a4 4 0 0 1 0 6M19 6a8 8 0 0 1 0 12"/>'
    ,eyeClosed: '<path d="m3 3 18 18M10.6 10.6a2 2 0 0 0 2.8 2.8M9.9 5.2A11 11 0 0 1 12 5c6.5 0 10 7 10 7a16 16 0 0 1-2.2 3.2M6.6 6.6C3.7 8.5 2 12 2 12s3.5 7 10 7a10 10 0 0 0 4.1-.9"/>'
    ,play: '<path d="m8 5 11 7-11 7Z"/>'
    ,folder: '<path d="M3 6h7l2 2h9v11H3z"/>'
    ,layers: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/>'
    ,discard: '<path d="M4 7v5h5"/><path d="M6.1 17.6A8 8 0 1 0 4 12"/>'
    ,shield: '<path d="M12 3 5 6v5c0 4.7 2.8 8.1 7 10 4.2-1.9 7-5.3 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-5"/>'
    ,circle: '<circle cx="12" cy="12" r="5" fill="currentColor" stroke="none"/>'
  };
export const uiIcon = (name: string): string => `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.code}</svg>`;
