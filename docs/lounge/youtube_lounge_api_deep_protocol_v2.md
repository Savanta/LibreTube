# YouTube Lounge API --- Deep Protocol Reference (v2)

## Internal Session & Event Specification (Reverse‑Engineered)

> ⚠️ Nieoficjalna dokumentacja techniczna. Opracowana na podstawie
> analizy ruchu sieciowego aplikacji YouTube TV oraz implementacji
> community.

------------------------------------------------------------------------

# 1. Cel dokumentu

Ten dokument opisuje:

-   pełny lifecycle sesji Lounge
-   strukturę bind
-   znaczenie SID / RID / AID / gsessionid
-   strukturę event batchy
-   model synchronizacji stanu
-   mechanizm reconnect
-   detekcję desynchronizacji

------------------------------------------------------------------------

# 2. High-Level Protocol Layers

Lounge składa się z dwóch warstw:

1.  Session Transport Layer (bind long polling)
2.  Command/Event Layer (payload JSON)

Transport jest realizowany przez HTTP long polling.

------------------------------------------------------------------------

# 3. Pełny Lifecycle Sesji

## 3.1 Pairing

Remote → /api/lounge/pairing/get_screen

Response:

-   screenId
-   loungeToken
-   expiration

------------------------------------------------------------------------

## 3.2 Initial Bind

Pierwsze wywołanie:

/bc/bind?app=web&mdx-version=3&device=REMOTE_CONTROL

Serwer zwraca:

-   SID
-   gsessionid
-   initial RID
-   initial AID

SID identyfikuje sesję. gsessionid wiąże ją z backendem Google.

------------------------------------------------------------------------

# 4. Parametry Sesji

## SID

Stałe ID sesji. Wysyłane przy każdym kolejnym bind.

## RID

Request ID. Zwiększane o 1 przy każdym bind.

RID = previousRID + 1

## AID

Last Acknowledged Event ID. Określa ostatni przetworzony event.

## gsessionid

Backend routing identifier.

------------------------------------------------------------------------

# 5. Struktura Bind Request

Kolejne bindy zawierają:

-   SID
-   RID
-   AID
-   CI (client instance)
-   TYPE=xmlhttp

Serwer: - trzyma połączenie otwarte - zwraca dane dopiero przy zdarzeniu

------------------------------------------------------------------------

# 6. Struktura Odpowiedzi Bind

Odpowiedź zawiera:

length-prefixed chunks

Format logiczny:

`<chunk_length>`{=html} `<json_payload>`{=html}

Payload zawiera tablicę eventów.

------------------------------------------------------------------------

# 7. Event Batch Structure

Przykład logiczny:

{ "events": \[ { "type": "nowPlaying", "videoId": "...", "positionMs":
12345, "state": "PLAYING" }, { "type": "volumeChanged", "volume": 45 }
\], "AID": 105 }

Po przetworzeniu: AID = 105

------------------------------------------------------------------------

# 8. Najczęstsze Event Types

-   nowPlaying
-   playbackState
-   seekComplete
-   volumeChanged
-   autoplay
-   adState
-   disconnected

------------------------------------------------------------------------

# 9. Command Payload Structure

Remote wysyła komendy osobnym requestem.

Przykład:

{ "command": "seekTo", "positionMs": 90000 }

Serwer nie gwarantuje natychmiastowego efektu. Należy czekać na event
potwierdzający.

------------------------------------------------------------------------

# 10. Model Synchronizacji

SCREEN = authoritative for state\
REMOTE = authoritative for commands

Remote: 1. wysyła komendę 2. czeka na event potwierdzający

------------------------------------------------------------------------

# 11. Playback Synchronization Model

TV wysyła snapshot:

-   positionMs
-   state
-   timestamp

Remote stosuje interpolację:

displayPosition = remotePosition + (now - lastUpdateTime)

Jeśli state == PAUSED: nie inkrementuj zegara.

------------------------------------------------------------------------

# 12. Desynchronization Detection

YouTube wykrywa desync gdy:

-   RID nie rośnie poprawnie
-   AID nie jest aktualizowany
-   bind nie jest wykonywany cyklicznie

W takim przypadku: serwer może zamknąć sesję.

------------------------------------------------------------------------

# 13. Reconnect Mechanizm

Jeśli bind zwróci błąd:

1.  zachowaj SID
2.  spróbuj ponownie z wyższym RID
3.  jeśli nie działa --- rozpocznij nową sesję

------------------------------------------------------------------------

# 14. Optimistic UI Strategy

Oficjalna aplikacja:

1.  natychmiast aktualizuje UI
2.  czeka na event
3.  reconcile state

To daje wrażenie braku laga.

------------------------------------------------------------------------

# 15. Minimalna Implementacja PRO

Wymagane komponenty:

-   Persistent bind loop
-   RID increment
-   AID tracking
-   Local playback clock
-   Event confirmation logic
-   Reconnect handler

------------------------------------------------------------------------

# 16. Najczęstsze Błędy Implementacyjne

-   Polling zamiast long polling
-   Reset RID
-   Ignorowanie AID
-   Brak zegara lokalnego
-   Brak obsługi reconnect

------------------------------------------------------------------------

# 17. Diagram Architektury Produkcyjnej

Thread A → Command Sender\
Thread B → Bind Loop\
Thread C → Playback Clock\
Thread D → Reconnect Supervisor

------------------------------------------------------------------------

# 18. Final Insight

Lounge API jest:

-   asymetryczne
-   event-driven
-   oparte na long polling
-   silnie zależne od poprawnego zarządzania RID/AID

Bez tych elementów synchronizacja TV → app nie będzie działać poprawnie.
