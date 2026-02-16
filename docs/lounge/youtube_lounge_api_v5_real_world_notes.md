# YouTube Lounge API --- Real‑World Reverse Engineering Notes (v5)

## Multi‑remote arbitration, edge cases, and production behavior

> ⚠️ Nieoficjalna dokumentacja reverse‑engineeringowa. Ten dokument
> opisuje zachowanie obserwowane w prawdziwych sesjach aplikacji
> YouTube.

------------------------------------------------------------------------

# Spis treści

1.  Cel v5\
2.  Multi‑remote model (kilka pilotów naraz)\
3.  Arbitration (kto kontroluje TV)\
4.  Event ordering i race conditions\
5.  Queue semantics (add / insert / autoplay)\
6.  Ads i ograniczenia sterowania\
7.  Hidden keepalive patterns\
8.  Session invalidation cases\
9.  TV app differences (Leanback variants)\
10. Latency model i UX strategie\
11. Real edge cases z logów\
12. Recovery playbook\
13. Production hardening checklist\
14. Final insights

------------------------------------------------------------------------

# 1. Cel v5

Po v1--v4 masz:

-   protokół
-   wire format
-   architekturę klienta

V5 odpowiada na pytanie:

**dlaczego oficjalna aplikacja działa stabilnie mimo chaosu sieciowego i
wielu remote jednocześnie?**

------------------------------------------------------------------------

# 2. Multi‑remote model

YouTube pozwala na kilka remote jednocześnie:

-   telefon A
-   telefon B
-   przeglądarka
-   tablet

Każdy remote:

-   ma własny bind loop
-   ma własny RID
-   dzieli ten sam stan SCREEN

SCREEN pozostaje single source of truth.

------------------------------------------------------------------------

# 3. Arbitration (kto "rządzi")

Nie ma twardego locka sesji.

Model:

-   ostatnia komenda wygrywa (last writer wins)
-   SCREEN publikuje wynik jako event

Przykład:

1.  Remote A -\> pause
2.  Remote B -\> play
3.  SCREEN -\> state=PLAYING

Oba remote dostają ten sam event.

## Wniosek

Nigdy nie zakładaj, że Twoja komenda została utrzymana.

------------------------------------------------------------------------

# 4. Event ordering i race conditions

Eventy mogą przyjść:

-   z opóźnieniem
-   w batchu
-   po reconnect

Dlatego:

-   porównuj AID
-   ignoruj eventy starsze niż current AID

Rule:

    if event.aid <= last_aid:
        ignore

------------------------------------------------------------------------

# 5. Queue semantics

Najczęstsze operacje:

-   addVideo
-   setPlaylist
-   next
-   previous

Obserwacja:

-   queue bywa przebudowywana przez autoplay
-   TV może zmienić kolejkę bez komendy remote

Dlatego queue zawsze synchronizuj z eventami.

------------------------------------------------------------------------

# 6. Ads (reklamy)

W trakcie reklamy:

-   seek często ignorowany
-   pause może być zablokowany
-   playbackState nadal się aktualizuje

Typowy pattern:

    adState = PLAYING
    commands -> accepted transportowo, ignorowane logicznie

Nie traktuj tego jako błąd.

------------------------------------------------------------------------

# 7. Hidden keepalive patterns

Poza `noop` obserwuje się:

-   krótkie batch bez eventów
-   empty payload

Cel:

-   utrzymanie NAT / TCP
-   test aktywności klienta

Parser powinien:

-   akceptować puste batch
-   nie resetować stanu

------------------------------------------------------------------------

# 8. Session invalidation

Najczęstsze przyczyny:

-   TV zamknęło aplikację
-   zmiana konta Google na TV
-   pairing token expired
-   konflikt backend routing

Objawy:

-   SID accepted -\> później 4xx
-   bind kończy się natychmiast

Recovery:

1.  spróbuj reconnect z tym samym SID (1--2 próby)
2.  jeśli fail -\> full pairing

------------------------------------------------------------------------

# 9. Różnice między TV app

## Android TV

-   najstabilniejszy event stream
-   częste keepalive

## WebOS / Tizen

-   czasem batchowane eventy
-   większe opóźnienia

## Konsolowe appki

-   agresywnie zamykają sesję w tle

Wniosek:

Implementuj tolerant parser i reconnect manager.

------------------------------------------------------------------------

# 10. Latency model (UX)

Oficjalna aplikacja:

-   optimistic UI (natychmiast)
-   reconciliation po evencie

Dobre progi:

-   \<200 ms: bez korekty

-   200--1000 ms: soft adjust

-   1000 ms: hard sync

------------------------------------------------------------------------

# 11. Real edge cases z logów

## Case A --- double seek

Użytkownik szybko przesuwa slider:

    seek 10s
    seek 90s

TV może wysłać:

    confirm 10s
    confirm 90s

Rozwiązanie:

-   identyfikuj latest user intent
-   ignoruj stare confirm jeśli lokalny intent nowszy

## Case B --- stale PLAYING while paused locally

Po reconnect:

-   clock mógł biec
-   event przychodzi z PAUSED

Zawsze trust SCREEN.

------------------------------------------------------------------------

# 12. Recovery playbook

## Soft reconnect

-   zachowaj state
-   restart bind
-   czekaj na snapshot

## Hard reconnect

-   reset SID
-   pairing od nowa
-   restore UI dopiero po first snapshot

------------------------------------------------------------------------

# 13. Production hardening checklist

-   [ ] Multi-remote safe state model
-   [ ] Ignore stale AID
-   [ ] Queue sync from events only
-   [ ] Ad-aware command handling
-   [ ] Empty batch handling
-   [ ] Soft vs hard reconnect
-   [ ] Optimistic UI + reconciliation
-   [ ] Structured telemetry

------------------------------------------------------------------------

# 14. Final insights

Największe sekrety stabilności YouTube:

1.  SCREEN zawsze ma rację.
2.  Eventy mogą przyjść w dowolnym momencie.
3.  UI jest tylko projekcją stanu, nie źródłem prawdy.
4.  Reconnect to normalna część życia sesji.

Jeśli traktujesz Lounge jako:

-   event-sourced system
-   z lokalną projekcją stanu

--- implementacja staje się przewidywalna i stabilna.
