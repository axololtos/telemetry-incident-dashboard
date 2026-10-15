import React, { useReducer, useState } from 'react';
import { SCENARIOS } from './scenarios.js';
import { initialState, reducer } from './state.js';
import { useDemo } from './useDemo.js';
import { useLive } from './useLive.js';

const time = (ms) => new Date(ms).toLocaleTimeString([], { hour12: false });
const n = (v) => Math.round(v || 0).toLocaleString();

const SOURCE_LABEL = {
  LLM: 'asked the LLM',
  CACHE_EXACT: 'cache: same error seen before',
  CACHE_SEMANTIC: 'cache: similar error',
  FALLBACK: 'LLM unavailable',
};

function Figure({ label, value, note }) {
  return (
    <div className="figure">
      <div className="figure-value">{value}</div>
      <div className="figure-label">{label}</div>
      {note && <div className="figure-note">{note}</div>}
    </div>
  );
}

function Incident({ a }) {
  const fromCache = a.source.startsWith('CACHE');
  return (
    <article className="incident">
      <header>
        <strong>{a.service}</strong>
        <span className="muted"> · {time(a.at)} · {a.errorCount} errors in the window</span>
      </header>
      <p className={`stamp ${fromCache ? 'stamp-cache' : 'stamp-llm'}`}>
        {SOURCE_LABEL[a.source] || a.source}
        {a.source === 'CACHE_SEMANTIC' && ` (${a.similarity.toFixed(2)} match)`}
        {' · '}{a.tokensUsed ? `${n(a.tokensUsed)} tokens` : '0 tokens'}
      </p>
      <pre className="diagnosis">{a.text}</pre>
      <details>
        <summary>what the model saw</summary>
        <pre className="context">{a.context}</pre>
      </details>
    </article>
  );
}

export default function App() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const [mode, setMode] = useState('demo');
  const [url, setUrl] = useState('http://localhost:8080');
  const [pendingUrl, setPendingUrl] = useState('http://localhost:8080');
  const [status, setStatus] = useState('idle');

  const { inject, playStory } = useDemo(mode === 'demo', dispatch);
  useLive(mode === 'live', url, dispatch, setStatus);

  const { m, logs, alerts } = state;
  const saved = m.tokensSaved;

  return (
    <div className="page">
      <header className="masthead">
        <div>
          <h1>Incident Desk</h1>
          <p className="muted">Kafka logs in, one LLM call per new kind of failure.</p>
        </div>
        <nav className="modes">
          <button className={mode === 'demo' ? 'on' : ''} onClick={() => setMode('demo')}>Demo</button>
          <button className={mode === 'live' ? 'on' : ''} onClick={() => setMode('live')}>Live backend</button>
        </nav>
      </header>

      {mode === 'demo' ? (
        <section className="note">
          <p>
            <strong>This is a simulation.</strong> The logs, the error-burst detection and the cache all run
            in your browser, and the diagnoses are written by hand. Nothing is sent anywhere. Pick a failure
            below, or let it tell the whole story.
          </p>
          <div className="buttons">
            <button className="primary" onClick={playStory}>Play the story (1 min)</button>
            {SCENARIOS.map((s) => (
              <button key={s.id} onClick={() => inject(s.id)}>{s.label}</button>
            ))}
          </div>
        </section>
      ) : (
        <section className="note">
          <p>
            Connects to the Spring Boot service over STOMP. Start it with <code>docker compose up -d</code> and{' '}
            <code>mvn spring-boot:run</code>, then run <code>scripts/send-sample-logs.sh</code>. Status: <strong>{status}</strong>
          </p>
          <form className="buttons" onSubmit={(e) => { e.preventDefault(); setUrl(pendingUrl); }}>
            <input value={pendingUrl} onChange={(e) => setPendingUrl(e.target.value)} aria-label="Backend URL" />
            <button className="primary" type="submit">Connect</button>
          </form>
        </section>
      )}

      <section className="figures">
        <Figure label="logs received" value={n(m.received)} note={`${n(m.logsPerSecond)} per second`} />
        <Figure label="errors" value={n(m.errors)} />
        <Figure label="incidents" value={n(m.alerts)} />
        <Figure label="served from cache" value={n(m.cacheHits)} />
        <Figure label="LLM calls" value={n(m.llmCalls)} note={`${n(m.tokensSpent)} tokens spent`} />
        <Figure label="tokens not spent" value={n(saved)} note="estimate" />
      </section>

      <main className="columns">
        <section>
          <h2>Incidents</h2>
          {alerts.length === 0 && <p className="empty">Nothing yet. An incident is raised when a service logs 10 errors within a minute.</p>}
          {alerts.map((a) => <Incident key={a.id} a={a} />)}
        </section>
        <section>
          <h2>Log stream</h2>
          <ol className="stream">
            {logs.map((l) => {
              const bad = l.level === 'ERROR' || l.level === 'FATAL';
              return (
                <li key={l.id} className={bad ? 'bad' : ''}>
                  <span className="t">{time(l.ts)}</span>
                  <span className="lvl">{l.level}</span>
                  <span className="svc">{l.service}</span>
                  <span className="msg">{l.message}</span>
                </li>
              );
            })}
          </ol>
        </section>
      </main>

      <footer className="muted">
        Java 21 · Spring Boot · Kafka · PostgreSQL + pgvector · LangChain4j · React
      </footer>
    </div>
  );
}
