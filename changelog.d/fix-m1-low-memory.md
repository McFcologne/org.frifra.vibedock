- **M1 keeps its pairing when memory runs low:** after a night of aborted transfers an M1 had so little memory left
  that it sent its request without a whole key, and on the server's 401 it removed its pairing. The server now answers
  a missing or cut-off key with 400, which every firmware ignores; firmware 0.3.3 also removes its pairing only after
  three 401 answers in a row (log *Server rejected the device key (401), 1 of 3*).
- **M1 restarts itself when memory runs low:** when three pulls in a row find no block of 6 kB left (a TLS connection
  needs 4.6 kB for its stack), the M1 restarts (*Restart: too little memory in one piece …*, start kind `low-memory`)
  instead of failing on. Between log events its log lines are no longer kept in memory but only in its file; they took
  up to 7 kB of its heap. Needs the new firmware.
