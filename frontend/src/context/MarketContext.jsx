import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { marketApi } from '../api/market.js';
import { WS_BASE_URL } from '../api/client.js';

const MarketContext = createContext(null);

function deltaSubscription(type, symbol) {
  return JSON.stringify({
    type,
    payload: {
      channels: [
        { name: 'v2/ticker', symbols: [symbol] },
        { name: 'all_trades', symbols: [symbol] },
      ],
    },
  });
}

/**
 * One shared STOMP-over-SockJS connection for the whole app.
 *
 * The backend pushes every instrument's tick to /topic/quotes (batched), plus an authoritative
 * account snapshot to /topic/account/{id} after the owner's own trades. Every tick carries its
 * source, so the SIMULATED badge is driven by real server data - never guessed client-side.
 */
export function MarketProvider({ children }) {
  const [quotes, setQuotes] = useState({});
  const [connected, setConnected] = useState(false);
  const [status, setStatus] = useState(null);
  const [lastTickAt, setLastTickAt] = useState(null);
  const clientRef = useRef(null);
  const deltaSocketRef = useRef(null);
  const deltaHandlersRef = useRef(new Map());
  const deltaReconnectRef = useRef(null);
  const deltaPingRef = useRef(null);
  const deltaEnabledRef = useRef(false);
  const deltaRetryRef = useRef(0);
  const deltaLastLogRef = useRef(new Map());

  const notifyDeltaStatus = useCallback((state) => {
    for (const handlers of deltaHandlersRef.current.values()) {
      for (const handler of handlers) handler.onStatus?.(state);
    }
  }, []);

  const connectDelta = useCallback(() => {
    if (
      !deltaEnabledRef.current ||
      deltaSocketRef.current ||
      deltaHandlersRef.current.size === 0
    ) {
      return;
    }
    const socketUrl =
      import.meta.env.VITE_DELTA_WS_URL || 'wss://socket.india.delta.exchange';
    let socket;
    try {
      socket = new WebSocket(socketUrl);
    } catch (error) {
      if (import.meta.env.DEV) console.error('[Delta WS] connection failed', error);
      notifyDeltaStatus('reconnecting');
      if (!deltaReconnectRef.current) {
        const delay = Math.min(1000 * 2 ** deltaRetryRef.current, 15000);
        deltaRetryRef.current += 1;
        deltaReconnectRef.current = window.setTimeout(() => {
          deltaReconnectRef.current = null;
          connectDelta();
        }, delay);
      }
      return;
    }
    deltaSocketRef.current = socket;
    if (import.meta.env.DEV) console.info('[Delta WS] connecting', socketUrl);
    let lastMessageAt = Date.now();

    const subscribe = (symbol, type = 'subscribe') => {
      if (socket.readyState !== WebSocket.OPEN) return;
      socket.send(deltaSubscription(type, symbol));
      if (import.meta.env.DEV) console.info(`[Delta WS] ${type}`, symbol);
    };

    socket.onopen = () => {
      if (deltaSocketRef.current !== socket) return;
      deltaRetryRef.current = 0;
      notifyDeltaStatus('connected');
      for (const symbol of deltaHandlersRef.current.keys()) subscribe(symbol);
      deltaPingRef.current = window.setInterval(() => {
        if (socket.readyState !== WebSocket.OPEN) return;
        if (Date.now() - lastMessageAt > 60000) {
          socket.close();
          return;
        }
        socket.send(JSON.stringify({ type: 'ping' }));
      }, 25000);
    };

    socket.onmessage = (frame) => {
      if (deltaSocketRef.current !== socket) return;
      lastMessageAt = Date.now();
      let message;
      try {
        message = JSON.parse(frame.data);
      } catch (error) {
        if (import.meta.env.DEV) console.warn('[Delta WS] malformed event', error);
        return;
      }
      if (message.type === 'pong') return;
      if (String(message.channel || message.type || '').toLowerCase().includes('_snapshot')) return;
      if (message.type === 'error') {
        if (import.meta.env.DEV) console.warn('[Delta WS] exchange error', message);
        notifyDeltaStatus('reconnecting');
        return;
      }
      const channel = String(message.channel || message.type || '').toLowerCase();
      if (channel === 'subscriptions' || channel === 'pong') return;
      const receivedAt = Date.now();
      const payload = message.data ?? message;
      const events = Array.isArray(payload)
        ? payload
        : Array.isArray(payload.trades)
          ? payload.trades.map((trade) => ({
              ...trade,
              symbol: trade.symbol || payload.symbol || message.symbol,
            }))
          : [payload];
      for (const event of events) {
        const symbol = String(
          event.symbol || event.product_symbol || message.symbol || '',
        ).toUpperCase();
        const handlers = deltaHandlersRef.current.get(symbol);
        if (!handlers) continue;
        const normalizedChannel = channel.includes('ticker')
          ? 'ticker'
          : channel.includes('trade')
            ? 'trade'
            : '';
        if (!normalizedChannel) continue;
        const eventTime =
          event.timestamp || event.time || event.created_at || event.updated_at || null;
        const logKey = `${symbol}:${normalizedChannel}`;
        if (
          import.meta.env.DEV &&
          receivedAt - (deltaLastLogRef.current.get(logKey) || 0) > 10000
        ) {
          deltaLastLogRef.current.set(logKey, receivedAt);
          console.debug(`[Delta WS] ${normalizedChannel} ${symbol}`, eventTime);
        }
        for (const handler of handlers) {
          if (normalizedChannel === 'ticker') {
            handler.onTicker?.({ ...event, receivedAt });
          } else {
            handler.onTrade?.({ ...event, receivedAt });
          }
        }
      }
    };

    socket.onerror = (error) => {
      if (import.meta.env.DEV) console.warn('[Delta WS] socket error', error);
      notifyDeltaStatus('reconnecting');
      socket.close();
    };

    socket.onclose = () => {
      if (deltaSocketRef.current !== socket) return;
      deltaSocketRef.current = null;
      if (deltaPingRef.current) window.clearInterval(deltaPingRef.current);
      deltaPingRef.current = null;
      if (deltaHandlersRef.current.size === 0 || !deltaEnabledRef.current) return;
      notifyDeltaStatus('reconnecting');
      const delay = Math.min(1000 * 2 ** deltaRetryRef.current, 15000);
      deltaRetryRef.current += 1;
      deltaReconnectRef.current = window.setTimeout(() => {
        deltaReconnectRef.current = null;
        connectDelta();
      }, delay);
    };
  }, [notifyDeltaStatus]);

  const subscribeDelta = useCallback(
    (symbol, handlers) => {
      const normalizedSymbol = String(symbol || '').trim().toUpperCase();
      if (!normalizedSymbol) return () => {};
      const subscriptions = deltaHandlersRef.current.get(normalizedSymbol) || new Set();
      const isNewSymbol = subscriptions.size === 0;
      subscriptions.add(handlers);
      deltaHandlersRef.current.set(normalizedSymbol, subscriptions);
      handlers.onStatus?.(deltaSocketRef.current?.readyState === WebSocket.OPEN ? 'connected' : 'reconnecting');
      if (isNewSymbol && deltaSocketRef.current?.readyState === WebSocket.OPEN) {
        deltaSocketRef.current.send(deltaSubscription('subscribe', normalizedSymbol));
        if (import.meta.env.DEV) console.info('[Delta WS] subscribe', normalizedSymbol);
      } else {
        connectDelta();
      }
      return () => {
        const active = deltaHandlersRef.current.get(normalizedSymbol);
        active?.delete(handlers);
        if (active?.size) return;
        deltaHandlersRef.current.delete(normalizedSymbol);
        const socket = deltaSocketRef.current;
        if (socket?.readyState === WebSocket.OPEN) {
          socket.send(deltaSubscription('unsubscribe', normalizedSymbol));
          if (import.meta.env.DEV) console.info('[Delta WS] unsubscribe', normalizedSymbol);
        }
        if (deltaHandlersRef.current.size === 0) {
          if (deltaReconnectRef.current) window.clearTimeout(deltaReconnectRef.current);
          if (deltaPingRef.current) window.clearInterval(deltaPingRef.current);
          deltaReconnectRef.current = null;
          deltaPingRef.current = null;
          deltaSocketRef.current?.close();
          deltaSocketRef.current = null;
        }
      };
    },
    [connectDelta],
  );

  useEffect(() => {
    let cancelled = false;

    marketApi
      .status()
      .then((value) => {
        if (!cancelled) setStatus(value);
      })
      .catch(() => {
        /* the badge simply stays unknown until the next successful call */
      });

    const client = new Client({
      webSocketFactory: () => new SockJS(`${WS_BASE_URL}/ws`),
      reconnectDelay: 4000,
      heartbeatIncoming: 12000,
      heartbeatOutgoing: 12000,
      onConnect: () => {
        if (cancelled) return;
        setConnected(true);
        client.subscribe('/topic/quotes', (frame) => {
          try {
            const ticks = JSON.parse(frame.body);
            const list = Array.isArray(ticks) ? ticks : [ticks];
            setQuotes((current) => {
              const next = { ...current };
              for (const tick of list) {
                const currentTime = current[tick.symbol]?.asOf
                  ? new Date(current[tick.symbol].asOf).getTime()
                  : 0;
                const nextTime = tick.asOf ? new Date(tick.asOf).getTime() : 0;
                if (currentTime > nextTime) continue;
                next[tick.symbol] = tick;
              }
              return next;
            });
            setLastTickAt(new Date().toISOString());
          } catch {
            /* a malformed frame must never take the UI down */
          }
        });
      },
      onWebSocketClose: () => {
        if (!cancelled) setConnected(false);
      },
      onStompError: () => {
        if (!cancelled) setConnected(false);
      },
    });

    client.activate();
    clientRef.current = client;

    return () => {
      cancelled = true;
      clientRef.current = null;
      client.deactivate();
    };
  }, []);

  useEffect(() => {
    deltaEnabledRef.current =
      status?.dataSource === 'LIVE' && String(status?.provider || '').toUpperCase() === 'DELTA';
    if (deltaEnabledRef.current && deltaHandlersRef.current.size) connectDelta();
    if (!deltaEnabledRef.current) {
      if (deltaReconnectRef.current) window.clearTimeout(deltaReconnectRef.current);
      if (deltaPingRef.current) window.clearInterval(deltaPingRef.current);
      deltaReconnectRef.current = null;
      deltaPingRef.current = null;
      deltaSocketRef.current?.close();
      deltaSocketRef.current = null;
      notifyDeltaStatus('disabled');
    }
  }, [status, connectDelta, notifyDeltaStatus]);

  useEffect(
    () => () => {
      if (deltaReconnectRef.current) window.clearTimeout(deltaReconnectRef.current);
      if (deltaPingRef.current) window.clearInterval(deltaPingRef.current);
      deltaHandlersRef.current.clear();
      deltaSocketRef.current?.close();
      deltaSocketRef.current = null;
    },
    [],
  );

  // Poll the status endpoint occasionally so the badge reflects provider changes even when idle.
  useEffect(() => {
    const interval = window.setInterval(() => {
      marketApi.status().then(setStatus).catch(() => {});
    }, 60000);
    return () => window.clearInterval(interval);
  }, []);

  /** Subscribe to any additional STOMP destination (e.g. the account channel). */
  const subscribe = useCallback((destination, handler) => {
    const client = clientRef.current;
    if (!client || !client.connected) {
      return () => {};
    }
    const subscription = client.subscribe(destination, (frame) => {
      try {
        handler(JSON.parse(frame.body));
      } catch {
        /* ignore malformed frames */
      }
    });
    return () => subscription.unsubscribe();
  }, []);

  const value = useMemo(
    () => ({
      quotes,
      connected,
      status,
      lastTickAt,
      subscribe,
      subscribeDelta,
      dataSource: status?.dataSource || quotes[Object.keys(quotes)[0]]?.source || null,
      dataSourceLabel: status?.dataSourceLabel || quotes[Object.keys(quotes)[0]]?.sourceLabel || null,
      isLive: (status?.dataSource || '').toUpperCase() === 'LIVE',
    }),
    [quotes, connected, status, lastTickAt, subscribe, subscribeDelta],
  );

  return <MarketContext.Provider value={value}>{children}</MarketContext.Provider>;
}

export function useMarket() {
  const context = useContext(MarketContext);
  if (!context) {
    throw new Error('useMarket must be used inside MarketProvider');
  }
  return context;
}
