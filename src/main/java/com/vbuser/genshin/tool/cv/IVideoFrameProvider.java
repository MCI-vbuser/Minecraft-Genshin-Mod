package com.vbuser.genshin.tool.cv;

public interface IVideoFrameProvider {
    boolean isPlaying();
    int getWidth();
    int getHeight();
    int[] getFramePixels();
    void start();
    void stop();
}
