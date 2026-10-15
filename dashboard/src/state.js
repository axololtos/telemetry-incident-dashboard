export const emptyMetrics = {
  received: 0, errors: 0, alerts: 0, cacheHits: 0, llmCalls: 0,
  tokensSpent: 0, tokensSaved: 0, logsPerSecond: 0,
};

export const initialState = { logs: [], alerts: [], m: emptyMetrics };

export function reducer(state, action) {
  switch (action.type) {
    case 'log':
      return { ...state, logs: [action.log, ...state.logs].slice(0, 120) };
    case 'alert':
      return { ...state, alerts: [action.alert, ...state.alerts].slice(0, 30) };
    case 'metrics':
      return { ...state, m: { ...state.m, ...action.m } };
    case 'reset':
      return initialState;
    default:
      return state;
  }
}

let counter = 0;
export const nextId = () => ++counter;
