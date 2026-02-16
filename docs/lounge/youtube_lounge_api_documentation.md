# YouTube Lounge API --- Nieoficjalna Dokumentacja Techniczna

> ⚠️ Dokument powstał na podstawie reverse-engineeringu oraz analizy
> ruchu sieciowego. Nie jest to oficjalna dokumentacja Google.

------------------------------------------------------------------------

# Spis treści

1.  Wprowadzenie\
2.  Architektura\
3.  Terminologia\
4.  Pairing (kod TV)\
5.  Tokeny\
6.  Bind / Connect\
7.  Long Polling\
8.  Lifecycle sesji\
9.  RID / AID / SID\
10. Event Stream\
11. Command Channel\
12. Synchronizacja postępu\
13. Zegar lokalny\
14. Minimalny klient\
15. Architektura produkcyjna\
16. Typowe błędy\
17. Advanced insights

------------------------------------------------------------------------

# 1. Wprowadzenie

YouTube Lounge API to wewnętrzny protokół używany do sterowania
aplikacją YouTube na TV z poziomu telefonu lub przeglądarki.

Nie jest to: - Chromecast API - YouTube Data API - IFrame API

------------------------------------------------------------------------

# 2. Architektura

REMOTE → Lounge Backend → SCREEN

REMOTE wysyła komendy.\
SCREEN jest źródłem prawdy o stanie.

------------------------------------------------------------------------

# 3. Terminologia

**Screen** --- urządzenie docelowe (TV)\
**Remote** --- urządzenie sterujące\
**Session** --- aktywne połączenie\
**Events** --- zmiany stanu

------------------------------------------------------------------------

# 4. Pairing (kod TV)

Endpoint logiczny:

POST /api/lounge/pairing/get_screen

Body:

pairing_code=XXXX-XXXX

Response:

-   screenId
-   loungeToken
-   name

------------------------------------------------------------------------

# 5. Tokeny

-   loungeToken może wygasać
-   należy go odświeżać cyklicznie

------------------------------------------------------------------------

# 6. Bind / Connect

Endpoint:

/bc/bind

Parametry typowe:

-   app=web
-   mdx-version=3
-   id=`<screenId>`{=html}
-   loungeIdToken=`<token>`{=html}

------------------------------------------------------------------------

# 7. Long Polling

To NIE jest websocket.

Schemat:

bind → WAIT → events → bind → WAIT → events

Brak ciągłej pętli bind = brak synchronizacji.

------------------------------------------------------------------------

# 8. Lifecycle sesji

Pierwszy bind zwraca:

-   SID (session id)
-   RID (request id)
-   AID (last event id)
-   gsessionid

------------------------------------------------------------------------

# 9. RID / AID / SID

SID --- stałe id sesji\
RID --- rośnie przy każdym bind\
AID --- id ostatniego eventu

RID = RID + 1 przy każdym zapytaniu.

------------------------------------------------------------------------

# 10. Event Stream

Przykładowe eventy:

-   nowPlaying
-   playbackState
-   volumeChanged
-   autoplay
-   disconnected

------------------------------------------------------------------------

# 11. Command Channel

Komendy:

-   play
-   pause
-   seekTo
-   setVolume
-   next
-   previous

------------------------------------------------------------------------

# 12. Synchronizacja postępu

TV NIE wysyła pozycji co sekundę.

Dostajesz snapshot:

-   positionMs
-   state
-   timestamp

------------------------------------------------------------------------

# 13. Zegar lokalny

displayPosition = remotePosition + (now - lastUpdateTime)

UI liczy czas lokalnie.

------------------------------------------------------------------------

# 14. Minimalny klient

PAIR\
↓\
CONNECT\
↓\
LOOP bind + parse events

Równolegle: wysyłanie komend.

------------------------------------------------------------------------

# 15. Architektura produkcyjna

Thread A --- komendy\
Thread B --- bind loop\
Thread C --- lokalny zegar

------------------------------------------------------------------------

# 16. Typowe błędy

-   Brak pętli bind
-   Brak RID increment
-   Brak AID update
-   Brak lokalnego zegara
-   Zakładanie że seek == wykonany

------------------------------------------------------------------------

# 17. Advanced Insights

REMOTE = authoritative for commands\
SCREEN = authoritative for state

Zawsze czekaj na event potwierdzający zmianę stanu.
