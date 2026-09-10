
## connections-c500

| test | subject | ops/sec (median) | spread | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| GET | resp-server (virtual threads) | 55,279 | ±4.1% | 4.511 | 6.751 | 7.855 |
| GET | resp-server (event loop) | 126,743 | ±1.0% | 1.943 | 2.479 | 3.231 |
| GET | Redis | 118,624 | ±23.8% | 2.079 | 2.583 | 3.111 |
| SET | resp-server (virtual threads) | 55,586 | ±10.0% | 4.591 | 6.855 | 7.975 |
| SET | resp-server (event loop) | 123,609 | ±13.3% | 1.991 | 2.583 | 3.655 |
| SET | Redis | 117,647 | ±6.1% | 2.087 | 2.663 | 3.183 |

## mixed-c50

| test | subject | ops/sec (median) | spread | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| GET | resp-server (virtual threads) | 53,079 | ±27.7% | 0.503 | 0.743 | 1.015 |
| GET | resp-server (event loop) | 123,153 | ±7.7% | 0.207 | 0.303 | 0.471 |
| GET | Redis | 129,032 | ±4.8% | 0.199 | 0.295 | 0.431 |
| HSET | resp-server (virtual threads) | 53,362 | ±34.6% | 0.503 | 0.735 | 0.959 |
| HSET | resp-server (event loop) | 127,877 | ±14.2% | 0.199 | 0.319 | 0.487 |
| HSET | Redis | 127,551 | ±29.9% | 0.199 | 0.295 | 0.415 |
| INCR | resp-server (virtual threads) | 54,585 | ±19.3% | 0.487 | 0.719 | 0.935 |
| INCR | resp-server (event loop) | 125,000 | ±11.2% | 0.207 | 0.415 | 0.687 |
| INCR | Redis | 127,877 | ±24.9% | 0.207 | 0.319 | 0.463 |
| LPUSH | resp-server (virtual threads) | 54,705 | ±19.9% | 0.487 | 0.711 | 0.927 |
| LPUSH | resp-server (event loop) | 127,714 | ±25.3% | 0.199 | 0.391 | 0.583 |
| LPUSH | Redis | 119,474 | ±56.5% | 0.215 | 0.343 | 0.527 |
| MSET (10 keys) | resp-server (virtual threads) | 52,604 | ±23.2% | 0.511 | 0.767 | 1.151 |
| MSET (10 keys) | resp-server (event loop) | 132,979 | ±5.3% | 0.215 | 0.439 | 0.695 |
| MSET (10 keys) | Redis | 141,443 | ±2.6% | 0.207 | 0.359 | 0.543 |
| PING_INLINE | resp-server (virtual threads) | 55,127 | ±3.7% | 0.479 | 0.711 | 0.927 |
| PING_INLINE | resp-server (event loop) | 114,025 | ±4.8% | 0.223 | 0.335 | 0.511 |
| PING_INLINE | Redis | 122,850 | ±4.8% | 0.207 | 0.311 | 0.431 |
| PING_MBULK | resp-server (virtual threads) | 53,821 | ±24.8% | 0.495 | 0.719 | 0.951 |
| PING_MBULK | resp-server (event loop) | 117,925 | ±8.3% | 0.215 | 0.303 | 0.479 |
| PING_MBULK | Redis | 112,486 | ±22.6% | 0.223 | 0.407 | 0.647 |
| RPOP | resp-server (virtual threads) | 53,447 | ±22.4% | 0.495 | 0.743 | 0.991 |
| RPOP | resp-server (event loop) | 121,507 | ±15.9% | 0.207 | 0.351 | 0.591 |
| RPOP | Redis | 130,208 | ±26.1% | 0.199 | 0.287 | 0.447 |
| SADD | resp-server (virtual threads) | 54,142 | ±22.4% | 0.495 | 0.727 | 0.959 |
| SADD | resp-server (event loop) | 125,628 | ±7.0% | 0.207 | 0.343 | 0.503 |
| SADD | Redis | 126,103 | ±10.5% | 0.207 | 0.327 | 0.527 |
| SET | resp-server (virtual threads) | 53,619 | ±24.5% | 0.495 | 0.743 | 0.967 |
| SET | resp-server (event loop) | 123,609 | ±11.2% | 0.207 | 0.295 | 0.487 |
| SET | Redis | 124,688 | ±19.1% | 0.207 | 0.303 | 0.447 |
| ZADD | resp-server (virtual threads) | 54,377 | ±23.5% | 0.495 | 0.719 | 0.943 |
| ZADD | resp-server (event loop) | 127,877 | ±7.9% | 0.199 | 0.311 | 0.511 |
| ZADD | Redis | 133,333 | ±1.8% | 0.199 | 0.311 | 0.447 |

## pipelined-P16

| test | subject | ops/sec (median) | spread | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| GET | resp-server (virtual threads) | 737,463 | ±14.0% | 0.583 | 0.895 | 1.591 |
| GET | resp-server (event loop) | 936,330 | ±4.2% | 0.823 | 1.231 | 1.887 |
| GET | Redis | 1,607,717 | ±5.7% | 0.399 | 0.607 | 0.863 |
| SET | resp-server (virtual threads) | 761,035 | ±28.4% | 0.559 | 0.959 | 1.519 |
| SET | resp-server (event loop) | 917,431 | ±8.1% | 0.839 | 1.159 | 2.039 |
| SET | Redis | 1,373,626 | ±8.7% | 0.479 | 0.695 | 1.079 |

## resp-server throughput relative to Redis

| config | test | virtual threads | event loop |
|---|---|---:|---:|
| connections-c500 | GET | 47% | 107% |
| connections-c500 | SET | 47% | 105% |
| mixed-c50 | GET | 41% | 95% |
| mixed-c50 | HSET | 42% | 100% |
| mixed-c50 | INCR | 43% | 98% |
| mixed-c50 | LPUSH | 46% | 107% |
| mixed-c50 | MSET (10 keys) | 37% | 94% |
| mixed-c50 | PING_INLINE | 45% | 93% |
| mixed-c50 | PING_MBULK | 48% | 105% |
| mixed-c50 | RPOP | 41% | 93% |
| mixed-c50 | SADD | 43% | 100% |
| mixed-c50 | SET | 43% | 99% |
| mixed-c50 | ZADD | 41% | 96% |
| pipelined-P16 | GET | 46% | 58% |
| pipelined-P16 | SET | 55% | 67% |
