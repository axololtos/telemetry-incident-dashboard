// Scenarios used by the in-browser demo. They mirror sample-data/*.ndjson on the backend.
// The diagnosis texts are hand-written stand-ins for what an LLM would return.

const rand = (a, b) => a + Math.floor(Math.random() * (b - a + 1));

const DIAGNOSES = {
  'db-pool': `Root cause: database connection pool is exhausted (confidence: high). Requests wait 30s for a connection and time out.
Impact: writes from this service are failing or very slow.
Fix:
1. Look for connections that are never returned (missing close, long transactions).
2. Check slow queries holding connections, then raise the pool size or lower the timeout.
3. Add an alert on pool wait time.`,
  oom: `Root cause: the indexer builds its whole result list in memory and runs out of heap (confidence: medium).
Impact: indexing workers are dying, so search results go stale.
Fix:
1. Page the data in batches instead of one ArrayList.
2. Raise -Xmx as a short-term relief.
3. Capture a heap dump on the next failure.`,
  npe: `Root cause: CardMapper.toToken gets a null card object, probably from requests with no saved payment method (confidence: high).
Impact: those checkout attempts fail with a 500.
Fix:
1. Null-check the card and return a clear 4xx.
2. Find the caller that skips card validation.`,
  upstream: `Root cause: payments-svc is not answering within the gateway's read timeout (confidence: medium).
Impact: checkout requests return 502 to customers.
Fix:
1. Check payments-svc health and recent deploys.
2. Add a circuit breaker and a shorter timeout at the gateway.
3. Retry only idempotent calls.`,
  jwt: `Root cause: tokens are signed with a key the auth service does not know, likely a key rotation that did not reach every instance (confidence: medium).
Impact: some users are logged out or get 401s.
Fix:
1. Compare the key sets loaded by each auth instance.
2. Keep the old key valid during rotation.`,
};

const stackText = (lines) => lines.join('\n');

export const SCENARIOS = [
  {
    id: 'db-orders', label: 'DB pool exhausted: orders', service: 'orders', family: 'db-pool',
    level: 'ERROR', count: 12,
    message: () => `HikariPool-1 - Connection is not available, request timed out after ${rand(30000, 30100)}ms`,
    stack: stackText([
      'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available',
      '\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)',
      '\tat com.shop.orders.Repository.save(Repository.java:48)',
      '\tat com.shop.orders.Service.handle(Service.java:71)',
    ]),
  },
  {
    id: 'db-inventory', label: 'Same failure, other service: inventory', service: 'inventory', family: 'db-pool',
    level: 'ERROR', count: 12,
    message: () => `HikariPool-1 - Connection is not available, request timed out after ${rand(30000, 30100)}ms`,
    stack: stackText([
      'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available',
      '\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)',
      '\tat com.shop.inventory.Repository.save(Repository.java:48)',
      '\tat com.shop.inventory.Service.handle(Service.java:71)',
    ]),
  },
  {
    id: 'oom-search', label: 'Out of memory: search', service: 'search', family: 'oom',
    level: 'FATAL', count: 11,
    message: () => `Indexer worker ${rand(100, 999)} died: Java heap space`,
    stack: stackText([
      'java.lang.OutOfMemoryError: Java heap space',
      '\tat java.util.ArrayList.grow(ArrayList.java:237)',
      '\tat com.shop.search.IndexBuilder.collect(IndexBuilder.java:112)',
      '\tat com.shop.search.IndexBuilder.run(IndexBuilder.java:64)',
    ]),
  },
  {
    id: 'npe-payments', label: 'Null pointer: payments', service: 'payments', family: 'npe',
    level: 'ERROR', count: 12,
    message: () => `Payment ${rand(5000, 5999)} failed: cannot read field "token" because "card" is null`,
    stack: stackText([
      'java.lang.NullPointerException: Cannot read field "token" because "card" is null',
      '\tat com.shop.payments.CardMapper.toToken(CardMapper.java:33)',
      '\tat com.shop.payments.PaymentService.charge(PaymentService.java:90)',
      '\tat com.shop.payments.PaymentController.pay(PaymentController.java:41)',
    ]),
  },
  {
    id: '502-gateway', label: 'Upstream 502: gateway', service: 'gateway', family: 'upstream',
    level: 'ERROR', count: 14,
    message: () => `502 Bad Gateway from upstream payments-svc after ${rand(5000, 5300)}ms`,
    stack: stackText([
      'org.springframework.web.client.ResourceAccessException: Read timed out',
      '\tat org.springframework.web.client.RestTemplate.doExecute(RestTemplate.java:791)',
      '\tat com.shop.gateway.PaymentsClient.charge(PaymentsClient.java:37)',
    ]),
  },
  {
    id: 'jwt-auth', label: 'JWT failures: auth', service: 'auth', family: 'jwt',
    level: 'ERROR', count: 12,
    message: () => `JWT signature validation failed for key id kid-${rand(0, 2)}`,
    stack: stackText([
      'io.jsonwebtoken.security.SignatureException: JWT signature does not match locally computed signature',
      '\tat com.shop.auth.TokenVerifier.verify(TokenVerifier.java:44)',
      '\tat com.shop.auth.AuthFilter.doFilter(AuthFilter.java:29)',
    ]),
  },
  {
    id: 'noise-orders', label: 'A few errors (below threshold)', service: 'orders', family: 'noise',
    level: 'ERROR', count: 6,
    message: () => `Coupon ${rand(1, 99)} rejected: expired`,
    stack: '',
  },
];

export const DIAGNOSIS_BY_FAMILY = DIAGNOSES;

// Played by the "Play the story" button: [delay in ms, scenario id]
export const STORY = [
  [0, 'db-orders'],
  [7000, 'noise-orders'],
  [13000, 'db-inventory'],
  [21000, 'oom-search'],
  [30000, 'db-orders'],
  [38000, '502-gateway'],
  [46000, 'jwt-auth'],
  [54000, 'npe-payments'],
];

export const byId = (id) => SCENARIOS.find((s) => s.id === id);
