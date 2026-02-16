# YouTube Lounge API --- Raw Wire Format Reference (v3)

## Bind framing, stream parsing, and real-world payload structure

> ⚠️ Nieoficjalna dokumentacja reverse‑engineeringowa. Ten dokument
> opisuje **warstwę transportową** i **format surowych danych**
> używanych przez YouTube Lounge (MDX).

------------------------------------------------------------------------

# Spis treści

1.  Cel dokumentu\
2.  Warstwa transportowa (HTTP long polling)\
3.  Endpoint bind --- parametry\
4.  Framing odpowiedzi (length‑prefixed chunks)\
5.  Parser strumienia --- algorytm krok po kroku\
6.  Format batchy eventów\
7.  Typowe komendy transportowe (`c`, `S`, `noop`)\
8.  RID / AID na poziomie wire\
9.  Przykładowa pełna sesja (raw → parsed)\
10. Obsługa reconnect\
11. Błędy parsowania i edge cases\
12. Pseudokod parsera production‑ready

------------------------------------------------------------------------

# 1. Cel dokumentu

Ten dokument odpowiada na pytania:

-   jak wygląda **surowa odpowiedź** bind
-   jak parser wie gdzie kończy się event
-   jak rozpoznać komendy kontrolne
-   jak poprawnie inkrementować RID i AID

To poziom potrzebny do napisania własnego klienta od zera.

------------------------------------------------------------------------

# 2. Warstwa transportowa

Transport:

-   HTTP(S)
-   `long polling`
-   brak websocketów

Model:

    client ---- bind request ----> server
    client <--- (wait...events) --- server
    client ---- next bind --------> server

Serwer odpowiada dopiero gdy:

-   pojawi się event
-   timeout
-   keepalive
-   reconnect instruction

------------------------------------------------------------------------

# 3. Endpoint bind --- parametry (wire level)

Typowy request (uproszczony):

    GET /bc/bind
        ?VER=8
        &RID=12345
        &SID=<sid>
        &AID=104
        &CI=0
        &TYPE=xmlhttp
        &zx=<random>
        &t=1

## Znaczenie

  parametr   znaczenie
  ---------- -------------------------
  VER        wersja protokołu
  RID        request id (rośnie)
  SID        session id
  AID        last acknowledged event
  CI         client instance
  TYPE       transport
  zx         cache buster
  t          tryb long-poll

------------------------------------------------------------------------

# 4. Framing odpowiedzi --- length‑prefixed chunks

To NAJWAŻNIEJSZA część.

Odpowiedź nie jest czystym JSON.

Format logiczny:

    <LEN>\n
    <PAYLOAD>
    <LEN>\n
    <PAYLOAD>
    ...

Gdzie:

-   LEN = długość payloadu w bajtach (ASCII)
-   payload = JSON-like array

Przykład (schematyczny):

    47
    [[105,["noop"]]]
    132
    [[106,["event",{"type":"playbackState","state":"PLAYING"}]]]

Parser musi:

1.  czytać długość do newline
2.  odczytać dokładnie N bajtów
3.  przekazać chunk do parsera JSON

------------------------------------------------------------------------

# 5. Parser strumienia --- algorytm

## Krok 1

Czytaj znaki aż do `\\n` → to LEN.

## Krok 2

Konwertuj LEN → int.

## Krok 3

Odczytaj dokładnie LEN bajtów.

## Krok 4

Zparsuj JSON.

## Krok 5

Powtarzaj aż EOF.

------------------------------------------------------------------------

# 6. Format batchy eventów

Najczęstszy kształt (logiczny):

``` json
[
  [
    106,
    [
      "event",
      {
        "type": "playbackState",
        "state": "PLAYING",
        "positionMs": 120034
      }
    ]
  ]
]
```

Interpretacja:

-   106 → nowy AID
-   "event" → typ transportowy
-   object → payload

Po przetworzeniu:

    AID = 106

------------------------------------------------------------------------

# 7. Typowe komendy transportowe

## `c` --- connect / control

Pojawia się przy inicjalizacji sesji.

Przykład logiczny:

``` json
[0, ["c", "SID_VALUE"]]
```

## `S` --- session meta

Zawiera routing / session metadata.

``` json
[1, ["S", "gsessionid_value"]]
```

## `noop` --- keepalive

Brak zmian stanu.

``` json
[105, ["noop"]]
```

Nie zmieniaj UI.

------------------------------------------------------------------------

# 8. RID / AID na poziomie wire

## RID

-   zwiększaj zawsze o 1
-   nawet gdy poprzedni bind był noop

## AID

-   aktualizuj do NAJWIĘKSZEGO otrzymanego id
-   nie resetuj po reconnect jeśli SID jest ten sam

------------------------------------------------------------------------

# 9. Przykładowa pełna sesja

## 1. Initial bind

Response chunks:

    23
    [[0,["c","SID123"]]]
    31
    [[1,["S","GSESSION456"]]]

Parser:

    SID = SID123
    gsessionid = GSESSION456

## 2. Playback event

    118
    [[10,["event",{"type":"nowPlaying","videoId":"abc","positionMs":0}]]]

AID = 10

## 3. Seek confirmation

    140
    [[11,["event",{"type":"playbackState","state":"PLAYING","positionMs":90000}]]]

AID = 11

------------------------------------------------------------------------

# 10. Reconnect

Reconnect występuje gdy:

-   timeout
-   HTTP 4xx/5xx
-   zerwane TCP

Strategia:

1.  zwiększ RID
2.  zachowaj SID
3.  wyślij bind ponownie

Jeśli serwer odrzuci SID:

-   rozpocznij nową sesję od pairing

------------------------------------------------------------------------

# 11. Edge Cases

## Chunk split (TCP fragmentation)

Nigdy nie zakładaj że:

-   LEN i payload przyjdą w jednym read()

Parser musi mieć bufor.

## Multiple chunks w jednym read()

Częste --- parsuj pętlą.

## Invalid JSON

Zdarza się przy reconnect --- ignoruj i ponów bind.

------------------------------------------------------------------------

# 12. Pseudokod parsera (production-ready)

``` python
buffer = b""

def feed(data):
    buffer += data

    while True:
        line = read_line_if_possible(buffer)
        if not line:
            break

        length = int(line)
        if len(buffer) < length:
            break

        payload = read_exact(buffer, length)
        obj = json.loads(payload)

        handle_chunk(obj)
```

## Handle chunk

``` python
def handle_chunk(chunk):
    aid = chunk[0]
    cmd = chunk[1][0]

    if cmd == "noop":
        return

    if cmd == "event":
        process_event(chunk[1][1])
        update_aid(aid)
```

------------------------------------------------------------------------

# 13. Playback synchronization (wire perspective)

Seek flow:

    REMOTE -> send seek command
    TV -> applies seek
    SERVER -> emits playbackState event
    REMOTE -> updates local clock

Nigdy nie aktualizuj pozycji finalnie bez eventu.

------------------------------------------------------------------------

# 14. Minimal checklist (wire-level)

-   [ ] length-prefixed parser
-   [ ] RID increment always
-   [ ] AID tracking
-   [ ] noop handling
-   [ ] reconnect loop
-   [ ] buffered stream parser
-   [ ] local playback clock

------------------------------------------------------------------------

# 15. Final insight

Większość błędów Lounge wynika NIE z komend, ale z niepoprawnego
parsowania wire-formatu bind.

Jeśli:

-   parser jest poprawny
-   RID/AID są poprawne

→ synchronizacja TV → app działa stabilnie.
