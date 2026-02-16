# YouTube Lounge API --- Implementation Cookbook (v4)

## Production-ready architecture, state machine, async model

> ⚠️ Nieoficjalna dokumentacja reverse‑engineeringowa. Ten dokument
> skupia się na **praktycznej implementacji stabilnego klienta** Lounge
> API.

------------------------------------------------------------------------

# Spis treści

1.  Cel v4\
2.  Architektura referencyjna\
3.  State Machine (pełny lifecycle)\
4.  Async model (Python / JS / Rust)\
5.  Event Dispatcher\
6.  Playback Clock (bez driftu)\
7.  Command Pipeline\
8.  Bind Loop (production pattern)\
9.  Reconnect bez glitchy\
10. Error handling\
11. Telemetry / debugging\
12. Minimalny blueprint produkcyjny\
13. Checklist release-ready

------------------------------------------------------------------------

# 1. Cel v4

Po v1--v3 wiesz:

-   jak działa pairing
-   jak wygląda wire format
-   jak parsować bind

Teraz celem jest:

✔ stabilny klient\
✔ zero desynchronizacji\
✔ brak skoków UI\
✔ odporność na reconnect

------------------------------------------------------------------------

# 2. Architektura referencyjna

Rekomendowany podział:

    +-----------------------+
    |   App / UI Layer      |
    +-----------+-----------+
                |
                v
    +-----------------------+
    |   Event Dispatcher    |
    +----+-------------+----+
         |             |
         v             v
    Bind Loop      Command Queue
         |             |
         v             v
    Transport      Command Sender
         |
         v
    Playback Clock

## Zasady

-   UI NIE rozmawia bezpośrednio z transportem.
-   Wszystko idzie przez dispatcher.
-   Stan jest single-source-of-truth.

------------------------------------------------------------------------

# 3. State Machine

## Stany

    IDLE
    PAIRING
    CONNECTING
    BOUND
    PLAYING
    PAUSED
    RECONNECTING
    DISCONNECTED

## Przejścia

    IDLE -> PAIRING -> CONNECTING -> BOUND
    BOUND -> PLAYING / PAUSED
    BOUND -> RECONNECTING -> BOUND
    RECONNECTING -> DISCONNECTED (fail)

## Reguła krytyczna

Nigdy nie zmieniaj stanu PLAYING/PAUSED bez eventu z TV.

------------------------------------------------------------------------

# 4. Async model

## Python (asyncio)

-   Task A: bind loop
-   Task B: command sender
-   Task C: playback clock

## JavaScript

-   AbortController dla bind
-   EventEmitter dla dispatchera
-   setInterval tylko dla local clock

## Rust (tokio)

-   channel mpsc dla events
-   select! dla reconnect i bind

------------------------------------------------------------------------

# 5. Event Dispatcher

Dispatcher mapuje eventy transportowe → domenowe.

Przykład:

    raw event -> normalize -> domain event -> subscribers

### Normalizacja

Usuń:

-   różnice namingów
-   brakujące pola
-   null payloady

------------------------------------------------------------------------

# 6. Playback Clock (anti-drift)

Największy błąd implementacji: dryf pozycji.

## Model

    base_position_ms
    base_timestamp_ms
    play_state

## Tick

    if state == PLAYING:
        position = base + (now - base_ts)
    else:
        position = base

## Korekcja

Przy nowym evencie:

    delta = abs(local - remote)

    if delta > 1000ms:
        hard sync
    else:
        soft correction (lerp)

------------------------------------------------------------------------

# 7. Command Pipeline

Komendy powinny być kolejką.

    UI -> enqueue -> sender -> wire

## Dlaczego?

-   seek spam
-   race conditions
-   reconnect retry

## Co trzymać w queue

-   command id
-   timestamp
-   optimistic state

------------------------------------------------------------------------

# 8. Bind Loop (production pattern)

Pseudo flow:

    while connected:
        send bind(RID, AID)
        wait response
        parse chunks
        dispatch events
        RID += 1

## Timeout strategy

-   30--60s normalny timeout
-   po timeout -\> natychmiast nowy bind

Timeout ≠ error.

------------------------------------------------------------------------

# 9. Reconnect bez glitchy

Najczęstszy problem: UI resetuje playback.

## Poprawna strategia

1.  Zamroź clock
2.  Zachowaj last known state
3.  Restart bind loop
4.  Czekaj na pierwszy event
5.  Resume clock

## Nie rób

❌ reset position = 0\
❌ clear queue natychmiast

------------------------------------------------------------------------

# 10. Error handling

## Retry policy

-   exponential backoff:
    -   1s
    -   2s
    -   4s
    -   8s (max)

## Fatal errors

-   invalid SID
-   pairing expired

→ pełny reconnect od pairing.

------------------------------------------------------------------------

# 11. Telemetry / Debugging

Loguj:

-   RID
-   AID
-   chunk length
-   event type
-   clock drift

To 90% debugowania Lounge.

Przykład logu:

    RID=120 AID=455 event=playbackState drift=120ms

------------------------------------------------------------------------

# 12. Minimalny blueprint produkcyjny

## Core modules

-   transport.py
-   bind_parser.py
-   session_state.py
-   playback_clock.py
-   dispatcher.py
-   command_queue.py
-   reconnect_manager.py

------------------------------------------------------------------------

# 13. Pseudokod całości

``` python
async def bind_loop():
    while session.active:
        resp = await transport.bind(rid, aid)
        for chunk in parse(resp):
            event = normalize(chunk)
            dispatcher.emit(event)
            aid = max(aid, event.aid)
        rid += 1
```

``` python
def on_playback_event(ev):
    clock.sync(ev.position, ev.timestamp, ev.state)
```

------------------------------------------------------------------------

# 14. Strategia optimistic UI

Po wysłaniu komendy:

    seek -> update slider immediately
    ``>

    Ale:

if confirm event differs: reconcile UI \`\`\`

To dokładnie robi oficjalna aplikacja.

------------------------------------------------------------------------

# 15. Checklist release-ready

-   [ ] Long polling bind loop
-   [ ] Buffering parser (fragmented TCP)
-   [ ] RID monotonic
-   [ ] AID tracking
-   [ ] Local playback clock
-   [ ] Reconnect manager
-   [ ] Command queue
-   [ ] Event dispatcher
-   [ ] Drift correction
-   [ ] Structured logs

------------------------------------------------------------------------

# 16. Final insight

Stabilny klient Lounge to nie parser.

To:

-   state machine
-   clock model
-   reconnect strategy

Jeśli te trzy elementy są poprawne, API działa bardzo stabilnie mimo
braku oficjalnej dokumentacji.
