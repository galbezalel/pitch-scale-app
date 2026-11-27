package com.example.pitchscale.shizuku;

interface IShizukuAudioService {
    boolean startLoopbackCapture(int excludeUid, int sampleRate) = 1;
    byte[] readPcmChunk(int maxBytes) = 2;
    void stopLoopbackCapture() = 3;
    void destroy() = 16777114;
}
