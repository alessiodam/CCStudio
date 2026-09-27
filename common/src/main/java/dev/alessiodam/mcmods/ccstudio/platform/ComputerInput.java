package dev.alessiodam.mcmods.ccstudio.platform;

public interface ComputerInput {
    void keyDown(int key);

    void keyUp(int key);

    void codepointTyped(int codepoint);

    void paste(String text);

    void mouseClick(int button, int x, int y);

    void mouseUp(int button, int x, int y);

    void mouseDrag(int button, int x, int y);

    void mouseScroll(int direction, int x, int y);

    void releaseAll();
}
