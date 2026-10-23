package com.mattia.appvolume;

import com.mattia.appvolume.PlayerInfo;

// Interfaccia dello UserService Shizuku che gira con identità shell (o root).
interface IVolumeService {
    // Codice di transazione riservato da Shizuku per distruggere il servizio.
    void destroy() = 16777114;

    // Player attualmente noti ad AudioService (tutti gli stati, non solo "started").
    List<PlayerInfo> getPlayers() = 1;

    // Livello 0-100 per UID; il mute resta separato e non perde il livello.
    void setLevel(int uid, int level) = 2;
    void setMute(int uid, boolean muted) = 3;

    // Ultime righe del log eventi (le più recenti in fondo).
    List<String> getLog() = 4;
    void clearLog() = 5;

    // UID con cui gira il servizio: 2000 = shell, 0 = root.
    int getServiceUid() = 6;
}
