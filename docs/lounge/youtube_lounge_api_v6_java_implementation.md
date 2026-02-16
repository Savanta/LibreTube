# YouTube Lounge API --- Full Reference Implementation Guide (v6)

## Java (production-oriented blueprint)

> ⚠️ Nieoficjalna dokumentacja reverse-engineeringowa. Ten dokument
> pokazuje **jak zbudować stabilnego klienta Lounge API w Javie**.

------------------------------------------------------------------------

# Spis treści

1.  Założenia
2.  Architektura (Java)
3.  Model danych
4.  State machine
5.  Transport HTTP (long polling)
6.  Parser bind (length-prefixed)
7.  Event dispatcher
8.  Playback clock
9.  Command pipeline
10. Reconnect manager
11. Przykładowy flow end‑to‑end
12. Skeleton kodu (Java)
13. Checklist production

------------------------------------------------------------------------

# 1. Założenia

Zakładamy:

-   Java 17+
-   HTTP client: `java.net.http.HttpClient`
-   JSON: Jackson (lub Gson)
-   model async: ExecutorService / CompletableFuture

Cele:

-   stabilny bind loop
-   poprawny RID/AID
-   brak driftu playbacku
-   reconnect bez resetów UI

------------------------------------------------------------------------

# 2. Architektura (Java)

    app
     ├── lounge/
     │   ├── LoungeSession
     │   ├── BindLoop
     │   ├── BindParser
     │   ├── EventDispatcher
     │   ├── PlaybackClock
     │   ├── CommandQueue
     │   └── ReconnectManager
     └── ui/

Zasada:

-   UI nie dotyka transportu
-   wszystko idzie przez EventDispatcher

------------------------------------------------------------------------

# 3. Model danych

## SessionState

``` java
public final class SessionState {
    public String sid;
    public long rid;
    public long aid;
    public String gsessionId;
}
```

## PlaybackState

``` java
public enum PlayerState {
    PLAYING, PAUSED, BUFFERING, UNKNOWN
}
```

``` java
public final class PlaybackSnapshot {
    public long positionMs;
    public long timestampMs;
    public PlayerState state;
}
```

------------------------------------------------------------------------

# 4. State machine

    IDLE
      -> PAIRING
      -> CONNECTING
      -> BOUND
      -> RECONNECTING
      -> DISCONNECTED

Reguła:

-   tylko event z TV zmienia PLAYING/PAUSED.

------------------------------------------------------------------------

# 5. Transport HTTP (long polling)

``` java
HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
```

Bind request:

``` java
HttpRequest req = HttpRequest.newBuilder(uri)
        .GET()
        .timeout(Duration.ofSeconds(70))
        .build();
```

Timeout ≠ error. Po timeout wysyłasz kolejny bind.

------------------------------------------------------------------------

# 6. Parser bind (length-prefixed)

Format:

    <LEN>\n
    <PAYLOAD>

## Parser (idea)

``` java
public final class BindParser {

    public List<String> parse(byte[] data) {
        List<String> chunks = new ArrayList<>();
        int i = 0;

        while (i < data.length) {
            int lineStart = i;
            while (i < data.length && data[i] != '\n') i++;

            int len = Integer.parseInt(new String(data, lineStart, i - lineStart));
            i++; // skip '\n'

            String payload = new String(data, i, len);
            chunks.add(payload);

            i += len;
        }
        return chunks;
    }
}
```

W produkcji dodaj bufor (fragmentacja TCP).

------------------------------------------------------------------------

# 7. Event dispatcher

``` java
public interface LoungeListener {
    void onPlayback(PlaybackSnapshot snapshot);
    void onQueueChanged();
}
```

``` java
public class EventDispatcher {
    private final List<LoungeListener> listeners = new CopyOnWriteArrayList<>();

    public void dispatchPlayback(PlaybackSnapshot s) {
        listeners.forEach(l -> l.onPlayback(s));
    }
}
```

------------------------------------------------------------------------

# 8. Playback clock (anti-drift)

``` java
public final class PlaybackClock {

    private long basePos;
    private long baseTs;
    private PlayerState state;

    public synchronized void sync(PlaybackSnapshot snap) {
        basePos = snap.positionMs;
        baseTs = System.currentTimeMillis();
        state = snap.state;
    }

    public synchronized long position() {
        if (state == PlayerState.PLAYING) {
            return basePos + (System.currentTimeMillis() - baseTs);
        }
        return basePos;
    }
}
```

Korekcja:

-   jeśli drift \> 1000 ms → hard sync.

------------------------------------------------------------------------

# 9. Command pipeline

Nie wysyłaj komend bezpośrednio.

``` java
BlockingQueue<Command> queue = new LinkedBlockingQueue<>();
```

Sender thread:

``` java
while (running) {
    Command cmd = queue.take();
    sendCommand(cmd);
}
```

Korzyści:

-   brak race conditions
-   retry po reconnect

------------------------------------------------------------------------

# 10. Reconnect manager

Strategia:

    1s -> 2s -> 4s -> 8s (max)

Pseudo:

``` java
for (int i = 0; i < max; i++) {
    tryReconnect();
    if (ok) break;
    Thread.sleep(backoff);
}
```

Soft reconnect:

-   zachowaj SID
-   nie resetuj UI

Hard reconnect:

-   nowy pairing

------------------------------------------------------------------------

# 11. Flow end‑to‑end

    PAIR
      -> get screenId + token
    CONNECT
      -> bind init (SID)
    LOOP
      -> bind (RID, AID)
      -> parse chunks
      -> dispatch events
      -> update AID
      -> RID++

Równolegle:

-   command sender
-   playback clock

------------------------------------------------------------------------

# 12. Skeleton kodu (Java)

## LoungeSession

``` java
public class LoungeSession {

    private final SessionState state = new SessionState();
    private final BindLoop bindLoop;
    private final PlaybackClock clock;

    public void start() {
        bindLoop.start();
    }

    public void seek(long ms) {
        enqueue(new SeekCommand(ms));
    }
}
```

## BindLoop (core)

``` java
public class BindLoop implements Runnable {

    public void run() {
        while (running) {
            byte[] resp = transport.bind(state.rid, state.aid);
            List<String> chunks = parser.parse(resp);

            for (String c : chunks) {
                Event ev = decoder.decode(c);
                state.aid = Math.max(state.aid, ev.aid());
                dispatcher.dispatch(ev);
            }

            state.rid++;
        }
    }
}
```

------------------------------------------------------------------------

# 13. Production checklist (Java)

-   [ ] Dedicated bind thread
-   [ ] Buffered parser (partial reads)
-   [ ] RID monotonic
-   [ ] AID max tracking
-   [ ] Command queue
-   [ ] Local playback clock
-   [ ] Soft reconnect
-   [ ] Structured logging
-   [ ] Backoff strategy
-   [ ] Event normalization

------------------------------------------------------------------------

# Final insight

W Javie stabilność zależy od trzech rzeczy:

1.  poprawny bind loop (single owner thread)
2.  izolowany SessionState
3.  clock synchronizowany tylko eventami z TV

Jeśli te zasady trzymasz --- klient Lounge działa stabilnie nawet przy
słabym łączu.
