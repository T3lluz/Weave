// The page's hero is the app's trackpad: cloth stretched over a frame. The pointer pushes a dent
// into it, a click sends a ring of light out through the dots, and one ring runs from the
// middle when the page opens, as it does in the app. Nothing is drawn while nothing moves.
(() => {
  const pad = document.querySelector('.pad[data-weave]');
  const canvas = pad && pad.querySelector('canvas');
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!pad || !canvas || !canvas.getContext || still) {
    if (pad) pad.classList.add('plain');
    return;
  }
  const ctx = canvas.getContext('2d');
  // the app's own numbers: a dot every 13 dp, offset every other row, 1.5 dp across
  const STEP = 13, ROW = STEP * 0.87, DOT = 1.5, DENT = 62, PULL = 1.25, EDGE = 28;
  const RIPPLE_MS = 760, REACH = 150, BAND = 10, LIGHT = [0.22, 0.5, 0.22];
  let w = 0, h = 0, dots = [], frame = 0, last = 0;
  // where the finger is, how far the cloth is pressed (a spring), and whether it is down
  const finger = { x: 0, y: 0, depth: 0, speed: 0, target: 0, on: false };
  const ripples = [];

  function size() {
    const box = pad.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    w = box.width; h = box.height;
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    dots = [];
    for (let y = STEP / 2, row = 0; y < h; y += ROW, row++) {
      for (let x = row % 2 ? STEP : STEP / 2; x < w; x += STEP) dots.push(x, y);
    }
    wake();
  }

  function wake() {
    if (!frame) { last = performance.now(); frame = requestAnimationFrame(draw); }
  }

  function ripple(x, y, far = 1) {
    if (ripples.length < 4) ripples.push({ x, y, far, at: performance.now() });
    wake();
  }

  function draw(now) {
    frame = 0;
    const dt = Math.min((now - last) / 1000, 0.05);
    last = now;
    // into the dent quickly, back out with a bounce a touch past flat, as the app's cloth does
    const [stiff, damp] = finger.target > finger.depth ? [700, 0.72] : [380, 0.36];
    finger.speed += (stiff * (finger.target - finger.depth) - 2 * damp * Math.sqrt(stiff) * finger.speed) * dt;
    finger.depth += finger.speed * dt;
    const settled = Math.abs(finger.target - finger.depth) < 0.002 && Math.abs(finger.speed) < 0.02;
    if (settled) { finger.depth = finger.target; finger.speed = 0; }
    for (let i = ripples.length - 1; i >= 0; i--) {
      if (now - ripples[i].at > RIPPLE_MS * ripples[i].far) ripples.splice(i, 1);
    }

    ctx.clearRect(0, 0, w, h);
    const depth = finger.depth;
    // the dent's walls are a shade darker all round the finger
    if (depth > 0.01) {
      const shade = ctx.createRadialGradient(finger.x, finger.y, 0, finger.x, finger.y, DENT * 1.9);
      shade.addColorStop(0, 'rgba(0,0,0,0)');
      shade.addColorStop(0.36, `rgba(0,0,0,${0.13 * Math.min(depth, 1)})`);
      shade.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = shade;
      ctx.fillRect(finger.x - DENT * 2, finger.y - DENT * 2, DENT * 4, DENT * 4);
    }
    const lit = [new Path2D(), new Path2D(), new Path2D()];
    const base = new Path2D();
    const rings = ripples.map((r) => {
      const t = (now - r.at) / (RIPPLE_MS * r.far);
      return { x: r.x, y: r.y, reach: REACH * r.far * (1 - (1 - t) * (1 - t)), fade: 1 - t };
    });
    let any = false;
    for (let i = 0; i < dots.length; i += 2) {
      let x = dots[i], y = dots[i + 1];
      if (depth !== 0) {
        // the cloth is drawn in toward the finger; the frame holds its edges where they are
        const dx = x - finger.x, dy = y - finger.y;
        const d2 = (dx * dx + dy * dy) / (DENT * DENT);
        if (d2 < 9) {
          const edge = Math.max(0, Math.min(1, Math.min(x, w - x, y, h - y) / EDGE));
          const k = PULL * depth * Math.exp(-d2) * edge;
          x = finger.x + dx / (1 + k); y = finger.y + dy / (1 + k);
        }
      }
      base.moveTo(x + DOT, y); base.arc(x, y, DOT, 0, 6.2832);
      for (const r of rings) {
        const off = Math.hypot(x - r.x, y - r.y) - r.reach;
        if (off > -BAND * 1.5 && off < BAND * 1.5) {
          const band = lit[off < -BAND / 2 ? 0 : off < BAND / 2 ? 1 : 2];
          band.moveTo(x + DOT, y); band.arc(x, y, DOT, 0, 6.2832);
          any = true;
        }
      }
    }
    ctx.fillStyle = 'rgba(255,255,255,0.07)';
    ctx.fill(base);
    if (any) {
      const fade = Math.max(...rings.map((r) => r.fade));
      lit.forEach((band, i) => { ctx.fillStyle = `rgba(255,255,255,${LIGHT[i] * fade})`; ctx.fill(band); });
    }
    if (!settled || ripples.length) wake();
  }

  function at(e) {
    const box = pad.getBoundingClientRect();
    finger.x = e.clientX - box.left; finger.y = e.clientY - box.top;
  }
  // a mouse resting on the cloth presses it a little; a button or a finger presses it all the way
  pad.addEventListener('pointermove', (e) => {
    at(e);
    if (e.pointerType === 'mouse' && !finger.on) finger.target = 0.55;
    wake();
  }, { passive: true });
  pad.addEventListener('pointerdown', (e) => {
    at(e); finger.on = true; finger.target = 1; finger.downAt = performance.now(); wake();
  }, { passive: true });
  const lift = (e) => {
    if (!finger.on) return;
    finger.on = false;
    finger.target = e.pointerType === 'mouse' && e.type === 'pointerup' ? 0.55 : 0;
    // a tap sends a ring out from where it landed
    if (e.type === 'pointerup' && performance.now() - finger.downAt < 320) ripple(finger.x, finger.y);
    wake();
  };
  pad.addEventListener('pointerup', lift, { passive: true });
  pad.addEventListener('pointercancel', lift, { passive: true });
  pad.addEventListener('pointerleave', () => { finger.on = false; finger.target = 0; wake(); }, { passive: true });

  new ResizeObserver(size).observe(pad);
  size();
  ripple(w / 2, h / 2, 1.9);
})();

// Which system's command shows: this computer's, to start with.
(() => {
  const tabs = [...document.querySelectorAll('[role=tab]')];
  const pick = (tab) => {
    for (const t of tabs) {
      const on = t === tab;
      t.setAttribute('aria-selected', on);
      document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
    }
  };
  tabs.forEach((t) => t.addEventListener('click', () => pick(t)));
  const linux = document.getElementById('tab-linux');
  if (linux && /Linux|X11/.test(navigator.userAgent) && !/Android/.test(navigator.userAgent)) pick(linux);

  const copyIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2.5"/><path d="M5 15V6.5A2.5 2.5 0 0 1 7.5 4H15"/></svg>';
  const doneIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
  for (const button of document.querySelectorAll('.copy')) {
    button.innerHTML = copyIcon;
    button.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(button.previousElementSibling.textContent); } catch { return; }
      button.innerHTML = doneIcon; button.classList.add('done');
      setTimeout(() => { button.innerHTML = copyIcon; button.classList.remove('done'); }, 1400);
    });
  }
})();
