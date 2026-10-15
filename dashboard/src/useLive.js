import { useEffect } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { nextId } from './state.js';

// Talks to the Spring Boot backend: STOMP over SockJS at <baseUrl>/ws.
export function useLive(active, baseUrl, dispatch, setStatus) {
  useEffect(() => {
    if (!active) return undefined;
    dispatch({ type: 'reset' });
    setStatus('connecting');

    const client = new Client({
      webSocketFactory: () => new SockJS(`${baseUrl.replace(/\/$/, '')}/ws`),
      reconnectDelay: 3000,
      onConnect: () => {
        setStatus('connected');
        client.subscribe('/topic/logs', (msg) => {
          const e = JSON.parse(msg.body);
          dispatch({ type: 'log', log: { id: nextId(), ts: Date.parse(e.timestamp), service: e.service, level: e.level, message: e.message } });
        });
        client.subscribe('/topic/alerts', (msg) => {
          const i = JSON.parse(msg.body);
          dispatch({
            type: 'alert',
            alert: {
              id: nextId(), at: Date.parse(i.at), service: i.service, errorCount: i.errorCount, context: i.context,
              source: i.diagnosis.source, similarity: i.diagnosis.similarity, tokensUsed: i.diagnosis.tokensUsed, text: i.diagnosis.text,
            },
          });
        });
        client.subscribe('/topic/metrics', (msg) => {
          const b = JSON.parse(msg.body);
          dispatch({ type: 'metrics', m: { ...b.totals, logsPerSecond: Math.round(b.logsPerSecond) } });
        });
      },
      onWebSocketClose: () => setStatus('connecting'),
      onStompError: () => setStatus('error'),
    });
    client.activate();
    return () => {
      client.deactivate();
      setStatus('idle');
    };
  }, [active, baseUrl, dispatch, setStatus]);
}
