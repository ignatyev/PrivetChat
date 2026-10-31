package ru.haier.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Whitelisted local voice-command bridge for PrivetChat. */
public final class VoiceCommandReceiver extends BroadcastReceiver {
    public static final String ACTION = "ru.haier.remote.action.VOICE_COMMAND";
    public static final String EXTRA_COMMAND = "command";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int key = keyFor(intent.getStringExtra(EXTRA_COMMAND));
        if (key < 0) return;
        Intent queued = new Intent("ru.haier.remote.widget.KEY");
        queued.setClass(context, RemoteWidget.class);
        queued.putExtra("key_code", key);
        context.sendBroadcast(queued);
    }

    public static int keyFor(String name) {
        if (name == null) return -1;
        switch (name) {
            case "wake": return 224;
            case "sleep": return 26;
            case "volume_up": return 24;
            case "volume_down": return 25;
            case "home": return 3;
            case "back": return 4;
            case "up": return 19;
            case "down": return 20;
            case "left": return 21;
            case "right": return 22;
            case "ok": return 23;
            default: return -1;
        }
    }
}
