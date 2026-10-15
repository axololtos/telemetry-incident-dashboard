import { useCallback, useEffect, useRef } from 'react';
import { DIAGNOSIS_BY_FAMILY, STORY, byId } from './scenarios.js';
import { nextId } from './state.js';

// Browser-side stand-in for the backend: same rules (sliding window, exact cache, similar cache, LLM),
// scaled down so a demo fits in a minute. Nothing leaves the page.
const WINDOW_MS = 60_000;
const THRESHOLD = 10;
const COOLDOWN_MS = 20_000;
const PROMPT_OVERHEAD = 80;
const TYPICAL_ANSWER = 250;

const NORMAL_SERVICES = ['orders', 'inventory', 'payments', 'gateway', 'auth', 'search'];
const PATHS = ['/api/orders', '/api/cart', '/api/items', '/health', '/api/login', '/api/search'];
const pick = (a) => a[Math.floor(Math.random() * a.length)];
const tokens = (s) => Math.ceil(s.length / 4);

function buildContext(scn, count, message) {
  const head = `service=${scn.service} errors=${count} distinct=1`;
  const body = `[1] x${count} ${scn.level} ${message}`;
  return [head, body, scn.stack].filter(Boolean).join('\n');
}

function freshSim() {
  return {
    received: 0, errors: 0, alerts: 0, cacheHits: 0, llmCalls: 0, tokensSpent: 0, tokensSaved: 0,
    lastReceived: 0, queue: [], windows: {}, lastAlert: {},
    exact: new Set(), families: new Set(), timers: [], story: [],
  };
}

export function useDemo(active, dispatch) {
  const sim = useRef(freshSim());

  const publishMetrics = useCallback(() => {
    const s = sim.current;
    dispatch({
      type: 'metrics',
      m: {
        received: s.received, errors: s.errors, alerts: s.alerts, cacheHits: s.cacheHits,
        llmCalls: s.llmCalls, tokensSpent: s.tokensSpent, tokensSaved: s.tokensSaved,
      },
    });
  }, [dispatch]);

  const resolveAlert = useCallback((scn, message) => {
    const s = sim.current;
    s.alerts += 1;
    const context = buildContext(scn, THRESHOLD, message);
    const text = DIAGNOSIS_BY_FAMILY[scn.family];
    const key = `${scn.family}:${scn.service}`;
    let source = 'LLM';
    let similarity = 0;
    if (s.exact.has(key)) {
      source = 'CACHE_EXACT';
      similarity = 1;
    } else if (s.families.has(scn.family)) {
      source = 'CACHE_SEMANTIC';
      similarity = 0.91 + Math.random() * 0.06;
    }
    const delay = source === 'LLM' ? 1400 : 150;
    const t = setTimeout(() => {
      let used = 0;
      if (source === 'LLM') {
        used = tokens(context) + tokens(text) + PROMPT_OVERHEAD;
        s.llmCalls += 1;
        s.tokensSpent += used;
        s.exact.add(key);
        s.families.add(scn.family);
      } else {
        s.cacheHits += 1;
        s.tokensSaved += tokens(context) + PROMPT_OVERHEAD + TYPICAL_ANSWER;
      }
      dispatch({
        type: 'alert',
        alert: { id: nextId(), at: Date.now(), service: scn.service, errorCount: THRESHOLD, context, source, similarity, tokensUsed: used, text },
      });
      publishMetrics();
    }, delay);
    s.timers.push(t);
  }, [dispatch, publishMetrics]);

  const emit = useCallback((log, scn) => {
    const s = sim.current;
    s.received += 1;
    dispatch({ type: 'log', log });
    if (log.level !== 'ERROR' && log.level !== 'FATAL') return;
    s.errors += 1;
    const now = Date.now();
    const w = (s.windows[log.service] = (s.windows[log.service] || []).filter((t) => now - t < WINDOW_MS));
    w.push(now);
    if (w.length >= THRESHOLD && now - (s.lastAlert[log.service] || 0) > COOLDOWN_MS) {
      s.lastAlert[log.service] = now;
      s.windows[log.service] = []; // demo only: start the next window clean so short bursts stay below threshold
      resolveAlert(scn, log.message);
    }
  }, [dispatch, resolveAlert]);

  const inject = useCallback((id) => {
    const scn = byId(id);
    if (!scn) return;
    for (let i = 0; i < scn.count; i++) sim.current.queue.push(scn);
  }, []);

  const playStory = useCallback(() => {
    sim.current.story.forEach(clearTimeout); // restarting replaces a story already playing
    sim.current.story = STORY.map(([delay, id]) => setTimeout(() => inject(id), delay));
  }, [inject]);

  useEffect(() => {
    if (!active) return undefined;
    sim.current = freshSim();
    dispatch({ type: 'reset' });

    const tick = setInterval(() => {
      const s = sim.current;
      const burst = Math.random() < 0.3 ? 3 : 2;
      for (let i = 0; i < burst; i++) {
        const service = pick(NORMAL_SERVICES);
        const level = Math.random() < 0.08 ? 'WARN' : 'INFO';
        const message = level === 'WARN'
          ? `Slow response ${Math.floor(800 + Math.random() * 600)}ms on ${pick(PATHS)}`
          : `GET ${pick(PATHS)} 200 in ${Math.floor(8 + Math.random() * 90)}ms`;
        emit({ id: nextId(), ts: Date.now(), service, level, message }, null);
      }
      for (let i = 0; i < 2 && s.queue.length; i++) {
        const scn = s.queue.shift();
        emit({ id: nextId(), ts: Date.now(), service: scn.service, level: scn.level, message: scn.message() }, scn);
      }
    }, 200);

    const metricsTimer = setInterval(() => {
      const s = sim.current;
      const rate = s.received - s.lastReceived;
      s.lastReceived = s.received;
      publishMetrics();
      dispatch({ type: 'metrics', m: { logsPerSecond: rate } });
    }, 1000);

    return () => {
      clearInterval(tick);
      clearInterval(metricsTimer);
      sim.current.timers.forEach(clearTimeout);
      sim.current.story.forEach(clearTimeout);
    };
  }, [active, dispatch, emit, publishMetrics]);

  return { inject, playStory };
}
