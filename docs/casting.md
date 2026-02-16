# Casting Architecture (DIAL + YouTube Lounge)

## Cel
Replikuje funkcję "Play on TV": wykrycie urządzenia, uruchomienie YouTube na TV, przesłanie playlisty/wideo i sterowanie odtwarzaniem.

## Warstwy protokołów
- SSDP/UPnP: broadcast M-SEARCH w LAN → odpowiedź z Location do DIAL.
- DIAL: GET /apps/YouTube zwraca stan + screenId; POST /apps/YouTube uruchamia app; kolejne GET potwierdza. Chromecast nie używa DIAL (mDNS) – wymaga parowania kodem lub mDNS.
- YouTube Lounge (bind): POST https://www.youtube.com/api/lounge/bc/bind utrzymuje sesję i wykonuje akcje (setPlaylist, play, pause, seekTo, getNowPlaying, addVideo...).

## Standardowy przepływ
1. SSDP search (lub cache) → wybór urządzenia DIAL.
2. GET /apps/YouTube → stan + screenId.
3. Jeśli app zatrzymana: POST /apps/YouTube → start; następnie GET → potwierdzenie.
4. screenId → loungeToken (pair/bind).
5. Pierwsze żądanie: setPlaylist + play w jednym bind (token = loungeToken).
6. Sterowanie ciągłe: bind stream/poll; akcje pause/seek/add.

## Sesja Lounge
- Pola: SID, GSESSIONID, AID (inkrement id wiadomości), ofs, cpn, RID, count.
- Reset sesji na 400/404/410 lub "Unknown SID": porzuć SID/GSID, wyzeruj AID/ofs, handshake ponownie.
- Akcje: tablica [id, ["action", payload]]; count i aid muszą zgadzać się z liczbą akcji.

## Synchronizacja postępu
- Idealnie: eventy nowPlaying/onStateChange z currentTime, duration, state/playerState w streamie bind.
- W praktyce niektóre odbiorniki nie emitują nowPlaying → fallback pollNowPlaying() (HTTP bind) co kilka sekund lub przy braku update >8 s.
- Lokalne wyliczanie pozycji: baza currentTime + upływ czasu gdy isPlaying = true; aktualizować przy każdej odpowiedzi zawierającej currentTime.
- Heartbeat/"stale" scenariusz: gdy długo brak update, użyj pollNowPlaying zamiast pustego ping.

## Kolejka
- setPlaylist + play razem, by nie ignorować stanu.
- Przy dodawaniu wielu elementów: throttling/delay, by nie "mieszać" kolejki.
- Playlisty URL: rozwijaj do listy ID (np. yt-dlp) i wysyłaj pojedyncze ID.

## Parowanie i cache
- Cache urządzeń (id/host/nazwa) przyspiesza cast; last-used ułatwia wybór.
- Parowanie kodem (Link with TV code) dla Chromecast/bez DIAL; ograniczenia: brak WoL, brak auto re-pair gdy screenId się zmieni.

## Obsługa błędów
- DIAL: powtórz POST /apps/YouTube gdy stan "stopped"; zwiększ timeout discovery gdy SSDP milczy.
- Lounge: na 400/404/410/Unknown SID reset sesji i handshake; przy timeoutach można ponowić próbę z zachowaniem sesji.
- Loguj payloady, gdy parseNowPlaying zwraca null, aby dopasować format odbiornika.

## Źródła i referencje
- ytcast (README): pełny flow SSDP → DIAL → screenId → loungeToken → bind (setPlaylist+play).
- Projekty z THANKS: casttube, plaincast, youtube-remote, youtube-cast-automation-api; artykuły 0x41.cf, xdavidhu (analiza tokenów/sesji).
- DIAL spec 2.2.1 (Netflix) dla szczegółów discovery/launch.
