package com.saaspaymentsolutions.axion;

public final class ComposerUiState {

    public static final float RUNNING_ALPHA = 0.55f;

    public final boolean sendVisible;
    public final boolean stopVisible;
    public final boolean messageInputEnabled;
    public final boolean attachEnabled;
    public final float attachAlpha;

    public static ComposerUiState idle() {
        return new ComposerUiState(false);
    }

    public static ComposerUiState running() {
        return new ComposerUiState(true);
    }

    public static ComposerUiState forRunState(boolean processing) {
        return processing ? running() : idle();
    }

    private ComposerUiState(boolean processing) {
        this.sendVisible = !processing;
        this.stopVisible = processing;
        this.messageInputEnabled = !processing;
        this.attachEnabled = !processing;
        this.attachAlpha = processing ? RUNNING_ALPHA : 1f;
    }

    public boolean sendAndStopCompatible() {
        return !(sendVisible && stopVisible);
    }
}