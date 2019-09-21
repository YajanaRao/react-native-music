package com.yajananrao.trackplayer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

import android.net.Uri;
import android.net.wifi.WifiManager;

import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Bundle;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import android.util.Log;

public class MediaPlayerService extends MediaBrowserServiceCompat implements AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "MediaPlayerService";
    private static final int NOTIFICATION_ID = 121;
    public static final String CHANNEL_ID = "com_yajananrao_trackplayer";
    private static final String CHANNEL_NAME = "Track Player";
    private static final String ACTION_PROGRESS_UPDATE = "ACTION_PROGRESS_UPDATE";

    private NotificationManager mNotificationManager;
    private NotificationManagerCompat mNotificationManagerCompat;
    private MediaPlayer mMediaPlayer;
    private MediaSessionCompat mMediaSessionCompat;
    private AudioFocusRequest focus;

    final Object focusLock = new Object();
    boolean resumeOnFocusGain = false;

    public Handler handler;

    public void post(Runnable r) {
        handler = new Handler();
        handler.post(r);
    }

    private void playbackNow(){
        if (!successfullyRetrievedAudioFocus()) {
            return;
        }

        try {
            mMediaSessionCompat.setActive(true);
            setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
            initNoisyReceiver();
            try {
                mMediaPlayer.start();
            } catch (Exception e) {
                // TODO: handle exception
                Log.e(TAG, "onPlay: " + e.toString());
                mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        Log.i(TAG, "onPlay: playing after preparation");
                        mp.start();
                    }
                });
            }

            showPlayingNotification();
        } catch (Exception e) {
            // TODO: handle exception
            Log.e(TAG, "onPlay: Parent " + e.toString());
        }
    }

    private void pausePlayback(){
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
            unregisterReceiver(mNoisyReceiver);
            showPausedNotification();
        }
    }

    private void stopPlayback(){
        try{
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            // Abandon audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.abandonAudioFocusRequest(focus);
            }
            setMediaPlaybackState(PlaybackStateCompat.STATE_STOPPED);
            try{
                if(mNoisyReceiver != null){
                    unregisterReceiver(mNoisyReceiver);
                }
            }catch (IllegalArgumentException e){
                Log.e(TAG, "noisy receiver "+e.toString());
            }

            // walk around for notification clear issue
            if(mMediaSessionCompat.isActive()){
                Log.i(TAG, "onStartCommand: is active");
                String channel = null;

                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    channel = NotificationChannel.DEFAULT_CHANNEL_ID;
                }
                startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID).build());
                stopSelf();
            }
            clearNotification();

            // Stop the service
            stopSelf();
            // Set the session inactive (and update metadata and state)
            mMediaSessionCompat.setActive(false);
            // stop the player (custom call)
            mMediaPlayer.stop();
            // Take the service out of the foreground
            stopForeground(true);
        }catch (Exception e){
            Log.e(TAG, "onStop: "+e.toString());
        }
    }

    private BroadcastReceiver mNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
            }
        }
    };

    private MediaSessionCompat.Callback mMediaSessionCallback = new MediaSessionCompat.Callback() {

        @Override
        public void onPlay() {
            super.onPlay();
            playbackNow();
        }

        @Override
        public void onStop() {
            super.onStop();
            stopPlayback();
        }

        @Override
        public void onPause() {
            super.onPause();
            pausePlayback();
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            super.onPlayFromSearch(query, extras);
        }

        @Override
        public void onPlayFromUri(Uri uri, Bundle extras) {
            super.onPlayFromUri(uri, extras);
            Log.i(TAG, "onPlayFromUri: song received"+ uri.toString());
            try {
                try {
                    if(mMediaPlayer != null){
                        if(mMediaPlayer.isPlaying()){
                            mMediaPlayer.stop();
                        }
                        mMediaPlayer.release();
                    }
                    initMediaPlayer();
                    mMediaPlayer.setDataSource(uri.toString());
//                    mMediaPlayer.prepare();
                    mMediaPlayer.prepareAsync();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "onPlayFromUri: set data source failed " + e.toString());
                }
                initMediaSessionMetadata(uri.toString());
            } catch (IOException e) {
                Log.e(TAG, "onPlayFromUri: " + e.toString());
                return;
            }
        }

        @Override
        public void onSkipToNext() {
            super.onSkipToNext();
            pausePlayback();
            setMediaPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT);
        }

        @Override
        public void onSkipToPrevious() {
            super.onSkipToPrevious();
            pausePlayback();
            setMediaPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS);
        }

        @Override
        public void onSeekTo(long pos) {
            super.onSeekTo(pos);
            Log.i(TAG, "onSeekTo "+pos);
            mMediaPlayer.seekTo((int)pos);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            Log.d(TAG, "executing custom action");
            if(action.equals(ACTION_PROGRESS_UPDATE)){
                if (mMediaPlayer.isPlaying()) {
                    setMediaPlaybackState(PlaybackStateCompat.STATE_NONE);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        try {
            super.onCreate();
            initMediaPlayer();
            initMediaSession();
            initNotification();
        } catch (Exception e) {
            // TODO: handle exception
            Log.e(TAG, "onCreate: " + e.toString());
        }
    }

    private void initNotification() {
        try {
            mNotificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW);
                notificationChannel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                notificationChannel.setShowBadge(true);
                notificationChannel.setSound(null, null);
                notificationChannel.enableVibration(false);
                mNotificationManager.createNotificationChannel(notificationChannel);
            }
        } catch (Exception e) {
            // TODO: handle exception
            Log.e(TAG, "initNotification" + e.toString());
        }
    }

    private void initNoisyReceiver() {
        // Handles headphones coming unplugged. cannot be done through a manifest
        // receiver
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(mNoisyReceiver, filter);
    }

    private void clearNotification() {
        try {
            // NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
            mNotificationManager.cancel(NOTIFICATION_ID);
            mNotificationManager.cancelAll();
            Log.i(TAG, "clearNotification");
        } catch (Exception e) {
            // TODO: handle exception
            Log.e(TAG, "clearNotification" + e.toString());
        }

    }

    private void initMediaPlayer() {
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setVolume(1.0f, 1.0f);
        mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                clearNotification();
                setMediaPlaybackState(PlaybackStateCompat.STATE_NONE);
                setMediaPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT);
            }
        });
       
    }

    private void showPlayingNotification() {
        try {
            NotificationCompat.Builder builder = MediaStyleHelper.from(this, mMediaSessionCompat);
            if (builder == null) {
                return;
            }
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_previous, "Previous", MediaButtonReceiver
                    .buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_next, "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));
            builder.setChannelId(CHANNEL_ID);
            mNotificationManagerCompat = NotificationManagerCompat.from(this);
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Exception exp) {
        }
    }

    private void showPausedNotification() {
        try {
            NotificationCompat.Builder builder = MediaStyleHelper.from(this, mMediaSessionCompat);
            if (builder == null) {
                return;
            }
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_previous, "Previous", MediaButtonReceiver
                    .buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
            builder.addAction(new NotificationCompat.Action(R.drawable.ic_skip_next, "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));
            builder.setChannelId(CHANNEL_ID);
            mNotificationManagerCompat = NotificationManagerCompat.from(this);
            mNotificationManagerCompat.notify(NOTIFICATION_ID, builder.build());
//            startForeground(NOTIFICATION_ID, builder.build());
            stopForeground(false);
        } catch (Exception exp) {
        }
    }

    private void initMediaSession() {
        ComponentName mediaButtonReceiver = new ComponentName(getApplicationContext(), MediaButtonReceiver.class);
        mMediaSessionCompat = new MediaSessionCompat(getApplicationContext(), "Tag", mediaButtonReceiver, null);

        mMediaSessionCompat.setCallback(mMediaSessionCallback);
        mMediaSessionCompat.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MediaButtonReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
        mMediaSessionCompat.setMediaButtonReceiver(pendingIntent);

        setSessionToken(mMediaSessionCompat.getSessionToken());
    }

    private void setMediaPlaybackState(int state) {
        int position = 0;
        PlaybackStateCompat.Builder playbackstateBuilder = new PlaybackStateCompat.Builder();
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        } else if(state == PlaybackStateCompat.STATE_PAUSED){
            playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        } else {
            playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        }
        if(mMediaPlayer != null){
            position = mMediaPlayer.getCurrentPosition();
            Log.i(TAG, "setMediaPlaybackState: position "+ position);
            
        }
//        Bundle customActionExtras = new Bundle();
//        playbackstateBuilder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
//                ACTION_PROGRESS_UPDATE, "", null)
//                .build());

        playbackstateBuilder.setState(state, position, 1.0f);
        mMediaSessionCompat.setPlaybackState(playbackstateBuilder.build());
    }

    private void initMediaSessionMetadata(String url) {
       try{
            String packageName = this.getPackageName();
            Intent appIntent = this.getPackageManager().getLaunchIntentForPackage(packageName);
            appIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            PendingIntent appPendingIntent = PendingIntent.getActivity(this, 0, appIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT);

            Utils utils = new Utils();
            HashMap<String, Object> metaData = utils.extractMetaData(url);
            MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
            // Notification icon in card
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON,
                    BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher_foreground));
            if (!metaData.containsKey("artcover")) {
                Bitmap bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.app_icon);
                metaData.put("artcover", bitmap);
            }
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, (Bitmap) metaData.get("artcover"));

            // lock screen icon for pre lollipop
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, (Bitmap) metaData.get("artcover"));
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, (String) metaData.get("title"));
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                    (String) metaData.get("albumArtist"));
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 1);
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1);
            String duration = (String) metaData.get("duration");
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION,  Long.parseLong(duration));
            mMediaSessionCompat.setMetadata(metadataBuilder.build());
            mMediaSessionCompat.setSessionActivity(appPendingIntent);
       }
       catch(Exception e){
            Log.e(TAG, "initMediaSessionMetadata: "+e.toString());
       }
    }

    private boolean successfullyRetrievedAudioFocus() {
        int result;
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(this)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(false).build();

            result = audioManager.requestAudioFocus(focus);
        } else {
            // noinspection deprecation
            result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        return result == AudioManager.AUDIOFOCUS_GAIN;
    }

    // Not important for general audio service, required for class
    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        if (TextUtils.equals(clientPackageName, getPackageName())) {
            return new BrowserRoot("RNAudio", null);
        }
        return null;
    }

    // Not important for general audio service, required for class
    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(null);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                synchronized(focusLock)
                {
                    resumeOnFocusGain = false;
                }
                Log.i(TAG, "onAudioFocusChange: AUDIOFOCUS_LOSS");
                pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                {
                    synchronized(focusLock) {
                        resumeOnFocusGain = mMediaPlayer.isPlaying();
                    }
                    pausePlayback();
                    Log.i(TAG, "onAudioFocusChange: AUDIOFOCUS_LOSS_TRANSIENT "+resumeOnFocusGain);
                    break;
                }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                {
                    if (mMediaPlayer != null) {
                        mMediaPlayer.setVolume(0.3f, 0.3f);
                    }
                    Log.i(TAG, "onAudioFocusChange: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                    break;
                }
            case AudioManager.AUDIOFOCUS_GAIN:
                {
                    if (resumeOnFocusGain) {
                        synchronized(focusLock) {
                            resumeOnFocusGain = false;
                        }
                        playbackNow();
                    }
                    Log.i(TAG, "onAudioFocusChange: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                    break;
                }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: ");
        MediaButtonReceiver.handleIntent(mMediaSessionCompat, intent);

        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            super.onTaskRemoved(rootIntent);
            clearNotification();
            stopSelf();
        } catch (Exception e) {
            //TODO: handle exception
            Log.e(TAG, "onTaskRemoved: "+ e.toString());
        }
    }

    @Override
    public void onDestroy() {
        try {
            Log.i(TAG, "onDestroy: ");
            super.onDestroy();
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.abandonAudioFocus(this);
            if (mMediaSessionCompat != null) {
                mMediaSessionCompat.release();
            }
            clearNotification();
            stopSelf();
            stopForeground(false);
        } catch (Exception e) {
            // TODO: handle exception
            Log.e(TAG, "onDestroy" + e.toString());
        }
    }

}
