- **Update interval of the data sources:** *Data sources → Update interval* chooses between a fixed and a dynamic
  interval, with a shortest and a longest one (default dynamic, 1 to 10 minutes). Dynamic learns for every source
  how often its provider answers: each HTTP 429 adds a minute, an hour without one tries a minute less, and a 429 at
  that shorter interval waits two, then four hours before the next try. Before, a source halved its interval with
  every successful answer, so after a single 429 the next answer brought it back to one minute and it soon ran into
  the limit again. The card of each source shows its current interval, and the server log every change. Server only.
